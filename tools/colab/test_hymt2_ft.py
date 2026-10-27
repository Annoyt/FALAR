#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверка tools/colab/hymt2_ft.py на ПК — маленькие входы, без видеокарты и без больших загрузок:
- текст и подсказки — как в приложении;
- корпус с границами фильмов, отбор, примеры;
- кодирование настоящим токенизатором Hy-MT2: потери только на ответе;
- шум распознавания на голосах и распознавателе приложения, в том числе через пул процессов, как в Colab;
- обучение крошечной модели той же архитектуры (LoRA, 3 шага) и перевод ею;
- с --gguf — выгрузка в GGUF конвертером llama.cpp той же версии и чтение llama.cpp ПК;
- с --pipeline — весь конвейер этапами ноутбука в миниатюре: настоящие субтитры (300 тыс. строк), шум,
  обучение, оценка с COMET (окружение ПК с видеокартой), сборка llama.cpp и Q4_K_M.

    ~/tools/venv-colab-test/bin/python tools/colab/test_hymt2_ft.py [--gguf] [--pipeline]

Нужны окружение с пинами ноутбука (transformers 4.57.6, peft 0.18.1, accelerate 1.10.1, sherpa-onnx 1.13.8,
soundfile, scipy, sacrebleu), файлы токенизатора Hy-MT2 без весов (FALAR_HYMT_TOK, по умолчанию
~/.cache/falar-stand/colab-test/hymt2-tok) и модели приложения (models/asr_multi, models/tts_pt, models/tts_ru).
"""
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
R = HERE.parent.parent
sys.path.insert(0, str(HERE))
import hymt2_ft as H  # noqa: E402

TOK = Path(os.environ.get('FALAR_HYMT_TOK', Path.home() / '.cache/falar-stand/colab-test/hymt2-tok'))
RUN = Path.home() / '.cache/falar-stand/colab-test/run'
FAILS = []


def check(cond, what):
    print(('  ок   ' if cond else '  ПРОВАЛ ') + what, flush=True)
    if not cond:
        FAILS.append(what)


def models_dir():
    own = R / 'models'
    if (own / 'asr_multi').exists():
        return own
    common = subprocess.run(['git', '-C', str(R), 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                            capture_output=True, text=True).stdout.strip()
    return Path(common).parent / 'models'


def test_text():
    print('текст и подсказки')
    check(H.clean_line('<i>Olá, tudo bem?</i>') == 'Olá, tudo bem?', 'разметка снимается')
    check(H.clean_line('{\\an8}Cuidado!') == 'Cuidado!', 'теги ASS снимаются')
    for s in ('♪ Garota de Ipanema ♪', '[RISOS]', '(вздыхает)', '- Oi. - Tudo bem?', 'Legendas por Fulano', '...',
              'Субтитры сделаны кем-то'):
        check(H.clean_line(s) is None, f'не реплика: {s}')
    check(H.clean_line('- Vamos embora.') == 'Vamos embora.', 'тире одного говорящего снимается')
    check(H.key('Olá, Tudo  bem?') == 'olá tudo bem', 'ключ фразы')
    check(H.usable_pair('Bom dia.', 'Доброе утро.', 30) and not H.usable_pair('Sim.', 'Да, конечно, я согласен с вами.', 30),
          'пары по длине')
    check(abs(H.wer('a b c', 'a x c') - 1 / 3) < 1e-9 and H.wer('Olá, mundo!', 'olá mundo') == 0, 'WER без знаков и регистра')
    a, b, c = 'a' * 100, 'b' * 100, 'c' * 100
    f, t = H.brief_fit, H.brief_tail
    w = 'um dois tres quatro cinco seis sete oito nove dez onze doze treze catorze quinze'
    ok = (f([a, b, c], 303) == [a, b, c] and f([a, b, c], 302) == [b, c] and f([a, b, c], 201) == [c]
          and f([a, None, '  ', b], 500) == [a, b] and f([w], 12) == [] and len(f([w], 13)) == 1
          and t('one two three four', 11) == '…three four' and t('one two three four', 9) == '…four'
          and t('abc def', 5) == '…def' and t('abcdefghij', 5) == '…ghij' and t('abcdef', 1) == '…')
    check(ok, 'Brief.fit / Brief.tail — случаи BriefTest')
    check(H.prompt('Olá', 'ru', simple=True) == 'Translate the following segment into Russian, without additional '
          'explanation.\n\nOlá', 'простая подсказка — как mt_bench hymt')
    want = ('[Background Information]\nA\nB\nPlease translate the following text into Portuguese (Brazil), taking the '
            'provided background information into consideration\n[Source Text]\nПривет')
    check(H.prompt('Привет', 'pt', ['A', 'B']) == want, 'подсказка уточнителя — как TranslatorService.hyPrompt')
    check(H.prompt('Привет', 'pt', []).startswith('[Background Information]\n\nPlease'), 'пустой фон — как в приложении')


def test_corpus():
    print('корпус, отбор, примеры')
    ids = ['a.xml\tb.xml\t1\t1', 'a.xml\tb.xml\t2\t2', 'a.xml\tb.xml\t3\t3', 'c.xml\td.xml\t1\t1', 'c.xml\td.xml\t2\t2']
    pt = ['Olá.', '<i>Tudo bem?</i>', 'Onde fica o banco?', 'Bom dia.', 'Obrigado pela ajuda.']
    ru = ['Привет.', 'Как дела?', 'Где банк?', 'Доброе утро.', 'Спасибо за помощь.']
    cfg = dict(H.CFG, ctx_lines=5, max_words=30)
    rows = list(H.corpus_rows(pt, ru, ids, cfg, {'где банк'}, {'train': 1.0, 'dev': 1.0, 'test': 1.0}))
    check([r['pt'] for r in rows] == ['Olá.', 'Tudo bem?', 'Bom dia.', 'Obrigado pela ajuda.'], 'фразы наборов проверки исключены')
    check(rows[1]['ctx_pt'] == ['Olá.'] and rows[1]['ctx_ru'] == ['Привет.'], 'контекст — прошлые реплики фильма')
    check(rows[2]['ctx_pt'] == [] and rows[3]['ctx_pt'] == ['Bom dia.'], 'на границе фильма контекст сбрасывается')
    check(all(r['part'] == H.split_of(r['doc']) for r in rows), 'часть — по фильму')
    import numpy as np
    vec = {'Olá.': [1, 0], 'Привет.': [1, 0], 'Tudo bem?': [1, 0], 'Как дела?': [0, 1]}
    keep = H.labse_filter(rows[:2], lambda xs: np.array([vec[x] for x in xs], float), 0.75)
    check([r['pt'] for r in keep] == ['Olá.'] and keep[0]['labse'] == 1.0, 'отсев по LaBSE')
    syn = [dict(part=p, doc=f'{p}{i}', pt=f'Frase {p} {i} aqui.', ru=f'Фраза {p} {i} тут.', ctx_pt=['Antes.'], ctx_ru=['Раньше.'])
           for p, n in (('train', 12), ('dev', 6), ('test', 6)) for i in range(n)]
    syn.append(dict(syn[0]))                                   # повтор выбрасывается
    sel = H.select_rows(syn, dict(H.CFG, n_train=8, n_dev=2, n_test=2, noisy_share=0.5))
    by = {p: [r for r in sel if r['part'] == p] for p in ('train', 'dev', 'test')}
    check(len(by['train']) == 8 and len(by['dev']) == 4 and len(by['test']) == 4, 'размеры частей')
    check(sum(r['dir'] == 'pt2ru' for r in by['train']) == 4, 'направления поровну')
    check(sum(r['noisy'] for r in by['train']) == 4 and all(r['noisy'] for r in by['test'])
          and not any(r['noisy'] for r in by['dev']), 'шум: доля обучения, весь тест, без проверки')
    import random
    rng = random.Random(1)
    r = dict(syn[0], dir='pt2ru', noisy=True, asr='frase treinou aqui', wer=0.4)
    ex = H.make_example(r, dict(H.CFG, simple_share=1.0), rng)
    check(ex and 'frase treinou aqui' in ex['messages'][0]['content'] and ex['messages'][1]['content'] == r['ru'],
          'пример с шумом: на входе распознанное, ответ — перевод сказанного')
    check(H.make_example(dict(r, wer=0.9), H.CFG, rng) is None, 'слишком испорченное — без примера')
    ex = H.make_example(dict(r, noisy=False), dict(H.CFG, simple_share=0.0, empty_bg=0.0), rng)
    check(ex['messages'][0]['content'].startswith('[Background Information]\nAntes.\n'), 'подсказка уточнителя с контекстом')
    return syn


def test_encode():
    print('кодирование токенизатором Hy-MT2')
    from transformers import AutoTokenizer
    tok = AutoTokenizer.from_pretrained(str(TOK))
    ex = dict(messages=[dict(role='user', content=H.prompt('Bom dia, tudo bem?', 'ru', ['Oi.'])),
                        dict(role='assistant', content='Доброе утро, как дела?')])
    e = H.encode(tok, ex, 768)
    n = sum(1 for x in e['labels'] if x == -100)
    check(tok.decode(e['input_ids'][:n]).endswith('<｜hy_Assistant｜>'), 'подсказка кончается началом ответа')
    check(e['labels'][n:] == e['input_ids'][n:] and e['input_ids'][-1] == tok.eos_token_id == 120020,
          'потери — на ответе и его конце (<｜hy_place▁holder▁no▁2｜>)')
    check(tok.decode(e['input_ids'][n:-1]) == 'Доброе утро, как дела?', 'ответ кодируется без искажений')
    full = tok.apply_chat_template(ex['messages'], tokenize=True)
    check(list(full[:len(e['input_ids'])]) == e['input_ids'], 'токены совпадают с шаблоном чата модели')
    check(H.encode(tok, ex, 10) is None, 'длиннее предела — без примера')
    return tok


def test_noisy():
    print('шум распознавания: голоса и распознаватель приложения')
    import numpy as np
    import soundfile as sf
    m = models_dir()
    c = RUN / 'cache'
    (c / 'voices').mkdir(parents=True, exist_ok=True)
    for v, d in (('pt_BR-faber-medium', 'tts_pt'), ('ru_RU-dmitri-medium', 'tts_ru')):
        link = c / 'voices' / f'vits-piper-{v}'
        if not link.exists():
            link.symlink_to(m / d)
    if not (c / 'asr').exists():
        (c / 'asr').symlink_to(m / 'asr_multi')
    rng = np.random.default_rng(1)
    sf.write(c / 'noise.wav', (rng.standard_normal(48000) * 0.1).astype(np.float32), 16000)
    sf.write(c / 'rir.wav', (np.exp(-np.arange(4800) / 800.0) * rng.standard_normal(4800)).astype(np.float32), 16000)
    lists = dict(rir=[str(c / 'rir.wav')], noise=[str(c / 'noise.wav')], music=[], babble=[str(c / 'noise.wav')])
    voices = {'pt': ['pt_BR-faber-medium'], 'ru': ['ru_RU-dmitri-medium']}
    base = dict(rir_prob=0.0, clean_audio=1.0, snr_db=(0, 20), seed=1, voices=voices)
    H._init_worker(str(c), lists)
    texts = [('pt', 'Bom dia, onde fica a farmácia mais próxima?'), ('ru', 'Добрый день, где здесь ближайшая аптека?')]
    t0 = time.time()
    clean = [H._noisy_one((i, lang, t, base)) for i, (lang, t) in enumerate(texts)]
    dt = (time.time() - t0) / len(texts)
    ws = [H.wer(t, r['asr']) for (_, t), r in zip(texts, clean)]
    check(all(w <= 0.25 for w in ws), f'без шума распознано почти всё: WER {[round(w, 2) for w in ws]}')
    loud = dict(base, clean_audio=0.0, rir_prob=1.0, snr_db=(-5.0, -5.0))
    noisy = [H._noisy_one((i, lang, t, loud)) for i, (lang, t) in enumerate(texts)]
    check(all('rir' in r['cond'] and 'dB' in r['cond'] for r in noisy), 'комната и шум легли')
    print(f'       при −5 dB: WER {[round(H.wer(t, r["asr"]), 2) for (_, t), r in zip(texts, noisy)]}; '
          f'{dt:.2f} с на фразу на одном ядре ПК')
    again = H._noisy_one((1, 'ru', texts[1][1], loud))
    check((again['voice'], again['cond']) == (noisy[1]['voice'], noisy[1]['cond']),
          'голос, комната и шум — от зерна фразы (озвучка VITS случайна сама по себе)')
    check(H.script_ok('ru', 'Добрый день') and not H.script_ok('ru', 'Dobry D, gdzie apteka?')
          and H.script_ok('pt', 'Bom dia') and not H.script_ok('pt', 'Добрый'), 'распознанное чужим алфавитом отсекается')
    import multiprocessing as mp
    with mp.get_context('spawn').Pool(2, initializer=H._init_worker, initargs=(str(c), lists)) as pool:
        res = pool.map(H._noisy_one, [(i, lang, t, base) for i, (lang, t) in enumerate(texts)])
    check(len(res) == 2 and all(r['asr'] for r in res) and [r['cond'] for r in res] == [r['cond'] for r in clean],
          'пул процессов (spawn), как в Colab, работает')


def tiny_base():
    """Крошечная модель архитектуры Hy-MT2 со случайными весами и настоящим токенизатором."""
    from transformers import AutoConfig, AutoModelForCausalLM
    d = RUN / 'tiny'
    if (d / 'config.json').exists():
        return d
    cfg = AutoConfig.from_pretrained(str(TOK))
    for k, v in dict(hidden_size=64, num_hidden_layers=2, num_attention_heads=4, num_key_value_heads=2,
                     intermediate_size=128, head_dim=16, attention_head_dim=16).items():
        if hasattr(cfg, k):
            setattr(cfg, k, v)
    m = AutoModelForCausalLM.from_config(cfg)
    m.save_pretrained(str(d))
    for f in TOK.iterdir():
        if f.name != 'config.json':
            shutil.copy(f, d / f.name)
    return d


def test_train(syn):
    print('обучение крошечной модели той же архитектуры')
    import random
    base = tiny_base()
    work = RUN / 'work'
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    rng = random.Random(1)
    rows = [dict(r, dir='pt2ru' if i % 2 else 'ru2pt', noisy=False) for i, r in enumerate(syn)]
    H.jdump([H.make_example(r, H.CFG, rng) for r in rows[:20]], work / 'sft_train.jsonl')
    H.jdump([H.make_example(r, H.CFG, rng) for r in rows[20:24]], work / 'sft_dev.jsonl')
    cfg = dict(H.CFG, work=str(work), cache=str(RUN / 'cache'), base=str(base), batch=2, accum=1, max_len=256)
    out = H.train(cfg, max_steps=3)
    check((out / 'DONE').exists() and (out / 'adapter_model.safetensors').exists(), 'LoRA обучилась и сохранилась')
    check(H.train(cfg, max_steps=3) == out, 'повторный запуск — пропуск готового этапа')
    tok, model = H.load_for_eval(cfg, 'ft')
    hyp = H.translate(tok, model, [H.prompt('Olá', 'ru', simple=True), H.prompt('Привет', 'pt', ['Oi.'])], batch=2, max_new=5)
    check(len(hyp) == 2 and all(isinstance(h, str) for h in hyp), 'перевод пачкой с левым дополнением')
    return cfg, base


def test_gguf(cfg, base):
    print('выгрузка в GGUF (llama.cpp ' + H.CFG['llama_cpp_tag'] + ') и чтение llama.cpp ПК')
    import torch
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer
    lc = RUN.parent / f'llama.cpp-{H.CFG["llama_cpp_tag"]}'
    if not lc.exists():
        subprocess.run(['git', 'clone', '-q', '--depth', '1', '--branch', H.CFG['llama_cpp_tag'],
                        'https://github.com/ggml-org/llama.cpp', str(lc)], check=True)
    subprocess.run(['/home/not_me/.local/bin/uv', 'pip', 'install', '-q', '--python', sys.executable, str(lc / 'gguf-py'),
                    'sentencepiece'], check=True)
    merged = RUN / 'merged'
    shutil.rmtree(merged, ignore_errors=True)
    m = PeftModel.from_pretrained(AutoModelForCausalLM.from_pretrained(str(base), torch_dtype=torch.float32),
                                  str(Path(cfg['work']) / 'lora')).merge_and_unload()
    m.save_pretrained(str(merged), safe_serialization=True)
    AutoTokenizer.from_pretrained(str(base)).save_pretrained(str(merged))
    for f in ('chat_template.jinja', 'generation_config.json'):
        shutil.copy(Path(base) / f, merged / f)
    out = RUN / 'tiny.gguf'
    r = subprocess.run([sys.executable, str(lc / 'convert_hf_to_gguf.py'), str(merged), '--outtype', 'f32',
                        '--outfile', str(out)], capture_output=True, text=True)
    check(r.returncode == 0 and out.exists(), 'конвертер llama.cpp принял слитую модель' +
          ('' if r.returncode == 0 else ': ' + r.stderr[-400:]))
    probe = ('import sys, llama_cpp; from llama_cpp import Llama; '
             'm = Llama(model_path=sys.argv[1], n_ctx=256, verbose=False); '
             'r = m.create_chat_completion(messages=[{"role": "user", "content": "Olá"}], max_tokens=4, temperature=0); '
             'print(llama_cpp.__version__, "читает, ответ:", repr(r["choices"][0]["message"]["content"][:20]))')
    main_py = models_dir().parent / '.venv' / 'bin' / 'python'
    d = json.loads((R / 'data' / 'mt_test' / 'tatoeba.json').read_text(encoding='utf-8'))
    strings = [it['pt'] for it in d['pt_to_ru'][:300]] + [it['ru'] for it in d['ru_to_pt'][:300]] + \
        ['R$ 1.250,00 em 3x', 'Цена — 1 500 ₽, скидка 10%.', 'COVID-19', '1990s', "d'água", 'Ёлка', 'ok👍', 'linha1\nlinha2']
    sp = RUN / 'tok_strings.json'
    sp.write_text(json.dumps(strings, ensure_ascii=False), encoding='utf-8')
    tok = AutoTokenizer.from_pretrained(str(base))
    hf = [tok(x, add_special_tokens=False)['input_ids'] for x in strings]
    lp = ('import json, sys; from llama_cpp import Llama; s = json.load(open(sys.argv[2])); '
          'm = Llama(model_path=sys.argv[1], vocab_only=True, verbose=False); '
          'print(json.dumps([m.tokenize(x.encode(), add_bos=False, special=False) for x in s]))')
    p = subprocess.run([str(main_py), '-c', lp, str(out), str(sp)], capture_output=True, text=True)
    ll = json.loads(p.stdout) if p.returncode == 0 else []
    same = sum(x == y for x, y in zip(hf, ll))
    check(same == len(strings), f'деление на токены в GGUF — как в transformers: {same} из {len(strings)}')
    for name, env in (('общий .venv (llama.cpp ПК, процессор)', {}),
                      ('~/tools/llama-cuda (видеокарта)', {'PYTHONPATH': str(Path.home() / 'tools/llama-cuda')})):
        p = subprocess.run([str(main_py), '-c', probe, str(out)], capture_output=True, text=True, env=dict(os.environ, **env))
        check(p.returncode == 0, f'{name}: ' + (p.stdout.strip() or p.stderr.strip()[-300:]))


def opus_mini(n=300_000):
    """Первые n строк настоящего архива OPUS — чтением zip по частям, без загрузки 741 МБ — в маленький zip того же
    вида: те же имена файлов, те же строки."""
    import io
    import urllib.request
    import zipfile
    out = RUN.parent / f'opus-mini-{n}.zip'
    if out.exists():
        return out

    class HttpFile(io.RawIOBase):
        def __init__(self, url):
            self.url, self.pos = url, 0
            self.size = int(urllib.request.urlopen(urllib.request.Request(url, method='HEAD'), timeout=60)
                            .headers['Content-Length'])

        def seekable(self):
            return True

        def readable(self):
            return True

        def seek(self, off, whence=0):
            self.pos = off if whence == 0 else self.pos + off if whence == 1 else self.size + off
            return self.pos

        def tell(self):
            return self.pos

        def readinto(self, b):
            if self.pos >= self.size:
                return 0
            end = min(self.size, self.pos + len(b)) - 1
            data = urllib.request.urlopen(urllib.request.Request(self.url, headers={'Range': f'bytes={self.pos}-{end}'}),
                                          timeout=120).read()
            b[:len(data)] = data
            self.pos += len(data)
            return len(data)

    z = zipfile.ZipFile(io.BufferedReader(HttpFile(H.CFG['opus_url']), buffer_size=1 << 20))
    with zipfile.ZipFile(f'{out}.tmp', 'w', zipfile.ZIP_DEFLATED) as w:
        for name in z.namelist():
            if name.startswith('OpenSubtitles.'):
                with z.open(name) as f:
                    w.writestr(name, b''.join(f.readline() for _ in range(n)))
    os.replace(f'{out}.tmp', out)
    return out


def test_pipeline(base):
    print('весь конвейер в миниатюре: настоящие субтитры (300 тыс. строк), крошечная модель, COMET и llama.cpp ПК')
    import numpy as np
    import soundfile as sf
    work, cache = RUN / 'pipe-work', RUN / 'pipe-cache'
    for d in (work, cache):
        shutil.rmtree(d, ignore_errors=True)
    cache.mkdir(parents=True)
    m = models_dir()
    (cache / 'voices').mkdir()                    # голоса и распознаватель приложения — вместо загрузок
    for v, d in (('pt_BR-faber-medium', 'tts_pt'), ('ru_RU-dmitri-medium', 'tts_ru')):
        (cache / 'voices' / f'vits-piper-{v}').symlink_to(m / d)
    (cache / 'asr').symlink_to(m / 'asr_multi')
    rr = cache / 'RIRS_NOISES'                     # шумы — синтетические, в раскладке RIRS_NOISES
    (rr / 'simulated_rirs' / 'smallroom' / 'Room001').mkdir(parents=True)
    (rr / 'pointsource_noises').mkdir()
    g = np.random.default_rng(1)
    sf.write(rr / 'simulated_rirs/smallroom/Room001/Room001-00001.wav',
             (np.exp(-np.arange(4800) / 800.0) * g.standard_normal(4800)).astype(np.float32), 16000)
    sf.write(rr / 'pointsource_noises/noise-free-sound-0001.wav', (g.standard_normal(48000) * 0.1).astype(np.float32), 16000)
    lc = RUN.parent / f'llama.cpp-{H.CFG["llama_cpp_tag"]}'
    if not lc.exists():
        subprocess.run(['git', 'clone', '-q', '--depth', '1', '--branch', H.CFG['llama_cpp_tag'],
                        'https://github.com/ggml-org/llama.cpp', str(lc)], check=True)
    (cache / 'llama.cpp').symlink_to(lc)
    comet_py = Path.home() / 'tools/venv-comet-gpu/bin/python'     # окружение COMET ПК с видеокартой

    def comet_env(c):
        (Path(c) / 'comet_score.py').write_text(H.COMET_SCRIPT)
        return comet_py
    H.comet_env = comet_env
    cfg = dict(H.CFG, work=str(work), cache=str(cache), base=str(base), opus_url='file://' + str(opus_mini()),
               opus_lines=300_000, n_train=200, n_dev=20, n_test=20, noisy_share=0.5, noise='rirs',
               voices={'pt': ['pt_BR-faber-medium'], 'ru': ['ru_RU-dmitri-medium']}, workers=2, batch=4, accum=1,
               max_len=256, eval_limit=40, gen_batch=16, build_jobs=2, comet_batch=8,
               comet_model=str(m / 'comet' / 'wmt22-comet-da' / 'checkpoints' / 'model.ckpt'))
    t0 = time.time()
    H.corpus(cfg)
    rows = H.jload(work / 'corpus.jsonl')
    check(len(rows) > 300 and {r['part'] for r in rows} == {'train', 'dev', 'test'}, f'корпус: {len(rows)} кандидатов, три части')
    check(sum(1 for r in rows if r['ctx_pt']) > len(rows) // 2, 'у большинства кандидатов есть прошлые реплики')
    H.jdump(H.labse_filter(rows, lambda xs: np.ones((len(xs), 2)) / np.sqrt(2), cfg['labse_min']), work / 'corpus_labse.jsonl')
    H.select(cfg)
    H.noisy(cfg)
    nz = [r for r in H.jload(work / 'noisy.jsonl') if r.get('noisy')]
    check(len(nz) >= 100 and sum(1 for r in nz if r.get('asr')) >= 0.8 * len(nz), f'шум: распознано {len(nz)} фраз')
    H.sft(cfg)
    ex = H.jload(work / 'sft_train.jsonl')
    check(len(ex) >= 120 and all(e['messages'][1]['role'] == 'assistant' for e in ex), f'примеров обучения: {len(ex)}')
    H.train(cfg)
    H.evaluate(cfg)
    summ = (work / 'eval' / 'summary.md').read_text(encoding='utf-8')
    check(summ.count('\n') >= 12 and 'noisyctx_pt2ru' in summ and 'tatoeba_ru2pt' in summ, 'оценка: таблица по всем наборам')
    first = json.load(open(work / 'eval' / 'ft_noisy_pt2ru.json', encoding='utf-8'))[0]
    check('comet' in first and first['src'] != first['prompt'], 'COMET посчитан; исходник для COMET — сказанное')
    H.export(cfg)
    ggufs = sorted((work / 'export').glob('*.gguf'))
    check(len(ggufs) == 2, 'выгрузка: два GGUF Q4_K_M — ' + ', '.join(f.name for f in ggufs))
    main_py = models_dir().parent / '.venv' / 'bin' / 'python'
    probe = ('import sys; from llama_cpp import Llama; m = Llama(model_path=sys.argv[1], n_ctx=256, verbose=False); '
             'm.create_chat_completion(messages=[{"role": "user", "content": "Olá"}], max_tokens=4, temperature=0); print("ок")')
    for f in ggufs:
        p = subprocess.run([str(main_py), '-c', probe, str(f)], capture_output=True, text=True)
        check(p.returncode == 0, f'{f.name} читается llama.cpp ПК')
    print(f'       весь конвейер: {time.time() - t0:.0f} с')


def main():
    RUN.mkdir(parents=True, exist_ok=True)
    test_text()
    syn = test_corpus()
    test_encode()
    test_noisy()
    cfg, base = test_train(syn)
    if '--gguf' in sys.argv:
        test_gguf(cfg, base)
    if '--pipeline' in sys.argv:
        test_pipeline(base)
    print(f'\nпровалов {len(FAILS)}' + ('' if not FAILS else ': ' + '; '.join(FAILS)))
    sys.exit(1 if FAILS else 0)


if __name__ == '__main__':
    main()
