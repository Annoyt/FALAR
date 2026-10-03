#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Эхо озвучки в микрофоне телефона: разбор записей стенда (bench/apk/test_aec_device.sh).

  .venv/bin/python tools/aec_eval.py <каталог записей>      # ~/.cache/falar-stand/aec-<время>

Что считается:
  1. По каждой записи (vr, vr_aec, vc, vc_aec): задержка «отдали в динамик → слышно в микрофоне»
     (взаимная корреляция с тем, что играли), фон до озвучки, уровень эха во время неё.
  2. Свой подавитель на записи без подавителя (vr): адаптивный фильтр в частотной области
     (разбитый по блокам, нормированный LMS — как в Speex/WebRTC AEC до нелинейной части) по известной
     озвучке; ERLE — насколько тише стало эхо.
  3. Человек поверх озвучки: в запись vr цифрово подмешана живая фраза из корпуса (на r дБ тише эха,
     как у микрофона телефона: свой динамик громче человека в метре); после подавителя — сколько слов
     человека распознаёт parakeet и сколько слов озвучки протекает (как T2 в tools/voices_echo.py).
"""
import json
import os
import re
import sys
import wave

import numpy as np
from scipy.signal import resample_poly

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read(p):
    with wave.open(p) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32) / 32768
        return x, w.getframerate()


def db(x):
    return 20 * np.log10(np.sqrt(np.mean(np.asarray(x, np.float64) ** 2)) + 1e-12)


def delay(mic, ref, at, lo=0.0, hi=0.8):
    """Задержка эха в отсчётах от at (где начали играть) — по максимуму взаимной корреляции."""
    a, b = at + int(lo * 16000), at + int(hi * 16000) + len(ref)
    seg = mic[a:b]
    n = 1 << int(np.ceil(np.log2(len(seg) + len(ref))))
    c = np.fft.irfft(np.fft.rfft(seg, n) * np.conj(np.fft.rfft(ref, n)), n)[:len(seg) - len(ref) + 1]
    k = int(np.argmax(np.abs(c)))
    return int(lo * 16000) + k, float(np.abs(c[k]) / (np.linalg.norm(ref) * np.linalg.norm(seg[k:k + len(ref)]) + 1e-12))


class PBFDAF:
    """Разбитый по блокам адаптивный фильтр в частотной области (нормированный LMS).
    Блок N отсчётов, P блоков фильтра (длина эха P·N), шаг mu; на выходе — ошибка (микрофон минус оценка эха)."""

    def __init__(self, n=256, p=8, mu=0.5, reg=1e-6):
        self.n, self.p, self.mu, self.reg = n, p, mu, reg
        self.W = np.zeros((p, n + 1), np.complex128)
        self.X = np.zeros((p, n + 1), np.complex128)
        self.xold = np.zeros(n)
        self.pw = np.zeros(n + 1); self.plt = 0.0

    def block(self, x, d):
        n = self.n
        xx = np.concatenate([self.xold, x]); self.xold = x.copy()
        self.X = np.roll(self.X, 1, axis=0); self.X[0] = np.fft.rfft(xx)
        y = np.fft.irfft(np.sum(self.W * self.X, axis=0))[n:]
        e = d - y
        E = np.fft.rfft(np.concatenate([np.zeros(n), e]))
        px = np.sum(np.abs(self.X) ** 2, axis=0)      # сумма по всем блокам фильтра: общий шаг — mu, а не mu·P
        # Мощность опоры по полосам — не ниже текущей: на начале фразы сглаженная отстаёт, и шаг,
        # нормированный по ней, выходил в разы больше нужного (фильтр разносило на первых словах).
        self.pw = np.maximum(0.9 * self.pw + 0.1 * px, px)
        m = float(np.mean(px)); self.plt = m if self.plt == 0 else 0.995 * self.plt + 0.005 * m
        # Речь — с паузами и цветным спектром: где опора тихая (пауза, верхние полосы), нормированный шаг
        # взлетает и фильтр разносит. Поэтому регуляризация — от долгой средней мощности опоры, а в
        # тихих блоках опоры фильтр не учится вовсе.
        if m > 0.01 * self.plt and m > 1e-9:
            delta = 0.05 * float(np.mean(self.pw)) + 1e-9   # от текущей мощности: долгая растёт медленно, и слабые полосы разносило
            G = self.mu * np.conj(self.X) * E / (self.pw + delta)
            g = np.fft.irfft(G, axis=1); g[:, n:] = 0
            self.W += np.fft.rfft(g, axis=1)
        return e

    def run(self, ref, mic):
        out = np.zeros_like(mic)
        for i in range(0, len(mic) - self.n + 1, self.n):
            out[i:i + self.n] = self.block(ref[i:i + self.n], mic[i:i + self.n])
        return out


def nlms(ref, mic, L=2048, mu=0.5, dtd=True):
    """Свой подавитель: нормированный LMS во времени (L отводов), опора выровнена по задержке эха заранее.
    dtd — не учиться, пока говорит человек: ошибка заметно громче оценки эха (на сходившемся фильтре).
    Возвращает (ошибка — микрофон без эха, оценка эха)."""
    w = np.zeros(L); xb = np.zeros(L); e = np.zeros_like(mic); yh = np.zeros_like(mic)
    pe = py = 1e-9
    for i in range(len(mic)):
        xb[1:] = xb[:-1]; xb[0] = ref[i]
        y = w @ xb; yh[i] = y; e[i] = mic[i] - y
        pe = 0.995 * pe + 0.005 * e[i] * e[i]; py = 0.995 * py + 0.005 * y * y
        if dtd and i > 32000 and pe > 4 * py:      # ошибка вчетверо громче эха — это человек, фильтр не трогаем
            continue
        nrm = xb @ xb
        if nrm > 1e-6:
            w += mu * e[i] * xb / (nrm + 1e-3)
    return e, yh


def res(e, yh, beta=2.0, floor=0.05):
    """Подавление остатка эха: где в полосе оценка эха сравнима с выходом — полоса глушится (Винер)."""
    n, h = 512, 256
    win = np.hanning(n)
    out = np.zeros(len(e) + n); norm = np.zeros(len(e) + n)
    for i in range(0, len(e) - n, h):
        E = np.fft.rfft(e[i:i + n] * win); Y = np.fft.rfft(yh[i:i + n] * win)
        g = np.maximum(floor, 1 - beta * np.abs(Y) ** 2 / (np.abs(E) ** 2 + 1e-12))
        out[i:i + n] += np.fft.irfft(E * g) * win; norm[i:i + n] += win ** 2
    return (out[:len(e)] / np.maximum(norm[:len(e)], 1e-6)).astype(np.float64)


_asr = None


def asr(x):
    global _asr
    import sherpa_onnx
    if _asr is None:
        d = os.environ.get('ASR_DIR', os.path.join(R, 'models', 'asr_multi'))
        _asr = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=f'{d}/encoder.int8.onnx', decoder=f'{d}/decoder.int8.onnx', joiner=f'{d}/joiner.int8.onnx',
            tokens=f'{d}/tokens.txt', num_threads=2, model_type='nemo_transducer')
    s = _asr.create_stream(); s.accept_waveform(16000, np.asarray(x, np.float32)); _asr.decode_stream(s)
    return s.result.text


def words(t):
    return [w for w in re.sub(r"[^\w' -]", ' ', t.lower()).split() if len(w) > 1]


def recall(ref, hyp):
    r, h = words(ref), set(words(hyp))
    return sum(w in h for w in r) / max(1, len(r))


def near(d, meta, ref16):
    """4. Живой голос из колонок ПК (NEAR=1): поверх озвучки и в тишине, по каждому режиму записи."""
    rows = [l.rstrip('\n').split('\t') for l in open(os.path.join(d, 'near.tsv'), encoding='utf-8') if l.strip()]
    tts_words = asr(ref16)
    print('\n## 4. Человек из колонок ПК: поверх озвучки и в тишине\n')
    print('| запись | озвучка | его слов распознано | слов озвучки в распознанном | уровень записи, dBFS |')
    print('|---|---|---|---|---|')
    agg = {}
    for cfg, clip, *_ in rows:
        p = os.path.join(d, f'aec_{cfg}.wav')
        if not os.path.exists(p) or cfg not in meta:
            continue
        mic, _ = read(p); at = int(meta[cfg]['play_sample'])
        hyp = asr(mic[max(0, at - 1600):])
        txt = open(clip + '.txt', encoding='utf-8').read().strip()
        rc, lk = recall(txt, hyp), recall(tts_words, hyp)
        quiet = '_q' in cfg
        print(f'| {cfg} | {"нет" if quiet else "есть"} | {rc * 100:.0f} % | {"—" if quiet else f"{lk * 100:.0f} %"} | {db(mic[at:]):.1f} |')
        key = ('vc' if cfg.startswith('vc') else 'vr', quiet)
        agg.setdefault(key, []).append((rc, lk))
    print('\n| режим записи | озвучка | фраз | его слов, среднее | слов озвучки, среднее |')
    print('|---|---|---|---|---|')
    for (src, quiet), v in sorted(agg.items()):
        print(f'| {src} | {"нет" if quiet else "есть"} | {len(v)} | {np.mean([a for a, _ in v]) * 100:.0f} % | {"—" if quiet else f"{np.mean([b for _, b in v]) * 100:.0f} %"} |')
    pc = os.path.join(d, 'pc_mic.wav')
    if os.path.exists(pc):
        x, _ = read(pc)
        print('\nОзвучка телефона у микрофона ПК — не тише ли она в другом режиме записи (первая секунда фразы, до голоса из колонок):\n')
        k, rho = delay(x, ref16[:16000], 0, 0.0, max(0.0, len(x) / 16000 - 2))
        levels = []
        for i in range(0, len(x) - len(ref16), 1600):
            pass
        # каждую озвучку ищем корреляцией первой секунды фразы по всей записи ПК
        r1 = ref16[:16000]
        n = 1 << int(np.ceil(np.log2(len(x) + len(r1))))
        c = np.fft.irfft(np.fft.rfft(x, n) * np.conj(np.fft.rfft(r1, n)), n)[:len(x) - len(r1)]
        c = np.abs(c); found = []
        for _ in range(8):
            j = int(np.argmax(c))
            if c[j] <= 0: break
            found.append(j); c[max(0, j - 8 * 16000):j + 8 * 16000] = 0
        for j in sorted(found):
            levels.append(db(x[j:j + 16000]))
        print('  уровни (по времени):', ' · '.join(f'{v:.1f}' for v in levels), 'dBFS')


def main():
    d = sys.argv[1]
    meta = json.load(open(os.path.join(d, 'aec.json'), encoding='utf-8'))
    ref, rr = read(os.path.join(d, 'aec_ref.wav'))
    ref16 = resample_poly(ref, 16000, rr).astype(np.float32)
    print(f'Озвучка: {len(ref16) / 16000:.1f} с · «{meta.get("text", "")[:60]}…»\n')
    print('## 1. Эхо по настройкам записи\n')
    print('| запись | подавитель Android | задержка, мс | сходство с озвучкой | фон до, dBFS | эхо, dBFS | эхо над фоном, дБ |')
    print('|---|---|---|---|---|---|---|')
    base = None
    for cfg in [c for c in meta if c.startswith(('vr', 'vc'))]:
        p = os.path.join(d, f'aec_{cfg}.wav')
        if not os.path.exists(p) or cfg not in meta:
            continue
        mic, _ = read(p); at = int(meta[cfg]['play_sample'])
        k, rho = delay(mic, ref16, at)
        s = at + k
        fon, eco = db(mic[max(0, at - 8000):at]), db(mic[s:s + len(ref16)])
        print(f'| {cfg} | {meta[cfg]["aec"]} | {k / 16:.0f} | {rho:.2f} | {fon:.1f} | {eco:.1f} | {eco - fon:.1f} |')
        if cfg == 'vr':
            base = (mic, at, k)
    if os.path.exists(os.path.join(d, 'near.tsv')):
        near(d, meta, ref16)
        return
    if base is None:
        return
    mic, at, k = base
    # Опора, выровненная по задержке эха: сдвигаем озвучку на at + k; фильтр сам доберёт остаток.
    pre = int(0.05 * 16000)                     # фильтру — немного «до», чтобы задержка была внутри окна
    refal = np.zeros_like(mic); s0 = at + k - pre
    refal[max(0, s0):max(0, s0) + len(ref16)] = ref16[:len(refal) - max(0, s0)]
    seg = slice(at + k, at + k + len(ref16))
    print('\n## 2. Свой подавитель на записи vr (только эхо и фон)\n')
    print('| фильтр, мс | шаг | эхо, dBFS | после фильтра | ERLE, дБ | + подавление остатка: dBFS · ERLE, дБ | последние 2 с: ERLE фильтра · с подавлением, дБ |')
    print('|---|---|---|---|---|---|---|')
    best = None
    tail = slice(seg.stop - 32000, seg.stop)
    for L, mu in ((1024, 0.5), (2048, 0.5), (2048, 0.2), (4096, 0.5)):
        e, yh = nlms(refal, mic.astype(np.float64), L, mu)
        r = res(e, yh)
        before = db(mic[seg])
        print(f'| {L / 16:.0f} | {mu} | {before:.1f} | {db(e[seg]):.1f} | {before - db(e[seg]):.1f} | {db(r[seg]):.1f} · {before - db(r[seg]):.1f} | '
              f'{db(mic[tail]) - db(e[tail]):.1f} · {db(mic[tail]) - db(r[tail]):.1f} |')
        if best is None or db(mic[tail]) - db(r[tail]) > best[0]:
            best = (db(mic[tail]) - db(r[tail]), L, mu)
    _, L, mu = best
    print(f'\nЛучший: фильтр {L / 16:.0f} мс, шаг {mu}.')

    # 3. Человек поверх озвучки
    sys.path.insert(0, os.path.join(R, 'tools'))
    import bz2
    idx = {}
    for code in ('por', 'rus'):
        with bz2.open(f'{R}/data/tatoeba/raw/{code}_sentences_with_audio.tsv.bz2', 'rt', encoding='utf-8') as f:
            for line in f:
                c = line.rstrip('\n').split('\t')
                if len(c) >= 3:
                    idx[c[1]] = c[2]
    clips = []
    for lang in ('ru', 'pt'):
        for l in open(f'{R}/bench/air/corpus/{lang}/LICENSES.tsv', encoding='utf-8'):
            st = l.split('\t')[0]
            clips.append(f'{R}/bench/air/corpus/{lang}/{st}')
    rng = np.random.default_rng(20261002)
    pick = [clips[i] for i in rng.choice(len(clips), 12, replace=False)]
    echo_rms = 10 ** (db(mic[seg]) / 20)
    tts_words = asr(mic[seg])
    print(f'\n## 3. Человек поверх озвучки: фраза из корпуса подмешана в запись vr через 1 с после начала эха, {len(pick)} фраз\n')
    print('| человек к эху, дБ | без подавителя: его слов · слов озвучки | со своим подавителем: его слов · слов озвучки |')
    print('|---|---|---|')
    for r in (-15, -10, -5, 0):
        a_rec, a_leak, b_rec, b_leak = [], [], [], []
        for c in pick:
            h, hr = read(c + '.wav'); h = h if hr == 16000 else resample_poly(h, 16000, hr).astype(np.float32)
            h = h * (echo_rms * 10 ** (r / 20) / (10 ** (db(h) / 20)))
            x = mic.copy(); s1 = seg.start + 16000
            nh = min(len(h), len(x) - s1); x[s1:s1 + nh] += h[:nh]       # nh, а не L: L — длина фильтра (было затёрто)
            e0, yh = nlms(refal, x.astype(np.float64), L, mu); e = res(e0, yh)
            win = slice(seg.start, max(seg.stop, s1 + nh) + 3200)
            txt = open(c + '.txt', encoding='utf-8').read()
            ha, hb = asr(x[win]), asr(e[win])
            a_rec.append(recall(txt, ha)); a_leak.append(recall(tts_words, ha))
            b_rec.append(recall(txt, hb)); b_leak.append(recall(tts_words, hb))
        print(f'| {r:+d} | {np.median(a_rec) * 100:.0f} % · {np.median(a_leak) * 100:.0f} % | {np.median(b_rec) * 100:.0f} % · {np.median(b_leak) * 100:.0f} % |')


if __name__ == '__main__':
    main()
