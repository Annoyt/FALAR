#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Шаг 16б: признаки звука для parakeet приложения — те же файлы модели (models/asr_multi, int8, как в sherpa-onnx),
своё декодирование (tools/parakeet_ort.py, жадный TDT как в sherpa-onnx), три вида признаков:
  sherpa    — как сейчас в приложении (kaldi-native-fbank: окно Пови, мел до 7600 Гц);
  hann8000  — окно Ханна и мел до 8000 Гц, остальное как у sherpa: что даст правка двух строк в нашем JNI;
  nemo      — признаки самого NeMo (nemo128.onnx из onnx-asr) — потолок «правильных признаков».
Куски — те же 21 запись (*.chunks.npz шага 15). На видеокарте (владелец 01.11: «запускай только на гпу»):
    V=~/tools/venv-comet-gpu/lib/python3.12/site-packages/nvidia
    LD_LIBRARY_PATH=$V/cu13/lib:$V/cudnn/lib PYTHONPATH=~/tools/ort-gpu-pkg \\
      ~/tools/venv-comet-gpu/bin/python tools/asr_feat_eval.py run sherpa near-pt …
    taskset -c 0 .venv/bin/python tools/asr_feat_eval.py score near-pt …
"""
import argparse
import json
import os
import sys
import time

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(R, 'results', 'asr_conf')
MAIN = os.path.dirname(os.path.dirname(R)) if os.path.basename(os.path.dirname(R)) == '.worktrees' else R
NEMO_PRE = os.path.join(MAIN, 'models', 'onnxasr-base-v3', 'nemo128.onnx')
ARMS = ('sherpa', 'hann8000', 'nemo')


def run(arm, recs):
    sys.path.insert(0, os.path.join(R, 'tools'))
    import onnxruntime as ort
    import parakeet_ort as po
    if 'CUDAExecutionProvider' not in ort.get_available_providers():
        sys.exit('нет CUDAExecutionProvider — см. начало файла')
    pk = po.Parakeet(os.path.join(R, 'models', 'asr_multi'), providers=['CUDAExecutionProvider', 'CPUExecutionProvider'],
                     feats=arm, nemo_pre=NEMO_PRE)
    for name in recs:
        path = os.path.join(OUT, f'{name}.feat-{arm}.json')
        done = json.load(open(path, encoding='utf-8')) if os.path.exists(path) else {'texts': [], 'sec': 0.0}
        d = np.load(os.path.join(OUT, f'{name}.chunks.npz'))
        edges = np.concatenate([[0], np.cumsum(d['lens'])])
        for k in range(len(d['lens'])):
            if k < len(done['texts']):
                continue
            a = d['audio'][edges[k]:edges[k + 1]].astype(np.float32) / 32768
            t0 = time.time()
            done['texts'].append(pk.text([y for _, y, *_ in pk.greedy(pk.encode(a))]))
            done['sec'] += time.time() - t0
            if k % 20 == 19:   # можно прервать и продолжить
                json.dump(done, open(path + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(path + '.tmp', path)
        json.dump(done, open(path + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(path + '.tmp', path)
        print(f'{arm} {name}: кусков {len(done["texts"])}, {done["sec"]:.0f} с', flush=True)


def score(recs):
    sys.path.insert(0, os.path.join(R, 'tools'))
    import asr_confidence as ac
    import dualmic_eval as de
    level = 0.975                                       # два плеча против sherpa
    tot, same, nall, harm = {}, 0, 0, {a: [] for a in ARMS[1:]}
    for name in recs:
        cache = json.load(open(os.path.join(OUT, name + '.json'), encoding='utf-8'))
        x, piece, plays, gap, lang = ac.load(name)
        segs = [tuple(s) for s in cache['segs']]
        texts = {'app': [r[0] for r in cache['asr']]}
        for arm in ARMS:
            texts[arm] = json.load(open(os.path.join(OUT, f'{name}.feat-{arm}.json'), encoding='utf-8'))['texts']
            assert len(texts[arm]) == len(segs), f'{name}: {arm} досчитан не до конца'
        same += sum(a == b for a, b in zip(texts['app'], texts['sherpa'])); nall += len(segs)
        rows = {k: de.per_phrase(segs, v, plays, gap, lang) for k, v in texts.items()}
        for k, v in rows.items():
            tot.setdefault(k, []).extend(v); tot.setdefault((lang, k), []).extend(v)
        n = sum(r[2] for r in rows['sherpa'])
        line = f'{name:20s} приложение {100 * sum(r[1] for r in rows["app"]) / n:5.1f} % · sherpa {100 * sum(r[1] for r in rows["sherpa"]) / n:5.1f} %'
        for arm in ARMS[1:]:
            d, lo, hi = de.boot(rows['sherpa'], rows[arm], level=level)
            if d >= 1 and lo > 0:
                harm[arm].append(name)
            line += f' · {arm} {100 * sum(r[1] for r in rows[arm]) / n:5.1f} % ({d:+.1f} [{lo:+.1f}; {hi:+.1f}])'
        print(line, flush=True)
    print(f'«sherpa» на ПК совпал с текстом приложения в {same} из {nall} кусков', flush=True)
    for key, title in ((None, 'все записи'), ('pt', 'pt-записи'), ('ru', 'ru-записи')):
        g = lambda k: tot[k] if key is None else tot[(key, k)]   # noqa: E731
        n = sum(r[2] for r in g('sherpa'))
        line = f'{title}: приложение {100 * sum(r[1] for r in g("app")) / n:.2f} % · sherpa {100 * sum(r[1] for r in g("sherpa")) / n:.2f} %'
        for arm in ARMS[1:]:
            d, lo, hi = de.boot(g('sherpa'), g(arm), level=level)
            line += f' · {arm} {100 * sum(r[1] for r in g(arm)) / n:.2f} % ({d:+.2f} [{lo:+.2f}; {hi:+.2f}])'
        print(line, flush=True)
    for arm in ARMS[1:]:
        print(f'{arm}: хуже на 1 п.п. и больше с интервалом выше нуля — {", ".join(harm[arm]) or "нигде"}', flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['run', 'score'])
    ap.add_argument('args', nargs='+')
    a = ap.parse_args()
    if a.cmd == 'run':
        run(a.args[0], a.args[1:])
    else:
        score(a.args)


if __name__ == '__main__':
    main()
