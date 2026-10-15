#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Второй микрофон, шаг 3: что даёт распознаванию каждый способ сложить два канала — на записях комнаты в два канала
(bench/air/record_air2.sh → bench/air/rec/st-<метка>-<язык>/room2.wav), на ПК, один поток.

Нарезка — близнец приложения (tools/vad_denoise_eval.segment, как сейчас по умолчанию: шумодав нарезки с −48 dBFS,
музыка, Винер в тишине, минимумы речи по тишине/шуму), распознавание — parakeet, как Engine.asr. Варианты:
  mean — (L + R) / 2: так телефон отдаёт приложению моно сейчас (results/2026-10-15-dual-mic.md);
  L, R — один микрофон: и нарезка, и распознавание по нему;
  best — нарезка по mean, распознаётся тот канал, где у куска лучше отношение к своему фону;
  das  — нарезка по mean, распознаётся сумма каналов, выровненная по задержке этого куска (GCC-PHAT, ±1 мс).
Счёт — как vad_denoise_eval.score: кусок относится к фразе, в чьё окно попал его конец; окна фраз — где файл фразы
нашёлся в записи взаимной корреляцией (tools/air2_check.find). Сравнение с mean — парный бутстрэп по фразам.

    taskset -c 0 .venv/bin/python tools/dualmic_eval.py st-laptop-near-pt [st-…] [--variants mean,L,R,best,das]
