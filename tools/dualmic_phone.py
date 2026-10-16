#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Два микрофона на телефоне (TESTS D23): счёт прогонов bench/air/gain_sweep.sh по стереозаписям комнаты
(bench/air/rec/st-*, room.wav — ссылка на room2.wav) — с --seg "dualmic=off" (среднее, как раньше) и "dualmic=auto"
(выровненная сумма). gain_sweep сам считает только записи record_air.sh, поэтому кусок здесь относится к фразе так же,
как в tools/dualmic_eval.py: по месту нарезки (segPos из at.tsv, отсчёты от начала подачи) в окне фразы, найденной
в записи взаимной корреляцией. Текст куска — всё, что распознано (asr и отбитое skip_*): сравнивается распознавание.

    .venv/bin/python tools/dualmic_phone.py <запись> <прогон off> <прогон auto>
    .venv/bin/python tools/dualmic_phone.py st-laptop-end-ru /tmp/falar-gain.X/st-laptop-end-ru-a0-gauto-limit-auto …
"""
import os
import sys

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
import dualmic_eval as de  # noqa: E402

SR = 16000
KINDS = ('asr', 'skip_short', 'skip_lang', 'skip_self')


def phone_segs(run):
    segs, texts, lags = [], [], []
    for line in open(os.path.join(run, 'listener.tsv'), encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) >= 3 and p[1] == 'dualmic' and p[2] not in ('mono', ''):
            lags.append(float(p[2]))
        if len(p) >= 14 and p[1] in KINDS + ('silence',) and p[13].strip():
            segs.append((0, 0, int(float(p[13]))))
            texts.append(p[3] if p[1] in KINDS else '')
    return segs, texts, lags


def main():
    name, off, auto = sys.argv[1:4]
    _, plays, gap, lang = de.load(name)
    rows = {}
    for tag, run in (('off', off), ('auto', auto)):
        segs, texts, lags = phone_segs(run)
        rows[tag] = de.per_phrase(segs, texts, plays, gap, lang)
        e = sum(r[1] for r in rows[tag]); n = sum(r[2] for r in rows[tag]); lost = sum(r[3] for r in rows[tag])
        extra = ''
        if lags:
            lags.sort()
            extra = f' · задержка куска: медиана {lags[len(lags) // 2]:+.3f} мс, кусков с суммой {len(lags)}'
        print(f'{name} {tag:4s}: WER {100 * e / n:5.1f} % · потеряно {lost:2d} из {len(rows[tag])} · кусков {len(segs)}{extra}')
    d, lo, hi = de.boot(rows['off'], rows['auto'])
    print(f'{name} auto против off: {d:+.1f} п.п. [{lo:+.1f}; {hi:+.1f}]')


if __name__ == '__main__':
    main()
