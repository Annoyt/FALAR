#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверочный набор перевода из Tatoeba: пары, которых модели не видели при обучении.

Зачем. Прежний набор (data/test_set.json) — 40 фраз на направление, и разница меньше 1–2 пунктов
chrF на нём — шум. Разговорник телефона для проверки не годится: 78 % его пар отобраны по совпадению
с переводом OPUS (results/2026-09-11-tatoeba-phrasebook.md), а прямые связи Tatoeba до осени 2021
года — обучающие данные самой OPUS-MT tc-big (Tatoeba Challenge v2021-08-07). Фразы из разговоров
владельца — без правильного перевода (там только перевод самого приложения), а chrF++ и COMET
меряют по эталону; к тому же они личные, а репозиторий открытый.

Что берётся:
  1. только прямые связи por↔rus — человек сам сказал «это перевод того»;
  2. только связи, появившиеся после отсечки (по умолчанию 2022-01-01 — позже обучающих данных
     OPUS-MT и NLLB). Дата связи — дата добавления более позднего из двух предложений. Дата есть у
     каждой пары, поэтому для моделей, обученных позже (Gemma, облако), можно взять пары посвежее;
  3. направление — по тому, какое предложение написано раньше: позднее — перевод раннего, раннее —
     исходник. Так эталон — настоящий перевод исходника, а не исходник — перевод эталона;
  4. оба предложения — от носителей: автор пишет на этом языке больше, чем на другом языке пары
     (так отсеялись две тысячи русских «эталонов» от автора-бразильца);
  5. португальский — бразильский: метка диалекта как в tatoeba_extract (теги → маркеры → автор);
  6. без цифр (приложение маскирует их до перевода), без мусорных символов, до 30 слов, перекос
     длин сторон не больше 2,2×; один исходник — один эталон;
  7. от одного автора — не больше половины исходников и не больше половины эталонов набора.

Выход: data/mt_test/tatoeba.json — те же ключи pt_to_ru/ru_to_pt, что у data/test_set.json, порядок
случайный (mt_bench --limit K берёт случайные K). У каждой пары: id предложений, авторы, дата,
есть ли исходник в разговорнике телефона (in_pb: в приложении такую фразу переведёт разговорник,
а не модель). Данные: Tatoeba, CC-BY 2.0 FR — авторы и id указаны у каждой пары.

    .venv/bin/python tools/mt_testset.py             # 1000 на направление, отсечка 2022-01-01
