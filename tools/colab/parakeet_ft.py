#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Дообучение parakeet-tdt-0.6b-v3 — распознавателя Falar — под бразильский португальский и шум комнаты, без потери
русского (results/2026-10-27-asr-confidence.md, шаги 16а и 16). Colab, A100. Данные — открытые; разговоры владельца
сюда не попадают.

Почему так (шаг 16а): готовое дообучение «только pt-BR» (TAGARELA) дало на записях комнаты −1,2 п.п. на португальском
(незначимо) и стёрло русский (WER 113 %). Модель приложения одна на оба языка, поэтому русский учится вместе с
португальским, а главный упор — на шум: подмешанные шум, музыка, говор и эхо.

Этапы (каждый пишет итог в WORK на Google Диске; готовое при повторном запуске пропускается):
  data      португальский: CORAA v1.1 (только pt-BR, по оценкам качества) и CORAA-MUPE (интервью, со знаками);
            русский: Golos farfield (дальний микрофон) и FLEURS ru; отложенные тесты: CORAA, MUPE, FLEURS pt-BR и ru,
            Golos farfield. Звук — 16 кГц FLAC на диск машины Colab
  labels    разметка со знаками и заглавными: где в данных их нет (CORAA, Golos), слова берутся из разметки, а знаки и
            заглавные — из ответа самого parakeet (он пишет со знаками, и этого нельзя потерять: от знаков зависит
            перевод). Междометия убираются; фраза отбрасывается, если parakeet расходится с разметкой больше чем на
            половину слов или пишет цифрами то, что в разметке словами
  noise     MUSAN (шум, музыка, говор нескольких голосов) и комнаты RIRS_NOISES — для подмешивания при обучении
  train     NeMo: полное дообучение, bf16, AdamW, косинус; шум и эхо подмешиваются на лету (lhotse);
            контрольные точки — на Диск, после обрыва продолжается с последней
  evaluate  до и после: WER на отложенных тестах — чистых и с шумом (SNR 5 и 10 дБ, тот же шум для обеих моделей)
  export    ONNX для sherpa-onnx, как scripts/nemo/parakeet-tdt-0.6b-v3/export_onnx.py sherpa-onnx 1.13.8:
            кодировщик, декодер, объединитель, int8 (кодировщик QUInt8, остальное QInt8), tokens.txt

