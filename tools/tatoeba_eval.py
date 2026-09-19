#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверка добытого из Tatoeba словаря перед заливкой на телефон.

1. Размер таблицы, распределение длин ключей, оценка памяти.
2. Конфликты с вычитанной вручную затравкой (phrasebook_seed.json).
3. Риск ложных нечётких совпадений: есть ли в таблице соседи в пределах
   правила приложения (ключ >= 16 символов, <= 8% правок) с РАЗНЫМ переводом.
4. Прогон тестового набора через ту же логику поиска, что и в приложении.
"""
import argparse, collections, json, os, random, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tatoeba_extract import norm  # noqa: E402
from tatoeba_phrasebook import chrf  # noqa: E402
from rapidfuzz import process  # noqa: E402
from rapidfuzz.distance import Levenshtein  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MINED_MIN_KEY, MINED_FUZZY, SEED_FUZZY = 16, 0.08, 0.12


def load_mined(path):
    t = {'pt2ru': {}, 'ru2pt': {}}
    tier = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) >= 4:
                t[p[0]][p[1]] = (p[2], p[3])
                tier[(p[0], p[1])] = p[4] if len(p) > 4 else 'direct'
    return t, tier


def load_seed(path):
    j = json.load(open(path, encoding='utf-8'))
    return {d: {k: (v.get('src', k), v['dst']) for k, v in m.items()} for d, m in j.items()}


def lookup(direction, text, seed, mined):
    """Повторяет Phrasebook.lookup: точное в затравке -> точное в корпусе -> нечёткое."""
    key = norm(text)
    if not key:
        return None
    s, m = seed.get(direction, {}), mined.get(direction, {})
    if key in s:
        return ('seed', s[key])
    if key in m:
        return ('mined', m[key])
    if len(key) < 8:
        return None
    best, bd = None, SEED_FUZZY
    for k, v in s.items():
        if abs(len(k) - len(key)) > 3:
            continue
        d = Levenshtein.distance(key, k) / max(len(key), len(k))
        if d < bd:
            bd, best = d, ('seed~', v)
    return best   # нечёткого поиска по корпусу в приложении нет: см. раздел «риск» ниже


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--mined', default=os.path.join(ROOT, 'data', 'tatoeba', 'phrasebook_tatoeba.tsv'))
    ap.add_argument('--seed', default=os.path.join(ROOT, 'bench', 'apk', 'phrasebook_seed.json'))
    ap.add_argument('--test', default=os.path.join(ROOT, 'data', 'test_set.json'))
    ap.add_argument('--sample', type=int, default=4000)
    a = ap.parse_args()
    rep = {}
    mined, tier = load_mined(a.mined)
    seed = load_seed(a.seed)

    # --- 1. размер
    rep['mined'] = {d: len(m) for d, m in mined.items()}
    rep['tiers'] = dict(collections.Counter(tier.values()))
    lens = [len(k) for m in mined.values() for k in m]
    lens.sort()
    rep['key_len'] = {'p50': lens[len(lens) // 2], 'p90': lens[int(len(lens) * .9)], 'max': lens[-1],
                      'ge16': sum(1 for x in lens if x >= MINED_MIN_KEY)}
    rep['chars_total'] = sum(len(k) + len(v[0]) + len(v[1]) for m in mined.values() for k, v in m.items())

    # --- 2. конфликты с затравкой
    conf, same = [], 0
    for d, sm in seed.items():
        for k, (ssrc, sdst) in sm.items():
            if k in mined.get(d, {}):
                mdst = mined[d][k][1]
                sc = chrf(mdst, sdst)
                if sc < 50:
                    conf.append((d, k, sdst, mdst, round(sc, 1)))
                else:
                    same += 1
    rep['seed_keys_also_mined'] = same + len(conf)
    rep['seed_conflicts'] = len(conf)
    rep['seed_conflict_examples'] = conf[:15]

    # --- 3. риск ложных нечётких совпадений
    random.seed(7)
    risk = {}
    for d, m in mined.items():
        keys = [k for k in m if len(k) >= MINED_MIN_KEY]
        if not keys:
            continue
        qs = random.sample(keys, min(a.sample, len(keys)))
        near, bad, ex = 0, 0, []
        for q in qs:
            for k, dist, _ in process.extract(q, keys, scorer=Levenshtein.normalized_distance,
                                              score_cutoff=MINED_FUZZY - 1e-9, limit=3):
                if k == q:
                    continue
                near += 1
                sc = chrf(m[k][1], m[q][1])
                if sc < 50:
                    bad += 1
                    if len(ex) < 10:
                        ex.append((q, m[q][1], k, m[k][1], round(dist, 3), round(sc, 1)))
                break
        risk[d] = {'sampled': len(qs), 'has_near_neighbour': near, 'diverging_translation': bad,
                   'examples': ex}
    rep['fuzzy_risk'] = risk

    # --- 4. тестовый набор
    ts = json.load(open(a.test, encoding='utf-8'))
    res = {}
    for split, direction, sf, tf in (('pt_to_ru', 'pt2ru', 'pt', 'ru'), ('ru_to_pt', 'ru2pt', 'ru', 'pt')):
        items = ts.get(split, [])
        kinds = collections.Counter()
        scores = []
        miss = []
        for it in items:
            h = lookup(direction, it[sf], seed, mined)
            if h is None:
                kinds['miss'] += 1
                miss.append(it[sf])
                continue
            kinds[h[0]] += 1
            scores.append(chrf(h[1][1], it[tf]))
        res[split] = {'n': len(items), 'hits': dict(kinds),
                      'chrf_on_hit': round(sum(scores) / len(scores), 1) if scores else None,
                      'missed': miss[:6]}
    rep['test_set'] = res

    print(json.dumps(rep, ensure_ascii=False, indent=1))
    with open(os.path.join(ROOT, 'data', 'tatoeba', 'eval.json'), 'w', encoding='utf-8') as f:
        json.dump(rep, f, ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
