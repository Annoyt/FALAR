#!/usr/bin/env python3
"""Запись комнаты с подмешанным фоном другой записи — умеренный шум для замера на телефоне.

На компьютере смеси считает tools/vad_denoise_eval.py (--mix), а телефону нужна папка записи, как у
bench/air/rec/near-pt: room.wav и журналы проигрывания. Здесь такая папка и собирается: речь та же,
меняется только фон. Фон — отрезки записи-донора вне окон фраз, по кругу, приведённые к RMS level dBFS;
смешивание — ровно vad_denoise_eval.mix, поэтому телефон и компьютер слушают один и тот же звук.

    python3 tools/air_mix.py near-pt noisy-pt -45            # → bench/air/rec/nearbg45-pt
    python3 tools/air_mix.py near-ru noisy-pt -40 --name nearbg40-ru

Имя оканчивается языком (-pt, -ru): по нему gain_sweep.sh выбирает направление. Записи комнаты личные
и в git не идут — смесь тоже ложится в bench/air/rec.
"""
import argparse
import os
import shutil
import sys
import wave

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
import vad_denoise_eval as E  # noqa: E402

COPY = ('player.tsv', 'player.offset', 'listener.tsv', 'listener.offset')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('rec', help='запись с речью, например near-pt')
    ap.add_argument('bg', help='запись-донор фона, например noisy-pt')
    ap.add_argument('level', type=float, help='уровень фона, RMS dBFS (например −45)')
    ap.add_argument('--name', help='имя папки; по умолчанию <rec до дефиса>bg<|уровень|>-<язык>')
    a = ap.parse_args()
    lang = a.rec.rsplit('-', 1)[-1]
    name = a.name or '%sbg%g-%s' % (a.rec.rsplit('-', 1)[0], abs(a.level), lang)
    if not name.endswith('-' + lang):
        sys.exit('имя должно оканчиваться языком записи: -' + lang)
    src, dst = os.path.join(R, 'bench', 'air', 'rec', a.rec), os.path.join(R, 'bench', 'air', 'rec', name)
    x, _, _ = E.load_rec(a.rec)
    y = E.mix(x, E.room_noise(a.bg), a.level)
    os.makedirs(dst, exist_ok=True)
    with wave.open(os.path.join(dst, 'room.wav'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(E.SR)
        w.writeframes(np.round(np.clip(y, -1, 32767 / 32768) * 32768).astype('<i2').tobytes())
    for f in COPY:
        shutil.copyfile(os.path.join(src, f), os.path.join(dst, f))
    note = open(os.path.join(src, 'rec.txt'), encoding='utf-8').read().strip() if os.path.exists(os.path.join(src, 'rec.txt')) else ''
    with open(os.path.join(dst, 'rec.txt'), 'w', encoding='utf-8') as f:
        f.write('%s\nсмесь: %s + фон %s (вне окон фраз, по кругу) %g dBFS — tools/air_mix.py\n' % (note, a.rec, a.bg, a.level))
    print('%s: %s + фон %s %g dBFS, %.1f мин' % (dst, a.rec, a.bg, a.level, len(y) / E.SR / 60))


if __name__ == '__main__':
    main()
