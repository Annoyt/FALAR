#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Запись комнаты, где фразы играет ПК, а слушает телефон (шаг 9, часть Б results/2026-10-27-asr-confidence.md).
Оболочка — bench/air/record_air_pc.sh; здесь звук:

    room_rec.py build  <список фраз> <язык> <выход.wav> [--gain дБ]   # фразы через 3 с, 2 с тишины впереди; .tsv рядом
    room_rec.py level  <запись.wav>                                   # фон (медиана тихой трети) и речь (верхние 10 %)
    room_rec.py finish <сырой.wav> <плейлист.wav> <папка записи> <язык> [--note текст]

finish находит, где в сыром потоке телефона начался плейлист (взаимная корреляция огибающих, затем точнее по самому
звуку), и пишет папку записи, как у bench/air/rec/near-pt: room.wav, player.tsv, listener.tsv, смещения и rec.txt —
её читают tools/asr_confidence.py и tools/asr_beam_eval.py. Часы телефона и ПК не нужны: привязка — по звуку.
"""
import argparse
import os
import wave

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SR = 16000
LEAD, GAP = 2.0, 3.0


def read(p):
    w = wave.open(p)
    assert w.getframerate() == SR and w.getsampwidth() == 2, p
    x = np.frombuffer(w.readframes(w.getnframes()), '<i2').astype(np.float32) / 32768
    return x.reshape(-1, w.getnchannels()).mean(axis=1) if w.getnchannels() > 1 else x


def write(p, x):
    with wave.open(p, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 32767 / 32768) * 32768).astype('<i2').tobytes())


def db(e):
    return 10 * np.log10(max(float(e), 1e-12))


def frames(x, n):
    k = len(x) // n
    return (x[:k * n].reshape(k, n) ** 2).mean(axis=1) if k else np.array([np.mean(x ** 2)])


def active_rms_db(x):
    e = frames(x, SR // 50)
    return db(e[e >= e.max() * 1e-3].mean())


def levels(x):
    """Фон — медиана тихой трети кадров по 100 мс, речь — медиана верхних 10 % (как bench/air/rec/README.md)."""
    e = np.sort(frames(x, SR // 10))
    return db(np.median(e[: max(1, len(e) // 3)])), db(np.median(e[int(len(e) * 0.9):]))


def envelope(x, hop=160):
    k = len(x) // hop
    return np.sqrt((x[:k * hop].reshape(k, hop) ** 2).mean(axis=1))


def xcorr_lag(a, b):
    """Сдвиг b внутри a (a длиннее), по нормированной огибающей через БПФ."""
    a = (a - a.mean()) / (a.std() + 1e-9); b = (b - b.mean()) / (b.std() + 1e-9)
    n = 1 << int(np.ceil(np.log2(len(a) + len(b))))
    c = np.fft.irfft(np.fft.rfft(a, n) * np.conj(np.fft.rfft(b, n)), n)[: len(a) - len(b) + 1]
    k = int(np.argmax(c))
    return k, float(c[k] / len(b))


def build(a):
    names = [l.strip() for l in open(a.list, encoding='utf-8') if l.strip()]
    parts, rows, t = [np.zeros(int(LEAD * SR), np.float32)], [], int(LEAD * SR)
    for nm in names:
        x = read(os.path.join(R, 'bench', 'air', 'fresh', a.lang, nm))
        x = x * 10 ** ((-24.0 + a.gain - active_rms_db(x)) / 20)
        rows.append((nm, t, len(x)))
        parts += [x, np.zeros(int(GAP * SR), np.float32)]
        t += len(x) + int(GAP * SR)
    y = np.concatenate(parts)
    peak = float(np.abs(y).max())
    if peak > 0.99:
        y *= 0.99 / peak
    write(a.out, y)
    with open(a.out[:-4] + '.tsv', 'w', encoding='utf-8') as f:
        for nm, s, n in rows:
            f.write(f'{nm}\t{s}\t{n}\n')
    print(f'плейлист: {len(rows)} фраз, {len(y) / SR:.0f} с' + (f', сжат ×{0.99 / peak:.2f}' if peak > 0.99 else ''))


def level(a):
    x = read(a.wav)
    bg, sp = levels(x)
    print(f'фон {bg:.1f} dBFS · речь (верхние 10 %) {sp:.1f} dBFS · длина {len(x) / SR:.1f} с')


def finish(a):
    raw, pl = read(a.raw), read(a.playlist)
    rows = [l.rstrip('\n').split('\t') for l in open(a.playlist[:-4] + '.tsv', encoding='utf-8')]
    k, score = xcorr_lag(envelope(raw), envelope(pl))
    # точнее — по самому звуку в окне ±0,1 с вокруг найденного по огибающей
    c0 = k * 160; w = 1600
    seg = raw[max(0, c0 - w): c0 + w + 30 * SR]; ref = pl[: 30 * SR]
    k2, score2 = xcorr_lag(seg, ref) if len(seg) > len(ref) else (w, 0.0)
    off = max(0, c0 - w) + k2
    d = a.out
    os.makedirs(d, exist_ok=True)
    write(os.path.join(d, 'room.wav'), raw)          # моно: два канала телефона — средним, как у прежних записей
    t0 = 1_800_000_000_000
    with open(os.path.join(d, 'listener.tsv'), 'w', encoding='utf-8') as f:
        f.write(f'{t0}\traw_begin\t{len(raw) // SR}\n')
    with open(os.path.join(d, 'player.tsv'), 'w', encoding='utf-8') as f:
        f.write(f'{t0}\tplay_begin\tfresh/{a.lang}\t{len(rows)}\t1\t{int(GAP * 1000)}\t-24\t-\n')
        for nm, s, n in rows:
            f.write(f'{t0 + (off + int(s)) * 1000 // SR}\tplay\t{nm}\t{int(n) * 1000 / SR:.1f}\t-24\t0\t{SR}\n')
    for o in ('player.offset', 'listener.offset'):
        open(os.path.join(d, o), 'w').write('0')
    bg, sp = levels(raw)
    with open(os.path.join(d, 'rec.txt'), 'w', encoding='utf-8') as f:
        f.write(f'язык={a.lang} пауза={int(GAP * 1000)} длительность={len(raw) // SR} комната: ПК играет новые фразы '
                f'(bench/air/fresh), слушает телефон (сырой поток), {a.note} — tools/room_rec.py '
                f'сдвиг={off / SR:.3f} совпадение={score:.2f}/{score2:.2f} фон={bg:.1f} речь_верх10={sp:.1f}\n')
    print(f'{os.path.basename(d)}: плейлист начался на {off / SR:.2f} с (совпадение огибающей {score:.2f}, звука '
          f'{score2:.2f}); фон {bg:.1f} dBFS, речь {sp:.1f} dBFS; фраз {len(rows)}')


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    b = sub.add_parser('build'); b.add_argument('list'); b.add_argument('lang'); b.add_argument('out')
    b.add_argument('--gain', type=float, default=0.0)
    lv = sub.add_parser('level'); lv.add_argument('wav')
    f = sub.add_parser('finish'); f.add_argument('raw'); f.add_argument('playlist'); f.add_argument('out')
    f.add_argument('lang'); f.add_argument('--note', default='')
    a = ap.parse_args()
    {'build': build, 'level': level, 'finish': finish}[a.cmd](a)


if __name__ == '__main__':
    main()
