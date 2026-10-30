#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Шаг 11 results/2026-10-27-asr-confidence.md: варианты исправления сомнительного слова — есть ли среди них то, что
сказали на самом деле.

Гипотеза — своё декодирование parakeet (tools/parakeet_ort.py: те же модели и признаки, что у sherpa-onnx; жадный
текст совпадает с ним в ~80 % кусков, расхождения — у почти равных вариантов). Слова и уверенность — как в
приложении (WordConf, tools/asr_confidence.words): слово сомнительно, если в нём есть токен с вероятностью ниже
0,7. Счёт — по неверным сомнительным словам, которые при выравнивании с эталоном заменяют одно его слово.

Источники:
- asr — ветвление в самом распознавателе: в самом неуверенном токене слова берутся 4 лучших других токена, декодер
  продолжает жадно до конца слова;
- dict — частые слова языка (data/common_words.txt, для pt ещё data/ocr_words_pt.txt.gz), близкие по звучанию
  (упрощённый фонетический ключ) и написанию; порядок — расстояние, потом частота;
- lm — ответы языковой модели (кэш JSON от tools/asr_suggest_lm.py, если есть).

    taskset -c 0 .venv/bin/python tools/asr_suggest.py near-pt … [--lm кэш.json] [--dump слова.json]
Кэш гипотез и вариантов распознавателя — results/asr_conf/<запись>.suggest.json.
"""
import argparse
import gzip
import json
import math
import os
import random
import re
import sys
import unicodedata

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
import asr_confidence as ac  # noqa: E402
from parakeet_ort import Parakeet  # noqa: E402

DOUBT = 0.7
SR = 16000


def word_spans(tokens, probs):
    """Слова гипотезы: (текст, уверенность, индексы токенов). Начало слова — токен с «▁»."""
    out, cur = [], None
    for k, (t, p) in enumerate(zip(tokens, probs)):
        if t.startswith('▁') or cur is None:
            if cur: out.append(cur)
            cur = [t.lstrip('▁'), p, [k]]
        else:
            cur[0] += t; cur[2].append(k)
            if any(ch.isalnum() for ch in t): cur[1] = min(cur[1], p)
    if cur: out.append(cur)
    return out


def norm1(w):
    n = ac.norm(w)
    return n[0] if len(n) == 1 else None


def align_pairs(hyp, ref):
    """Пары выравнивания по расстоянию правки: для слова гипотезы — индекс слова эталона при совпадении или замене."""
    n, m = len(hyp), len(ref)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1): d[i][0] = i
    for j in range(m + 1): d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (hyp[i - 1] != ref[j - 1]))
    pair = [None] * n; i, j = n, m
    while i > 0 and j > 0:
        if d[i][j] == d[i - 1][j - 1] + (hyp[i - 1] != ref[j - 1]):
            pair[i - 1] = j - 1; i -= 1; j -= 1
        elif d[i][j] == d[i - 1][j] + 1: i -= 1
        else: j -= 1
    return pair


# ---- словарь -------------------------------------------------------------------------------------------------
def strip_acc(s):
    return ''.join(c for c in unicodedata.normalize('NFD', s) if unicodedata.category(c) != 'Mn')


def key_pt(w):
    w = w.lower().replace('ç', 's')
    w = strip_acc(w)
    for a, b in (('ss', 's'), ('sc', 's'), ('xc', 's'), ('ch', 'x'), ('lh', 'li'), ('nh', 'ni'), ('qu', 'k'),
                 ('rr', 'r'), ('y', 'i'), ('w', 'u'), ('z', 's')):
        w = w.replace(a, b)
    w = re.sub(r'c(?=[ei])', 's', w); w = w.replace('c', 'k')
    w = re.sub(r'g(?=[ei])', 'j', w); w = w.replace('gu', 'g')
    w = re.sub(r'^h', '', w).replace('h', '')
    w = re.sub(r'o$', 'u', w); w = re.sub(r'e$', 'i', w); w = re.sub(r'os$', 'us', w); w = re.sub(r'es$', 'is', w)
    return w


def key_ru(w):
    w = w.lower().replace('ё', 'е').replace('ъ', '').replace('ь', '')
    w = re.sub(r'т?ся$', 'ца', w)
    w = w.replace('о', 'а').replace('я', 'и').replace('е', 'и').replace('э', 'и')
    for a, b in (('б', 'п'), ('в', 'ф'), ('г', 'к'), ('д', 'т'), ('ж', 'ш'), ('з', 'с')):
        w = re.sub(a + '$', b, w)
    return w


def lev(a, b, cap=3):
    if abs(len(a) - len(b)) > cap: return cap + 1
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b); best = cur[0]
        for j, cb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)); best = min(best, cur[j])
        if best > cap: return cap + 1
        prev = cur
    return prev[-1]


class Dictionary:
    def __init__(self, lang, exclude=()):
        self.lang = lang; self.key = key_pt if lang == 'pt' else key_ru
        freq = {}
        for line in open(os.path.join(R, 'data', 'common_words.txt'), encoding='utf-8'):
            p = line.split()
            if len(p) >= 3 and p[0] == lang:
                w = norm1(p[1])
                if w: freq[w] = freq.get(w, 0) + int(p[2])
        if lang == 'pt':
            with gzip.open(os.path.join(R, 'data', 'ocr_words_pt.txt.gz'), 'rt', encoding='utf-8') as f:
                for line in f:
                    p = line.split()
                    if len(p) >= 2 and p[1].isdigit():
                        w = norm1(p[0])
                        if w: freq[w] = max(freq.get(w, 0), int(p[1]))
        for w in exclude: freq.pop(w, None)
        self.words = [(w, c, self.key(w)) for w, c in freq.items() if c >= 2 and len(w) >= 1]

    def suggest(self, w, k=3):
        kw = self.key(w); cap = max(1, len(kw) // 3)
        cand = []
        for v, c, kv in self.words:
            if v == w or abs(len(kv) - len(kw)) > cap: continue
            dk = lev(kw, kv, cap)
            if dk > cap: continue
            cand.append((dk, lev(w, v, 4), -math.log(c), v))
        cand.sort()
        return [v for *_, v in cand[:k]]


# ---- распознаватель ------------------------------------------------------------------------------------------
def asr_alternatives(pk, enc, out, span, branch=4):
    """Ветвление в самом неуверенном токене слова: до branch других токенов и жадное продолжение до конца слова.
    Возвращает [(слово, log p токена ветвления)] в порядке распознавателя."""
    ks = span[2]
    probs = [math.exp(out[i][2]) for i in ks]
    alnum = [i for i in ks if any(ch.isalnum() for ch in pk.tokens[out[i][1]])] or ks
    worst = min(alnum, key=lambda i: math.exp(out[i][2]))
    lsm = out[worst][3]
    prefix = ''.join(pk.tokens[out[i][1]] for i in ks if i < worst)
    order = np.argsort(-lsm)
    alts, seen = [], set()
    for y in order[: branch * 3 + 2]:
        y = int(y)
        if y == out[worst][1] or y == pk.blank: continue
        tok = pk.tokens[y]
        if worst != ks[0] and tok.startswith('▁'): continue          # начал бы новое слово
        cont = pk.greedy(enc, start=out[worst][4], force=y, stop_at_word=True)
        word = (prefix + ''.join(pk.tokens[c[1]] for c in cont)).replace('▁', '')
        nw = norm1(word)
        if nw and nw not in seen and nw != norm1(''.join(pk.tokens[out[i][1]] for i in ks).replace('▁', '')):
            alts.append((nw, float(lsm[y]))); seen.add(nw)
        if len(alts) >= branch: break
    return alts


def boot_ci(rows, f, n=2000, seed=0):
    """95 % интервал доли по бутстрэпу фраз: rows — список (фраза, успех)."""
    by = {}
    for ph, s in rows: by.setdefault(ph, []).append(s)
    keys = list(by); rnd = random.Random(seed); vals = []
    for _ in range(n):
        smp = [by[keys[rnd.randrange(len(keys))]] for _ in keys]
        tot = sum(len(x) for x in smp)
        vals.append(sum(sum(x) for x in smp) / max(1, tot))
    vals.sort()
    return vals[int(0.025 * n)], vals[int(0.975 * n)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('recs', nargs='+')
    ap.add_argument('--out', default=os.path.join(R, 'results', 'asr_conf'))
    ap.add_argument('--lm', help='кэш ответов языковой модели (tools/asr_suggest_lm.py)')
    ap.add_argument('--dump', help='куда выписать сомнительные слова с контекстом (для языковой модели)')
    ap.add_argument('--branch', type=int, default=4, help='сколько других токенов пробовать (шаг 12: 8)')
    a = ap.parse_args()
    pk = None
    items = []
    for name in a.recs:
        lang = name.split('-')[-1]
        cpath = os.path.join(a.out, name + ('.suggest.json' if a.branch == 4 else f'.suggest{a.branch}.json'))
        if os.path.exists(cpath):
            got = json.load(open(cpath, encoding='utf-8'))
        else:
            pk = pk or Parakeet(os.path.join(R, 'models', 'asr_multi'), threads=1)
            x, piece, plays, gap, _ = ac.load(name)
            cache = json.load(open(os.path.join(a.out, name + '.json'), encoding='utf-8'))
            wins = []
            for k, (pn, at, dur) in enumerate(plays):
                end = at + dur + gap / 1000.0
                if k + 1 < len(plays): end = min(end, plays[k + 1][1])
                wins.append([pn, at, end, []])
            for s in cache['segs']:
                hit = next((w for w in wins if w[1] <= s[2] / SR <= w[2]), None)
                if hit is not None: hit[3].append(s)
            got = []
            for pn, _, _, ss in wins:
                if len(ss) != 1: continue
                ref = ac.norm(open(ac.vde.ref_file(lang, pn), encoding='utf-8').read().strip())
                enc = pk.encode(piece(ss[0][0], ss[0][1]))
                out = pk.greedy(enc)
                toks = [pk.tokens[o[1]] for o in out]; probs = [math.exp(o[2]) for o in out]
                spans = word_spans(toks, probs)
                hyp = [norm1(w) for w, _, _ in spans]
                if not ref or not hyp or any(h is None for h in hyp): continue
                pair = align_pairs(hyp, ref)
                for i, (w, c, ks) in enumerate(spans):
                    if c >= DOUBT: continue
                    j = pair[i]
                    rec = dict(phrase=f'{name}/{pn}', lang=lang, hyp=hyp[i], conf=c, ref=ref[j] if j is not None else None,
                               sent=' '.join(hyp), idx=i, ok=j is not None and hyp[i] == ref[j],
                               cands=asr_alternatives(pk, enc, out, [w, c, ks], branch=a.branch))
                    rec['asr'] = [v for v, _ in rec['cands']][:3]
                    got.append(rec)
            json.dump(got, open(cpath + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(cpath + '.tmp', cpath)
        print(f'{name}: сомнительных слов {len(got)}, неверных заменой {sum(1 for g in got if not g["ok"] and g["ref"])}', flush=True)
        items += got
    if a.dump:
        json.dump(items, open(a.dump, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
    lm = json.load(open(a.lm, encoding='utf-8')) if a.lm and os.path.exists(a.lm) else {}
    dicts = {}
    wrong = [g for g in items if not g['ok']]
    subs = [g for g in wrong if g['ref']]
    print(f'\nсомнительных слов {len(items)} · неверных {len(wrong)} · из них заменой одного слова {len(subs)} '
          f'({100 * len(subs) / max(1, len(wrong)):.0f} %)')
    for lang in sorted({g['lang'] for g in subs}):
        dicts[lang] = Dictionary(lang)
    res = {'asr': [], 'dict': [], 'asr+dict': [], 'lm': []}
    for g in subs:
        a3 = g['asr'][:3]
        d3 = dicts[g['lang']].suggest(g['hyp'], 3)
        mix = (a3[:2] + [w for w in d3 if w not in a3[:2]])[:3]
        key = f'{g["phrase"]}#{g["idx"]}'
        for src, cand in (('asr', a3), ('dict', d3), ('asr+dict', mix), ('lm', lm.get(key))):
            if cand is None: continue
            res[src].append((g['phrase'], g['ref'] in cand[:3], g['ref'] in cand[:1], g['lang']))
    print('источник · hit@3 [95 %] · hit@1 · слов')
    for src, rows in res.items():
        if not rows: continue
        for lang in ('pt', 'ru', None):
            rr = [r for r in rows if lang is None or r[3] == lang]
            if not rr: continue
            h3 = sum(r[1] for r in rr) / len(rr); h1 = sum(r[2] for r in rr) / len(rr)
            lo, hi = boot_ci([(r[0], r[1]) for r in rr], None)
            print(f'   {src:9s} {lang or "все":3s} {100 * h3:5.1f} % [{100 * lo:.1f}; {100 * hi:.1f}] · {100 * h1:5.1f} % · {len(rr)}')


if __name__ == '__main__':
    main()