Итоги по вариантам кэшируются в results/dualmic/ (не в git) — прерванный прогон продолжается.
"""
import argparse
import json
import os
import sys

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
sys.path.insert(0, os.path.join(R, 'bench', 'asr2'))
import air2_check as a2  # noqa: E402
import asr_filter_eval as afe  # noqa: E402
import vad_denoise_eval as vde  # noqa: E402
from wer2 import norm, wer  # noqa: E402

SR = 16000
APP = dict(vad_min=0.064, vad_min_noise=0.25, minsp=150, minsp_noise=250, carry=True)


def load(name):
    d = os.path.join(R, 'bench', 'air', 'rec', name)
    meta = dict(kv.split('=', 1) for kv in open(os.path.join(d, 'rec.txt'), encoding='utf-8').read().split())
    x, rate = a2.read(os.path.join(d, 'room2.wav'))
    assert rate == SR and x.shape[1] == 2, (rate, x.shape)
    lang = meta['язык']; t0, off = int(meta['начало_телефон']), int(meta['сдвиг_часов'])
    mono = x.mean(axis=1)
    plays = []
    for line in open(os.path.join(d, 'player.tsv'), encoding='utf-8'):
        cid, a, b, dur = line.rstrip('\n').split('\t')
        clip, _ = a2.read(os.path.join(R, 'bench', 'air', 'corpus', lang, cid + '.wav'))
        k, score = a2.find(mono, clip[:, 0], (int(a) + off - t0) / 1000.0 * SR, SR)
        if k is not None and score >= 0.3:
            plays.append((cid + '.wav', k / SR, len(clip) / SR))
    return x.astype(np.float32), plays, int(meta['пауза']), lang


def background(ch):
    fr = SR // 10
    e = np.array([np.mean(ch[i:i + fr] ** 2) for i in range(0, len(ch) - fr, fr)])
    return float(np.median(np.sort(e)[: len(e) // 3]))


def segs_texts(x, variant, asr, cache):
    if variant in cache:
        return [tuple(s) for s in cache[variant]['segs']], cache[variant]['texts']
    L, Rc = x[:, 0], x[:, 1]
    mean = (L + Rc) / 2
    base = {'L': L, 'R': Rc}.get(variant, mean)
    segs, _ = vde.segment(base, 'dn_sil', 'gtcrn', 0.0, -48.0, True, vde.Wiener(), **APP)
    if variant in ('mean', 'L', 'R'):
        texts = [asr(base[a:b]) for a, b, _ in segs]
    elif variant == 'best':
        bl, br = background(L), background(Rc)
        texts = []
        for a, b, _ in segs:
            sl, sr = float(np.mean(L[a:b] ** 2)) / bl, float(np.mean(Rc[a:b] ** 2)) / br
            texts.append(asr((L if sl >= sr else Rc)[a:b]))
    elif variant == 'das':
        texts = []
        for a, b, _ in segs:
            d_ms, _ = a2.gcc_phat(L[a:b].astype(np.float64), Rc[a:b].astype(np.float64), SR)
            k = int(round(d_ms * SR / 1000))      # R позже L на k отсчётов — берём R на k позже
            rr = np.zeros(b - a, np.float32); s0, s1 = max(a + k, 0), min(b + k, len(Rc))
            rr[s0 - a - k:s1 - a - k] = Rc[s0:s1]
            texts.append(asr((L[a:b] + rr) / 2))
    else:
        raise SystemExit(f'нет варианта {variant}')
    cache[variant] = dict(segs=[list(map(int, s)) for s in segs], texts=texts)
    return segs, texts


def per_phrase(segs, texts, plays, gap, lang):
    """Как vad_denoise_eval.score, но с ошибками по каждой фразе — для парного сравнения."""
    wins = []
    for k, (name, at, dur) in enumerate(plays):
        end = at + dur + gap / 1000.0
        if k + 1 < len(plays):
            end = min(end, plays[k + 1][1])
        wins.append([name, at, end, []])
    for s, t in zip(segs, texts):
        hit = next((w for w in wins if w[1] <= s[2] / SR <= w[2]), None)
        if hit is not None:
            hit[3].append(t)
    rows = []
    for name, _, _, heard_l in wins:
        ref = open(os.path.join(R, 'bench', 'air', 'corpus', lang, name[:-4] + '.txt'), encoding='utf-8').read().strip()
        heard = ' '.join(t for t in heard_l if t)
        e, n = wer(ref, heard)
        rows.append((name, e, n, not heard))
    return rows


def boot(base, var, n=10000, seed=1):
    """Парный бутстрэп разницы WER (вариант − mean), п.п.: оценка и 95 % интервал."""
    rng = np.random.default_rng(seed)
    eb = np.array([r[1] for r in base], float); ev = np.array([r[1] for r in var], float); nw = np.array([r[2] for r in base], float)
    d = 100 * (ev.sum() - eb.sum()) / nw.sum()
    idx = rng.integers(0, len(nw), (n, len(nw)))
    ds = 100 * (ev[idx].sum(1) - eb[idx].sum(1)) / nw[idx].sum(1)
    return d, float(np.percentile(ds, 2.5)), float(np.percentile(ds, 97.5))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('recs', nargs='+')
    ap.add_argument('--variants', default='mean,L,R,best,das')
    ap.add_argument('--out', default=os.path.join(R, 'results', 'dualmic'))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    asr = afe.Asr(1)
    allrows = {}
    for name in a.recs:
        x, plays, gap, lang = load(name)
        cpath = os.path.join(a.out, name + '.json')
        cache = json.load(open(cpath, encoding='utf-8')) if os.path.exists(cpath) else {}
        print(f'{name}: фраз найдено {len(plays)}, {len(x) / SR:.0f} с', flush=True)
        for v in a.variants.split(','):
            segs, texts = segs_texts(x, v, asr, cache)
            json.dump(cache, open(cpath + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False)
            os.replace(cpath + '.tmp', cpath)
            rows = per_phrase(segs, texts, plays, gap, lang)
            allrows.setdefault(v, []).extend(rows)
            e = sum(r[1] for r in rows); n = sum(r[2] for r in rows); lost = sum(r[3] for r in rows)
            line = f'  {v:5s} WER {100 * e / n:5.1f} % · потеряно {lost:2d} из {len(rows)} · кусков {len(segs)}'
            if v != 'mean' and 'mean' in cache:
                base = per_phrase([tuple(s) for s in cache['mean']['segs']], cache['mean']['texts'], plays, gap, lang)
                d, lo, hi = boot(base, rows)
                line += f' · против mean {d:+.1f} п.п. [{lo:+.1f}; {hi:+.1f}]'
            print(line, flush=True)
    if len(a.recs) > 1 and 'mean' in allrows:
        print('все записи вместе:')
        for v, rows in allrows.items():
            e = sum(r[1] for r in rows); n = sum(r[2] for r in rows)
            line = f'  {v:5s} WER {100 * e / n:5.1f} %'
            if v != 'mean':
                d, lo, hi = boot(allrows['mean'], rows)
                line += f' · против mean {d:+.1f} п.п. [{lo:+.1f}; {hi:+.1f}]'
            print(line)


if __name__ == '__main__':
    main()
