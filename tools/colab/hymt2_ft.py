#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Дообучение Hy-MT2-1.8B — модели уточнителя Falar — на A100 в Google Colab (results/2026-10-27-asr-confidence.md,
шаг 7). Учим переводить фразу, искажённую распознаванием, с контекстом разговора, и отвечать только переводом самой
фразы. Данные — только открытые и синтетические: разговоры владельца сюда не попадают.

Этапы (каждый пишет итог в WORK на Google Диске и при повторном запуске пропускается — сессия Colab может оборваться):
  corpus  OpenSubtitles v2018 pt_br–ru (OPUS) с границами фильмов: прошлые реплики того же фильма — контекст; чистка;
          фразы наборов проверки качества (Tatoeba, ситуации) исключены; фильмы делятся на обучение / проверку / тест
  labse   отсев плохо выровненных пар по сходству LaBSE
  select  обучающие и тестовые фразы, у части — шум распознавания
  noisy   фраза озвучивается голосом Piper, на неё ложатся комната (RIR) и шум (MUSAN), распознаёт
          parakeet-tdt-0.6b-v3 int8 — тот же, что в приложении (sherpa-onnx 1.13.8); слишком испорченное отбрасывается
  sft     примеры в чате Hy-MT2: простая подсказка или подсказка уточнителя приложения с прошлыми репликами
  train   LoRA (по умолчанию) или полное дообучение; потери — только на ответе
  evaluate  до и после: Tatoeba и ситуации (эталон от людей) и отложенный тест с шумом (фильмы, которых не было
          в обучении); COMET и chrF, парный бутстрэп
  export  слияние и GGUF Q4_K_M (llama.cpp v0.7.0) — и исходная модель тем же путём, для честного сравнения на ПК

