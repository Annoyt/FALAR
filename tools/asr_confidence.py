#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Уверенность распознавателя против его настоящих ошибок — на записях комнаты, где известно, что сказано
(results/2026-10-27-asr-confidence.md, шаг 1).

Нарезка — близнец приложения (tools/vad_denoise_eval.segment, как по умолчанию с 0.32.0), распознавание — parakeet,
один поток, с вероятностями токенов (ys_log_probs). Двухканальные записи (st-*) распознаются по сумме, выровненной по
задержке куска, — как в приложении с 0.34.0 (tools/dualmic_eval.app_delay). Уверенность слова — наименьшая вероятность
его токенов (токен с пробелом в начале открывает слово, знаки препинания не считаются). Слово верное, если при
выравнивании с эталоном фразы оно совпало со словом эталона. Берутся фразы, в окно которых попал ровно один кусок.

Печатает:
- для слов: при пороге уверенности — точность пометки (доля неверных среди помеченных), полноту (доля пойманных
  неверных) и долю помеченных верных;
- для фраз: при пороге доли сомнительных токенов (вероятность < 0,5) — долю плохих (WER ≥ 50 %) среди помеченных,
  долю помеченных хороших (WER ≤ 10 %) и долю пойманных плохих.

    taskset -c 0 .venv/bin/python tools/asr_confidence.py near-pt far-pt noisy-pt near-ru st-laptop-end-ru …
