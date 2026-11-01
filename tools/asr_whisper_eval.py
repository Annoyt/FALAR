#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Шаг 15: Whisper-large-v3-turbo как потолок качества — на тех же кусках тех же записей комнаты, что у шагов 2, 9,
10, 13 (кэш results/asr_conf/), против жадного parakeet приложения. Два плеча: W — Whisper на всех кусках, H — Whisper
только там, где у parakeet есть сомнительное слово (уверенность ниже 0,7, как пометки WordConf), остальное — parakeet.
Счёт по фразам (dualmic_eval.per_phrase), парный бутстрэп, интервалы 97,5 %.

Три шага, в разных окружениях (sherpa-onnx — в .venv, torch с CUDA — в ~/tools/venv-comet-gpu):
    taskset -c 0 .venv/bin/python tools/asr_whisper_eval.py dump near-pt …            # куски звука → *.chunks.npz
    taskset -c 0 ~/tools/venv-comet-gpu/bin/python tools/asr_whisper_eval.py whisper near-pt … [--model DIR]
    taskset -c 0 .venv/bin/python tools/asr_whisper_eval.py score near-pt …
"""
import argparse
import json
import os
import sys
import time

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(R, 'results', 'asr_conf')
MODEL = os.path.join(R, 'models', 'whisper-large-v3-turbo')
DOUBT = 0.7          # как пометки сомнительных слов (WordConf)
SR = 16000
NOISY_PT = ('noisy-pt', 'pc-cafe-pt', 'pc-street-pt', 's10-cafe-pt', 's10-street-pt')   # шумные pt-записи


def dump(recs):
    sys.path.insert(0, os.path.join(R, 'tools'))
    import asr_confidence as ac
    for name in recs:
        path = os.path.join(OUT, f'{name}.chunks.npz')
        if os.path.exists(path):
            continue
        cache = json.load(open(os.path.join(OUT, name + '.json'), encoding='utf-8'))
        x, piece, plays, gap, lang = ac.load(name)
        segs = [tuple(s) for s in cache['segs']]
        chunks = [np.clip(np.round(piece(s[0], s[1]) * 32767), -32768, 32767).astype(np.int16) for s in segs]
        lens = np.array([len(c) for c in chunks], np.int64)
        np.savez(path + '.tmp.npz', audio=np.concatenate(chunks) if chunks else np.zeros(0, np.int16), lens=lens, lang=lang)
        os.replace(path + '.tmp.npz', path)
        print(f'{name}: кусков {len(chunks)}, {lens.sum() / SR:.0f} с звука', flush=True)


def whisper(recs, model_dir, beams):
    import torch
    from transformers import WhisperForConditionalGeneration, WhisperProcessor
    proc = WhisperProcessor.from_pretrained(model_dir)
    model = WhisperForConditionalGeneration.from_pretrained(model_dir, torch_dtype=torch.float16).to('cuda').eval()
    torch.set_num_threads(1)
    for name in recs:
        path = os.path.join(OUT, f'{name}.whisper.json')
        done = json.load(open(path, encoding='utf-8')) if os.path.exists(path) else {'texts': [], 'sec': 0.0}
        d = np.load(os.path.join(OUT, f'{name}.chunks.npz'))
        lang = {'pt': 'portuguese', 'ru': 'russian'}[str(d['lang'])]
        edges = np.concatenate([[0], np.cumsum(d['lens'])])
        for k in range(len(d['lens'])):
            if k < len(done['texts']):
                continue
            a = d['audio'][edges[k]:edges[k + 1]].astype(np.float32) / 32768
            t0 = time.time()
            feats = proc(a, sampling_rate=SR, return_tensors='pt').input_features.to('cuda', torch.float16)
            with torch.no_grad():
                ids = model.generate(feats, language=lang, task='transcribe', num_beams=beams, max_new_tokens=200)
            done['texts'].append(proc.batch_decode(ids, skip_special_tokens=True)[0].strip())
            done['sec'] += time.time() - t0
            if k % 20 == 19:   # можно прервать и продолжить
                json.dump(done, open(path + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(path + '.tmp', path)
        json.dump(done, open(path + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(path + '.tmp', path)
        print(f'{name}: кусков {len(done["texts"])}, {done["sec"]:.0f} с на видеокарте', flush=True)


def score(recs, stop_on_harm=False):
    sys.path.insert(0, os.path.join(R, 'tools'))
    import asr_confidence as ac
    import dualmic_eval as de
    level = 0.975
    allg, allw, allh, tw, nh, nall = [], [], [], 0.0, 0, 0
    sub = {'g': [], 'w': [], 'h': []}
    for name in recs:
        cache = json.load(open(os.path.join(OUT, name + '.json'), encoding='utf-8'))
        x, piece, plays, gap, lang = ac.load(name)
        segs = [tuple(s) for s in cache['segs']]
        wj = json.load(open(os.path.join(OUT, f'{name}.whisper.json'), encoding='utf-8'))
        assert len(wj['texts']) == len(segs), f'{name}: Whisper досчитан не до конца'
        greedy = [r[0] for r in cache['asr']]
        doubt = [any(c < DOUBT for _, c in ac.words(r[1], r[2])) for r in cache['asr']]
        hyb = [w if dd else g for w, g, dd in zip(wj['texts'], greedy, doubt)]
        g = de.per_phrase(segs, greedy, plays, gap, lang)
        w = de.per_phrase(segs, wj['texts'], plays, gap, lang)
        h = de.per_phrase(segs, hyb, plays, gap, lang)
        allg += g; allw += w; allh += h; tw += wj['sec']; nh += sum(doubt); nall += len(doubt)
        if name in NOISY_PT:
            sub['g'] += g; sub['w'] += w; sub['h'] += h
        n = sum(r[2] for r in g)
        dw, lw, hw = de.boot(g, w, level=level)
        dh, lh, hh = de.boot(g, h, level=level)
        print(f'{name:20s} parakeet {100 * sum(r[1] for r in g) / n:5.1f} % · Whisper {100 * sum(r[1] for r in w) / n:5.1f} % '
              f'({dw:+.1f} [{lw:+.1f}; {hw:+.1f}]) · при сомнении {100 * sum(r[1] for r in h) / n:5.1f} % ({dh:+.1f} [{lh:+.1f}; {hh:+.1f}]), '
              f'Whisper на {sum(doubt)} из {len(doubt)} кусков', flush=True)
    n = sum(r[2] for r in allg)
    print(f'все вместе: parakeet {100 * sum(r[1] for r in allg) / n:.2f} %', flush=True)
    for title, v in (('Whisper', allw), ('при сомнении', allh)):
        d, lo, hi = de.boot(allg, v, level=level)
        print(f'   {title}: {100 * sum(r[1] for r in v) / n:.2f} % · {d:+.2f} п.п. [{lo:+.2f}; {hi:+.2f}] (интервал 97,5 %)', flush=True)
    if sub['g']:
        n = sum(r[2] for r in sub['g'])
        print(f'шумные pt-записи вместе: parakeet {100 * sum(r[1] for r in sub["g"]) / n:.2f} %', flush=True)
        for title, k in (('Whisper', 'w'), ('при сомнении', 'h')):
            d, lo, hi = de.boot(sub['g'], sub[k], level=level)
            print(f'   {title}: {100 * sum(r[1] for r in sub[k]) / n:.2f} % · {d:+.2f} п.п. [{lo:+.2f}; {hi:+.2f}]', flush=True)
    print(f'Whisper при сомнении — на {nh} из {nall} кусков; время Whisper на видеокарте ПК: {tw:.0f} с на все куски', flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['dump', 'whisper', 'score'])
    ap.add_argument('recs', nargs='+')
    ap.add_argument('--model', default=MODEL)
    ap.add_argument('--beams', type=int, default=5)
    a = ap.parse_args()
    {'dump': lambda: dump(a.recs), 'whisper': lambda: whisper(a.recs, a.model, a.beams), 'score': lambda: score(a.recs)}[a.cmd]()


if __name__ == '__main__':
    main()