Ноутбук tools/colab/hymt2_ft.ipynb собирается из этого файла (tools/colab/build_notebook.py); проверка на ПК —
tools/colab/test_hymt2_ft.py (маленькие входы, без видеокарты).
"""
import hashlib
import io
import json
import math
import os
import random
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

VOICES = {'pt': ['pt_BR-faber-medium', 'pt_BR-jeff-medium', 'pt_BR-cadu-medium', 'pt_BR-miro-high',
                 'pt_BR-edresson-low', 'pt_BR-dii-high'],
          'ru': ['ru_RU-dmitri-medium', 'ru_RU-irina-medium', 'ru_RU-denis-medium', 'ru_RU-ruslan-medium']}

CFG = dict(
    work='/content/drive/MyDrive/falar-hymt2',   # итоги этапов — на Диске, переживают обрыв сессии
    cache='/content/cache',                      # большие загрузки — на диске машины Colab
    base='tencent/Hy-MT2-1.8B',
    seed=1,
    # корпус
    opus_url='https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/moses/pt_br-ru.txt.zip',
    tests_url='https://raw.githubusercontent.com/Annoyt/FALAR/main/data',
    opus_lines=23_603_236,       # пар в v2018 pt_br–ru (OPUS API): от него — доля строк в кандидаты
    ctx_lines=5,                 # прошлых реплик фильма в контексте — не больше
    max_words=30,
    labse_min=0.75,
    # отбор
    n_train=100_000,             # обучающих примеров, поровну pt→ru и ru→pt
    noisy_share=0.35,            # доля с шумом распознавания (озвучка и распознавание — на процессоре, ~10 фраз/с)
    n_dev=500,                   # на направление: потери при обучении
    n_test=500,                  # на направление: отложенный тест с шумом
    # шум
    voices=VOICES,               # голоса Piper по языкам (sherpa-onnx, tts-models)
    noise='musan',               # musan — шум, музыка, говор (11 ГБ); rirs — только шумы из RIRS_NOISES (1,3 ГБ)
    snr_db=(0.0, 20.0),
    clean_audio=0.15,            # доля озвучек без шума
    rir_prob=0.4,
    wer_max=0.6,                 # испорченнее — пример выбрасывается: учить надо восстанавливать, а не сочинять
    workers=0,                   # 0 — по числу ядер
    # примеры
    simple_share=0.3,            # доля с простой подсказкой (остальное — подсказка уточнителя приложения)
    empty_bg=0.2,                # доля подсказок уточнителя с пустым фоном
    # обучение
    full_ft=False,               # True — полное дообучение (A100 80 ГБ), иначе LoRA
    lora_r=64, lora_alpha=128, lora_dropout=0.05,
    lora_targets=('q_proj', 'k_proj', 'v_proj', 'o_proj', 'gate_proj', 'up_proj', 'down_proj'),
    lr=2e-4, lr_full=1e-5, epochs=1, batch=16, accum=2, max_len=768,
    # оценка и выгрузка
    gen_batch=64,
    eval_limit=0,                # не больше фраз в каждом наборе оценки (0 — все)
    build_jobs=0,                # потоков сборки llama.cpp (0 — по числу ядер)
    comet_model='Unbabel/wmt22-comet-da',
    comet_batch=64,
    llama_cpp_tag='v0.7.0',
)

LANG = {'ru': 'Russian', 'pt': 'Brazilian Portuguese'}          # простая подсказка (mt_bench.py, hymt)
APP_LANG = {'ru': 'Russian', 'pt': 'Portuguese (Brazil)'}       # TranslatorService.langName
SIMPLE = 'Translate the following segment into {lang}, without additional explanation.\n\n{src}'
APP = ('[Background Information]\n{bg}\nPlease translate the following text into {lang}, taking the provided '
       'background information into consideration\n[Source Text]\n{src}')                # TranslatorService.hyPrompt
FRESH_CHARS = 1200                                                                      # Brief.FRESH_CHARS
VOICE_URL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-{v}.tar.bz2'
ASR_REPO = 'csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8'                     # models/manifest.json
OPENSLR = ['https://us.openslr.org/resources', 'https://www.openslr.org/resources', 'https://openslr.elda.org/resources']
SR = 16000


def gpu_kind():
    """'bf16' — видеокарта с родным bf16 (A100, L4, H100: архитектура 8.0+), 'fp16' — без него (T4: 7.5),
    None — видеокарты нет."""
    import torch
    if not torch.cuda.is_available():
        return None
    return 'bf16' if torch.cuda.get_device_capability()[0] >= 8 else 'fp16'


PROFILES = dict(big=dict(n_train=100_000, noisy_share=0.35, n_test=500, eval_limit=0),       # A100, H100
                small=dict(n_train=40_000, noisy_share=0.25, n_test=300, eval_limit=300))   # T4: 16 ГБ, 2 ядра


def with_profile(cfg, profile='auto'):
    """Объём по видеокарте: с родным bf16 (A100, H100) — big, иначе (T4) — small. Без видеокарты (этапы на
    процессоре) — объём того отбора, что уже лежит на Диске, иначе small. Явно заданное в SETTINGS не трогается."""
    if profile == 'auto':
        kind = gpu_kind()
        meta = Path(cfg['work']) / 'selected.meta.json'
        if kind is None and meta.exists():
            have = json.loads(meta.read_text())
            profile = next((n for n, pr in PROFILES.items() if all(have.get(k) == v for k, v in pr.items()
                                                                   if k in have)), 'small')
        else:
            profile = 'big' if kind == 'bf16' else 'small'
    return dict(cfg, **{k: v for k, v in PROFILES[profile].items() if k not in cfg.get('_set', ())}, profile=profile)


def need_gpu(stage):
    if gpu_kind() is None:
        raise SystemExit(f'этап {stage} — на видеокарте: Среда выполнения → Сменить среду выполнения → GPU (T4 или A100); '
                         'готовые этапы сохранены на Диске и повторно не считаются')


def for_gpu(cfg):
    """Настройки под видеокарту: на T4 (16 ГБ, без bf16) — пачки меньше, накопление больше (размер шага тот же).
    Явно заданное в SETTINGS не трогается: применяется к ключам, которых нет в cfg['_set']."""
    if gpu_kind() != 'fp16':
        return cfg
    t4 = dict(batch=8, accum=4, gen_batch=32, comet_batch=32)
    return dict(cfg, **{k: v for k, v in t4.items() if k not in cfg.get('_set', ())})


def log(*a):
    print(time.strftime('%H:%M:%S'), *a, flush=True)


def sh(cmd, **kw):
    log('$', cmd if len(cmd) < 300 else cmd[:300] + '…')
    subprocess.run(cmd, shell=True, check=True, **kw)


def paths(cfg):
    w, c = Path(cfg['work']), Path(cfg['cache'])
    for d in (w, c):
        d.mkdir(parents=True, exist_ok=True)
    return w, c


def fetch(url, dst):
    """Загрузка с докачкой; готовый файл не качается заново."""
    dst = Path(dst)
    if dst.exists():
        return dst
    dst.parent.mkdir(parents=True, exist_ok=True)
    sh(f'curl -fL --retry 5 --retry-delay 5 -C - -o "{dst}.part" "{url}"')
    os.replace(f'{dst}.part', dst)
    return dst


def jdump(rows, path):
    path = Path(path)
    with open(f'{path}.tmp', 'w', encoding='utf-8') as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')
    os.replace(f'{path}.tmp', path)


def jload(path):
    with open(path, encoding='utf-8') as f:
        return [json.loads(line) for line in f if line.strip()]


# ---------------------------------------------------------------- текст

TAGS = re.compile(r'<[^>]+>|\{[^}]*\}')
CREDITS = re.compile(r'legenda|tradu[cç][aã]o|sincroni|revis[aã]o|субтитр|перев[её]л|перевод:|синхрон|opensubtitles|'
                     r'www\.|http|\.com|\.org|\.net', re.I)
TWO_SPEAKERS = re.compile(r'(^|\s)-\s*\S.*\s-\s*\S')


def clean_line(s):
    """Строка субтитров без разметки; None — не реплика (песня, звук, титры, два говорящих в одной строке)."""
    s = re.sub(r'\s+', ' ', TAGS.sub('', s)).strip()
    if not s or '♪' in s or '#' in s or '♫' in s or CREDITS.search(s) or TWO_SPEAKERS.search(s):
        return None
    if re.fullmatch(r'[\[\(].*[\]\)]', s):              # [RISOS], (вздыхает)
        return None
    s = re.sub(r'^-\s*', '', s).strip()
    if not re.search(r'[A-Za-zÀ-ÿА-Яа-яЁё]{2}', s):
        return None
    return s


def key(s):
    """Ключ для сравнения фраз: без регистра, знаков и лишних пробелов."""
    return ' '.join(re.sub(r'[^\w\s]', ' ', s.lower()).split())


def usable_pair(pt, ru, max_words):
    if pt is None or ru is None:
        return False
    w = len(pt.split())
    if w > max_words or len(ru.split()) > max_words:
        return False
    r = len(ru) / max(1, len(pt))
    return 0.5 <= r <= 2.0


def brief_fit(lines, budget=FRESH_CHARS):
    """Brief.fit приложения: фон в пределах бюджета, ближние реплики первыми; самая близкая, если сама не
    влезает, — своим хвостом с «…». Порядок на выходе — от старых к новым."""
    out, left = [], budget
    for line in reversed(lines or []):
        line = (line or '').strip()
        if not line:
            continue
        if len(line) + 1 <= left:
            out.append(line)
            left -= len(line) + 1
            continue
        if not out and left > 12:
            out.append(brief_tail(line, left - 1))
        break
    return out[::-1]


def brief_tail(s, n):
    """Brief.tail: конец строки не длиннее n знаков с «…» впереди; обрывок слова на отрезе выбрасывается."""
    if len(s) <= n:
        return s
    if n <= 1:
        return '…'
    start = len(s) - (n - 1)
    if not s[start - 1].isspace():
        sp = s.find(' ', start)
        if sp != -1 and sp < len(s) - 1:
            start = sp + 1
    return '…' + s[start:].strip()


def script_ok(lang, text):
    """Распознанное — тем же алфавитом, что язык фразы: parakeet изредка пишет русскую речь латиницей
    («Dobry D, gdzie apteka?»), а приложение такие куски отбрасывает по языку — в обучение они не идут."""
    cyr = sum(1 for ch in text if 'а' <= ch.lower() <= 'я' or ch in 'ёЁ')
    lat = sum(1 for ch in text if 'a' <= ch.lower() <= 'z' or 'à' <= ch.lower() <= 'ÿ')
    if cyr + lat == 0:
        return False
    return (cyr if lang == 'ru' else lat) / (cyr + lat) >= 0.5


def wer(ref, hyp):
    r, h = key(ref).split(), key(hyp).split()
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)] / max(1, len(r))


def prompt(src, tgt_lang, ctx=None, simple=False):
    if simple:
        return SIMPLE.format(lang=LANG[tgt_lang], src=src)
    return APP.format(bg='\n'.join(brief_fit(ctx or [])), lang=APP_LANG[tgt_lang], src=src)


# ---------------------------------------------------------------- корпус

def split_of(doc):
    """Фильм — целиком в одной части: обучение 98 %, проверка 1 %, тест 1 %."""
    h = int.from_bytes(hashlib.sha1(doc.encode()).digest()[:4], 'big') % 1000
    return 'train' if h < 980 else 'dev' if h < 990 else 'test'


def test_keys(cfg, c):
    """Ключи фраз наборов проверки качества — в обучение они не попадают."""
    out = set()
    for name in ('mt_test/tatoeba.json', 'test_set.json'):
        p = fetch(f"{cfg['tests_url']}/{name}", c / 'tests' / name.replace('/', '_'))
        d = json.loads(Path(p).read_text(encoding='utf-8'))
        for k in ('pt_to_ru', 'ru_to_pt'):
            for it in d[k]:
                out.update(key(it[x]) for x in ('pt', 'ru') if it.get(x))
    return out


def corpus_rows(lines_pt, lines_ru, lines_ids, cfg, exclude, pick):
    """Кандидаты из параллельных строк OPUS (moses + ids): фраза, перевод, прошлые реплики того же фильма.
    pick — вероятность взять строку в каждой части (train/dev/test)."""
    rng = random.Random(cfg['seed'])
    k = cfg['ctx_lines']
    doc_prev, buf, part = None, [], 'train'
    for n, (pt, ru, ids) in enumerate(zip(lines_pt, lines_ru, lines_ids)):
        f = ids.rstrip('\n').split('\t')
        doc = f[0] + '|' + (f[1] if len(f) > 1 else '')
        if doc != doc_prev:
            doc_prev, buf, part = doc, [], split_of(doc)
        pt, ru = clean_line(pt), clean_line(ru)
        if rng.random() < pick[part] and usable_pair(pt, ru, cfg['max_words']) \
                and key(pt) not in exclude and key(ru) not in exclude:
            yield dict(part=part, doc=doc, pt=pt, ru=ru,
                       ctx_pt=[x[0] for x in buf[-k:]], ctx_ru=[x[1] for x in buf[-k:]])
        if pt is not None and ru is not None:
            buf.append((pt, ru))
            if len(buf) > k:
                buf.pop(0)
        if n % 2_000_000 == 0 and n:
            log(f'  строк корпуса: {n:,}')


SELECT_KEYS = ('n_train', 'n_dev', 'n_test', 'noisy_share', 'seed')
# что получается из отбора: при другом отборе оно устарело
DOWNSTREAM = ('noisy.jsonl', 'noisy_shards', 'sft_train.jsonl', 'sft_dev.jsonl', 'checkpoints', 'lora', 'full', 'eval',
              'export')


def stash(w, names, why):
    """Устаревшие итоги этапов — в stale/<время>/ (не удаляются)."""
    stale, k = w / 'stale' / time.strftime('%Y%m%d-%H%M%S'), 1
    while stale.exists():
        k += 1
        stale = w / 'stale' / time.strftime(f'%Y%m%d-%H%M%S-{k}')
    stale.mkdir(parents=True)
    for name in names:
        if (w / name).exists():
            shutil.move(str(w / name), str(stale / name))
    log(f'{why} — отложено в {stale}')


def corpus(cfg):
    """Кандидатов набирается с запасом под n_train (corpus.meta.json). Собранный под меньший объём (например, под
    T4, а теперь A100) собирается заново; собранный прежней версией, без отметки, — берётся как есть."""
    w, c = paths(cfg)
    out, meta = w / 'corpus.jsonl', w / 'corpus.meta.json'
    if out.exists():
        have = json.loads(meta.read_text()) if meta.exists() else None
        if have is None or have['n_train'] >= cfg['n_train']:
            log('corpus: готов', out)
            return out
        stash(w, ('corpus.jsonl', 'corpus.meta.json', 'corpus_labse.jsonl', 'selected.jsonl', 'selected.meta.json')
              + DOWNSTREAM, f'corpus: собран под {have["n_train"]:,} примеров, нужно {cfg["n_train"]:,}')
    z = zipfile.ZipFile(fetch(cfg['opus_url'], c / 'opus' / 'pt_br-ru.txt.zip'))
    names = {n.rsplit('.', 1)[1]: n for n in z.namelist() if n.startswith('OpenSubtitles.')}
    exclude = test_keys(cfg, c)
    total = cfg['opus_lines']
    need = {'train': cfg['n_train'] * 3.5, 'dev': cfg['n_dev'] * 2 * 6, 'test': cfg['n_test'] * 2 * 6}
    share = {'train': 0.98, 'dev': 0.01, 'test': 0.01}
    pick = {p: min(1.0, need[p] / (total * share[p])) for p in need}
    files = [io.TextIOWrapper(z.open(names[x]), encoding='utf-8', errors='replace') for x in ('pt_br', 'ru', 'ids')]
    rows = list(corpus_rows(*files, cfg, exclude, pick))
    jdump(rows, out)
    meta.write_text(json.dumps(dict(n_train=cfg['n_train'])))
    log('corpus:', {p: sum(1 for r in rows if r['part'] == p) for p in need}, '→', out)
    return out


def labse_filter(rows, encode, threshold):
    """Пары с косинусным сходством LaBSE не ниже порога; encode(list[str]) → нормированные векторы."""
    import numpy as np
    a = np.asarray(encode([r['pt'] for r in rows]))
    b = np.asarray(encode([r['ru'] for r in rows]))
    sim = (a * b).sum(1)
    keep = []
    for r, s in zip(rows, sim):
        if s >= threshold:
            keep.append(dict(r, labse=round(float(s), 3)))
    return keep


def labse(cfg):
    w, _ = paths(cfg)
    out = w / 'corpus_labse.jsonl'
    if out.exists():
        log('labse: готов', out)
        return out
    need_gpu('labse')
    from sentence_transformers import SentenceTransformer
    m = SentenceTransformer('sentence-transformers/LaBSE', device='cuda')
    m.half()
    rows = jload(w / 'corpus.jsonl')
    keep = labse_filter(rows, lambda xs: m.encode(xs, batch_size=512, normalize_embeddings=True,
                                                   convert_to_numpy=True, show_progress_bar=True), cfg['labse_min'])
    jdump(keep, out)
    log(f'labse: осталось {len(keep):,} из {len(rows):,} (порог {cfg["labse_min"]})')
    return out


def select_rows(rows, cfg):
    """Без повторов; направления — на разных фразах; у доли обучающих и у всех тестовых — шум распознавания."""
    rng = random.Random(cfg['seed'])
    seen, parts = set(), {'train': [], 'dev': [], 'test': []}
    for r in rows:
        if key(r['pt']) in seen or key(r['ru']) in seen:
            continue
        seen.update((key(r['pt']), key(r['ru'])))
        parts[r['part']].append(r)
    for p in parts.values():
        rng.shuffle(p)
    out = []
    for part, n, noisy in (('train', cfg['n_train'] // 2, cfg['noisy_share']), ('dev', cfg['n_dev'], 0.0),
                           ('test', cfg['n_test'], 1.0)):
        src = parts[part]
        if len(src) < 2 * n:
            log(f'select: в части {part} фраз {len(src)} — меньше нужных {2 * n}')
        for i, r in enumerate(src[:2 * n]):
            d = 'pt2ru' if i % 2 == 0 else 'ru2pt'
            out.append(dict(r, dir=d, noisy=(i // 2) < int(round(noisy * n))))
    return out


def select(cfg):
    """Отбор помнит свои настройки (selected.meta.json). Сделанный с другими — или прежней версией ноутбука, без
    отметки, — откладывается вместе со всем, что из него получено, в stale/<время>/ (не удаляется) и делается заново."""
    w, _ = paths(cfg)
    out, meta = w / 'selected.jsonl', w / 'selected.meta.json'
    want = {k: cfg[k] for k in SELECT_KEYS}
    if out.exists():
        have = json.loads(meta.read_text()) if meta.exists() else None
        if have == want:
            log('select: готов', out)
            return out
        stash(w, ('selected.jsonl', 'selected.meta.json') + DOWNSTREAM,
              f'select: прежний отбор сделан с другими настройками ({have}) — он и всё, что из него,')
    rows = select_rows(jload(w / 'corpus_labse.jsonl'), cfg)
    jdump(rows, out)
    meta.write_text(json.dumps(want))
    log('select:', {p: sum(1 for r in rows if r['part'] == p) for p in ('train', 'dev', 'test')},
        'с шумом:', sum(r['noisy'] for r in rows))
    return out


# ---------------------------------------------------------------- шум распознавания

def noise_assets(cfg, c):
    """Голоса, распознаватель, комнаты и шумы — на диск машины Colab."""
    for lang in cfg['voices']:
        for v in cfg['voices'][lang]:
            d = c / 'voices' / f'vits-piper-{v}'
            if not d.exists():
                arc = fetch(VOICE_URL.format(v=v), c / 'voices' / f'vits-piper-{v}.tar.bz2')
                sh(f'tar xjf "{arc}" -C "{c / "voices"}"')
    asr = c / 'asr'
    for f in ('encoder.int8.onnx', 'decoder.int8.onnx', 'joiner.int8.onnx', 'tokens.txt'):
        fetch(f'https://huggingface.co/{ASR_REPO}/resolve/main/{f}', asr / f)
    rirs = c / 'RIRS_NOISES'
    if not rirs.exists():
        arc = None
        for base in OPENSLR:
            try:
                arc = fetch(f'{base}/28/rirs_noises.zip', c / 'rirs_noises.zip')
                break
            except subprocess.CalledProcessError:
                log('  зеркало не ответило:', base)
        sh(f'cd "{c}" && unzip -q "{arc}"')
    if cfg['noise'] == 'musan' and not (c / 'musan' / 'DONE').exists():
        for base in OPENSLR:
            try:
                arc = fetch(f'{base}/17/musan.tar.gz', c / 'musan.tar.gz')
                break
            except subprocess.CalledProcessError:
                log('  зеркало не ответило:', base)
        sh(f'tar xzf "{c / "musan.tar.gz"}" -C "{c}" && touch "{c / "musan" / "DONE"}" && rm "{c / "musan.tar.gz"}"')


def noise_lists(cfg, c):
    def wavs(*globs):
        out = []
        for g in globs:
            out += sorted(str(p) for p in c.glob(g))
        return out
    lists = dict(rir=wavs('RIRS_NOISES/simulated_rirs/*/*/*.wav'))
    if cfg['noise'] == 'musan' and (c / 'musan').exists():
        lists.update(noise=wavs('musan/noise/*/*.wav'), music=wavs('musan/music/*/*.wav'),
                     babble=wavs('musan/speech/*/*.wav'))
    else:
        lists.update(noise=wavs('RIRS_NOISES/pointsource_noises/*.wav'), music=[], babble=[])
    return lists


_W = {}


def _init_worker(c, lists):
    import sherpa_onnx
    _W.update(c=Path(c), lists=lists, tts={})
    a = Path(c) / 'asr'
    _W['asr'] = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=str(a / 'encoder.int8.onnx'), decoder=str(a / 'decoder.int8.onnx'), joiner=str(a / 'joiner.int8.onnx'),
        tokens=str(a / 'tokens.txt'), num_threads=1, model_type='nemo_transducer', decoding_method='greedy_search')


def _tts(voice):
    import sherpa_onnx
    if voice not in _W['tts']:
        d = _W['c'] / 'voices' / f'vits-piper-{voice}'
        cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(d / f'{voice}.onnx'), lexicon='',
                                                       tokens=str(d / 'tokens.txt'), data_dir=str(d / 'espeak-ng-data')),
            provider='cpu', num_threads=1, debug=False), max_num_sentences=1)
        _W['tts'][voice] = sherpa_onnx.OfflineTts(cfg)
    return _W['tts'][voice]


def _read(path, n, rng):
    """n отсчётов из файла с случайного места (короткий — по кругу), моно, 16 кГц."""
    import numpy as np
    import soundfile as sf
    info = sf.info(path)
    total = info.frames
    if total <= 0:
        return np.zeros(n, np.float32)
    start = rng.randrange(max(1, total - n)) if total > n else 0
    x, sr = sf.read(path, start=start, frames=min(n, total), dtype='float32', always_2d=True)
    x = x[:, 0]
    if sr != SR:
        from scipy.signal import resample_poly
        g = math.gcd(SR, sr)
        x = resample_poly(x, SR // g, sr // g).astype(np.float32)
    if len(x) < n:
        x = np.tile(x, n // max(1, len(x)) + 1)[:n]
    return x[:n]


def degrade(x, lists, rng, cfg):
    """Комната и шум поверх чистой речи 16 кГц; возвращает звук и описание."""
    import numpy as np
    from scipy.signal import fftconvolve
    desc = []
    if lists['rir'] and rng.random() < cfg['rir_prob']:
        import soundfile as sf
        h, _ = sf.read(rng.choice(lists['rir']), dtype='float32', always_2d=True)
        h = h[:SR, 0]                                     # импульс комнаты — с начала, не длиннее секунды
        h = h / (np.sqrt((h ** 2).sum()) + 1e-9)
        y = fftconvolve(x, h)[:len(x)]
        x = (y * (np.sqrt((x ** 2).mean()) / (np.sqrt((y ** 2).mean()) + 1e-9))).astype(np.float32)
        desc.append('rir')
    pad = [int(SR * rng.uniform(0.3, 0.8)) for _ in range(2)]
    x = np.concatenate([np.zeros(pad[0], np.float32), x, np.zeros(pad[1], np.float32)])
    if rng.random() >= cfg['clean_audio']:
        kinds = [k for k in ('noise', 'music', 'babble') if lists[k]]
        kind = rng.choice(kinds)
        if kind == 'babble':
            n = sum(_read(rng.choice(lists['babble']), len(x), rng) for _ in range(rng.randint(3, 6)))
        else:
            n = _read(rng.choice(lists[kind]), len(x), rng)
        snr = rng.uniform(*cfg['snr_db'])
        ps = (x[pad[0]:len(x) - pad[1]] ** 2).mean() + 1e-12
        pn = (n ** 2).mean() + 1e-12
        x = x + n * np.sqrt(ps / (pn * 10 ** (snr / 10)))
        desc.append(f'{kind}{snr:.0f}dB')
    peak = np.abs(x).max()
    if peak > 0.95:
        x = x * (0.95 / peak)
    return x.astype(np.float32), '+'.join(desc) or 'clean'


def _noisy_one(item):
    """Озвучить фразу, испортить звук, распознать. item: (id, язык, текст, настройки шума)."""
    import numpy as np
    from scipy.signal import resample_poly
    i, lang, text, cfg = item
    # Голос, скорость, комната и шум — от зерна фразы; сама озвучка VITS случайна при каждом вызове, поэтому набор
    # фиксируется шардами на Диске, а не повтором прогона.
    rng = random.Random(cfg['seed'] * 1_000_003 + i)
    voice = rng.choice(cfg['voices'][lang])
    a = _tts(voice).generate(text, sid=0, speed=rng.uniform(0.9, 1.15))
    x = np.asarray(a.samples, np.float32)
    if len(x) == 0:
        return dict(id=i, asr='', voice=voice, cond='tts-empty')
    g = math.gcd(SR, a.sample_rate)
    x = resample_poly(x, SR // g, a.sample_rate // g).astype(np.float32)
    x, cond = degrade(x, _W['lists'], rng, cfg)
    s = _W['asr'].create_stream()
    s.accept_waveform(SR, x)
    _W['asr'].decode_stream(s)
    return dict(id=i, asr=s.result.text.strip(), voice=voice, cond=cond)


def noisy(cfg):
    w, c = paths(cfg)
    out = w / 'noisy.jsonl'
    if out.exists():
        log('noisy: готов', out)
        return out
    import multiprocessing as mp
    noise_assets(cfg, c)
    lists = noise_lists(cfg, c)
    log('noisy: файлов —', {k: len(v) for k, v in lists.items()})
    rows = jload(w / 'selected.jsonl')
    todo = [(i, r['dir'][:2], r[r['dir'][:2]], {k: cfg[k] for k in ('rir_prob', 'clean_audio', 'snr_db', 'seed', 'voices')})
            for i, r in enumerate(rows) if r['noisy']]
    shards = w / 'noisy_shards'
    shards.mkdir(exist_ok=True)
    size, workers = 2000, cfg['workers'] or os.cpu_count()
    ctx = mp.get_context('spawn')
    with ctx.Pool(workers, initializer=_init_worker, initargs=(str(c), lists)) as pool:
        for k in range(0, len(todo), size):
            sp = shards / f'{k // size:04d}.jsonl'
            if sp.exists():
                continue
            t0 = time.time()
            res = pool.map(_noisy_one, todo[k:k + size], chunksize=8)
            jdump(res, sp)
            log(f'  шум: {k + len(res):,} из {len(todo):,} · {len(res) / (time.time() - t0):.1f} фраз/с')
    got = {}
    for sp in sorted(shards.glob('*.jsonl')):
        for r in jload(sp):
            got[r['id']] = r
    kept = []
    for i, r in enumerate(rows):
        if r['noisy']:
            g = got.get(i)
            if g is None:
                continue
            e = wer(r[r['dir'][:2]], g['asr'])
            r = dict(r, asr=g['asr'], wer=round(e, 3), voice=g['voice'], cond=g['cond'])
        kept.append(r)
    jdump(kept, out)
    ws = [r['wer'] for r in kept if 'wer' in r]
    alien = sum(1 for r in kept if r.get('asr') and not script_ok(r['dir'][:2], r['asr']))
    log(f'noisy: чужим алфавитом {alien:,} (в обучение не идут)')
    log(f'noisy: распознано {len(ws):,}; WER средний {sum(ws) / max(1, len(ws)):.3f}; без ошибок '
        f'{sum(1 for x in ws if x == 0) / max(1, len(ws)):.0%}; хуже порога {cfg["wer_max"]}: '
        f'{sum(1 for x in ws if x > cfg["wer_max"]) / max(1, len(ws)):.0%}')
    return out


# ---------------------------------------------------------------- примеры

def make_example(r, cfg, rng):
    """Пример в чате Hy-MT2 или None (шум испортил фразу сильнее порога)."""
    s, t = r['dir'][:2], r['dir'][3:]
    src = r[s]
    if r.get('noisy'):
        if not r.get('asr') or r.get('wer', 1.0) > cfg['wer_max'] or not script_ok(s, r['asr']):
            return None
        src = r['asr']
    if rng.random() < cfg['simple_share']:
        p = prompt(src, t, simple=True)
    else:
        ctx = [] if rng.random() < cfg['empty_bg'] else r[f'ctx_{s}'][-rng.randint(1, cfg['ctx_lines']):]
        p = prompt(src, t, ctx)
    return dict(messages=[dict(role='user', content=p), dict(role='assistant', content=r[t])],
                dir=r['dir'], noisy=bool(r.get('noisy')))


def sft(cfg):
    w, _ = paths(cfg)
    out = {p: w / f'sft_{p}.jsonl' for p in ('train', 'dev')}
    if all(p.exists() for p in out.values()):
        log('sft: готов')
        return out
    rng = random.Random(cfg['seed'])
    rows = jload(w / 'noisy.jsonl')
    for part in out:
        ex = [e for e in (make_example(r, cfg, rng) for r in rows if r['part'] == part) if e]
        rng.shuffle(ex)
        jdump(ex, out[part])
        log(f'sft {part}: {len(ex):,} примеров, с шумом {sum(e["noisy"] for e in ex):,}')
    return out


# ---------------------------------------------------------------- обучение

def encode(tok, ex, max_len):
    """Подсказка — как у модели при переводе (шаблон чата, начало ответа), потери — только на ответе и его конце.
    Предупреждение transformers 4.57 о «неверном регулярном выражении» токенизатора (fix_mistral_regex) для Hy-MT2
    ложное: без исправления токены совпадают с llama.cpp на официальном GGUF (613 строк из 613), с исправлением —
    нет (числа: «R$ 1.250,00», «COVID-19»). Исправление не включать: учить надо тем делением, каким модель
    работает на телефоне."""
    p = tok.apply_chat_template([ex['messages'][0]], add_generation_prompt=True, tokenize=True)
    a = tok(ex['messages'][1]['content'], add_special_tokens=False)['input_ids'] + [tok.eos_token_id]
    ids = list(p) + a
    if len(ids) > max_len:
        return None
    return dict(input_ids=ids, labels=[-100] * len(p) + a)


class Collator:
    def __init__(self, pad_id):
        self.pad = pad_id

    def __call__(self, batch):
        import torch
        n = max(len(b['input_ids']) for b in batch)
        ids = torch.full((len(batch), n), self.pad, dtype=torch.long)
        lab = torch.full((len(batch), n), -100, dtype=torch.long)
        att = torch.zeros((len(batch), n), dtype=torch.long)
        for i, b in enumerate(batch):
            k = len(b['input_ids'])
            ids[i, :k] = torch.tensor(b['input_ids'])
            lab[i, :k] = torch.tensor(b['labels'])
            att[i, :k] = 1
        return dict(input_ids=ids, labels=lab, attention_mask=att)


def build_model(cfg, model):
    """LoRA поверх модели (или сама модель при полном дообучении)."""
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant': False})
    if cfg['full_ft']:
        return model
    from peft import LoraConfig, get_peft_model
    model.enable_input_require_grads()
    lc = LoraConfig(r=cfg['lora_r'], lora_alpha=cfg['lora_alpha'], lora_dropout=cfg['lora_dropout'],
                    target_modules=list(cfg['lora_targets']), task_type='CAUSAL_LM')
    m = get_peft_model(model, lc)
    m.print_trainable_parameters()
    return m


def train(cfg, model_path=None, max_steps=-1):
    w, _ = paths(cfg)
    done = w / ('full' if cfg['full_ft'] else 'lora')
    if (done / 'DONE').exists():
        log('train: готов', done)
        return done
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments
    from transformers.trainer_utils import get_last_checkpoint
    kind = gpu_kind()
    if model_path is None:
        need_gpu('train')
    if cfg['full_ft'] and kind != 'bf16':
        raise SystemExit('полное дообучение — только на A100 (bf16, 40+ ГБ); на T4 — LoRA (full_ft=False)')
    cfg = for_gpu(cfg)
    src = model_path or cfg['base']
    tok = AutoTokenizer.from_pretrained(src)
    data = {}
    for part in ('train', 'dev'):
        ex = [encode(tok, e, cfg['max_len']) for e in jload(w / f'sft_{part}.jsonl')]
        data[part] = [e for e in ex if e]
        log(f'train: {part} — {len(data[part]):,} примеров (длиннее {cfg["max_len"]} токенов: {len(ex) - len(data[part])})')
    # полное — веса fp32, счёт bf16; LoRA — замороженная модель в bf16 (A100) или fp16 (T4), сама LoRA — fp32 (peft)
    dtype = torch.float32 if cfg['full_ft'] else torch.float16 if kind == 'fp16' else torch.bfloat16
    model = AutoModelForCausalLM.from_pretrained(src, torch_dtype=dtype, attn_implementation='sdpa')
    model = build_model(cfg, model)
    ck = w / 'checkpoints'
    args = TrainingArguments(
        output_dir=str(ck), per_device_train_batch_size=cfg['batch'], gradient_accumulation_steps=cfg['accum'],
        per_device_eval_batch_size=cfg['batch'], learning_rate=cfg['lr_full'] if cfg['full_ft'] else cfg['lr'],
        lr_scheduler_type='cosine_with_min_lr', lr_scheduler_kwargs={'min_lr_rate': 0.1}, warmup_ratio=0.03,
        num_train_epochs=cfg['epochs'], max_steps=max_steps, bf16=kind == 'bf16', fp16=kind == 'fp16',
        logging_steps=25, save_steps=500, save_total_limit=2, eval_strategy='steps', eval_steps=500,
        group_by_length=True, report_to='none', remove_unused_columns=False, seed=cfg['seed'],
        dataloader_num_workers=2, gradient_checkpointing=True, gradient_checkpointing_kwargs={'use_reentrant': False},
        optim='adamw_torch_fused' if torch.cuda.is_available() else 'adamw_torch')
    tr = Trainer(model=model, args=args, train_dataset=data['train'], eval_dataset=data['dev'],
                 data_collator=Collator(tok.pad_token_id))
    last = get_last_checkpoint(str(ck)) if ck.exists() else None
    if last:
        log('train: продолжаю с', last)
    tr.train(resume_from_checkpoint=last)
    done.mkdir(parents=True, exist_ok=True)
    (tr.model.to(torch.bfloat16) if cfg['full_ft'] else tr.model).save_pretrained(str(done))
    tok.save_pretrained(str(done))
    (done / 'DONE').write_text(json.dumps(tr.state.log_history[-5:], ensure_ascii=False))
    log('train: готово →', done)
    return done


# ---------------------------------------------------------------- оценка

def load_for_eval(cfg, which, base=None):
    """Исходная модель или дообученная (LoRA влита), bf16, для пакетного жадного перевода."""
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    w, _ = paths(cfg)
    base = base or cfg['base']
    tok = AutoTokenizer.from_pretrained(base)
    tok.padding_side = 'left'
    dt = {'bf16': torch.bfloat16, 'fp16': torch.float16}.get(gpu_kind(), torch.float32)
    if which == 'base':
        m = AutoModelForCausalLM.from_pretrained(base, torch_dtype=dt)
    elif cfg['full_ft']:
        m = AutoModelForCausalLM.from_pretrained(str(w / 'full'), torch_dtype=dt)
    else:
        from peft import PeftModel
        m = PeftModel.from_pretrained(AutoModelForCausalLM.from_pretrained(base, torch_dtype=dt), str(w / 'lora'))
        m = m.merge_and_unload()
    return tok, m.to('cuda' if torch.cuda.is_available() else 'cpu').eval()


def translate(tok, model, prompts, batch=64, max_new=200):
    """Жадный перевод пачками: короткие к коротким; ответ — без служебных знаков и внешних кавычек."""
    import torch
    order = sorted(range(len(prompts)), key=lambda i: len(prompts[i]))
    out = [None] * len(prompts)
    for k in range(0, len(order), batch):
        idx = order[k:k + batch]
        enc = [tok.apply_chat_template([dict(role='user', content=prompts[i])], add_generation_prompt=True,
                                       tokenize=True) for i in idx]
        n = max(len(e) for e in enc)
        ids = torch.full((len(enc), n), tok.pad_token_id, dtype=torch.long)
        att = torch.zeros((len(enc), n), dtype=torch.long)
        for j, e in enumerate(enc):
            ids[j, n - len(e):] = torch.tensor(e)
            att[j, n - len(e):] = 1
        with torch.no_grad():
            g = model.generate(input_ids=ids.to(model.device), attention_mask=att.to(model.device),
                               max_new_tokens=max_new, do_sample=False, temperature=None, top_p=None, top_k=None,
                               repetition_penalty=1.0, eos_token_id=tok.eos_token_id, pad_token_id=tok.pad_token_id)
        for j, i in enumerate(idx):
            out[i] = tok.decode(g[j, n:], skip_special_tokens=True).strip().strip('"')
    return out


def eval_sets(cfg):
    """Наборы оценки: Tatoeba и ситуации (эталон от людей, простая подсказка) и отложенный тест с шумом
    (фильмы, которых не было в обучении): распознанное с простой подсказкой и с подсказкой уточнителя и контекстом,
    и то же сказанное — потолок."""
    w, c = paths(cfg)
    sets = {}
    for name, f in (('tatoeba', 'mt_test/tatoeba.json'), ('situations', 'test_set.json')):
        # наборы — на диске машины Colab: после смены среды (T4 → A100) корпус берётся готовым с Диска, а их тут нет
        d = json.loads(Path(fetch(f"{cfg['tests_url']}/{f}", c / 'tests' / f.replace('/', '_'))).read_text(encoding='utf-8'))
        for k, dr in (('pt_to_ru', 'pt2ru'), ('ru_to_pt', 'ru2pt')):
            s, t = dr[:2], dr[3:]
            sets[f'{name}_{dr}'] = [dict(src=it[s], ref=it[t], prompt=prompt(it[s], t, simple=True)) for it in d[k]]
    if cfg['eval_limit']:
        sets = {n: v[:cfg['eval_limit']] for n, v in sets.items()}
    test = [r for r in jload(w / 'noisy.jsonl') if r['part'] == 'test' and r.get('asr')]
    for dr in ('pt2ru', 'ru2pt'):
        s, t = dr[:2], dr[3:]
        rs = [r for r in test if r['dir'] == dr]
        sets[f'noisy_{dr}'] = [dict(src=r[s], ref=r[t], prompt=prompt(r['asr'], t, simple=True)) for r in rs]
        sets[f'noisyctx_{dr}'] = [dict(src=r[s], ref=r[t], prompt=prompt(r['asr'], t, r[f'ctx_{s}'])) for r in rs]
        sets[f'cleanctx_{dr}'] = [dict(src=r[s], ref=r[t], prompt=prompt(r[s], t, r[f'ctx_{s}'])) for r in rs]
    if cfg['eval_limit']:
        sets = {n: v[:cfg['eval_limit']] for n, v in sets.items()}
    return sets


COMET_SCRIPT = r'''
import json, sys
from comet import download_model, load_from_checkpoint
m = load_from_checkpoint(sys.argv[1] if sys.argv[1].endswith(".ckpt") else download_model(sys.argv[1]))
for path in sys.argv[3:]:
    d = json.load(open(path, encoding="utf-8"))
    data = [dict(src=r["src"], mt=r["hyp"], ref=r["ref"]) for r in d]
    sc = m.predict(data, batch_size=int(sys.argv[2]), gpus=1, progress_bar=False).scores
    for r, s in zip(d, sc):
        r["comet"] = round(float(s), 4)
    json.dump(d, open(path, "w", encoding="utf-8"), ensure_ascii=False)
'''


def comet_env(c):
    """COMET — в своём окружении на Python 3.12 (как .venv-comet на ПК): у unbabel-comet 2.2.7 numpy<2, а такого
    numpy нет для Python 3.13, на котором Colab с осени 2026 года, и venv там без ensurepip. Окружение ставит uv,
    torch — сборка CUDA 12.6 (идёт и на драйверах постарше)."""
    env = c / 'comet_env'
    py = env / 'bin' / 'python'
    if not (env / 'DONE').exists():
        shutil.rmtree(env, ignore_errors=True)
        sh(f'"{sys.executable}" -m pip -q install uv')
        uv = f'"{sys.executable}" -m uv'
        sh(f'{uv} venv -q --python 3.12 "{env}"')
        sh(f'{uv} pip install -q --python "{py}" "torch==2.14.1" --index-url https://download.pytorch.org/whl/cu126')
        sh(f'{uv} pip install -q --python "{py}" "unbabel-comet==2.2.7"')
        (env / 'DONE').touch()
    (c / 'comet_score.py').write_text(COMET_SCRIPT)
    return py


def summary_table(ev, names):
    """Таблица «до и после» по наборам оценки: COMET (если посчитан) с парным бутстрэпом и chrF."""
    import sacrebleu
    has = all('comet' in json.load(open(ev / f'{w}_{n}.json', encoding='utf-8'))[0] for n in names for w in ('base', 'ft'))
    lines = ['| набор | n | COMET исходная | COMET дообученная | Δ [95 %] p | chrF исх. → дообуч. |', '|---|---|---|---|---|---|']
    for n in names:
        a = json.load(open(ev / f'base_{n}.json', encoding='utf-8'))
        b = json.load(open(ev / f'ft_{n}.json', encoding='utf-8'))
        ca = sacrebleu.corpus_chrf([r['hyp'] for r in a], [[r['ref'] for r in a]]).score
        cb = sacrebleu.corpus_chrf([r['hyp'] for r in b], [[r['ref'] for r in b]]).score
        if has:
            d, lo, hi, p = boot([r['comet'] for r in a], [r['comet'] for r in b])
            ma = sum(r['comet'] for r in a) / len(a)
            lines.append(f'| {n} | {len(a)} | {ma:.4f} | {ma + d:.4f} | {d:+.4f} [{lo:+.4f}; {hi:+.4f}] p={p:.3f} | '
                         f'{ca:.1f} → {cb:.1f} |')
        else:
            lines.append(f'| {n} | {len(a)} | — | — | — | {ca:.1f} → {cb:.1f} |')
    if not has:
        lines.append('\nCOMET не посчитан — посчитать на ПК: tools/colab/eval_local.py папка_eval')
    return lines


def boot(a, b, n_boot=1000, seed=12345):
    """Парный бутстрэп: среднее b − a, 95 % интервал, p (двусторонний)."""
    import numpy as np
    a, b = np.asarray(a), np.asarray(b)
    idx = np.random.default_rng(seed).integers(0, len(a), (n_boot, len(a)))
    d = (b - a)[idx].mean(1)
    s = np.sort(d)
    return float((b - a).mean()), float(s[n_boot // 40]), float(s[n_boot - n_boot // 40 - 1]), \
        float(min(1.0, 2 * min((d <= 0).mean(), (d >= 0).mean())))


def evaluate(cfg):
    need_gpu('evaluate')
    cfg = for_gpu(cfg)
    w, c = paths(cfg)
    ev = w / 'eval'
    ev.mkdir(exist_ok=True)
    summary = ev / 'summary.md'
    if summary.exists() and all('comet' in json.load(open(f, encoding='utf-8'))[0] for f in ev.glob('*_*.json')):
        log('evaluate: готов', summary)
        print(summary.read_text(encoding='utf-8'))
        return summary
    sets = eval_sets(cfg)
    for which in ('base', 'ft'):
        if all((ev / f'{which}_{n}.json').exists() for n in sets):
            continue
        tok, model = load_for_eval(cfg, which)
        for n, rows in sets.items():
            p = ev / f'{which}_{n}.json'
            if p.exists():
                continue
            hyp = translate(tok, model, [r['prompt'] for r in rows], cfg['gen_batch'])
            json.dump([dict(r, hyp=h) for r, h in zip(rows, hyp)], open(p, 'w', encoding='utf-8'), ensure_ascii=False)
            log(f'  {which} {n}: {len(rows)}')
        del model
        __import__('gc').collect()
        __import__('torch').cuda.empty_cache()
    todo = [str(p) for p in sorted(ev.glob('*.json')) if 'comet' not in json.load(open(p, encoding='utf-8'))[0]]
    if todo:
        try:      # COMET не держит выгрузку: без него — таблица по chrF, COMET досчитывается на ПК
            sh(f'"{comet_env(c)}" "{c / "comet_score.py"}" "{cfg["comet_model"]}" {cfg["comet_batch"]} '
               + ' '.join(f'"{t}"' for t in todo))
        except subprocess.CalledProcessError as e:
            log('evaluate: COMET не посчитан —', e)
    lines = summary_table(ev, list(sets))
    summary.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print('\n'.join(lines))
    return summary


# ---------------------------------------------------------------- выгрузка

def llama_cpp(cfg, c):
    """llama.cpp закреплённой версии: конвертер и llama-quantize (без видеокарты)."""
    d = c / 'llama.cpp'
    if not d.exists():
        sh(f'git clone --depth 1 --branch {cfg["llama_cpp_tag"]} https://github.com/ggml-org/llama.cpp "{d}"')
    q = d / 'build' / 'bin' / 'llama-quantize'
    if not q.exists():
        if __import__('importlib.util').util.find_spec('sentencepiece') is None:   # gguf-py конвертер берёт сам
            sh(f'"{sys.executable}" -m pip -q install sentencepiece')
        sh(f'cmake -S "{d}" -B "{d / "build"}" -DGGML_CUDA=OFF -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF '
           f'-DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF > /dev/null')
        sh(f'cmake --build "{d / "build"}" --target llama-quantize -j {cfg["build_jobs"] or os.cpu_count()} > /dev/null')
    return d, q


def to_gguf(hf_dir, out, d, q):
    out = Path(out)
    if out.exists():
        return out
    bf16 = out.with_name(out.stem + '-bf16.gguf')
    sh(f'"{sys.executable}" "{d / "convert_hf_to_gguf.py"}" "{hf_dir}" --outtype bf16 --outfile "{bf16}"')
    sh(f'"{q}" "{bf16}" "{out}" Q4_K_M')
    bf16.unlink()
    return out


def export(cfg):
    w, c = paths(cfg)
    ex = w / 'export'
    ex.mkdir(exist_ok=True)
    ft, base = ex / 'Hy-MT2-1.8B-falar-ft-Q4_K_M.gguf', ex / 'Hy-MT2-1.8B-base-Q4_K_M.gguf'
    if ft.exists() and base.exists():
        log('export: готов', ex)
        return ex
    import torch
    from huggingface_hub import snapshot_download
    from transformers import AutoModelForCausalLM, AutoTokenizer
    d, q = llama_cpp(cfg, c)
    src = cfg['base'] if Path(cfg['base']).exists() else snapshot_download(cfg['base'])
    merged = c / 'merged'
    if not (merged / 'config.json').exists():
        if cfg['full_ft']:
            m = AutoModelForCausalLM.from_pretrained(str(w / 'full'), torch_dtype=torch.bfloat16)
        else:
            from peft import PeftModel
            m = PeftModel.from_pretrained(AutoModelForCausalLM.from_pretrained(src, torch_dtype=torch.bfloat16),
                                          str(w / 'lora')).merge_and_unload()
        m.save_pretrained(str(merged), safe_serialization=True)
        AutoTokenizer.from_pretrained(src).save_pretrained(str(merged))
        for f in ('chat_template.jinja', 'generation_config.json'):
            if (Path(src) / f).exists():
                shutil.copy(Path(src) / f, merged / f)
        del m
    to_gguf(merged, ft, d, q)
    to_gguf(src, base, d, q)
    for f in (ft, base):
        log(f'export: {f.name} — {f.stat().st_size / 2 ** 30:.2f} ГБ')
    return ex


STAGES = dict(corpus=corpus, labse=labse, select=select, noisy=noisy, sft=sft, train=train, evaluate=evaluate,
              export=export)

if __name__ == '__main__':
    for st in sys.argv[1:] or list(STAGES):
        STAGES[st](CFG)