Запуск — из ноутбука tools/colab/parakeet_ft.ipynb (tools/colab/build_parakeet_notebook.py) в отдельном окружении
Python 3.12 с NeMo: python parakeet_ft.py ЭТАП --cfg cfg.json. Проверка на ПК — tools/colab/test_parakeet_ft.py.
"""
import argparse
import difflib
import io
import json
import math
import os
import random
import re
import subprocess
import sys
import tarfile
import time
from pathlib import Path

SR = 16000
OPENSLR = ['https://us.openslr.org/resources', 'https://www.openslr.org/resources', 'https://openslr.elda.org/resources']

CFG = dict(
    work='/content/drive/MyDrive/falar-parakeet',   # итоги этапов — на Диске, переживают обрыв сессии
    cache='/content/cache',                         # звук и большие загрузки — на диске машины Colab
    base='nvidia/parakeet-tdt-0.6b-v3',
    seed=1,
    # данные: часов по источникам (обучение)
    hours=dict(coraa=70, mupe=70, golos_far=60, sova=50, fleurs_ru=8),   # Golos farfield — всё, что есть (~10 ч)
    ru_min_share=0.33,           # русского в обучении не меньше этой доли часов — повтором фраз (иначе забывается)
    test_n=400,                  # фраз в каждом отложенном тесте
    fleurs_tests=True,           # тесты FLEURS pt-BR и ru (архивы ~0,5 ГБ)
    dev_n=200,                   # фраз проверки при обучении — на язык
    min_sec=1.0, max_sec=20.0,
    label_wer_max=0.5,           # parakeet против разметки: расходится сильнее — фраза отбрасывается
    stream_buffer=2000,          # перемешивание потока с Hugging Face: строк в буфере
    data_workers=0,              # процессов разбора звука (0 — по числу ядер)
    asr_batch=32,                # фраз в пачке при распознавании (разметка, оценка)
    # шум: MUSAN (шум, музыка, говор) и комнаты RIRS_NOISES; на ПК для проверки — свои папки (noise_dirs, speech_dirs)
    noise_src='musan', noise_dirs=(), speech_dirs=(), rir_src='rirs',
    noise_snr=(0.0, 20.0), noise_prob=0.6, rir_prob=0.3, babble_n=300, babble_voices=(3, 6),
    test_snr=(5.0, 10.0),
    # обучение
    lr=3e-5, min_lr=1e-6, warmup=400, max_steps=5000, batch_duration=600, val_every=500,
    weight_decay=1e-3, grad_clip=1.0, num_workers=8,
    freeze_encoder=False,        # только для проверки на малой видеокарте: учится декодер с объединителем
    # выгрузка
    export_fp32=False,           # True — ещё и fp32 (2,4 ГБ) рядом с int8
)

# Объём по видеокарте: A100 80 ГБ — пачки по 900 с звука, 40 ГБ — 500, L4 и A10 (24 ГБ) — 200, T4 (16 ГБ, без bf16) —
# 150 и fp16; smoke — проверка всего пути на малых данных (минуты).
PROFILES = dict(
    a100_80=dict(batch_duration=900),
    a100_40=dict(batch_duration=400),
    l4=dict(batch_duration=200),
    t4=dict(batch_duration=150, max_steps=3000),
    smoke=dict(hours=dict(coraa=0.15, mupe=0.15, golos_far=0.15, sova=0.1, fleurs_ru=0), test_n=30, dev_n=15, max_steps=20,
               val_every=10, warmup=5, babble_n=10, batch_duration=8, max_sec=8.0, num_workers=2, fleurs_tests=False,
               stream_buffer=50, asr_batch=4),
)

FILLERS = {'pt': {'ãh', 'ah', 'ahn', 'eh', 'éh', 'uh', 'uhm', 'hum', 'hm', 'hmm', 'mm', 'mhm', 'aham', 'uhum', 'hã', 'ã',
                  'an', 'ehm', 'ah-hã', 'uh-hum'},
           'ru': {'э', 'ээ', 'эээ', 'м', 'мм', 'ммм', 'хм', 'а-а', 'э-э', 'угу', 'ага-а'}}
PUNCT = '.,!?;:…'


def log(*a):
    print(time.strftime('%H:%M:%S'), *a, flush=True)


def sh(cmd, **kw):
    log('$', cmd if len(cmd) < 300 else cmd[:300] + '…')
    subprocess.run(cmd, shell=True, check=True, **kw)


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
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(f'{path}.tmp', 'w', encoding='utf-8') as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')
    os.replace(f'{path}.tmp', path)


def jload(path):
    with open(path, encoding='utf-8') as f:
        return [json.loads(line) for line in f if line.strip()]


def paths(cfg):
    w, c = Path(cfg['work']), Path(cfg['cache'])
    for d in (w, c):
        d.mkdir(parents=True, exist_ok=True)
    return w, c


def gpu_profile():
    """Профиль по видеокарте: a100_80, a100_40, l4 (24 ГБ), t4; None — видеокарты нет."""
    try:
        import torch
    except ImportError:
        return None
    if not torch.cuda.is_available():
        return None
    gb = torch.cuda.get_device_properties(0).total_memory / 2 ** 30
    if torch.cuda.get_device_capability()[0] < 8:
        return 't4'
    return 'a100_80' if gb > 60 else 'a100_40' if gb > 30 else 'l4' if gb > 18 else 't4'


def with_profile(cfg, profile='auto'):
    if profile == 'auto':
        profile = gpu_profile() or 'a100_40'
    return dict(cfg, **{k: v for k, v in PROFILES[profile].items() if k not in cfg.get('_set', ())}, profile=profile)


# ---------------------------------------------------------------- текст

def words_norm(text):
    """Слова для сравнения: нижний регистр, «ё» как «е», без знаков (дефис и апостроф внутри слова — часть слова)."""
    t = (text or '').lower().replace('ё', 'е')
    t = re.sub(r"[^\w\s'-]", ' ', t)
    return [w.strip("'-") for w in t.split() if w.strip("'-")]


def wer(ref, hyp):
    r, h = words_norm(ref), words_norm(hyp)
    if not r:
        return 0.0 if not h else 1.0
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
    return d[len(h)] / len(r)


def drop_fillers(text, lang):
    """Междометия-паузы — не слова фразы: parakeet их не пишет, и учить его этому незачем («ãh», «э-э»)."""
    out = []
    for tok in text.split():
        core = tok.strip(PUNCT + '"«»()').lower()
        if core in FILLERS[lang]:
            tail = ''.join(ch for ch in tok if ch in PUNCT)
            if tail and out and not out[-1][-1:] in PUNCT:
                out[-1] += tail[-1]                 # знак после междометия остаётся у предыдущего слова
            continue
        out.append(tok)
    s = ' '.join(out).strip()
    s = re.sub(r'\s+([.,!?;:…])', r'\1', s)
    s = re.sub(r'^[,;:]\s*', '', s)
    return s


def pnc_transfer(ref, teacher):
    """Слова — из разметки ref (без знаков, нижний регистр), знаки и заглавные — из ответа teacher (parakeet).
    Выравнивание по нормализованным словам: где слова совпали — берётся слово ответа как есть (с регистром и знаком
    после него), где нет — слово разметки в нижнем регистре. Первое слово — с заглавной, если так у ответа.
    Возвращает (текст, доля несовпавших слов разметки)."""
    rw = words_norm(ref)
    tt = teacher.split()
    tn = [(words_norm(t) or [''])[0] for t in tt]
    if not rw:
        return '', 1.0
    sm = difflib.SequenceMatcher(a=rw, b=tn, autojunk=False)
    out, matched = [], 0
    for op, a0, a1, b0, b1 in sm.get_opcodes():
        if op == 'equal':
            out += tt[b0:b1]
            matched += a1 - a0
        elif op in ('replace', 'delete'):
            out += rw[a0:a1]
    if out and tt and tt[0][:1].isupper():
        out[0] = out[0][:1].upper() + out[0][1:]
    if out and tt and tt[-1][-1:] in '.!?…' and out[-1][-1:] not in PUNCT:
        out[-1] += tt[-1][-1]
    return ' '.join(out), 1 - matched / len(rw)


DIGIT = re.compile(r'\d')


# ---------------------------------------------------------------- данные

def _write_flac(path, audio, sr):
    import numpy as np
    import soundfile as sf
    x = np.asarray(audio, dtype=np.float32)
    if x.ndim > 1:
        x = x.mean(axis=1)
    if sr != SR:
        import librosa
        x = librosa.resample(x, orig_sr=sr, target_sr=SR)
    path.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(path), x, SR, format='FLAC')
    return len(x) / SR


def _decode(audio):
    """Звук из строки датасета с Audio(decode=False): байты файла или путь."""
    import soundfile as sf
    if audio.get('bytes'):
        x, sr = sf.read(io.BytesIO(audio['bytes']), dtype='float32')
    else:
        x, sr = sf.read(audio['path'], dtype='float32')
    return x, sr


def _stream(name, split, seed, buffer=2000):
    from datasets import Audio, load_dataset
    ds = load_dataset(name, split=split, streaming=True)
    ds = ds.cast_column('audio', Audio(decode=False))
    return ds.shuffle(seed=seed, buffer_size=buffer) if buffer else ds


def coraa_ok(r):
    """CORAA: только pt-BR и без замечаний оценщиков (шум, второй голос, тихо)."""
    if r.get('variety') != 'pt_br':
        return False
    try:
        up, down = float(r.get('up_votes') or 0), float(r.get('down_votes') or 0)
        noise = float(r.get('votes_for_noise_or_low_voice') or 0)
        second = float(r.get('votes_for_second_voice') or 0)
    except ValueError:
        return False
    return up > down and noise == 0 and second == 0


SOURCES = {
    # имя: (датасет, сплит обучения, сплит теста, язык, поле текста, знаки в тексте, фильтр)
    'coraa': ('Racoci/CORAA-v1.1', 'train', 'test', 'pt', 'text', False, coraa_ok),
    'mupe': ('nilc-nlp/CORAA-MUPE-ASR', 'train', 'test', 'pt', 'original_text', True, None),
    'golos_far': ('bond005/sberdevices_golos_100h_farfield', 'train', 'test', 'ru', 'transcription', False, None),
    'sova': ('bond005/sova_rudevices', 'train', 'test', 'ru', 'transcription', False, None),
}


def _audio_job(job):
    """В процессе пула: звук строки → FLAC 16 кГц, если длительность годится. Возвращает длительность или None.
    Уже записанный файл не разбирается: длительность — из его заголовка (повторный запуск после обрыва)."""
    path, audio, lo, hi = job
    if audio is None:
        import soundfile as sf
        dur = sf.info(path).duration
        return dur if lo <= dur <= hi else None
    x, sr = _decode(audio)
    dur = len(x) / sr
    if not lo <= dur <= hi:
        return None
    p = Path(path)
    if not p.exists():
        _write_flac(p, x, sr)
    return dur


def take_stream(src, split, cfg, c, hours=None, n=None):
    """Строки источника до нужного объёма: часов (обучение) или фраз (тест). Поток читается здесь, разбор звука и
    запись FLAC — в пуле процессов (на 200 ч звука в одном процессе ушли бы часы)."""
    import multiprocessing as mp
    from concurrent.futures import ProcessPoolExecutor
    name, _, _, lang, field, pnc, ok = SOURCES[src]
    rows, total, pending = [], 0.0, []
    workers = cfg['data_workers'] or os.cpu_count() or 2
    last = [time.time(), 0]                            # строка хода — раз в минуту
    log(f'{src}/{split}: читаю поток {name}, нужно {f"{hours} ч" if hours is not None else f"{n} фраз"}; '
        f'процессов разбора звука {workers}')

    def enough():
        return (hours is not None and total >= hours * 3600) or (n is not None and len(rows) >= n)

    def collect(block):
        """Готовое — по порядку подачи (итог не зависит от скорости процессов); block — ждать, пока в работе не
        останется меньше 4 задач на процесс."""
        nonlocal total
        while pending:
            fut, row = pending[0]
            if not fut.done() and not (block and len(pending) >= workers * 4):
                break
            pending.pop(0)
            dur = fut.result()
            if dur is not None and not enough():
                rows.append(dict(row, duration=round(dur, 3)))
                total += dur
            if time.time() - last[0] > 60:
                last[0] = time.time()
                log(f'  {src}/{split}: {len(rows)} фраз, {total / 3600:.2f} ч')

    # spawn, а не fork: поток datasets держит свои нити, а fork посреди них может замереть навсегда
    with ProcessPoolExecutor(workers, mp_context=mp.get_context('spawn')) as pool:
        for i, r in enumerate(_stream(name, split, cfg['seed'], cfg['stream_buffer'])):
            if enough():
                break
            text = (r.get(field) or '').strip()
            if not text or (ok and not ok(r)):
                continue
            text = drop_fillers(text, lang)
            if len(words_norm(text)) < 1:
                continue
            p = c / 'audio' / f'{src}-{split}' / f'{i:07d}.flac'
            fut = pool.submit(_audio_job, (str(p), None if p.exists() else r['audio'], cfg['min_sec'], cfg['max_sec']))
            pending.append((fut, dict(audio_filepath=str(p), text=text, lang=lang, src=src, pnc=pnc)))
            collect(len(pending) >= workers * 8)
        while pending and not enough():
            fut, row = pending.pop(0)
            dur = fut.result()
            if dur is not None:
                rows.append(dict(row, duration=round(dur, 3)))
                total += dur
        for fut, _ in pending:
            fut.cancel()
    log(f'{src}/{split}: {len(rows)} фраз, {total / 3600:.2f} ч')
    return rows


def fleurs(lang_code, split, cfg, c, hours=None, n=None):
    """FLEURS (google/fleurs, CC BY 4.0): архив звука и tsv с исходным текстом (со знаками) — без скрипта загрузки."""
    from huggingface_hub import hf_hub_download
    lang = lang_code[:2]
    tsv = hf_hub_download('google/fleurs', f'data/{lang_code}/{split}.tsv', repo_type='dataset')
    arc = hf_hub_download('google/fleurs', f'data/{lang_code}/audio/{split}.tar.gz', repo_type='dataset')
    # таблица: id, файл, исходный текст, нормализованный, по буквам, число отсчётов (16 кГц), пол
    meta = []
    for line in open(tsv, encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) >= 7 and p[5].isdigit():
            text = drop_fillers(p[2].strip(), lang)
            dur = int(p[5]) / SR
            if text and cfg['min_sec'] <= dur <= cfg['max_sec']:
                meta.append((p[1], text, dur))
    random.Random(cfg['seed']).shuffle(meta)
    pick, total = {}, 0.0                            # выбор — по таблице, до чтения звука
    for name, text, dur in meta:
        if (hours is not None and total >= hours * 3600) or (n is not None and len(pick) >= n):
            break
        if name not in pick:
            pick[name] = (text, dur)
            total += dur
    # tar.gz — только подряд: чтение вразнобой распаковывает архив каждый раз с начала (в Colab 01.11 — часы)
    import soundfile as sf
    got = {}
    with tarfile.open(arc, mode='r|gz') as tf:
        for m in tf:
            base = os.path.basename(m.name)
            if not m.isfile() or base not in pick:
                continue
            p = c / 'audio' / f'fleurs_{lang}-{split}' / (base.rsplit('.', 1)[0] + '.flac')
            if not p.exists():
                x, sr = sf.read(io.BytesIO(tf.extractfile(m).read()), dtype='float32')
                _write_flac(p, x, sr)
            got[base] = p
            if len(got) == len(pick):                 # всё нужное найдено — дальше архив не читаем
                break
    rows = [dict(audio_filepath=str(got[name]), duration=round(dur, 3), text=text, lang=lang, src=f'fleurs_{lang}', pnc=True)
            for name, (text, dur) in pick.items() if name in got]
    log(f'fleurs_{lang}/{split}: {len(rows)} фраз, {sum(r["duration"] for r in rows) / 3600:.2f} ч')
    return rows


def restore_stream(src, split, rows, cfg, c):
    """Звука нет (новая машина Colab: диск прежней пропал вместе с ней), а список фраз на Диске есть. Поток с тем же
    seed идёт в том же порядке: номер строки потока — тот же файл. Пишутся только недостающие, длительность каждого
    сверяется со списком — иначе ошибка, а не тихая подмена звука под чужой текст."""
    import multiprocessing as mp
    from collections import deque
    from concurrent.futures import ProcessPoolExecutor
    need = {int(Path(r['audio_filepath']).stem): r for r in rows if not os.path.exists(r['audio_filepath'])}
    if not need:
        return
    last, workers = max(need), cfg['data_workers'] or os.cpu_count() or 2
    log(f'{src}/{split}: восстанавливаю звук — {len(need)} файлов из {len(rows)} (на диске машины Colab их нет)')
    bad, done, t0, q = 0, 0, time.time(), deque()

    def check(block):
        nonlocal bad, done
        while q and (block or q[0][1].done()):
            row, fut = q.popleft()
            dur = fut.result()
            done += 1
            if dur is None or abs(dur - row['duration']) > 0.05:
                bad += 1
            if not block:
                continue
            if len(q) < workers * 4:
                break
    with ProcessPoolExecutor(workers, mp_context=mp.get_context('spawn')) as pool:
        for i, r in enumerate(_stream(SOURCES[src][0], split, cfg['seed'], cfg['stream_buffer'])):
            if i in need:
                q.append((need[i], pool.submit(_audio_job, (need[i]['audio_filepath'], r['audio'], 0.0, 1e9))))
                check(len(q) >= workers * 8)
            if i >= last:
                break
            if time.time() - t0 > 60:
                t0 = time.time()
                log(f'  {src}/{split}: восстановлено {done} из {len(need)} (поток — строка {i} из ~{last})')
        while q:
            check(True)
    left = sum(1 for r in rows if not os.path.exists(r['audio_filepath']))
    if bad or left:
        raise SystemExit(f'{src}/{split}: поток с тем же seed дал другой звук (не совпали по длительности {bad}, нет файлов '
                         f'{left}). Удалите папку data в falar-parakeet на Диске и запустите снова: данные соберутся с нуля')
    log(f'{src}/{split}: звук восстановлен — {len(need)} файлов, длительности совпали')


def restore_audio(cfg, out, c):
    """Части, уже собранные на Диске: если их звука нет на этой машине — восстановить (FLEURS — тем же выбором по
    таблице, потоки Hugging Face — тем же порядком)."""
    for f in sorted((out / 'parts').glob('*.jsonl')):
        rows = jload(f)
        miss = sum(1 for r in rows if not os.path.exists(r['audio_filepath']))
        if not miss:
            continue
        key = f.stem                                  # coraa_train, golos_far_test, fleurs_ru_train …
        src, split = key.rsplit('_', 1)
        if src.startswith('fleurs_'):
            code = {'fleurs_ru': 'ru_ru', 'fleurs_pt': 'pt_br'}[src]
            log(f'{key}: восстанавливаю звук — {miss} файлов (на диске машины Colab их нет)')
            again = fleurs(code, split, cfg, c, hours=cfg['hours'].get(src) if split == 'train' else None,
                           n=cfg['test_n'] if split == 'test' else None)
            if [r['audio_filepath'] for r in again] != [r['audio_filepath'] for r in rows]:
                raise SystemExit(f'{key}: выбор FLEURS разошёлся со списком на Диске — удалите папку data и запустите снова')
        else:
            restore_stream(src, SOURCES[src][1] if split == 'train' else SOURCES[src][2], rows, cfg, c)


def data(cfg):
    w, c = paths(cfg)
    out = w / 'data'
    restore_audio(cfg, out, c)                         # новая машина Colab: звук собранных частей — заново
    if (out / 'raw_train.jsonl').exists():
        log('данные уже собраны:', out)
        return

    def part(key, fn):
        """Источник целиком — на Диск, как только собран: после обрыва он не собирается заново."""
        f = out / 'parts' / f'{key}.jsonl'
        if f.exists():
            rows = jload(f)
            log(f'{key}: уже собран — {len(rows)} фраз')
            return rows
        rows = fn()
        jdump(rows, f)
        return rows

    h = cfg['hours']
    train = []
    for src in SOURCES:
        if h.get(src):
            train += part(f'{src}_train', lambda: take_stream(src, SOURCES[src][1], cfg, c, hours=h[src]))
    if h.get('fleurs_ru'):
        train += part('fleurs_ru_train', lambda: fleurs('ru_ru', 'train', cfg, c, hours=h['fleurs_ru']))
    tests = {}
    for src in SOURCES:
        if h.get(src):
            tests[src] = part(f'{src}_test', lambda: take_stream(src, SOURCES[src][2], cfg, c, n=cfg['test_n']))
    if cfg['fleurs_tests']:
        tests['fleurs_pt'] = part('fleurs_pt_test', lambda: fleurs('pt_br', 'test', cfg, c, n=cfg['test_n']))
        tests['fleurs_ru'] = part('fleurs_ru_test', lambda: fleurs('ru_ru', 'test', cfg, c, n=cfg['test_n']))
    for name, rows in tests.items():
        jdump(rows, out / f'raw_test_{name}.jsonl')
    jdump(train, out / 'raw_train.jsonl')
    log('часов обучения:', {s: round(sum(r['duration'] for r in train if r['src'] == s) / 3600, 2)
                           for s in sorted({r['src'] for r in train})})


# ---------------------------------------------------------------- разметка со знаками

def load_model(name_or_path, device='cuda'):
    import nemo.collections.asr as nemo_asr
    import torch
    for attempt in (1, 2):                           # на ПК однажды не прочиталась с первого раза (02.11) — повтор
        try:
            if os.path.exists(str(name_or_path)):
                m = nemo_asr.models.ASRModel.restore_from(str(name_or_path), map_location='cpu')
            else:
                m = nemo_asr.models.ASRModel.from_pretrained(name_or_path, map_location='cpu')
            break
        except Exception as e:
            if attempt == 2:
                raise
            log('  модель не загрузилась, ещё раз:', type(e).__name__, str(e)[:120])
    # декодер на графах CUDA компилирует ядро через NVRTC из cuda-bindings; в Colab 02.11 его PTX оказался новее
    # драйвера (CUDA_ERROR_UNSUPPORTED_PTX_VERSION). Обычный пакетный декодер NeMo — без графов, результат тот же
    from omegaconf import open_dict
    dc = m.cfg.decoding
    with open_dict(dc):
        dc.greedy.use_cuda_graph_decoder = False
    m.change_decoding_strategy(dc, verbose=False)
    m = m.to(device if torch.cuda.is_available() else 'cpu').eval()
    return m


def transcribe(model, files, batch=32, part=2000):
    """Распознать файлы; кусками по part — со строкой хода (на 150 тыс. фраз иначе тишина на четверть часа)."""
    res, t0 = [], time.time()
    for k in range(0, len(files), part):
        out = model.transcribe([str(f) for f in files[k:k + part]], batch_size=batch, verbose=False)
        if isinstance(out, tuple):                   # старые версии: (лучшие, все)
            out = out[0]
        res += [(o.text if hasattr(o, 'text') else str(o)).strip() for o in out]
        if len(files) > part:
            log(f'  распознано {len(res)} из {len(files)} ({time.time() - t0:.0f} с)')
    return res


def label_rows(rows, texts, cfg):
    """Разметка для обучения и тестов: знаки и заглавные — у parakeet там, где в данных их нет."""
    keep, why = [], {'расхождение': 0, 'цифры': 0, 'пусто': 0}
    for r, t in zip(rows, texts):
        r = dict(r, base=t)
        if r['pnc']:
            text, miss = r['text'], wer(r['text'], t)
        else:
            if DIGIT.search(t) and not DIGIT.search(r['text']):
                why['цифры'] += 1
                continue
            text, miss = pnc_transfer(r['text'], t)
        if not text.strip():
            why['пусто'] += 1
            continue
        if miss > cfg['label_wer_max']:
            why['расхождение'] += 1
            continue
        r['text'] = text
        keep.append(r)
    return keep, why


def labels(cfg):
    w, c = paths(cfg)
    d = w / 'data'
    if (d / 'train.jsonl').exists():
        log('разметка уже готова')
        return
    model = load_model(cfg['base'])
    for raw in sorted(d.glob('raw_*.jsonl')):
        rows = jload(raw)
        t0 = time.time()
        texts = transcribe(model, [r['audio_filepath'] for r in rows], cfg['asr_batch'])
        keep, why = label_rows(rows, texts, cfg)
        name = raw.name.replace('raw_', '')
        jdump(keep, d / name)
        log(f'{name}: оставлено {len(keep)} из {len(rows)}; отброшено {why}; {time.time() - t0:.0f} с')
    # проверка при обучении — из обучающего набора по языкам (в обучение не идёт)
    tr = jload(d / 'train.jsonl')
    rng = random.Random(cfg['seed'])
    rng.shuffle(tr)
    dev = [r for lang in ('pt', 'ru') for r in [x for x in tr if x['lang'] == lang][:cfg['dev_n']]]
    held = {r['audio_filepath'] for r in dev}
    tr = [r for r in tr if r['audio_filepath'] not in held]
    # русского заметно меньше португальского: без повтора модель его забывает (шаг 16а — TAGARELA)
    hrs = {lang: sum(r['duration'] for r in tr if r['lang'] == lang) / 3600 for lang in ('pt', 'ru')}
    share = cfg['ru_min_share']
    k = 1
    if hrs['ru'] > 0 and hrs['ru'] / (hrs['ru'] + hrs['pt']) < share:
        k = min(4, math.ceil(share * hrs['pt'] / ((1 - share) * hrs['ru'])))
        tr += [r for r in tr if r['lang'] == 'ru'] * (k - 1)
    rng.shuffle(tr)
    log(f'обучение: pt {hrs["pt"]:.1f} ч, ru {hrs["ru"]:.1f} ч × {k} (повтор) → доля ru '
        f'{k * hrs["ru"] / (k * hrs["ru"] + hrs["pt"]):.0%}')
    jdump(tr, d / 'train.jsonl')
    jdump(dev, d / 'dev.jsonl')                       # одна выборка на оба языка — одна метрика val_wer


# ---------------------------------------------------------------- шум

def noise(cfg):
    import numpy as np
    import soundfile as sf
    w, c = paths(cfg)
    d = w / 'noise'
    def files_ok(manifest):
        return manifest.exists() and all(os.path.exists(r['audio_filepath']) for r in jload(manifest))
    if files_ok(d / 'noise.jsonl') and files_ok(d / 'rir.jsonl'):
        log('шум уже готов')
        return
    if (d / 'noise.jsonl').exists():
        log('списки шума на Диске есть, а файлов на этой машине нет (новая машина Colab) — скачиваю заново')
    rirs = c / 'RIRS_NOISES'
    if cfg['rir_src'] == 'rirs' and not rirs.exists():
        arc = None
        for base in OPENSLR:
            try:
                arc = fetch(f'{base}/28/rirs_noises.zip', c / 'rirs_noises.zip')
                break
            except subprocess.CalledProcessError:
                log('  зеркало не ответило:', base)
        sh(f'cd "{c}" && unzip -q "{arc}"')
    if cfg['noise_src'] == 'musan' and not (c / 'musan' / 'DONE').exists():
        for base in OPENSLR:
            try:
                fetch(f'{base}/17/musan.tar.gz', c / 'musan.tar.gz')
                break
            except subprocess.CalledProcessError:
                log('  зеркало не ответило:', base)
        sh(f'tar xzf "{c / "musan.tar.gz"}" -C "{c}" && touch "{c / "musan" / "DONE"}" && rm "{c / "musan.tar.gz"}"')
    rng = random.Random(cfg['seed'])

    def info(p):
        n = sf.info(str(p)).frames / sf.info(str(p)).samplerate
        return dict(audio_filepath=str(p), duration=round(n, 3), text='')
    if cfg['noise_src'] == 'musan':
        files = sorted(c.glob('musan/noise/*/*.wav')) + sorted(c.glob('musan/music/*/*.wav'))
        speech = sorted(c.glob('musan/speech/*/*.wav'))
    else:
        files = [p for d0 in cfg['noise_dirs'] for p in sorted(Path(d0).rglob('*.wav'))]
        speech = [p for d0 in cfg['speech_dirs'] for p in sorted(Path(d0).rglob('*.wav'))]
    rows = [info(p) for p in files]
    # говор: несколько голосов разом — как в кафе (один голос шумом не считается: он и есть речь)
    bab = c / 'babble'
    for k in range(cfg['babble_n']):
        p = bab / f'babble_{k:04d}.wav'
        if not p.exists():
            n = rng.randint(*cfg['babble_voices'])
            mix = np.zeros(SR * 15, np.float32)
            for s in rng.sample(speech, n):
                x, sr = sf.read(str(s), dtype='float32')
                if x.ndim > 1:
                    x = x.mean(axis=1)
                if len(x) < SR:
                    continue
                st = rng.randint(0, max(0, len(x) - len(mix)))
                seg = x[st:st + len(mix)]
                seg = seg / (np.sqrt(np.mean(seg ** 2)) + 1e-6)
                mix[:len(seg)] += seg
            mix /= (np.abs(mix).max() + 1e-6) / 0.5
            p.parent.mkdir(parents=True, exist_ok=True)
            sf.write(str(p), mix, SR)
        rows.append(info(p))
    jdump(rows, d / 'noise.jsonl')
    from lhotse import Recording, RecordingSet
    if cfg['rir_src'] == 'rirs':                    # настоящие и смоделированные комнаты RIRS_NOISES
        files = sorted(rirs.glob('simulated_rirs/*/*/*.wav')) + [p for p in sorted(rirs.glob('real_rirs_isotropic_noises/*.wav'))
                                                                   if 'noise' not in p.name]
    else:                                           # проверка без загрузки: простые комнаты — затухающий шум
        files = []
        for k in range(20):
            p = c / 'rir_synth' / f'rir_{k:02d}.wav'
            if not p.exists():
                n = int(SR * rng.uniform(0.2, 0.8))
                h = np.random.default_rng(k).standard_normal(n).astype(np.float32) * np.exp(-6.9 * np.arange(n) / n)
                h[0] = 1.0
                p.parent.mkdir(parents=True, exist_ok=True)
                sf.write(str(p), h / np.abs(h).max(), SR)
            files.append(p)
    rir = [info(p) for p in files]
    # RecordingSet — единственный вид комнат, который берёт загрузчик NeMo (lhotse/dataloader.py, rir_path)
    RecordingSet.from_recordings(Recording.from_file(str(p)) for p in files).to_file(str(d / 'rir_recordings.jsonl.gz'))
    jdump(rir, d / 'rir.jsonl')
    log(f'шум: {len(rows)} файлов (говор {cfg["babble_n"]}); комнат: {len(rir)}')


# ---------------------------------------------------------------- обучение

def ds_cfg(manifest, cfg, train, w):
    from omegaconf import OmegaConf
    d = dict(manifest_filepath=str(manifest), sample_rate=SR, use_lhotse=True, shuffle=train,
             num_workers=cfg['num_workers'], pin_memory=True, batch_duration=cfg['batch_duration'],
             quadratic_duration=30, num_buckets=20 if train else 1, shuffle_buffer_size=10000 if train else 0,
             max_duration=cfg['max_sec'], min_duration=cfg['min_sec'], seed=cfg['seed'])
    if train:
        # NeMo 2.7.3, lhotse/dataloader.py: шум — манифест NeMo (словарь с manifest_filepath), комнаты — RecordingSet
        # самой lhotse; без него lhotse берёт синтетические комнаты
        rir = w / 'noise' / 'rir_recordings.jsonl.gz'
        # эхо — только из файлов комнат: свой генератор комнат lhotse требует torchaudio, а его нет ни в зависимостях
        # NeMo, ни в нашем окружении (проверено на ПК: «torchaudio-free Lhotse installation»)
        d.update(noise_path=dict(manifest_filepath=str(w / 'noise' / 'noise.jsonl')), noise_snr=list(cfg['noise_snr']),
                 noise_mix_prob=cfg['noise_prob'], rir_enabled=cfg['rir_prob'] > 0 and rir.exists(),
                 rir_path=str(rir) if rir.exists() else None, rir_prob=cfg['rir_prob'])
    else:
        d['batch_duration'] = min(cfg['batch_duration'], 300)
    return OmegaConf.create(d)


def env_report():
    """Версии и пути — в журнал до обучения: в Colab ошибка с первого шага иначе неотличима от нехватки памяти."""
    import numba
    import torch
    lines = [f'torch {torch.__version__} (CUDA {torch.version.cuda}), numba {numba.__version__}, '
             f'CUDA_HOME={os.environ.get("CUDA_HOME", "")}']
    if torch.cuda.is_available():
        p = torch.cuda.get_device_properties(0)
        lines.append(f'видеокарта {p.name}, {p.total_memory / 2 ** 30:.1f} ГБ, sm_{p.major}{p.minor}')
    try:
        from numba import cuda
        from numba.cuda.cudadrv import libs, nvvm
        lines.append(f'драйвер CUDA {cuda.cudadrv.driver.driver.get_version()}, NVVM {nvvm.NVVM().get_version()} '
                     f'({libs.get_cudalib("nvvm")}), libdevice {libs.get_libdevice()}')
    except Exception as e:                            # без NVVM потеря TDT не скомпилируется
        lines.append(f'numba CUDA: ОШИБКА {type(e).__name__}: {e}')
    for line in lines:
        log(line)


def progress_callback(every=50):
    """Строка хода раз в every шагов и val_wer после проверки — вместо полосы прогресса: она пишет обновление на
    каждом шаге, и журнал на Диске вырастал до мегабайтов."""
    import lightning.pytorch as pl

    class Progress(pl.Callback):
        def __init__(self):
            self.t0 = time.time()

        def on_train_batch_end(self, trainer, pl_module, outputs, batch, batch_idx):
            s = trainer.global_step
            if s % every == 0 and s:
                loss = outputs.get('loss') if isinstance(outputs, dict) else outputs
                loss = float(loss.detach()) if hasattr(loss, 'detach') else float('nan')
                lr = trainer.optimizers[0].param_groups[0]['lr']
                el = time.time() - self.t0
                log(f'шаг {s} из {trainer.max_steps}: потеря {loss:.3f}, lr {lr:.2e}, {el / 60:.0f} мин, '
                    f'осталось ~{el / s * (trainer.max_steps - s) / 60:.0f} мин')

        def on_validation_epoch_end(self, trainer, pl_module):
            m = trainer.callback_metrics
            if 'val_wer' in m:
                log(f'проверка на шаге {trainer.global_step}: val_wer {float(m["val_wer"]):.4f}')
    return Progress()


def train(cfg):
    import lightning.pytorch as pl
    import torch
    from omegaconf import OmegaConf, open_dict
    w, c = paths(cfg)
    env_report()
    out = w / 'model' / 'parakeet-ft.nemo'
    if out.exists():
        log('обучение уже сделано:', out)
        return
    d = w / 'data'
    torch.manual_seed(cfg['seed'])
    model = load_model(cfg['base'])
    bf16 = torch.cuda.is_available() and torch.cuda.get_device_capability()[0] >= 8
    with open_dict(model.cfg):
        model.cfg.train_ds = ds_cfg(d / 'train.jsonl', cfg, True, w)
        model.cfg.validation_ds = ds_cfg(d / 'dev.jsonl', cfg, False, w)
        model.cfg.optim = OmegaConf.create(dict(
            name='adamw', lr=cfg['lr'], betas=[0.9, 0.98], weight_decay=cfg['weight_decay'],
            sched=dict(name='CosineAnnealing', warmup_steps=cfg['warmup'], min_lr=cfg['min_lr'],
                       max_steps=cfg['max_steps'])))
    model.setup_training_data(model.cfg.train_ds)
    model.setup_validation_data(model.cfg.validation_ds)
    # load_model отдаёт модель в режиме оценки, а Lightning 2.x режим подмодулей сохраняет как есть: без этого LSTM
    # декодера падает на обратном проходе («cudnn RNN backward can only be called in training mode», проверено на ПК)
    model.train()
    if cfg['freeze_encoder']:
        model.encoder.freeze()
        model.preprocessor.freeze()
    # контрольные точки Lightning — с состоянием оптимизатора, ~7 ГБ каждая: на диск машины Colab, не на Google Диск
    # (после обрыва сессии обучение начнётся заново); на Диск — только итоговая модель .nemo, 2,5 ГБ
    ckpt = c / 'ckpt'
    trainer = pl.Trainer(devices=1, accelerator='gpu', precision='bf16-mixed' if bf16 else '16-mixed',
                         max_steps=cfg['max_steps'], val_check_interval=cfg['val_every'], check_val_every_n_epoch=None,
                         limit_train_batches=cfg['val_every'], log_every_n_steps=50, gradient_clip_val=cfg['grad_clip'],
                         use_distributed_sampler=False, enable_checkpointing=False, logger=False,
                         num_sanity_val_steps=0, enable_progress_bar=False,
                         callbacks=[progress_callback(max(1, min(50, cfg['max_steps'] // 10)))])
    from nemo.utils.exp_manager import exp_manager
    exp_manager(trainer, OmegaConf.create(dict(
        exp_dir=str(ckpt), name='ckpt', create_tensorboard_logger=False, create_checkpoint_callback=True,
        checkpoint_callback_params=dict(monitor='val_wer', mode='min', save_top_k=1, always_save_nemo=False,
                                        every_n_train_steps=cfg['val_every'], every_n_epochs=None, save_last=True),
        resume_if_exists=True, resume_ignore_no_checkpoint=True, use_datetime_version=False, version='run')))
    model.set_trainer(trainer)
    model.setup_optimization(model.cfg.optim)
    log(f'обучение: {cfg["max_steps"]} шагов, пачка {cfg["batch_duration"]} с звука, lr {cfg["lr"]}, '
        f'{"bf16" if bf16 else "fp16"}; контрольные точки — {ckpt}')
    trainer.fit(model)
    # на Диск — лучшая по проверке (val_wer), а не последняя
    cb = trainer.checkpoint_callback
    best = getattr(cb, 'best_model_path', '') if cb else ''
    if best and os.path.exists(best):
        sd = torch.load(best, map_location='cpu', weights_only=False)['state_dict']
        model.load_state_dict(sd)
        log(f'лучшая проверка: {best} (val_wer {float(cb.best_model_score):.4f})')
    out.parent.mkdir(parents=True, exist_ok=True)
    model.save_to(str(out))
    log('сохранено:', out)


# ---------------------------------------------------------------- оценка

def noisy_copy(rows, cfg, c, w, snr, tag):
    """Тот же тест с подмешанным шумом: фиксированный шум для каждой фразы (обе модели слышат одно и то же)."""
    import numpy as np
    import soundfile as sf
    noise_rows = jload(w / 'noise' / 'noise.jsonl')
    rng = random.Random(f'{cfg["seed"]}-{tag}-{snr}')
    out = []
    for r in rows:
        p = c / 'noisy' / f'{tag}-{int(snr)}' / (Path(r['audio_filepath']).stem + '.flac')
        if not p.exists():
            x, _ = sf.read(r['audio_filepath'], dtype='float32')
            nz = rng.choice(noise_rows)
            n, sr = sf.read(nz['audio_filepath'], dtype='float32')
            if n.ndim > 1:
                n = n.mean(axis=1)
            if sr != SR:
                import librosa
                n = librosa.resample(n, orig_sr=sr, target_sr=SR)
            if len(n) < len(x):
                n = np.tile(n, int(math.ceil(len(x) / max(1, len(n)))))
            st = rng.randint(0, len(n) - len(x))
            n = n[st:st + len(x)]
            px, pn = np.mean(x ** 2) + 1e-9, np.mean(n ** 2) + 1e-9
            y = x + n * math.sqrt(px / (pn * 10 ** (snr / 10)))
            y /= max(1.0, float(np.abs(y).max()) / 0.99)
            p.parent.mkdir(parents=True, exist_ok=True)
            sf.write(str(p), y, SR, format='FLAC')
        out.append(dict(r, audio_filepath=str(p)))
    return out


def corpus_wer(rows, hyps):
    import numpy as np
    errs = np.array([wer(r['text'], h) * max(1, len(words_norm(r['text']))) for r, h in zip(rows, hyps)])
    n = np.array([max(1, len(words_norm(r['text']))) for r in rows])
    rng = np.random.default_rng(12345)
    idx = rng.integers(0, len(rows), (1000, len(rows)))
    return float(errs.sum() / n.sum()), errs, n, idx


def evaluate(cfg):
    import numpy as np
    w, c = paths(cfg)
    d, out = w / 'data', w / 'eval'
    out.mkdir(parents=True, exist_ok=True)
    sets = {}
    for f in sorted(d.glob('test_*.jsonl')):
        name = f.stem.replace('test_', '')
        rows = jload(f)
        sets[name] = rows
        for snr in cfg['test_snr']:
            sets[f'{name}+шум{int(snr)}'] = noisy_copy(rows, cfg, c, w, snr, name)
    jdump([dict(r, set=k) for k, v in sets.items() for r in v], out / 'sets.jsonl')
    hyps = {}
    for tag in ('base', 'ft'):
        cache = out / f'hyps_{tag}.json'
        if not cache.exists() or not set(json.loads(cache.read_text(encoding='utf-8'))) >= set(sets):
            # каждая модель — в своём процессе: видеопамять освобождается целиком, сбой одной не тянет другую
            subprocess.run([sys.executable, os.path.abspath(__file__), '_hyps', '--cfg-json', json.dumps(cfg), '--tag', tag],
                           check=True)
        hyps[tag] = json.loads(cache.read_text(encoding='utf-8'))
    lines = ['| тест | фраз | до | после | разница [95 %] |', '|---|---|---|---|---|']
    for k, rows in sets.items():
        a, ea, n, idx = corpus_wer(rows, hyps['base'][k])
        b, eb, _, _ = corpus_wer(rows, hyps['ft'][k])
        ds = np.sort((eb[idx].sum(1) - ea[idx].sum(1)) / n[idx].sum(1))
        lines.append(f'| {k} | {len(rows)} | {100 * a:.2f} % | {100 * b:.2f} % | {100 * (b - a):+.2f} '
                     f'[{100 * ds[25]:+.2f}; {100 * ds[974]:+.2f}] |')
    (out / 'summary.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print('\n'.join(lines))


# ---------------------------------------------------------------- выгрузка

def add_meta(filename, meta, external=None):
    import onnx
    m = onnx.load(filename)
    while len(m.metadata_props):
        m.metadata_props.pop()
    for k, v in meta.items():
        p = m.metadata_props.add()
        p.key, p.value = k, str(v)
    if external:
        onnx.save(m, filename, save_as_external_data=True, all_tensors_to_one_file=True, location=external)
    else:
        onnx.save(m, filename)


def export(cfg):
    """Как export_onnx.py sherpa-onnx 1.13.8 для parakeet-tdt-0.6b-v3 — те же имена, метаданные и сжатие."""
    from onnxruntime.quantization import QuantType, quantize_dynamic
    w, c = paths(cfg)
    out = w / 'export'
    if (out / 'encoder.int8.onnx').exists():
        log('выгрузка уже есть:', out)
        return
    tmp = c / 'export'
    tmp.mkdir(parents=True, exist_ok=True)
    model = load_model(w / 'model' / 'parakeet-ft.nemo')   # на видеокарте, если она есть
    os.chdir(tmp)
    with open('tokens.txt', 'w', encoding='utf-8') as f:
        for i, s in enumerate(model.joint.vocabulary):
            f.write(f'{s} {i}\n')
        f.write(f'<blk> {i + 1}\n')
    model.encoder.export('encoder.onnx')
    model.decoder.export('decoder.onnx')
    model.joint.export('joiner.onnx')
    norm = model.cfg.preprocessor.normalize
    meta = dict(vocab_size=model.decoder.vocab_size, normalize_type='' if norm == 'NA' else norm,
                pred_rnn_layers=model.decoder.pred_rnn_layers, pred_hidden=model.decoder.pred_hidden,
                subsampling_factor=8, model_type='EncDecRNNTBPEModel', version='2', model_author='NeMo',
                url='https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 (Falar: дообучение pt-BR + ru + шум)',
                comment='Only the transducer branch is exported', feat_dim=128)
    for m in ('encoder', 'decoder', 'joiner'):
        quantize_dynamic(model_input=f'./{m}.onnx', model_output=f'./{m}.int8.onnx',
                         weight_type=QuantType.QUInt8 if m == 'encoder' else QuantType.QInt8)
    add_meta('encoder.int8.onnx', meta)
    out.mkdir(parents=True, exist_ok=True)
    keep = ['encoder.int8.onnx', 'decoder.int8.onnx', 'joiner.int8.onnx', 'tokens.txt']
    if cfg['export_fp32']:
        add_meta('encoder.onnx', meta, external='encoder.weights')
        keep += ['encoder.onnx', 'encoder.weights', 'decoder.onnx', 'joiner.onnx']
    for f in keep:
        sh(f'cp "{tmp / f}" "{out / f}"')
    log('выгрузка:', out, [f'{f} {(out / f).stat().st_size / 2 ** 20:.0f} МБ' for f in keep])


def _hyps(cfg, tag):
    """Ответы одной модели на все тесты оценки (зовёт evaluate в отдельном процессе)."""
    w, _ = paths(cfg)
    out = w / 'eval'
    rows = jload(out / 'sets.jsonl')
    model = load_model(cfg['base'] if tag == 'base' else w / 'model' / 'parakeet-ft.nemo')
    texts = transcribe(model, [r['audio_filepath'] for r in rows], cfg['asr_batch'])
    res = {}
    for r, t in zip(rows, texts):
        res.setdefault(r['set'], []).append(t)
    (out / f'hyps_{tag}.json').write_text(json.dumps(res, ensure_ascii=False), encoding='utf-8')
    log(f'ответы {tag}: {len(rows)} фраз')


STAGES = dict(data=data, labels=labels, noise=noise, train=train, evaluate=evaluate, export=export)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('stage', choices=list(STAGES) + ['_hyps'])
    ap.add_argument('--cfg', help='JSON с настройками ноутбука (поверх CFG)')
    ap.add_argument('--profile', default='auto', help='auto | ' + ' | '.join(PROFILES))
    ap.add_argument('--cfg-json', help=argparse.SUPPRESS)
    ap.add_argument('--tag', help=argparse.SUPPRESS)
    a = ap.parse_args()
    try:
        if a.stage == '_hyps':
            _hyps(json.loads(a.cfg_json), a.tag)
        else:
            over = json.loads(Path(a.cfg).read_text(encoding='utf-8')) if a.cfg else {}
            cfg = with_profile(dict(CFG, **over, _set=tuple(over)), over.get('profile', a.profile))
            log('этап', a.stage, '· профиль', cfg['profile'])
            STAGES[a.stage](cfg)
    except SystemExit as e:
        if e.code not in (None, 0):
            print(e.code if isinstance(e.code, str) else f'выход {e.code}', file=sys.stderr, flush=True)
            os._exit(1)
    except BaseException:
        import traceback
        traceback.print_exc()
        sys.stdout.flush(); sys.stderr.flush()
        os._exit(1)
    # выход сразу, без обхода нитей: у потоков datasets при обычном завершении Python падал «PyGILState_Release»
    # (код 134) уже после сделанной работы, и ноутбук остановил бы «Выполнить всё» на успешном этапе
    sys.stdout.flush(); sys.stderr.flush()
    os._exit(0)


if __name__ == '__main__':
    main()
