#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Разбор стенда микрофонов (bench/apk/test_micpair_device.sh → --es mictest, TranslatorService.micStand): сколько
микрофонов знает Android и что о них сообщает, и по записям — копия ли это одного микрофона или два разных, какой
канал ближе к динамику и на сколько. Шаг 1 проверки второго микрофона (владелец 15.10).

Тот же счёт, что у MicPair.java на телефоне (уровни без постоянной составляющей, доля одинаковых отсчётов, задержка
правого канала по взаимной корреляции в ±1 мс), сверяется с цифрами телефона из mic.json — расхождение значит, что
близнецы разошлись. Сверх того на ПК: задержка по GCC-PHAT с долями отсчёта и связность каналов на фоне — у двух
микрофонов в 15 см друг от друга в рассеянном шуме комнаты она на 1–4 кГц близка к нулю, у копии одного — 1.

    .venv/bin/python tools/mic_probe.py .stand/mic-<время>
"""
import json
import os
import re
import sys
import wave

import numpy as np
from scipy.signal import coherence

CFG = re.compile(r"(vr|mic|cam|unp)_(mo|st)_(16|48)(?:_([a-z]+))?$")
SRC = {"vr": "VOICE_RECOGNITION", "mic": "MIC", "cam": "CAMCORDER", "unp": "UNPROCESSED"}
C = 343.0  # м/с


def read(path):
    with wave.open(path) as w:
        ch, rate, n = w.getnchannels(), w.getframerate(), w.getnframes()
        x = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float64)
    return x.reshape(-1, ch), rate


def db(e, n):
    return 10 * np.log10(max(e / n / 32768.0 ** 2, 1e-12)) if n else -120.0


def stats(x, a, b, max_lag):
    """Как MicPair.stats: x — кадры × 2."""
    seg = x[a:b]
    n = len(seg)
    if n == 0:
        return dict(db_l=-120.0, db_r=-120.0, same=0.0, lag=0, peak=0.0)
    same = float(np.mean(seg[:, 0] == seg[:, 1]))
    l = seg[:, 0] - seg[:, 0].mean()
    r = seg[:, 1] - seg[:, 1].mean()
    el, er = float(l @ l), float(r @ r)
    norm = np.sqrt(el * er)
    best, best_k = -2.0, 0
    for k in range(-max_lag, max_lag + 1):
        if k >= 0:
            c = float(l[: n - k] @ r[k:])
        else:
            c = float(l[-k:] @ r[: n + k])
        c = c / norm if norm > 0 else 0.0
        if c > best:
            best, best_k = c, k
    return dict(db_l=db(el, n), db_r=db(er, n), same=same, lag=best_k, peak=best)


def gcc_phat(x, a, b, rate, max_ms=1.0):
    """Задержка R относительно L по GCC-PHAT, в мс, с долями отсчёта (парабола по трём точкам)."""
    l, r = x[a:b, 0], x[a:b, 1]
    n = 1 << int(np.ceil(np.log2(2 * len(l))))
    L, R = np.fft.rfft(l - l.mean(), n), np.fft.rfft(r - r.mean(), n)
    g = np.conj(L) * R
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
    return (k - m + frac) * 1000.0 / rate


def coh(x, a, b, rate, lo=1000, hi=4000):
    seg = x[a:b]
    if len(seg) < 512:
        return float("nan")
    f, c = coherence(seg[:, 0], seg[:, 1], fs=rate, nperseg=512)
    sel = (f >= lo) & (f <= hi)
    return float(np.mean(c[sel]))


def main():
    run = sys.argv[1]
    meta = json.load(open(os.path.join(run, "mic.json"), encoding="utf-8"))
    print(f"телефон: {meta.get('model')} ({meta.get('device')}), Android SDK {meta.get('sdk')} · громкость "
          f"{meta.get('volume')}/{meta.get('volume_max')} · UNPROCESSED: {meta.get('unprocessed')} · "
          f"шум в динамик: {'да' if meta.get('speaker') else 'нет'}")
    print(f"Android знает микрофонов: {len(meta.get('mics', []))}" + (f" (ошибка: {meta['mics_error']})" if "mics_error" in meta else ""))
    for m in meta.get("mics", []):
        print(f"  {m.get('address') or '—'} · {m.get('desc')} · тип {m.get('type')} · место {m.get('location')} · группа "
              f"{m.get('group')}/{m.get('index')} · где {m.get('pos', 'не сказано')} · направленность {m.get('directionality')}"
              + (f" · чувствительность {m['sens_dbfs']} dBFS" if "sens_dbfs" in m else ""))
    print("входы:", ", ".join(f"{d['address'] or d['type']} ({d['name']}; каналов {d['channels'] or '?'})" for d in meta.get("inputs", [])))
    print()
    parity_bad = 0
    mono = {}
    summary = []
    for cfg, r in meta.items():
        if not isinstance(r, dict) or not CFG.match(cfg):
            continue
        if "error" in r:
            print(f"{cfg}: запись не создалась — {r['error']}")
            continue
        x, rate = read(os.path.join(run, f"mic_{cfg}.wav"))
        ch = x.shape[1]
        act = ", ".join(f"{a.get('address') or a.get('desc')} (каналы {a.get('channels')})" for a in r.get("active", [])) or "не сказано"
        print(f"{cfg}: {SRC[CFG.match(cfg).group(1)]}, {ch} кан., {rate} Гц" + (f", микрофон {r['preferred']}" if r.get("preferred") else "")
              + f" · пишут: {act} · вход {r.get('routed')} · вывод {r.get('out')}")
        max_lag = int(round(rate * 0.001))
        for s in r["segments"]:
            a, b = s["from"], s["to"]
            if ch == 2:
                q = stats(x, a, b, max_lag)
                bad = abs(q["db_l"] - s["db_l"]) > 0.05 or abs(q["db_r"] - s["db_r"]) > 0.05 or q["lag"] != s["lag"] or abs(q["same"] - s["same"]) > 1e-6
                parity_bad += bad
                extra = ""
                if s["name"] == "фон":
                    extra = f" · одинаковых отсчётов {100 * q['same']:.0f} % · связность 1–4 кГц {coh(x, a, b, rate):.2f}"
                else:
                    gp = gcc_phat(x, a, b, rate)
                    extra = (f" · L−R {q['db_l'] - q['db_r']:+.1f} дБ · R позже на {q['lag'] * 1000.0 / rate:+.3f} мс (пик {q['peak']:.2f}),"
                             f" GCC-PHAT {gp:+.3f} мс ≈ {gp * C / 10:+.1f} см разницы путей")
                print(f"  {s['name']}: L {q['db_l']:.1f} R {q['db_r']:.1f} dBFS{extra}" + ("  ← расходится с телефоном" if bad else ""))
            else:
                v = db(float(((x[a:b, 0] - x[a:b, 0].mean()) ** 2).sum()), b - a)
                bad = abs(v - s["db"]) > 0.05
                parity_bad += bad
                mono.setdefault(cfg, {})[s["name"]] = v
                print(f"  {s['name']}: {v:.1f} dBFS" + ("  ← расходится с телефоном" if bad else ""))
        if ch == 2:
            fon = next((s for s in r["segments"] if s["name"] == "фон"), None)
            if fon is not None:
                q = stats(x, fon["from"], fon["to"], max_lag)
                two = q["same"] < 0.5
                summary.append((cfg, two, coh(x, fon["from"], fon["to"], rate)))
        print()
    if summary:
        print("итог по стерео:")
        for cfg, two, c in summary:
            print(f"  {cfg}: {'два разных микрофона' if two else 'копия одного микрофона в двух каналах'} (связность фона {c:.2f})")
    for a, b in (("vr_mo_16_bottom", "vr_mo_16_back"),):
        if a in mono and b in mono and "обоими" in mono[a] and "обоими" in mono[b]:
            d = mono[a]["обоими"] - mono[b]["обоими"]
            print(f"  отдельно: нижний {mono[a]['обоими']:.1f}, задний {mono[b]['обоими']:.1f} dBFS на шум динамика — "
                  f"{'нижний' if d > 0 else 'задний'} ближе к динамику на {abs(d):.1f} дБ")
    print(f"сверка счёта с телефоном: {'совпадает' if parity_bad == 0 else f'расходится в {parity_bad} отрезках'}")
    return 1 if parity_bad else 0


if __name__ == "__main__":
    sys.exit(main())
