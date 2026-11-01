#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Своё декодирование parakeet-tdt-0.6b-v3 (models/asr_multi, те же int8-файлы, что у sherpa-onnx) на onnxruntime — ради
того, чего sherpa-onnx наружу не отдаёт: вероятностей других токенов в каждой точке выдачи и ветвления от неё (шаг 11
results/2026-10-27-asr-confidence.md: варианты исправления сомнительного слова).

Признаки — как у sherpa-onnx для NeMo-моделей (offline-recognizer-transducer-nemo-impl.h + kaldi-native-fbank),
чтобы жадный текст совпадал с приложением.
Жадный TDT — как в sherpa-onnx (offline-transducer-greedy-search-nemo-decoder.cc): токен и длительность из одного
выхода объединителя. Сверка с sherpa-onnx — tools/asr_suggest.py --check.
"""
import math
import os

import numpy as np
import onnxruntime as ort

SR, NFFT, WIN, HOP, NMEL = 16000, 512, 400, 160, 128
DURATIONS = (0, 1, 2, 3, 4)


def _mel_knf(sr=SR, n_fft=NFFT, n_mels=NMEL, low=0.0, high=SR / 2 - 400):
    """Мел-фильтры, как у kaldi-native-fbank с is_librosa: шкала и нормировка Слейни, бины БПФ 0..n_fft/2."""
    def mel(f):
        return f * 3 / 200.0 if f <= 1000 else 15 + 14.545078505785561 * np.log(f / 1000)

    def hz(m):
        return 200.0 / 3 * m if m <= 15 else 1000 * np.exp((m - 15) * 0.06875177742094911)

    lo, hi = mel(low), mel(high)
    d = (hi - lo) / (n_mels + 1)
    width = sr / n_fft
    w = np.zeros((n_mels, n_fft // 2 + 1), np.float32)
    for b in range(n_mels):
        l, c, r = hz(lo + b * d), hz(lo + (b + 1) * d), hz(lo + (b + 2) * d)
        for i in range(n_fft // 2 + 1):
            f = width * i
            if l < f < r:
                w[b, i] = ((f - l) / (c - l) if f <= c else (r - f) / (r - c)) * 2 / (r - l)
    return w


_MEL = _mel_knf()
_MEL8000 = _mel_knf(high=SR / 2)
_POVEY = (0.5 - 0.5 * np.cos(2 * np.pi / (WIN - 1) * np.arange(WIN))) ** 0.85
_HANN = 0.5 - 0.5 * np.cos(2 * np.pi / WIN * np.arange(WIN))          # knf «hann»: периодическое


def features(x, kind='sherpa'):
    """[128, T] — признаки, как у sherpa-onnx для NeMo-моделей (kaldi-native-fbank: snip_edges=false с отражением
    краёв, без DC и без дизера, предыскажение 0,97 внутри кадра, окно Пови, БПФ 512, мел Слейни 0–7600 Гц,
    log(max(e, eps)), затем нормировка по признаку: среднее и смещённое СКО + 1e-5).
    kind='hann8000' — то же, но окно Ханна и мел до 8000 Гц: как у NeMo; в sherpa-onnx 1.13.8 эти две настройки для
    NeMo-моделей закомментированы (offline-recognizer-transducer-nemo-impl.h, PostInit)."""
    win, mel_fb = (_HANN, _MEL8000) if kind == 'hann8000' else (_POVEY, _MEL)
    x = np.asarray(x, np.float32)
    n = len(x)
    nf = (n + HOP // 2) // HOP
    idx = (HOP * np.arange(nf) + HOP // 2 - WIN // 2)[:, None] + np.arange(WIN)[None, :]
    while True:                                           # отражение у краёв, как в knf
        neg, big = idx < 0, idx >= n
        if not (neg.any() or big.any()):
            break
        idx = np.where(neg, -idx - 1, np.where(big, 2 * n - 1 - idx, idx))
    fr = x[idx].astype(np.float32)
    pre = fr.copy()
    fr[:, 1:] = pre[:, 1:] - 0.97 * pre[:, :-1]
    fr[:, 0] = pre[:, 0] - 0.97 * pre[:, 0]
    fr *= win.astype(np.float32)[None, :]
    spec = np.abs(np.fft.rfft(fr, n=NFFT, axis=1)) ** 2
    mel = np.log(np.maximum(spec.astype(np.float32) @ mel_fb.T, np.finfo(np.float32).eps))   # [T, 128]
    mu = mel.mean(axis=0, keepdims=True)
    sd = np.sqrt(((mel - mu) ** 2).mean(axis=0, keepdims=True))
    return ((mel - mu) / (sd + 1e-5)).T.astype(np.float32)


class Parakeet:
    def __init__(self, model_dir, threads=1, providers=None, feats='sherpa', nemo_pre=None):
        """feats: 'sherpa' (как приложение), 'hann8000' (окно Ханна, мел до 8000 Гц) или 'nemo' — признаки самого NeMo
        (nemo_pre — путь к nemo128.onnx из onnx-asr; выход уже нормирован по признаку)."""
        so = ort.SessionOptions(); so.intra_op_num_threads = threads; so.inter_op_num_threads = 1
        so.log_severity_level = 3
        p = providers or ['CPUExecutionProvider']
        self.feats = feats
        self.pre = ort.InferenceSession(nemo_pre, so, providers=p) if feats == 'nemo' else None
        self.enc = ort.InferenceSession(os.path.join(model_dir, 'encoder.int8.onnx'), so, providers=p)
        self.dec = ort.InferenceSession(os.path.join(model_dir, 'decoder.int8.onnx'), so, providers=p)
        self.joi = ort.InferenceSession(os.path.join(model_dir, 'joiner.int8.onnx'), so, providers=p)
        self.tokens = {}
        for line in open(os.path.join(model_dir, 'tokens.txt'), encoding='utf-8'):
            s, i = line.rstrip('\n').rsplit(' ', 1)
            self.tokens[int(i)] = s
        self.vocab = int(self.enc.get_modelmeta().custom_metadata_map.get('vocab_size', 8192))
        self.blank = self.vocab                             # 8192: токены 0..8191, затем пустой, затем длительности
        self.din = [i.name for i in self.dec.get_inputs()]

    def encode(self, x):
        if self.pre is not None:
            x = np.asarray(x, np.float32)
            f = self.pre.run(None, {'waveforms': x[None], 'waveforms_lens': np.array([len(x)], np.int64)})[0]
        else:
            f = features(x, self.feats)[None]
        out, _ = self.enc.run(None, {'audio_signal': f, 'length': np.array([f.shape[2]], np.int64)})
        return out[0]                                       # [1024, T']

    def decoder(self, y, s1, s2):
        out = self.dec.run(None, {self.din[0]: np.array([[y]], np.int32), self.din[1]: np.array([1], np.int32),
                                  self.din[2]: s1, self.din[3]: s2})
        return out[0], out[2], out[3]                       # [1, 640, 1], состояния

    def joint(self, e, g):
        return self.joi.run(None, {'encoder_outputs': e[None, :, None], 'decoder_outputs': g})[0][0, 0, 0]

    def greedy(self, enc, start=None, max_per_frame=5, stop_at_word=False, force=None):
        """Жадный TDT. Возвращает выдачи: (t, токен, log p токена, log-softmax по токенам, состояние до выдачи).
        start — (t, g, s1, s2, n_в_кадре) для ветвления; force — токен, выдаваемый первым вместо лучшего;
        stop_at_word — остановиться перед токеном, начинающим новое слово (после хотя бы одной выдачи)."""
        T = enc.shape[1]
        if start is None:
            s1 = np.zeros((2, 1, 640), np.float32); s2 = np.zeros((2, 1, 640), np.float32)
            g, s1, s2 = self.decoder(self.blank, s1, s2)
            t, n_frame = 0, 0
        else:
            t, g, s1, s2, n_frame = start
        out = []
        while t < T:
            lg = self.joint(enc[:, t], g)
            tok = lg[: self.vocab + 1]
            lsm = tok - (tok.max() + np.log(np.exp(tok - tok.max()).sum()))
            y = int(np.argmax(tok)) if force is None else force
            skip = DURATIONS[int(np.argmax(lg[self.vocab + 1:]))]
            if force is not None:
                force = None
            if y != self.blank:
                if stop_at_word and out and self.tokens[y].startswith('▁'):
                    break
                out.append((t, y, float(lsm[y]), lsm, (t, g, s1, s2, n_frame)))
                g, s1, s2 = self.decoder(y, s1, s2)
                n_frame += 1
            if skip > 0:
                n_frame = 0
            if n_frame >= max_per_frame:
                n_frame = 0; skip = 1
            if y == self.blank and skip == 0:
                n_frame = 0; skip = 1
            t += skip
        return out

    def text(self, ids):
        return ''.join(self.tokens[i] for i in ids).replace('▁', ' ').strip()
