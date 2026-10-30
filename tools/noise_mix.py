#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Смеси для шага 9 results/2026-10-27-asr-confidence.md: новые фразы Tatoeba (bench/air/fresh) поверх шума DEMAND —
папка записи, как у bench/air/rec/near-pt, чтобы её читали те же инструменты (tools/asr_confidence.py,
tools/asr_beam_eval.py, vad_denoise_eval.load_rec).

Фразы — через паузу 3 с, как играл корпус второй телефон; речь — к −24 dBFS по активной части фразы (у near-pt
медиана верхних 10 % кадров записи — −22 dBFS, здесь она пишется в rec.txt); фон — непрерывный кусок первого
канала DEMAND, приведённый к RMS level dBFS. Перед первой фразой — 2 с одного фона: оценке шума нарезки есть с
чего начать. Фразы делятся между средами без повторов: у каждой записи свои 40 (порядок — от seed).

    python3 -I tools/noise_mix.py --demand data/noise/demand --fresh bench/air/fresh --level -40 --per 40 --seed 9

Записи ложатся в bench/air/rec/dm-<среда>-<язык> (не в git): dm-cafe-pt, dm-rest-ru и т. д.
"""
import argparse
import os
import random
import wave

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SR = 16000
ENVS = (('cafe', 'PCAFETER'), ('rest', 'PRESTO'), ('hall', 'OHALLWAY'),
        ('stat', 'PSTATION'), ('street', 'STRAFFIC'), ('bus', 'TBUS'))


def read(p):
    w = wave.open(p)
    assert w.getframerate() == SR and w.getsampwidth() == 2 and w.getnchannels() == 1, p
    return np.frombuffer(w.readframes(w.getnframes()), '<i2').astype(np.float32) / 32768


def write(p, x):
    with wave.open(p, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 32767 / 32768) * 32768).astype('<i2').tobytes())


def db(e):
    return 10 * np.log10(max(e, 1e-12))


def frames(x, n=SR // 50):
    k = len(x) // n
    return (x[:k * n].reshape(k, n) ** 2).mean(axis=1) if k else np.array([np.mean(x ** 2)])


def active_rms_db(x):
    """Уровень речи фразы: средняя энергия кадров по 20 мс не ниже чем на 30 дБ от самого громкого."""
    e = frames(x)
    keep = e[e >= e.max() * 1e-3]
    return db(keep.mean())


def top10_db(x):
    """Как в bench/air/rec/README.md: медиана верхних 10 % кадров по 100 мс."""
    e = np.sort(frames(x, SR // 10))
    return db(np.median(e[int(len(e) * 0.9):]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--demand', required=True, help='папка с <СРЕДА>.wav (первый канал DEMAND, 16 кГц)')
    ap.add_argument('--fresh', required=True, help='bench/air/fresh: <язык>/tat_*.wav и .txt')
    ap.add_argument('--level', type=float, default=-40.0, help='RMS фона, dBFS')
    ap.add_argument('--speech', type=float, default=-24.0, help='уровень активной речи фразы, dBFS')
    ap.add_argument('--per', type=int, default=40, help='фраз на запись')
    ap.add_argument('--gap', type=int, default=3000, help='пауза между фразами, мс')
    ap.add_argument('--seed', type=int, default=9)
    ap.add_argument('--langs', default='pt,ru')
    a = ap.parse_args()
    for lang in a.langs.split(','):
        clips = sorted(f for f in os.listdir(os.path.join(a.fresh, lang)) if f.endswith('.wav'))
        random.Random(a.seed).shuffle(clips)
        need = a.per * len(ENVS)
        assert len(clips) >= need, f'{lang}: фраз {len(clips)}, нужно {need}'
        for k, (short, env) in enumerate(ENVS):
            mine = clips[k * a.per:(k + 1) * a.per]
            noise = read(os.path.join(a.demand, env + '.wav'))
            parts, plays, t = [np.zeros(2 * SR, np.float32)], [], 2 * SR
            for c in mine:
                x = read(os.path.join(a.fresh, lang, c))
                x = x * 10 ** ((a.speech - active_rms_db(x)) / 20)
                plays.append((c, t, len(x)))
                parts += [x, np.zeros(a.gap * SR // 1000, np.float32)]
                t += len(x) + a.gap * SR // 1000
            speech = np.concatenate(parts)
            start = (k * 37 + (0 if lang == 'pt' else 113)) * SR % max(1, len(noise) - len(speech))
            bg = noise[start:start + len(speech)]
            if len(bg) < len(speech):           # фон короче записи — по кругу
                bg = np.resize(noise, len(speech))
            bg = bg * 10 ** ((a.level - db(np.mean(bg ** 2))) / 20)
            x = speech + bg
            peak = float(np.abs(x).max())
            if peak > 0.99:
                x *= 0.99 / peak
            name = f'dm-{short}-{lang}'
            d = os.path.join(R, 'bench', 'air', 'rec', name)
            os.makedirs(d, exist_ok=True)
            write(os.path.join(d, 'room.wav'), x)
            t0 = 1_800_000_000_000                # условные часы: начало записи
            with open(os.path.join(d, 'listener.tsv'), 'w', encoding='utf-8') as f:
                f.write(f'{t0}\traw_begin\t{len(x) // SR}\n')
            with open(os.path.join(d, 'player.tsv'), 'w', encoding='utf-8') as f:
                f.write(f'{t0}\tplay_begin\tfresh/{lang}\t{len(mine)}\t1\t{a.gap}\t{a.speech}\t-\n')
                for c, s, n in plays:
                    f.write(f'{t0 + s * 1000 // SR}\tplay\t{c}\t{n * 1000 / SR:.1f}\t{a.speech}\t0\t{SR}\n')
            for o in ('player.offset', 'listener.offset'):
                open(os.path.join(d, o), 'w').write('0')
            with open(os.path.join(d, 'rec.txt'), 'w', encoding='utf-8') as f:
                f.write(f'язык={lang} пауза={a.gap} длительность={len(x) // SR} смесь: новые фразы Tatoeba '
                        f'(bench/air/fresh) + DEMAND {env} {a.level:.0f} dBFS — tools/noise_mix.py '
                        f'речь_верх10={top10_db(x):.1f} фон_rms={db(np.mean(bg ** 2)):.1f} сжатие={min(1, 0.99 / peak):.3f}\n')
            print(f'{name}: фраз {len(mine)}, {len(x) / SR:.0f} с, речь (верх 10 %) {top10_db(x):.1f} dBFS, '
                  f'фон {db(np.mean(bg ** 2)):.1f} dBFS' + (f', сжато ×{0.99 / peak:.2f}' if peak > 0.99 else ''))


if __name__ == '__main__':
    main()
