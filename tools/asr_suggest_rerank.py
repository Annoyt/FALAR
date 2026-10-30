#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Шаг 12 results/2026-10-27-asr-confidence.md: варианты распознавателя (8 ветвлений, tools/asr_suggest.py --branch 8)
по смыслу — log p фразы с подставленным кандидатом у языковой модели (Gemma 3 4B, видеокарта ПК) плюс log p токена
ветвления у распознавателя. Без подбора веса.

    PYTHONPATH=~/tools/llama-cuda .venv/bin/python tools/asr_suggest_rerank.py шаг12.json шаг11.json кэш.json --model gemma.gguf

шаг12.json, шаг11.json — выписки tools/asr_suggest.py --dump (с --branch 8 и без); кэш — log p фраз (с продолжением).
"""
import argparse
import json
import os
import random
import sys

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
from asr_suggest import boot_ci  # noqa: E402

PREFIX = {'pt': 'Frase em português: ', 'ru': 'Фраза на русском: '}


class Scorer:
    def __init__(self, model):
        from llama_cpp import Llama
        self.llm = Llama(model_path=model, n_ctx=256, n_gpu_layers=-1, n_threads=1, logits_all=True, verbose=False)

    def logp(self, prefix, text):
        """log p текста после вступления: сумма по токенам текста."""
        pre = self.llm.tokenize(prefix.encode('utf-8'), add_bos=True)
        full = self.llm.tokenize((prefix + text).encode('utf-8'), add_bos=True)
        self.llm.reset(); self.llm.eval(full)
        sc = np.array(self.llm.scores[: len(full)], dtype=np.float64)
        lp = 0.0
        for i in range(len(pre), len(full)):
            row = sc[i - 1]
            m = row.max(); lse = m + np.log(np.exp(row - m).sum())
            lp += row[full[i]] - lse
        return float(lp)


def paired_diff(a, b, n=2000, seed=0):
    """Разница долей b − a и её 95 % интервал по бутстрэпу фраз; a, b — [(фраза, успех)] в одном порядке."""
    by = {}
    for (ph, x), (_, y) in zip(a, b): by.setdefault(ph, []).append((x, y))
    keys = list(by); rnd = random.Random(seed); vals = []
    for _ in range(n):
        smp = [p for _ in keys for p in by[keys[rnd.randrange(len(keys))]]]
        vals.append((sum(y for _, y in smp) - sum(x for x, _ in smp)) / max(1, len(smp)))
    vals.sort()
    d = (sum(s for _, s in b) - sum(s for _, s in a)) / max(1, len(a))
    return d, vals[int(0.025 * n)], vals[int(0.975 * n)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('items8'); ap.add_argument('items4'); ap.add_argument('cache')
    ap.add_argument('--model', required=True)
    a = ap.parse_args()
    it8 = {f'{g["phrase"]}#{g["idx"]}': g for g in json.load(open(a.items8, encoding='utf-8')) if not g['ok'] and g['ref']}
    it4 = {f'{g["phrase"]}#{g["idx"]}': g for g in json.load(open(a.items4, encoding='utf-8')) if not g['ok'] and g['ref']}
    keys = [k for k in it4 if k in it8]
    print(f'слов шага 11: {len(it4)}, совпало с выпиской шага 12: {len(keys)}')
    cache = json.load(open(a.cache, encoding='utf-8')) if os.path.exists(a.cache) else {}
    sc = None
    for n, k in enumerate(keys):
        g = it8[k]; words = g['sent'].split()
        for w, _ in g['cands']:
            ck = f'{k}|{w}'
            if ck in cache: continue
            sc = sc or Scorer(a.model)
            cache[ck] = sc.logp(PREFIX[g['lang']], ' '.join(words[:g['idx']] + [w] + words[g['idx'] + 1:]))
        if n % 20 == 0:
            json.dump(cache, open(a.cache + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(a.cache + '.tmp', a.cache)
    json.dump(cache, open(a.cache + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(a.cache + '.tmp', a.cache)
    rows = {'ASR-4 (шаг 11)': [], 'ASR-8, порядок распознавателя': [], 'ASR-8, порядок модели': [],
            'ASR-8, модель + распознаватель': [], 'потолок: среди всех 8': []}
    langs = []
    for k in keys:
        g = it8[k]; ref = g['ref']; ph = g['phrase']
        cands = g['cands']
        by_asr = [w for w, _ in cands]
        by_lm = [w for w, _ in sorted(cands, key=lambda c: -cache[f'{k}|{c[0]}'])]
        by_mix = [w for w, _ in sorted(cands, key=lambda c: -(cache[f'{k}|{c[0]}'] + c[1]))]
        rows['ASR-4 (шаг 11)'].append((ph, ref in it4[k]['asr'][:3]))
        rows['ASR-8, порядок распознавателя'].append((ph, ref in by_asr[:3]))
        rows['ASR-8, порядок модели'].append((ph, ref in by_lm[:3]))
        rows['ASR-8, модель + распознаватель'].append((ph, ref in by_mix[:3]))
        rows['потолок: среди всех 8'].append((ph, ref in by_asr))
        langs.append(g['lang'])
    print('способ · hit@3 [95 %] · pt · ru')
    for name, rr in rows.items():
        h = sum(s for _, s in rr) / len(rr); lo, hi = boot_ci(rr, None)
        pt = [s for (_, s), l in zip(rr, langs) if l == 'pt']; ru = [s for (_, s), l in zip(rr, langs) if l == 'ru']
        print(f'   {name:34s} {100 * h:5.1f} % [{100 * lo:.1f}; {100 * hi:.1f}] · {100 * sum(pt) / max(1, len(pt)):5.1f} % · '
              f'{100 * sum(ru) / max(1, len(ru)):5.1f} %')
    d, lo, hi = paired_diff(rows['ASR-4 (шаг 11)'], rows['ASR-8, модель + распознаватель'])
    print(f'разница «модель + распознаватель» − «ASR-4»: {100 * d:+.1f} п.п. [{100 * lo:+.1f}; {100 * hi:+.1f}]')


if __name__ == '__main__':
    main()
