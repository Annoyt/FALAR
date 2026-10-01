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
        self.pw = np.zeros(n + 1)

    def block(self, x, d):
        n = self.n
        xx = np.concatenate([self.xold, x]); self.xold = x.copy()
        self.X = np.roll(self.X, 1, axis=0); self.X[0] = np.fft.rfft(xx)
        y = np.fft.irfft(np.sum(self.W * self.X, axis=0))[n:]
        e = d - y
        E = np.fft.rfft(np.concatenate([np.zeros(n), e]))
        px = np.sum(np.abs(self.X) ** 2, axis=0) / self.p
        self.pw = 0.9 * self.pw + 0.1 * px
        if np.mean(px) > 1e-7:                   # опора молчит — не учимся на одном шуме
            delta = 1e-2 * np.mean(self.pw) + 1e-9
            G = self.mu * np.conj(self.X) * E / (self.pw + delta)
            g = np.fft.irfft(G, axis=1); g[:, n:] = 0
            self.W += np.fft.rfft(g, axis=1)
        return e

    def run(self, ref, mic):
        out = np.zeros_like(mic)
        for i in range(0, len(mic) - self.n + 1, self.n):
            out[i:i + self.n] = self.block(ref[i:i + self.n], mic[i:i + self.n])
        return out


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
    for cfg in ('vr', 'vr_aec', 'vc', 'vc_aec'):
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
    if base is None:
        return
    mic, at, k = base
    # Опора, выровненная по задержке эха: сдвигаем озвучку на at + k; фильтр сам доберёт остаток.
    pre = int(0.05 * 16000)                     # фильтру — немного «до», чтобы задержка была внутри окна
    refal = np.zeros_like(mic); s0 = at + k - pre
    refal[max(0, s0):max(0, s0) + len(ref16)] = ref16[:len(refal) - max(0, s0)]
    seg = slice(at + k, at + k + len(ref16))
    print('\n## 2. Свой подавитель на записи vr (только эхо и фон)\n')
    print('| блок, отсчётов | длина фильтра, мс | шаг | эхо до, dBFS | после, dBFS | ERLE, дБ | последние 2 с: ERLE, дБ |')
    print('|---|---|---|---|---|---|---|')
    best = None
    for n, p_, mu in ((256, 4, 0.5), (256, 8, 0.5), (256, 16, 0.5), (256, 8, 0.2), (256, 8, 0.8), (128, 16, 0.5)):
        e = PBFDAF(n, p_, mu).run(refal, mic)
        before, after = db(mic[seg]), db(e[seg])
        tail = slice(seg.stop - 32000, seg.stop)
        erle_tail = db(mic[tail]) - db(e[tail])
        print(f'| {n} | {n * p_ / 16:.0f} | {mu} | {before:.1f} | {after:.1f} | {before - after:.1f} | {erle_tail:.1f} |')
        if best is None or erle_tail > best[0]:
            best = (erle_tail, n, p_, mu)
    _, n, p_, mu = best
    print(f'\nЛучший по последним 2 с: блок {n}, фильтр {n * p_ / 16:.0f} мс, шаг {mu}.')

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
            L = min(len(h), len(x) - s1); x[s1:s1 + L] += h[:L]
            e = PBFDAF(n, p_, mu).run(refal, x)
            win = slice(seg.start, max(seg.stop, s1 + L) + 3200)
            txt = open(c + '.txt', encoding='utf-8').read()
            ha, hb = asr(x[win]), asr(e[win])
            a_rec.append(recall(txt, ha)); a_leak.append(recall(tts_words, ha))
            b_rec.append(recall(txt, hb)); b_leak.append(recall(tts_words, hb))
        print(f'| {r:+d} | {np.median(a_rec) * 100:.0f} % · {np.median(a_leak) * 100:.0f} % | {np.median(b_rec) * 100:.0f} % · {np.median(b_leak) * 100:.0f} % |')


if __name__ == '__main__':
    main()
