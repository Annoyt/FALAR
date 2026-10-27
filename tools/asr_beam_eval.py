#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Распознавание с поиском по лучу (modified_beam_search) против жадного — на тех же кусках записей комнаты, что у
tools/asr_confidence.py (берутся из его кэша results/asr_conf/). Parakeet, один поток. Счёт — по всем фразам, как
tools/dualmic_eval.per_phrase; сравнение — парный бутстрэп по фразам. Время — сумма по кускам на этом ПК.

    taskset -c 0 .venv/bin/python tools/asr_beam_eval.py near-pt … [--paths 4]
"""
import argparse
import json
import os
import sys
import time

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
import asr_confidence as ac  # noqa: E402
import dualmic_eval as de  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('recs', nargs='+')
    ap.add_argument('--paths', type=int, default=4)
    ap.add_argument('--out', default=os.path.join(R, 'results', 'asr_conf'))
    ap.add_argument('--noisy', action='store_true', help='луч только для кусков, закрытых при включённом шумодаве нарезки')
    a = ap.parse_args()
    m = os.path.join(R, 'models', 'asr_multi')
    rec = ac.vde.so.OfflineRecognizer.from_transducer(
        encoder=f'{m}/encoder.int8.onnx', decoder=f'{m}/decoder.int8.onnx', joiner=f'{m}/joiner.int8.onnx',
        tokens=f'{m}/tokens.txt', num_threads=1, model_type='nemo_transducer', decoding_method='modified_beam_search',
        max_active_paths=a.paths)
    allg, allb, tg, tb = [], [], 0.0, 0.0
    for name in a.recs:
        cache = json.load(open(os.path.join(a.out, name + '.json'), encoding='utf-8'))
        x, piece, plays, gap, lang = ac.load(name)
        segs = [tuple(s) for s in cache['segs']]
        bpath = os.path.join(a.out, f'{name}.beam{a.paths}.json')
        if os.path.exists(bpath):
            beam = json.load(open(bpath, encoding='utf-8'))
        else:
            texts, sec = [], 0.0
            for s in segs:
                t0 = time.time(); texts.append(ac.recognize(rec, piece(s[0], s[1]))[0]); sec += time.time() - t0
            beam = dict(texts=texts, sec=sec)
            json.dump(beam, open(bpath + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(bpath + '.tmp', bpath)
        greedy = [r[0] for r in cache['asr']]
        texts = beam['texts']
        if a.noisy:                                       # где шумодав нарезки выключен — жадный, как сейчас
            fpath = os.path.join(a.out, f'{name}.gate.json')
            if os.path.exists(fpath): flags = json.load(open(fpath, encoding='utf-8'))
            else:
                flags = []
                s2, _ = ac.vde.segment(x, 'dn_sil', 'gtcrn', 0.0, -48.0, True, ac.vde.Wiener(), on_out=flags, **ac.APP)
                assert [tuple(t) for t in s2] == segs, 'нарезка разошлась с кэшем'
                json.dump(flags, open(fpath, 'w', encoding='utf-8'))
            texts = [bt if f else gt for bt, gt, f in zip(texts, greedy, flags)]
            print(f'   {name}: кусков при шумодаве {sum(flags)} из {len(flags)}', flush=True)
        g = de.per_phrase(segs, greedy, plays, gap, lang)
        b = de.per_phrase(segs, texts, plays, gap, lang)
        allg += g; allb += b; tb += beam['sec']
        e1, n = sum(r[1] for r in g), sum(r[2] for r in g); e2 = sum(r[1] for r in b)
        d, lo, hi = de.boot(g, b)
        print(f'{name:20s} жадный {100 * e1 / n:5.1f} % · луч {100 * e2 / n:5.1f} % · разница {d:+.1f} п.п. [{lo:+.1f}; {hi:+.1f}]', flush=True)
    n = sum(r[2] for r in allg)
    d, lo, hi = de.boot(allg, allb)
    print(f'все вместе: жадный {100 * sum(r[1] for r in allg) / n:.2f} % · луч {100 * sum(r[1] for r in allb) / n:.2f} % · '
          f'{d:+.2f} п.п. [{lo:+.2f}; {hi:+.2f}] · время луча на ПК {tb:.0f} с')


if __name__ == '__main__':
    main()
