#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Голос самого приложения против голосов разговора: можно ли не глушить микрофон, пока телефон говорит.

Владелец 01.10: «когда идёт отправка в облако, он перестаёт записывать диалог» — по журналу это
заглушка микрофона на время озвучки. Предложено: с голосами разговора микрофон не глушить, а голос
приложения (Piper) отсеет отпечаток — он не участник. Замер на ПК, эталонный отпечаток
(tools/voiceprint_ref.py) и то же распознавание, что в приложении (parakeet через sherpa-onnx):

  T1. Озвучка одна: косинус голоса Piper (pt_BR-faber, ru_RU-dmitri; results/tts) к слепкам живых людей
      из корпуса — пройдёт ли она порог слушания (Voices.hear).
  T2. Человек поверх озвучки: живая фраза участника начинается через 0,5 с после начала озвучки, громкость
      человека относительно озвучки r дБ. Узнаётся ли участник, сколько его слов в распознанном и сколько
      слов озвучки туда протекло.
  T3. Человек сразу после озвучки (пауза 0,3 с) одним куском — то, что получит слушание без заглушки,
      если нарезка не разрежет их паузой.

  .venv/bin/python tools/voices_echo.py            # таблицы — в stdout (markdown)

Ограничение: смесь цифровая, без комнаты и динамика телефона; уровень озвучки у микрофона телефона
меряется на телефоне отдельно.
"""
import bz2
import glob
import os
import re
import sys
import wave

import numpy as np
from scipy.signal import resample_poly

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
import voiceprint_ref as V  # noqa: E402

RNG = np.random.default_rng(20261001)
HEAR = {1: 0.38, 2: 0.40, 3: 0.42}


def read(p):
    with wave.open(p) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32) / 32768
        sr = w.getframerate()
    return x if sr == 16000 else resample_poly(x, 16000, sr).astype(np.float32)


def rms(x):
    return float(np.sqrt(np.mean(x ** 2)) + 1e-9)


def noise(sec, db=-60):
    return RNG.normal(0, 10 ** (db / 20), int(sec * 16000)).astype(np.float32)


def speakers():
    idx = {}
    for code in ('por', 'rus'):
        with bz2.open(f'{R}/data/tatoeba/raw/{code}_sentences_with_audio.tsv.bz2', 'rt', encoding='utf-8') as f:
            for line in f:
                c = line.rstrip('\n').split('\t')
                if len(c) >= 3:
                    idx[c[1]] = c[2]
    out = {}
    for lang in ('pt', 'ru'):
        for l in open(f'{R}/bench/air/corpus/{lang}/LICENSES.tsv', encoding='utf-8'):
            st, aid = l.split('\t')[:2]
            u = idx.get(aid)
            if u:
                out.setdefault((u, lang), []).append(f'{R}/bench/air/corpus/{lang}/{st}')
    return {k: sorted(v) for k, v in out.items() if len(v) >= 4}


_asr = None


def asr(x):
    global _asr
    import sherpa_onnx
    if _asr is None:
        d = os.environ.get('ASR_DIR', os.path.join(R, 'models', 'asr_multi'))
        _asr = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=f'{d}/encoder.int8.onnx', decoder=f'{d}/decoder.int8.onnx', joiner=f'{d}/joiner.int8.onnx',
            tokens=f'{d}/tokens.txt', num_threads=2, model_type='nemo_transducer')
    s = _asr.create_stream()
    s.accept_waveform(16000, np.concatenate([noise(0.5), x]))
    _asr.decode_stream(s)
    return s.result.text


def words(t):
    return [w for w in re.sub(r"[^\w' -]", ' ', t.lower()).split() if len(w) > 1]


def recall(ref, hyp):
    r, h = words(ref), set(words(hyp))
    return sum(w in h for w in r) / max(1, len(r))


def unit(v):
    return v / np.linalg.norm(v)


def main():
    spk = speakers()
    prints = {}
    tests = {}
    for k, files in spk.items():
        e = [V.embed(read(p + '.wav')) for p in files[:3]]
        prints[k] = {1: e[0], 2: unit(e[0] + e[1]), 3: unit(e[0] + e[1] + e[2])}
        tests[k] = files[3:9]
    tts = sorted(glob.glob(f'{R}/results/tts/pt_BR-faber-medium/*.wav')) + sorted(glob.glob(f'{R}/results/tts/ru_RU-dmitri-medium/*.wav'))
    print(f'Участников: {len(prints)} ({", ".join(u for u, _ in prints)}); фраз озвучки: {len(tts)}\n')

    # T1 — озвучка одна
    print('## T1. Озвучка одна против слепков участников\n')
    print('| голос | фраз | косинус к ближайшему слепку: медиана · p95 · макс | прошла порог слушания (слепок 1 / 2 / 3 фразы) |')
    print('|---|---|---|---|')
    for voice in ('pt_BR-faber-medium', 'ru_RU-dmitri-medium'):
        best = {1: [], 2: [], 3: []}
        for p in [t for t in tts if voice in t]:
            e = V.embed(np.concatenate([noise(1.0), read(p), noise(0.3)]))
            for kk in (1, 2, 3):
                best[kk].append(max(float(e @ pr[kk]) for pr in prints.values()))
        b3 = np.array(best[3])
        passed = ' / '.join(f'{sum(c >= HEAR[kk] for c in best[kk])} из {len(best[kk])}' for kk in (1, 2, 3))
        print(f'| {voice} | {len(b3)} | {np.median(b3):.2f} · {np.percentile(b3, 95):.2f} · {b3.max():.2f} | {passed} |')

    # T2, T3 — человек поверх озвучки и сразу после неё
    tts_text = {p: asr(read(p)) for p in tts}
    pairs = []
    keys = list(tests)
    for i, k in enumerate(keys):
        for j, h in enumerate(tests[k][:3]):
            pairs.append((k, h, tts[(i * 3 + j) % len(tts)]))
    print(f'\n## T2. Человек поверх озвучки (начинает через 0,5 с) — {len(pairs)} пар\n')
    print('| человек к озвучке, дБ | участник узнан (слепок 3 фразы, порог 0,42) | косинус к его слепку, медиана | его слов в распознанном, медиана | слов озвучки протекло, медиана |')
    print('|---|---|---|---|---|')
    for r in (-15, -10, -5, 0, 5, 10):
        ok = cos = rec = leak = 0; cs, rs, ls = [], [], []
        for k, h, t in pairs:
            hx, tx = read(h + '.wav'), read(t)
            hx = hx * (rms(tx) * 10 ** (r / 20) / rms(hx))
            n = max(len(tx), 8000 + len(hx))
            mix = np.zeros(n, np.float32); mix[:len(tx)] += tx; mix[8000:8000 + len(hx)] += hx
            seg = np.concatenate([noise(1.0), mix, noise(0.3)])
            c = float(V.embed(seg) @ prints[k][3]); cs.append(c); ok += c >= HEAR[3]
            hyp = asr(mix); rs.append(recall(open(h + '.txt', encoding='utf-8').read(), hyp)); ls.append(recall(tts_text[t], hyp))
        print(f'| {r:+d} | {ok} из {len(pairs)} | {np.median(cs):.2f} | {np.median(rs) * 100:.0f} % | {np.median(ls) * 100:.0f} % |')

    print(f'\n## T3. Человек сразу после озвучки, одним куском (пауза 0,3 с) — {len(pairs)} пар\n')
    print('| человек к озвучке, дБ | участник узнан | косинус, медиана | его слов, медиана | слов озвучки протекло, медиана |')
    print('|---|---|---|---|---|')
    for r in (-10, 0, 10):
        ok = 0; cs, rs, ls = [], [], []
        for k, h, t in pairs:
            hx, tx = read(h + '.wav'), read(t)
            hx = hx * (rms(tx) * 10 ** (r / 20) / rms(hx))
            mix = np.concatenate([tx, noise(0.3), hx])
            c = float(V.embed(np.concatenate([noise(1.0), mix, noise(0.3)])) @ prints[k][3]); cs.append(c); ok += c >= HEAR[3]
            hyp = asr(mix); rs.append(recall(open(h + '.txt', encoding='utf-8').read(), hyp)); ls.append(recall(tts_text[t], hyp))
        print(f'| {r:+d} | {ok} из {len(pairs)} | {np.median(cs):.2f} | {np.median(rs) * 100:.0f} % | {np.median(ls) * 100:.0f} % |')


if __name__ == '__main__':
    main()
