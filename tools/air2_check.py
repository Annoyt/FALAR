#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверка прохода записи комнаты в два канала (bench/air/record_air2.sh): легла ли запись, где в ней фразы,
какие уровни у каналов и откуда приходит звук колонки.

Каждая сыгранная фраза находится в записи взаимной корреляцией с самим файлом около места, которое дают часы
(player.tsv на часах ПК, начало записи и сдвиг часов телефона — rec.txt). По окну фразы: уровень каждого канала и
задержка правого канала относительно левого (GCC-PHAT, доли отсчёта). Голос с торца телефона приходит на дальний
микрофон позже — до ~0,45 мс при 15 см между микрофонами, сбоку — почти одновременно.

    .venv/bin/python tools/air2_check.py bench/air/rec/st-<метка>-<язык> bench/air/corpus/<язык>
"""
import os
import sys
import wave

import numpy as np


def read(path):
    with wave.open(path) as w:
        ch, rate, n = w.getnchannels(), w.getframerate(), w.getnframes()
        x = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float64) / 32768.0
    return x.reshape(-1, ch), rate


def dbfs(x):
    return 10 * np.log10(max(float(np.mean(x ** 2)), 1e-12))


def gcc_phat(l, r, rate, max_ms=1.0):
    n = 1 << int(np.ceil(np.log2(2 * len(l))))
    g = np.conj(np.fft.rfft(l - l.mean(), n)) * np.fft.rfft(r - r.mean(), n)
    g /= np.maximum(np.abs(g), 1e-12)
    cc = np.fft.irfft(g, n)
    m = int(round(rate * max_ms / 1000))
    cc = np.concatenate([cc[-m:], cc[: m + 1]])
    k = int(np.argmax(cc))
    frac = 0.0
    if 0 < k < len(cc) - 1:
        y0, y1, y2 = cc[k - 1], cc[k], cc[k + 1]
        d = y0 - 2 * y1 + y2
        frac = 0.5 * (y0 - y2) / d if d != 0 else 0.0
    return (k - m + frac) * 1000.0 / rate, float(cc[k])


def find(rec, clip, at, rate, before=0.6, after=1.2):
    """Начало фразы в записи (кадр) около at: максимум взаимной корреляции суммы каналов с файлом."""
    a = max(0, int(at - before * rate)); b = min(len(rec), int(at + after * rate) + len(clip))
    seg = rec[a:b]
    if len(seg) < len(clip):
        return None, 0.0
    n = 1 << int(np.ceil(np.log2(len(seg) + len(clip))))
    cc = np.fft.irfft(np.fft.rfft(seg, n) * np.conj(np.fft.rfft(clip, n)), n)[: len(seg) - len(clip) + 1]
    k = int(np.argmax(cc))
    e = np.sqrt(float(np.sum(clip ** 2)) * float(np.sum(seg[k:k + len(clip)] ** 2)))
    return a + k, float(cc[k] / e) if e > 0 else 0.0


def main():
    run, ref = sys.argv[1], sys.argv[2]
    meta = dict(kv.split("=", 1) for kv in open(os.path.join(run, "rec.txt"), encoding="utf-8").read().split())
    x, rate = read(os.path.join(run, "room2.wav"))
    if x.shape[1] != 2:
        print(f"в записи {x.shape[1]} канал(а), а нужно два"); return 1
    t0, off = int(meta["начало_телефон"]), int(meta["сдвиг_часов"])
    print(f"запись: {len(x) / rate:.1f} с, {rate} Гц, 2 канала · {meta['метка']}-{meta['язык']}, громкость проигрывания "
          f"{meta['громкость_проигрывания']} (системная {meta['громкость_системы']})")
    fr = int(0.1 * rate)
    e = np.array([[dbfs(x[i:i + fr, c]) for c in (0, 1)] for i in range(0, len(x) - fr, fr)])
    for c, name in ((0, "L"), (1, "R")):
        s = np.sort(e[:, c])
        print(f"  {name}: фон {np.median(s[: len(s) // 3]):.1f} dBFS (медиана тихой трети), речь {np.median(s[-len(s) // 10:]):.1f} "
              f"(медиана верхних 10 %), пиков у края {100 * float(np.mean(np.abs(x[:, c]) > 0.99)):.2f} %")
    mono = x.mean(axis=1)
    rows = []
    for line in open(os.path.join(run, "player.tsv"), encoding="utf-8"):
        cid, a, b, dur = line.rstrip("\n").split("\t")
        clip, crate = read(os.path.join(ref, cid + ".wav"))
        clip = clip[:, 0]
        if crate != rate:
            print(f"  {cid}: частота файла {crate} ≠ {rate} — пропуск"); continue
        at = (int(a) + off - t0) / 1000.0 * rate
        k, score = find(mono, clip, at, rate)
        if k is None:
            rows.append((cid, None, score, None, None, None)); continue
        w = x[k:k + len(clip)]
        d, pk = gcc_phat(w[:, 0], w[:, 1], rate)
        rows.append((cid, (k - at) / rate * 1000, score, dbfs(w[:, 0]) - dbfs(w[:, 1]), d, pk))
    ok = [r for r in rows if r[1] is not None and r[2] >= 0.3]
    print(f"фраз сыграно {len(rows)}, найдено в записи уверенно (корреляция ≥ 0,3) {len(ok)}")
    if ok:
        lat = np.array([r[1] for r in ok]); dl = np.array([r[3] for r in ok]); dd = np.array([r[4] for r in ok])
        print(f"  от часов до звука в записи: медиана {np.median(lat):.0f} мс, разброс (10–90 %) {np.percentile(lat, 10):.0f}…{np.percentile(lat, 90):.0f}")
        print(f"  L − R по уровню речи: медиана {np.median(dl):+.1f} дБ ({np.percentile(dl, 10):+.1f}…{np.percentile(dl, 90):+.1f})")
        print(f"  R позже L: медиана {np.median(dd):+.3f} мс ({np.percentile(dd, 10):+.3f}…{np.percentile(dd, 90):+.3f}) ≈ "
              f"{np.median(dd) * 34.3:+.1f} см разницы путей; пик GCC-PHAT медиана {np.median([r[5] for r in ok]):.2f}")
    bad = [r[0] for r in rows if r[1] is None or r[2] < 0.3]
    if bad:
        print(f"  не нашлись или сомнительны: {', '.join(bad[:10])}{' …' if len(bad) > 10 else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
