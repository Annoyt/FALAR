#!/usr/bin/env python3
"""Однословные ответы («Sim», «Não», «Да», «Спасибо»): доходят ли они до распознавания при нарезке приложения.

Нарезка берёт у silero признак «речь», а он появляется только после 0,25 с речи подряд (min_speech_duration в
Engine.buildVad), и дальше требует ещё minSpeechMs = 250 мс речи после срабатывания (TranslatorService.startVad,
`voiced`). Вместе — около полусекунды: слово короче не даст куска вовсе. В корпусе bench/air/corpus короче 0,7 с
ничего нет, поэтому слова вырезаются из фраз корпуса по меткам времени parakeet и ставятся в тихую комнату
(фон near-pt, по 1,5 с до и после). Для каждого слова: дало ли оно кусок при минимуме речи 250, 150 и 100 мс
(нарезка по умолчанию, auto3) и узнал ли его parakeet. Обратная сторона меньшего минимума — лишние куски в тишине и
шуме — считается на самих записях комнаты (--recs), с тем же счётом, что у vad_denoise_eval.

  taskset -c N .venv/bin/python tools/short_reply_eval.py [--words 40] [--recs near-pt,near-ru,noisy-pt,far-pt]
"""
import argparse
import glob
import json
import os
import re
import sys
import time
import wave

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
sys.path.insert(0, os.path.join(R, 'bench', 'asr2'))
import vad_denoise_eval as vde  # noqa: E402
from wer2 import norm  # noqa: E402

SR = 16000


def read_wav(path):
    with wave.open(path) as w:
        return np.frombuffer(w.readframes(w.getnframes()), '<i2').astype(np.float32) / 32768


class Asr:
    def __init__(self):
        m = os.path.join(R, 'models', 'asr_multi')
        self.rec = vde.so.OfflineRecognizer.from_transducer(
            encoder=f'{m}/encoder.int8.onnx', decoder=f'{m}/decoder.int8.onnx', joiner=f'{m}/joiner.int8.onnx',
            tokens=f'{m}/tokens.txt', num_threads=1, model_type='nemo_transducer', decoding_method='greedy_search')

    def run(self, x):
        s = self.rec.create_stream(); s.accept_waveform(SR, np.ascontiguousarray(x, np.float32)); self.rec.decode_stream(s)
        return s.result


def words_of(res):
    """Слова по токенам: начало — токен с «▁»; конец — начало следующего слова или конец последнего токена."""
    toks, ts = list(res.tokens), list(res.timestamps)
    durs = list(getattr(res, 'durations', []) or [])
    out, cur = [], None
    for k, t in enumerate(toks):
        if t.startswith('▁') or t.startswith(' ') or cur is None:   # sherpa на ПК отдаёт токены с пробелом вместо «▁»
            if cur is not None: out.append(cur)
            cur = [t.replace('▁', '').strip(), ts[k], None, k]
        else:
            cur[0] += t
        cur[2] = ts[k] + (durs[k] if k < len(durs) else 0.08)
    if cur is not None: out.append(cur)
    for i in range(len(out) - 1):
        out[i][2] = min(out[i][2], out[i + 1][1])
    return [(w, a, b) for w, a, b, _ in out]


def pick_words(asr, per_lang, lo, hi):
    """Слова от 3 букв длительностью lo…hi с: (язык, слово, звук файла, начало, конец)."""
    picked = {'pt': [], 'ru': []}
    for lang in ('pt', 'ru'):
        for w in sorted(glob.glob(os.path.join(R, 'bench', 'air', 'corpus', lang, '*.wav'))):
            if len(picked[lang]) >= per_lang: break
            x = read_wav(w); res = asr.run(x)
            for word, a, b in words_of(res):
                letters = re.sub(r'[^\w]', '', word)
                if len(letters) >= 3 and lo <= b - a <= hi and a >= 0.05:
                    picked[lang].append((word, x, a, b, os.path.basename(w))); break
    return picked


