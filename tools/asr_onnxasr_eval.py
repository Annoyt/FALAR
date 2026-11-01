#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Шаг 16а: готовое дообучение parakeet-tdt-0.6b-v3 под бразильский португальский (TAGARELA, onnx-asr) против
исходного parakeet v3 в том же движке (onnx-asr, fp32) — на тех же кусках 21 записи комнаты (*.chunks.npz шага 15).
Счёт по фразам (dualmic_eval.per_phrase), парный бутстрэп; отдельно португальские и русские записи.

    PYTHONPATH=~/tools/onnx-asr-pkg taskset -c 0 .venv/bin/python tools/asr_onnxasr_eval.py run base models/onnxasr-base-v3 near-pt …
    PYTHONPATH=~/tools/onnx-asr-pkg taskset -c 0 .venv/bin/python tools/asr_onnxasr_eval.py run tagarela models/onnxasr-tagarela near-pt …
    taskset -c 0 .venv/bin/python tools/asr_onnxasr_eval.py score near-pt …
"""
import argparse
import json
import os
import sys
import time

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(R, 'results', 'asr_conf')
SR = 16000


def run(tag, model_dir, recs):
    import onnx_asr
    import onnxruntime as rt
    so = rt.SessionOptions()
    so.intra_op_num_threads = so.inter_op_num_threads = 1
    model = onnx_asr.load_model('nemo-parakeet-tdt-0.6b-v3', model_dir, sess_options=so, providers=['CPUExecutionProvider'])
    for name in recs:
        path = os.path.join(OUT, f'{name}.oa-{tag}.json')
        done = json.load(open(path, encoding='utf-8')) if os.path.exists(path) else {'texts': [], 'sec': 0.0}
        d = np.load(os.path.join(OUT, f'{name}.chunks.npz'))
        edges = np.concatenate([[0], np.cumsum(d['lens'])])
        for k in range(len(d['lens'])):
            if k < len(done['texts']):
                continue
            a = d['audio'][edges[k]:edges[k + 1]].astype(np.float32) / 32768
            t0 = time.time()
            done['texts'].append(str(model.recognize(a, sample_rate=SR)).strip())
            done['sec'] += time.time() - t0
            if k % 20 == 19:   # можно прервать и продолжить
                json.dump(done, open(path + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(path + '.tmp', path)
        json.dump(done, open(path + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(path + '.tmp', path)
        print(f'{tag} {name}: кусков {len(done["texts"])}, {done["sec"]:.0f} с на одном ядре', flush=True)


def score(recs):
    sys.path.insert(0, os.path.join(R, 'tools'))
    import asr_confidence as ac
    import dualmic_eval as de
    tot = {}
    for name in recs:
        cache = json.load(open(os.path.join(OUT, name + '.json'), encoding='utf-8'))
        x, piece, plays, gap, lang = ac.load(name)
        segs = [tuple(s) for s in cache['segs']]
        texts = {'app': [r[0] for r in cache['asr']]}
        for tag in ('base', 'tagarela'):
            texts[tag] = json.load(open(os.path.join(OUT, f'{name}.oa-{tag}.json'), encoding='utf-8'))['texts']
            assert len(texts[tag]) == len(segs), f'{name}: {tag} досчитан не до конца'
        rows = {k: de.per_phrase(segs, v, plays, gap, lang) for k, v in texts.items()}
        for k, v in rows.items():
            tot.setdefault((lang, k), []).extend(v)
        n = sum(r[2] for r in rows['base'])
        d, lo, hi = de.boot(rows['base'], rows['tagarela'])
        print(f'{name:20s} приложение {100 * sum(r[1] for r in rows["app"]) / n:5.1f} % · v3 onnx-asr '
              f'{100 * sum(r[1] for r in rows["base"]) / n:5.1f} % · TAGARELA {100 * sum(r[1] for r in rows["tagarela"]) / n:5.1f} % '
              f'({d:+.1f} [{lo:+.1f}; {hi:+.1f}])', flush=True)
    for lang in ('pt', 'ru'):
        if (lang, 'base') not in tot:
            continue
        b, t, a = tot[(lang, 'base')], tot[(lang, 'tagarela')], tot[(lang, 'app')]
        n = sum(r[2] for r in b)
        d, lo, hi = de.boot(b, t)
        d2, lo2, hi2 = de.boot(a, b)
        print(f'{lang}-записи вместе: v3 onnx-asr {100 * sum(r[1] for r in b) / n:.2f} % → TAGARELA {100 * sum(r[1] for r in t) / n:.2f} % · '
              f'{d:+.2f} п.п. [{lo:+.2f}; {hi:+.2f}]; приложение (sherpa int8) {100 * sum(r[1] for r in a) / n:.2f} %, '
              f'v3 onnx-asr против приложения {d2:+.2f} [{lo2:+.2f}; {hi2:+.2f}]', flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['run', 'score'])
    ap.add_argument('args', nargs='+')
    a = ap.parse_args()
    if a.cmd == 'run':
        run(a.args[0], a.args[1], a.args[2:])
    else:
        score(a.args)


if __name__ == '__main__':
    main()
