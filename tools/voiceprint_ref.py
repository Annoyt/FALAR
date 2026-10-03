#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Отпечаток голоса — эталон на столе для Voiceprint.java: признаки и модель ровно как в приложении.

Зачем свой, а не sherpa-onnx. 01.10.2026 замер на живых записях Tatoeba показал, что отпечаток
3D-Speaker CAM++ через SpeakerEmbeddingExtractor sherpa-onnx (1.13.7, Python) людей почти не
различает: AUC 0,56 на фразах около 2 с, отпечаток зависит от 0,5 с нулей в конце сильнее, чем от
голоса. Та же модель через ONNX Runtime с признаками по рецепту 3D-Speaker (Kaldi fbank, 80 полос,
вычитание среднего по времени — speakerlab/process/processor.py, FBank) — AUC 0,999 на тех же
фразах и 1,000 на кусках по 6 с. Отпечаток одной и той же записи у двух путей совпадает на 0,01,
так что это не порог, а другие признаки на входе модели.

Признаки (как torchaudio.compliance.kaldi.fbank по умолчанию, dither 0):
  кадр 25 мс (400 отсчётов), шаг 10 мс (160), snip_edges — только целые кадры;
  из кадра вычитается его среднее; предыскажение 0,97 (первый отсчёт — сам с собой);
  окно Пови (ханнинг в степени 0,85); БПФ 512, мощность; 80 треугольных мел-полос от 20 Гц до 8 кГц
  (мел = 1127·ln(1 + f/700), бин Найквиста не участвует); натуральный логарифм с полом float32 eps;
  из каждой полосы вычитается её среднее по всем кадрам. Звук — float в [-1, 1], 16 кГц.

  python3 tools/voiceprint_ref.py golden <out.json> <wav>...   # эталон для VoiceprintTest
  python3 tools/voiceprint_ref.py cos <wav> <wav>              # косинус двух записей

Модель — models/speaker/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx: вход x [N, T, 80],
выход embedding [N, 512]; отпечаток — выход единичной длины, сходство — скалярное произведение.
"""
import json
import os
import sys
import wave

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL = os.path.join(R, 'models', 'speaker', '3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx')
SR, FL, FS, NFFT, NMEL, LOW = 16000, 400, 160, 512, 80, 20.0
EPS = float(np.finfo(np.float32).eps)


def mel(f):
    return 1127.0 * np.log(1.0 + f / 700.0)


def banks():
    """Треугольные полосы Kaldi в мел-шкале: [80, 257], бин Найквиста — нулевой."""
    c = np.linspace(mel(LOW), mel(SR / 2), NMEL + 2)
    fm = mel(np.arange(NFFT // 2 + 1) * SR / NFFT)
    w = np.zeros((NMEL, NFFT // 2 + 1))
    for m in range(NMEL):
        l, ce, r = c[m], c[m + 1], c[m + 2]
        inside = (fm > l) & (fm < r)
        w[m] = np.where(inside, np.minimum((fm - l) / (ce - l), (r - fm) / (r - ce)), 0.0)
    w[:, -1] = 0.0
    return w


W = banks()
WIN = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(FL) / (FL - 1))) ** 0.85


def fbank(x):
    """[T, 80] float32 с вычтенным средним; меньше одного кадра — пусто."""
    x = np.asarray(x, dtype=np.float64)
    n = 1 + (len(x) - FL) // FS if len(x) >= FL else 0
    if n <= 0:
        return np.zeros((0, NMEL), np.float32)
    fr = x[np.arange(FL)[None, :] + FS * np.arange(n)[:, None]]
    fr = fr - fr.mean(1, keepdims=True)
    fr = np.concatenate([fr[:, :1] * (1 - 0.97), fr[:, 1:] - 0.97 * fr[:, :-1]], 1)
    fr = fr * WIN
    sp = np.abs(np.fft.rfft(fr, NFFT)) ** 2
    f = np.log(np.maximum(sp @ W.T, EPS))
    return (f - f.mean(0, keepdims=True)).astype(np.float32)


_sess = None


def embed(x):
    global _sess
    if _sess is None:
        import onnxruntime as ort
        so = ort.SessionOptions(); so.intra_op_num_threads = 2
        _sess = ort.InferenceSession(MODEL, so, providers=['CPUExecutionProvider'])
    f = fbank(x)
    if len(f) == 0:
        return None
    e = _sess.run(None, {'x': f[None]})[0][0].astype(np.float64)
    return e / np.linalg.norm(e)


def read(p):
    with wave.open(p) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, p
        return np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32) / 32768


def main():
    if len(sys.argv) >= 4 and sys.argv[1] == 'golden':
        out = []
        for p in sys.argv[3:]:
            x = read(p); f = fbank(x); e = embed(x)
            out.append({'wav': os.path.relpath(p, R), 'frames': int(len(f)),
                        'fbank_head': [[round(float(v), 5) for v in row] for row in f[:3]],
                        'fbank_mean_abs': round(float(np.abs(f).mean()), 6),
                        'emb': [round(float(v), 6) for v in e]})
        json.dump(out, open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False)
        print('эталон:', len(out), 'записей →', sys.argv[2])
    elif len(sys.argv) == 4 and sys.argv[1] == 'cos':
        print(round(float(embed(read(sys.argv[2])) @ embed(read(sys.argv[3]))), 4))
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main()