def clip_of(x, a, b, room, k):
    """Слово (с 40 мс до и 60 мс после, края по 8 мс) в тихой комнате: 1,5 с фона до и после."""
    i, j = max(0, int((a - 0.04) * SR)), min(len(x), int((b + 0.06) * SR))
    y = x[i:j].copy(); f = int(0.008 * SR)
    y[:f] *= np.linspace(0, 1, f, dtype=np.float32); y[-f:] *= np.linspace(1, 0, f, dtype=np.float32)
    off = (k * 7919) % max(1, len(room) - 4 * SR)
    pre, post = room[off:off + SR * 3 // 2].copy(), room[off + SR * 3 // 2:off + 3 * SR].copy()
    return np.concatenate([pre, y, post]), len(pre), len(pre) + len(y)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--words', type=int, default=40, help='слов на язык')
    ap.add_argument('--lo', type=float, default=0.2); ap.add_argument('--hi', type=float, default=0.45)
    ap.add_argument('--minsp', default='250,150,100')
    ap.add_argument('--recs', default='near-pt,near-ru,noisy-pt,far-pt', help='записи для обратной стороны; пусто — не считать')
    ap.add_argument('--out', default=os.path.join(R, 'results', 'asr_filters', 'short_reply.json'))
    a = ap.parse_args()
    asr = Asr(); t0 = time.time()
    picked = pick_words(asr, a.words, a.lo, a.hi)
    room = vde.room_noise('near-pt').astype(np.float32)
    minsps = [int(m) for m in a.minsp.split(',')]
    res = {'words': {}, 'recs': {}}
    print(f'слов: pt {len(picked["pt"])}, ru {len(picked["ru"])} (длительность {a.lo}–{a.hi} с), подбор {time.time() - t0:.0f} с', flush=True)
    for lang in ('pt', 'ru'):
        rows = []
        for k, (word, x, wa, wb, fn) in enumerate(picked[lang]):
            clip, c0, c1 = clip_of(x, wa, wb, room, k)
            r = {'word': word, 'file': fn, 'dur': round(wb - wa, 2)}
            for m in minsps:
                segs, _ = vde.segment(clip, 'dn_sil', 'gtcrn', 0.0, -48.0, True, vde.Wiener(), minsp=m)
                hit = [s for s in segs if s[0] <= c1 and s[1] >= c0]
                got = ''
                if hit:
                    got = asr.run(clip[hit[0][0]:hit[0][1]]).text.strip()
                r[f'seg{m}'] = len(hit); r[f'asr{m}'] = got
                r[f'ok{m}'] = bool(got) and norm(word) == norm(got)[:len(norm(word))] and len(norm(word)) > 0
            rows.append(r)
        res['words'][lang] = rows
        print(f'\n{lang}: слов {len(rows)}')
        print('| минимум речи | кусок есть | слово узнано |'); print('|---|---|---|')
        for m in minsps:
            print(f'| {m} мс | {sum(1 for r in rows if r[f"seg{m}"])} из {len(rows)} | {sum(1 for r in rows if r[f"ok{m}"])} |')
        print('примеры:', '; '.join(f'{r["word"]} {r["dur"]} с → ' + '/'.join(("«" + r[f"asr{m}"] + "»" if r[f"seg{m}"] else '—') for m in minsps) for r in rows[:8]))
    recs = [r for r in a.recs.split(',') if r]
    for name in recs:
        x, plays, gap = vde.load_rec(name); lang = 'ru' if name.endswith('-ru') else 'pt'
        res['recs'][name] = {}
        for m in minsps:
            t1 = time.time()
            segs, stat = vde.segment(x, 'dn_sil', 'gtcrn', 0.0, -48.0, True, vde.Wiener(), minsp=m)
            texts = [asr.run(x[s0:s1]).text.strip() for s0, s1, _ in segs]
            sc = vde.score(segs, texts, plays, gap, lang); sc['short'] = sum(1 for s0, s1, _ in segs if s1 - s0 < 0.5 * SR + 1300 * 16)
            res['recs'][name][m] = sc
            print(f'{name} минимум {m} мс: WER {sc["wer"]} потеряно {sc["lost"]} кусков {sc["segments"]} вне окон {sc["outside"]} '
                  f'разрезано {sc["split"]} ({time.time() - t1:.0f} с)', flush=True)
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    json.dump(res, open(a.out, 'w'), ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