Куски и токены кэшируются в results/asr_conf/ (не в git).
"""
import argparse
import json
import math
import os
import sys

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
sys.path.insert(0, os.path.join(R, 'bench', 'asr2'))
import dualmic_eval as de  # noqa: E402
import vad_denoise_eval as vde  # noqa: E402
from wer2 import norm, wer  # noqa: E402

SR = 16000
APP = dict(vad_min=0.064, vad_min_noise=0.25, minsp=150, minsp_noise=250, carry=True)
DOUBT = 0.5


def recognizer():
    m = os.path.join(R, 'models', 'asr_multi')
    return vde.so.OfflineRecognizer.from_transducer(
        encoder=f'{m}/encoder.int8.onnx', decoder=f'{m}/decoder.int8.onnx', joiner=f'{m}/joiner.int8.onnx',
        tokens=f'{m}/tokens.txt', num_threads=1, model_type='nemo_transducer', decoding_method='greedy_search')


def recognize(rec, x):
    s = rec.create_stream()
    s.accept_waveform(SR, np.ascontiguousarray(x, np.float32))
    rec.decode_stream(s)
    r = s.result
    return r.text.strip(), list(r.tokens), [float(v) for v in r.ys_log_probs]


def load(name):
    """(звук для нарезки, звук для распознавания по куску (функция), окна фраз, пауза, язык)."""
    lang = name.split('-')[-1]
    if name.startswith('st-'):
        x, plays, gap, lang = de.load(name)
        L, Rc = x[:, 0], x[:, 1]
        mean = (L + Rc) / 2

        def piece(a, b):
            k = de.app_delay(L[a:b].astype(np.float64), Rc[a:b].astype(np.float64))
            rr = np.zeros(b - a, np.float32); s0, s1 = max(a + k, 0), min(b + k, len(Rc))
            rr[s0 - a - k:s1 - a - k] = Rc[s0:s1]
            return (L[a:b] + rr) / 2
        return mean.astype(np.float32), piece, plays, gap, lang
    x, plays, gap = vde.load_rec(name)
    plays = [p for p in plays if os.path.exists(os.path.join(R, 'bench', 'air', 'corpus', lang, p[0][:-4] + '.txt'))]
    return x, (lambda a, b: x[a:b]), plays, gap, lang


def words(tokens, lps):
    """Слова гипотезы: (нормализованный текст, наименьшая вероятность токенов)."""
    out, cur, conf = [], '', 1.0
    for t, lp in zip(tokens, lps):
        if t.startswith(' ') or t.startswith('▁'):
            if cur: out.append((cur, conf))
            cur, conf = t.lstrip(' ▁'), math.exp(lp)
        else:
            cur += t
            if any(ch.isalnum() for ch in t): conf = min(conf, math.exp(lp))
    if cur: out.append((cur, conf))
    res = []
    for w, c in out:
        res.extend((p, c) for p in norm(w))
    return res


def align(hyp, ref):
    """Для каждого слова гипотезы — совпало ли оно со словом эталона при выравнивании по расстоянию правки."""
    n, m = len(hyp), len(ref)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1): d[i][0] = i
    for j in range(m + 1): d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (hyp[i - 1] != ref[j - 1]))
    ok = [False] * n; i, j = n, m
    while i > 0 and j > 0:
        if d[i][j] == d[i - 1][j - 1] + (hyp[i - 1] != ref[j - 1]):
            ok[i - 1] = hyp[i - 1] == ref[j - 1]; i -= 1; j -= 1
        elif d[i][j] == d[i - 1][j] + 1: i -= 1
        else: j -= 1
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('recs', nargs='+')
    ap.add_argument('--out', default=os.path.join(R, 'results', 'asr_conf'))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    rec = None
    W, PH = [], []
    for name in a.recs:
        cpath = os.path.join(a.out, name + '.json')
        x, piece, plays, gap, lang = load(name)
        if os.path.exists(cpath):
            cache = json.load(open(cpath, encoding='utf-8'))
        else:
            rec = rec or recognizer()
            segs, _ = vde.segment(x, 'dn_sil', 'gtcrn', 0.0, -48.0, True, vde.Wiener(), **APP)
            cache = dict(segs=[list(map(int, s)) for s in segs], asr=[recognize(rec, piece(s[0], s[1])) for s in segs])
            json.dump(cache, open(cpath + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(cpath + '.tmp', cpath)
        wins = []
        for k, (pn, at, dur) in enumerate(plays):
            end = at + dur + gap / 1000.0
            if k + 1 < len(plays): end = min(end, plays[k + 1][1])
            wins.append([pn, at, end, []])
        for s, r in zip(cache['segs'], cache['asr']):
            hit = next((w for w in wins if w[1] <= s[2] / SR <= w[2]), None)
            if hit is not None: hit[3].append(r)
        nw = nph = 0
        for pn, _, _, rs in wins:
            if len(rs) != 1 or not rs[0][0]: continue
            text, toks, lps = rs[0]
            ref = norm(open(os.path.join(R, 'bench', 'air', 'corpus', lang, pn[:-4] + '.txt'), encoding='utf-8').read().strip())
            hw = words(toks, lps)
            if not hw or not ref: continue
            ok = align([w for w, _ in hw], ref)
            W.extend((c, o) for (_, c), o in zip(hw, ok)); nw += len(hw)
            alnum = [math.exp(lp) for t, lp in zip(toks, lps) if any(ch.isalnum() for ch in t)]
            share = sum(1 for p in alnum if p < DOUBT) / max(1, len(alnum))
            e, n = wer(' '.join(ref), text)
            PH.append((share, e / max(1, n), name)); nph += 1
        print(f'{name}: фраз с одним куском {nph}, слов {nw}', flush=True)
    wrong = sum(1 for _, o in W if not o); right = len(W) - wrong
    print(f'\nслов {len(W)}, из них неверных {wrong} ({100 * wrong / max(1, len(W)):.1f} %)')
    pos = sorted(c for c, o in W if not o); neg = sorted(c for c, o in W if o)
    auc = np.mean([[1.0 if p < q else 0.5 if p == q else 0.0 for q in neg[::max(1, len(neg) // 2000)]] for p in pos[::max(1, len(pos) // 2000)]]) if pos and neg else float('nan')
    print(f'различение неверных слов по уверенности (AUC): {auc:.3f}')
    print('порог уверенности слова · точность пометки · полнота · помечено верных')
    for t in (0.3, 0.5, 0.7, 0.8, 0.9, 0.95):
        fl = [(c, o) for c, o in W if c < t]
        tp = sum(1 for _, o in fl if not o)
        print(f'   < {t:.2f}: {100 * tp / max(1, len(fl)):5.1f} % · {100 * tp / max(1, wrong):5.1f} % · {100 * (len(fl) - tp) / max(1, right):5.1f} %')
    bad = [p for p in PH if p[1] >= 0.5]; good = [p for p in PH if p[1] <= 0.1]
    print(f'\nфраз {len(PH)}: плохих (WER ≥ 50 %) {len(bad)}, хороших (WER ≤ 10 %) {len(good)}')
    print('порог доли сомнительных токенов · плохих среди помеченных · помечено хороших · поймано плохих · помечено фраз')
    for T in (0.1, 0.15, 0.2, 0.3, 0.4, 0.5):
        fl = [p for p in PH if p[0] >= T]
        print(f'   ≥ {T:.2f}: {100 * sum(1 for p in fl if p[1] >= 0.5) / max(1, len(fl)):5.1f} % · '
              f'{100 * sum(1 for p in good if p[0] >= T) / max(1, len(good)):5.1f} % · '
              f'{100 * sum(1 for p in bad if p[0] >= T) / max(1, len(bad)):5.1f} % · {len(fl)}')


if __name__ == '__main__':
    main()