"""
import argparse, collections, json, os, random, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tatoeba_extract import (BAD_CHARS, DIGIT, WORD_RE, clean, conversational,  # noqa: E402
                             dialect_score, norm, opentext, read_tags)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, 'data', 'tatoeba', 'raw')
OUT = os.path.join(ROOT, 'data', 'mt_test', 'tatoeba.json')
PB = os.path.join(ROOT, 'data', 'tatoeba', 'phrasebook_tatoeba.tsv')
PB_SEED = os.path.join(ROOT, 'bench', 'apk', 'phrasebook_seed.json')
CYR = re.compile(r'[А-Яа-яЁё]')
LAT = re.compile(r'[A-Za-zÀ-ÿ]')
MAX_WORDS, MAX_RATIO = 30, 2.2


def sentences(path):
    """id -> (текст, автор, дата добавления или '')."""
    out = {}
    with opentext(path) as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < 6:
                continue
            added = p[4] if p[4][:1].isdigit() and not p[4].startswith('0000') else ''
            out[p[0]] = (p[2], p[3], added)
    return out


def user_labels(por, raw):
    """Автор -> BR/PT/?: те же правила, что в tatoeba_extract (теги +6, маркеры, перевес втрое)."""
    br_tag, pt_tag = read_tags(os.path.join(raw, 'tags.csv'), set(por))
    br, pt, n = collections.Counter(), collections.Counter(), collections.Counter()
    for sid, (text, user, _) in por.items():
        b, p = dialect_score(text)
        b += 6 if sid in br_tag else 0
        p += 6 if sid in pt_tag else 0
        br[user] += b
        pt[user] += p
        n[user] += 1
    lab = {}
    for u in n:
        b, p = br[u], pt[u]
        lab[u] = '?' if b + p < 5 else 'BR' if b >= 3 * p else 'PT' if p >= 3 * b else '?'
    return br_tag, pt_tag, lab


def label(sid, text, user, br_tag, pt_tag, ulab):
    if sid in br_tag:
        return 'BR'
    if sid in pt_tag:
        return 'PT'
    b, p = dialect_score(text)
    return 'BR' if b > p else 'PT' if p > b else ulab.get(user, '?')


def ok_text(t, script):
    if len(t) < 2 or BAD_CHARS.search(t) or DIGIT.search(t) or not script.search(t):
        return False
    if t.count('"') % 2 or t.count('«') != t.count('»'):
        return False
    w = WORD_RE.findall(t)
    return 0 < len(w) <= MAX_WORDS


def phrasebook_keys():
    keys = {'pt2ru': set(), 'ru2pt': set()}
    if os.path.exists(PB):
        with open(PB, encoding='utf-8') as f:
            for line in f:
                p = line.split('\t', 2)
                if len(p) >= 2 and p[0] in keys:
                    keys[p[0]].add(p[1])
    if os.path.exists(PB_SEED):
        for d, m in json.load(open(PB_SEED, encoding='utf-8')).items():
            keys.setdefault(d, set()).update(m)
    return keys


def pick(cands, n, cap, rng):
    """Случайные n пар: исходник не повторяется, от одного автора — не больше cap·n с каждой стороны."""
    rng.shuffle(cands)
    lim = max(1, int(n * cap))
    by_src, by_ref, seen, out = collections.Counter(), collections.Counter(), set(), []
    for c in cands:
        k = norm(c['src'])
        if k in seen or by_src[c['src_by']] >= lim or by_ref[c['ref_by']] >= lim:
            continue
        seen.add(k)
        by_src[c['src_by']] += 1
        by_ref[c['ref_by']] += 1
        out.append(c)
        if len(out) >= n:
            break
    return out


def check(out, n, cap, cutoff):
    """Что набор обещает — проверяется на каждой сборке."""
    for key, src_lang in (('pt_to_ru', 'pt'), ('ru_to_pt', 'ru')):
        items = out[key]
        assert 0 < len(items) <= n, (key, len(items))
        ref_lang = 'ru' if src_lang == 'pt' else 'pt'
        srcs = [norm(x[src_lang]) for x in items]
        assert len(set(srcs)) == len(srcs), f'{key}: исходник повторяется'
        for x in items:
            assert x['added'] >= cutoff[:10], (key, x)
            assert x[src_lang] and x[ref_lang] and not DIGIT.search(x['pt'] + x['ru']), (key, x)
        lim = max(1, int(n * cap))
        for side in (src_lang + '_by', ref_lang + '_by'):
            top = collections.Counter(x[side] for x in items).most_common(1)[0]
            assert top[1] <= lim, (key, side, top)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--raw', default=RAW)
    ap.add_argument('--out', default=OUT)
    ap.add_argument('--cutoff', default='2022-01-01', help='связи не старше этой даты')
    ap.add_argument('-n', type=int, default=1000, help='пар на направление')
    ap.add_argument('--cap', type=float, default=0.5, help='доля набора от одного автора')
    ap.add_argument('--seed', type=int, default=20261002)
    a = ap.parse_args()

    sys.stderr.write('предложения...\n')
    por = sentences(os.path.join(a.raw, 'por_sentences_detailed.tsv'))
    rus = sentences(os.path.join(a.raw, 'rus_sentences_detailed.tsv'))
    npor = collections.Counter(u for _, u, _ in por.values())
    nrus = collections.Counter(u for _, u, _ in rus.values())
    sys.stderr.write('диалект авторов...\n')
    br_tag, pt_tag, ulab = user_labels(por, a.raw)
    pb = phrasebook_keys()

    drop = collections.Counter()
    cands = {'pt2ru': [], 'ru2pt': []}
    with opentext(os.path.join(a.raw, 'por-rus_links.tsv')) as f:
        for line in f:
            p = line.split()
            if len(p) != 2:
                continue
            pid, rid = p
            if pid not in por or rid not in rus:
                drop['нет предложения'] += 1
                continue
            (pt, pu, pa), (ru, ru_u, ra) = por[pid], rus[rid]
            if not pa or not ra:
                drop['нет даты'] += 1
                continue
            if max(pa, ra) < a.cutoff:
                drop['до отсечки'] += 1
                continue
            if pa == ra:
                drop['одна дата у обоих'] += 1
                continue
            d = 'pt2ru' if pa < ra else 'ru2pt'
            if not (npor[pu] > nrus[pu] and nrus[ru_u] > npor[ru_u]):
                drop['не носитель'] += 1
                continue
            if label(pid, pt, pu, br_tag, pt_tag, ulab) == 'PT':
                drop['португальский Португалии'] += 1
                continue
            pt, ru = clean(pt), clean(ru)
            if not ok_text(pt, LAT) or not ok_text(ru, CYR):
                drop['фильтр текста'] += 1
                continue
            r = len(pt) / len(ru)
            if r > MAX_RATIO or r < 1 / MAX_RATIO:
                drop['перекос длин'] += 1
                continue
            src, ref = (pt, ru) if d == 'pt2ru' else (ru, pt)
            cands[d].append({'src': src, 'ref': ref, 'src_by': pu if d == 'pt2ru' else ru_u,
                             'ref_by': ru_u if d == 'pt2ru' else pu, 'pt': pt, 'ru': ru,
                             'pid': int(pid), 'rid': int(rid), 'pt_by': pu, 'ru_by': ru_u,
                             'added': max(pa, ra)[:10], 'conv': conversational(pt, ru),
                             'in_pb': norm(src) in pb[d]})

    rng = random.Random(a.seed)
    out = {'about': 'Проверочный набор перевода pt-BR <-> ru из Tatoeba: прямые связи после отсечки, '
                    'направление — от раннего предложения к позднему, обе стороны — от носителей. '
                    'Собран tools/mt_testset.py.',
           'source': 'Tatoeba (https://tatoeba.org), CC-BY 2.0 FR; у каждой пары — id предложений и авторы',
           'cutoff': a.cutoff, 'seed': a.seed, 'cap': a.cap,
           'newest_sentence': max(x[2] for x in list(por.values()) + list(rus.values()))[:10]}
    stats = {'dropped': dict(drop), 'pool': {d: len(c) for d, c in cands.items()}}
    keys = {'pt2ru': 'pt_to_ru', 'ru2pt': 'ru_to_pt'}
    for d, c in cands.items():
        sel = pick(c, a.n, a.cap, rng)
        out[keys[d]] = [{k: x[k] for k in ('pt', 'ru', 'pid', 'rid', 'pt_by', 'ru_by', 'added', 'conv', 'in_pb')}
                        for x in sel]
        words = sorted(len(WORD_RE.findall(x['src'])) for x in sel)
        stats[d] = {
            'n': len(sel),
            'src_authors': len({x['src_by'] for x in sel}), 'ref_authors': len({x['ref_by'] for x in sel}),
            'top_src': collections.Counter(x['src_by'] for x in sel).most_common(3),
            'top_ref': collections.Counter(x['ref_by'] for x in sel).most_common(3),
            'words_p10_p50_p90_max': [words[len(words) // 10], words[len(words) // 2],
                                      words[len(words) * 9 // 10], words[-1]] if words else [],
            'in_phrasebook': sum(x['in_pb'] for x in sel), 'conversational': sum(x['conv'] for x in sel),
            'by_year': dict(sorted(collections.Counter(x['added'][:4] for x in sel).items())),
        }
    out['stats'] = stats
    check(out, a.n, a.cap, a.cutoff)
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write('\n')
    print(json.dumps(stats, ensure_ascii=False, indent=1))
    print('->', a.out)


if __name__ == '__main__':
    main()
