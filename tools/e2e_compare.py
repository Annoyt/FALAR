#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сквозной тест «речь → перевод» на наборах tools/e2e_set.py: подготовка прогонов и сравнение.

Прогоны — выход MtRun (код приложения) или mt_bench.py на наборах results/asr_conf/e2e/<запись>[.clean].json,
по набору на запись (ПУТЬ=ЗАПИСЬ). Перед COMET исходником ставится сказанное на самом деле (поле true набора),
а то, что ушло в перевод, — в поле inp: COMET оценивает перевод сказанного, а не распознанного (--fix).
Сравнение B − A — по всем записям вместе (парный бутстрэп по фразам) и по каждой записи; пары — по записи и
месту фразы в наборе, сказанное у пары обязано совпасть.

    .venv/bin/python tools/e2e_compare.py --fix ПРОГОН.json …
    .venv/bin/python tools/e2e_compare.py A.json B.json [--pooled]
    .venv/bin/python tools/e2e_compare.py --gap A.clean.json A.json B.clean.json B.json
        во сколько B обходится слух против A: Δ = (B − B.clean) − (A − A.clean) по COMET каждой фразы
"""
import argparse
import json
import os
import sys
from pathlib import Path

import numpy as np

R = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(R / 'tools'))
import mt_metrics as mm  # noqa: E402

E2E = R / 'results' / 'asr_conf' / 'e2e'


def fix(path):
    """src ← сказанное (true), inp ← то, что переводилось. Какой это набор записи — распознанное или сказанное —
    решают все фразы записи по порядку: у хорошей записи распознанное первой фразы бывает слово в слово как сказано."""
    d = json.loads(Path(path).read_text())
    s = d['summary']
    if s.get('e2e_src') == 'true':
        return 'уже'
    src = s['direction'][:2]
    key = 'pt_to_ru' if src == 'pt' else 'ru_to_pt'
    groups = {}
    for r in d['results']:
        groups.setdefault(r['set'], []).append(r)
    for name, rs in groups.items():
        variants = [json.loads((E2E / f'{name}{x}.json').read_text())[key] for x in ('', '.clean')]
        items = next((v for v in variants if len(v) == len(rs) and all(r['src'] == it[src] for r, it in zip(rs, v))), None)
        assert items is not None, f'{path}: фразы записи {name} не совпали по порядку ни с распознанным, ни со сказанным'
        for r, it in zip(rs, items):
            r['inp'], r['src'] = r['src'], it['true']
    s['e2e_src'] = 'true'
    Path(path).write_text(json.dumps(d, ensure_ascii=False, indent=1))
    return 'исправлено'


def pairs(a_path, b_path):
    A, B = (json.loads(Path(p).read_text())['results'] for p in (a_path, b_path))
    def keyed(rs):
        pos, out = {}, {}
        for r in rs:
            k = pos.get(r['set'], 0); pos[r['set']] = k + 1
            out[(r['set'], k)] = r
        return out
    ka, kb = keyed(A), keyed(B)
    out = []
    for k, a in ka.items():
        b = kb.get(k)
        if b is None:
            continue
        assert a['src'] == b['src'], f'{k}: сказанное у пары разное — прогоны на разных наборах или без --fix'
        out.append((k[0], a, b))
    return out


def delta(ps):
    """COMET и chrF: A, B, Δ = B − A, 95 % интервал, p (двусторонний) — как mt_metrics.compare."""
    idx = mm._rng().integers(0, len(ps), (mm.N_BOOT, len(ps)))
    refs = [a['ref'] for _, a, _ in ps]
    res = {}
    if all('comet' in a and 'comet' in b for _, a, b in ps):
        ca = np.array([a['comet'] for _, a, _ in ps]); cb = np.array([b['comet'] for _, _, b in ps])
        res['COMET'] = (ca.mean(), cb.mean(), (cb - ca)[idx].mean(1))
    m, sa = mm._chrf_stats([a['hyp'] for _, a, _ in ps], refs, 0)
    _, sb = mm._chrf_stats([b['hyp'] for _, _, b in ps], refs, 0)
    va, vb = (m._compute_score_from_stats(x.sum(0)).score for x in (sa, sb))
    res['chrF'] = (va, vb, np.array([m._compute_score_from_stats(sb[i].sum(0)).score
                                     - m._compute_score_from_stats(sa[i].sum(0)).score for i in idx]))
    out = {}
    for metric, (va, vb, ds) in res.items():
        s = np.sort(ds)
        lo, hi = s[mm.N_BOOT // 40], s[mm.N_BOOT - mm.N_BOOT // 40 - 1]
        p = min(1.0, 2 * min((ds <= 0).mean(), (ds >= 0).mean()))
        out[metric] = (float(va), float(vb), float(vb - va), float(lo), float(hi), float(p))
    return out


def gap(ac, aa, bc, ba):
    """Цена слуха у B против цены у A, по фразам: (B − B.clean) − (A − A.clean), COMET; 95 % интервал и p."""
    pa, pb = pairs(ac, aa), pairs(bc, ba)
    assert len(pa) == len(pb) and all(x[1]['src'] == y[1]['src'] and x[0] == y[0] for x, y in zip(pa, pb)), \
        'прогоны A и B — на разных наборах'
    da = np.array([a2['comet'] - a1['comet'] for _, a1, a2 in pa])
    db = np.array([b2['comet'] - b1['comet'] for _, b1, b2 in pb])
    idx = mm._rng().integers(0, len(da), (mm.N_BOOT, len(da)))
    ds = (db - da)[idx].mean(1); srt = np.sort(ds)
    lo, hi = srt[mm.N_BOOT // 40], srt[mm.N_BOOT - mm.N_BOOT // 40 - 1]
    p = min(1.0, 2 * min((ds <= 0).mean(), (ds >= 0).mean()))
    print(f'цена слуха: A {da.mean():+.4f}, B {db.mean():+.4f}; B − A {db.mean() - da.mean():+.4f} [{lo:+.4f}; {hi:+.4f}] '
          f'p={p:.3f} (n={len(da)}; плюс — B теряет меньше)')


def show(title, n, d):
    parts = []
    for metric, f in (('COMET', '.4f'), ('chrF', '.1f')):
        if metric in d:
            va, vb, dl, lo, hi, p = d[metric]
            parts.append(f'{metric} {va:{f}} → {vb:{f}} Δ {dl:+{f}} [{lo:+{f}}; {hi:+{f}}] p={p:.3f}')
    print(f'{title:22s} n={n:4d}: ' + ' · '.join(parts), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('files', nargs='+')
    ap.add_argument('--fix', action='store_true', help='исходник для COMET — сказанное (true), распознанное — в inp')
    ap.add_argument('--pooled', action='store_true', help='только итог по всем записям вместе')
    ap.add_argument('--gap', action='store_true', help='четыре прогона: A.clean A B.clean B — цена слуха B против A')
    a = ap.parse_args()
    if a.fix:
        for f in a.files:
            print(f'{Path(f).name}: {fix(f)}')
        return
    if a.gap:
        if len(a.files) != 4:
            sys.exit('--gap: четыре прогона — A.clean A B.clean B')
        return gap(*a.files)
    if len(a.files) != 2:
        sys.exit('сравнение: ровно два прогона, A и B')
    ps = pairs(*a.files)
    print(f'B − A: {Path(a.files[1]).name} против {Path(a.files[0]).name}')
    show('все записи вместе', len(ps), delta(ps))
    for name in ([] if a.pooled else dict.fromkeys(s for s, _, _ in ps)):
        sub = [p for p in ps if p[0] == name]
        if len(sub) >= 2:
            show(name, len(sub), delta(sub))


if __name__ == '__main__':
    main()
