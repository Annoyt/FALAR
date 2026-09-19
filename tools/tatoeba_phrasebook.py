#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сборка словаря быстрых фраз для приложения из разобранной Tatoeba.

Вход:  data/tatoeba/pairs_direct.tsv, data/tatoeba/pivot_candidates.tsv
Выход: data/tatoeba/phrasebook_tatoeba.tsv  (direction \t key \t src \t dst \t tier)
       data/tatoeba/report.json

Ярус direct — прямые связи por<->rus, человеческий перевод, берём как есть.
Ярус pivot  — пары, собранные через английский; принимаем только если совпадают
              с выводом OPUS-MT по chrF выше калиброванного порога, либо
              подтверждены двумя разными английскими предложениями.
"""
import argparse, collections, json, os, sys, unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tatoeba_extract import norm, opentext, read_links  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, 'data', 'tatoeba')


# ------------------------------------------------------------------ chrF (n=6, beta=2)
def ngrams(s, n):
    s = ' '.join(s.split())
    return collections.Counter(s[i:i + n] for i in range(len(s) - n + 1))


def chrf(hyp, ref, maxn=6, beta=2.0):
    if not hyp or not ref:
        return 0.0
    ps, rs = [], []
    for n in range(1, maxn + 1):
        h, r = ngrams(hyp, n), ngrams(ref, n)
        if not h or not r:
            continue
        ov = sum((h & r).values())
        ps.append(ov / sum(h.values()))
        rs.append(ov / sum(r.values()))
    if not ps:
        return 0.0
    p, r = sum(ps) / len(ps), sum(rs) / len(rs)
    if p + r == 0:
        return 0.0
    b2 = beta * beta
    return 100.0 * (1 + b2) * p * r / (b2 * p + r)


# ------------------------------------------------------------------ ввод-вывод
def read_pairs(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, encoding='utf-8') as f:
        head = f.readline().rstrip('\n').split('\t')
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) == len(head):
                rows.append(dict(zip(head, p)))
    return rows


class MT:
    def __init__(self, direction, threads=(6, 2)):
        import ctranslate2, sentencepiece as spm
        d = {'pt2ru': 'pt2ru', 'ru2pt': 'ru2pt'}[direction]
        self.tr = ctranslate2.Translator(os.path.join(ROOT, 'models', 'opus-ct2', d), device='cpu',
                                         compute_type='int8', inter_threads=threads[0], intra_threads=threads[1])
        self.src = spm.SentencePieceProcessor(model_file=os.path.join(ROOT, 'models', 'opus-onnx', d, 'source.spm'))
        self.dst = spm.SentencePieceProcessor(model_file=os.path.join(ROOT, 'models', 'opus-onnx', d, 'target.spm'))

    def run(self, sents, bs=64, log_every=20000):
        out = []
        for i in range(0, len(sents), 4096):
            chunk = sents[i:i + 4096]
            toks = [self.src.encode(s, out_type=str) + ['</s>'] for s in chunk]
            res = self.tr.translate_batch(toks, max_batch_size=bs, beam_size=1)
            out += [self.dst.decode(r.hypotheses[0]) for r in res]
            if i and i % log_every < 4096:
                sys.stderr.write('    %d/%d\n' % (len(out), len(sents)))
        return out


QUOTES = set('"\u00ab\u00bb\u201c\u201d\u2018\u2019\u201e')


def usable(pt, ru):
    """Реплики в кавычках и обрывки диалога в разговорник не годятся."""
    for t in (pt, ru):
        if QUOTES & set(t):
            return False
        if t.lstrip().startswith(('-', '\u2013', '\u2014')):
            return False
        if '\u2026' in t or '...' in t:
            return False
    return True


def eng_multiplicity(pairs, raw):
    """Через сколько разных английских предложений связаны pid и rid."""
    need_p = {pid for pid, _ in pairs}
    need_r = {rid for _, rid in pairs}
    pe, re_ = {}, {}
    for path, need, into in ((os.path.join(raw, 'por-eng_links.tsv'), need_p, pe),
                             (os.path.join(raw, 'rus-eng_links.tsv'), need_r, re_)):
        with opentext(path) as f:
            for line in f:
                q = line.split()
                if len(q) == 2 and q[0] in need:
                    into.setdefault(q[0], set()).add(q[1])
    return {(pid, rid): len(pe.get(pid, ()) & re_.get(rid, ())) for pid, rid in pairs}


def cached_run(engine, sents, path):
    """Переводы кладём на диск: перезапуск не пересчитывает уже готовое."""
    cache = {}
    if os.path.exists(path):
        with open(path, encoding='utf-8') as f:
            for line in f:
                q = line.rstrip('\n').split('\t')
                if len(q) == 2:
                    cache[q[0]] = q[1]
    todo = [s for s in sents if s not in cache]
    sys.stderr.write('  в кэше %d, переводим %d\n' % (len(sents) - len(todo), len(todo)))
    if todo:
        out = engine.run(todo)
        with open(path, 'a', encoding='utf-8') as f:
            for s, h in zip(todo, out):
                h = h.replace('\t', ' ').replace('\n', ' ')
                cache[s] = h
                f.write('%s\t%s\n' % (s.replace('\t', ' '), h))
    return [cache[s] for s in sents]


def pick(cands):
    """cands: [(dst, weight, len)] -> лучший перевод: по весу, затем по краткости."""
    return sorted(cands, key=lambda c: (-c[1], c[2], c[0]))[0][0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--no-pivot', action='store_true')
    ap.add_argument('--pivot-percentile', type=float, default=25.0,
                    help='порог chrF берём как этот процентиль качества MT на прямых парах')
    ap.add_argument('--max-len-ratio', type=float, default=2.2)
    ap.add_argument('--cap', type=int, default=0, help='ограничить размер таблицы (0 — без ограничения)')
    ap.add_argument('--out', default=DATA)
    a = ap.parse_args()
    rep = {}

    direct = read_pairs(os.path.join(DATA, 'pairs_direct.tsv'))
    pivot = [] if a.no_pivot else read_pairs(os.path.join(DATA, 'pivot_candidates.tsv'))
    rep['direct_in'], rep['pivot_in'] = len(direct), len(pivot)

    def ratio_ok(x, y):
        return 1 / a.max_len_ratio <= len(x) / max(1, len(y)) <= a.max_len_ratio

    direct = [r for r in direct if ratio_ok(r['pt'], r['ru']) and usable(r['pt'], r['ru'])]
    pivot = [r for r in pivot if ratio_ok(r['pt'], r['ru']) and usable(r['pt'], r['ru'])]
    rep['direct_len_ok'], rep['pivot_len_ok'] = len(direct), len(pivot)

    # --- ярус direct: группируем по нормализованному ключу, вес = число связей
    def build(rows, key_field, val_field, allow_pt):
        buckets = collections.defaultdict(lambda: collections.defaultdict(lambda: [0, None]))
        for r in rows:
            if not allow_pt and r['label'] == 'PT':
                continue
            k = norm(r[key_field])
            if not k:
                continue
            cell = buckets[k][norm(r[val_field])]
            cell[0] += 1
            if cell[1] is None or len(r[val_field]) < len(cell[1][1]):
                cell[1] = (r[key_field], r[val_field])
        out = {}
        for k, cands in buckets.items():
            best = sorted(cands.values(), key=lambda c: (-c[0], len(c[1][1]), c[1][1]))[0]
            out[k] = best[1]
        return out

    conv = {}
    for r in direct + pivot:
        for kf in ('pt', 'ru'):
            conv[norm(r[kf])] = conv.get(norm(r[kf]), 0) or int(r.get('conv', 0))

    pt2ru = build(direct, 'pt', 'ru', allow_pt=True)    # слушаем португальский — диалект не важен
    ru2pt = build(direct, 'ru', 'pt', allow_pt=False)   # говорим по-португальски — только pt-BR
    rep['direct_pt2ru'], rep['direct_ru2pt'] = len(pt2ru), len(ru2pt)

    tiers = {}
    for k in pt2ru:
        tiers[('pt2ru', k)] = 'direct'
    for k in ru2pt:
        tiers[('ru2pt', k)] = 'direct'

    # --- калибровка порога: качество MT на заведомо верных прямых парах
    if pivot:
        sys.stderr.write('калибровка порога на прямых парах...\n')
        sample = direct[::max(1, len(direct) // 3000)][:3000]
        mt = MT('pt2ru')
        hyp = cached_run(mt, [r['pt'] for r in sample], os.path.join(DATA, 'mt_cache_pt2ru.tsv'))
        scores = sorted(chrf(h, r['ru']) for h, r in zip(hyp, sample))
        thr = scores[int(len(scores) * a.pivot_percentile / 100)]
        rep['chrf_direct'] = {'p10': round(scores[len(scores) // 10], 1),
                              'p25': round(scores[len(scores) // 4], 1),
                              'median': round(scores[len(scores) // 2], 1),
                              'mean': round(sum(scores) / len(scores), 1)}
        rep['pivot_threshold'] = round(thr, 1)
        sys.stderr.write('  порог chrF = %.1f (%s)\n' % (thr, rep['chrf_direct']))

        # --- ярус pivot: сверяем с MT в обе стороны
        sys.stderr.write('кратность английского пивота...\n')
        mult = eng_multiplicity({(r['pid'], r['rid']) for r in pivot}, os.path.join(DATA, 'raw'))
        rep['pivot_mult_ge2'] = sum(1 for v in mult.values() if v >= 2)
        for direction, kf, vf, allow_pt in (('pt2ru', 'pt', 'ru', True), ('ru2pt', 'ru', 'pt', False)):
            have = pt2ru if direction == 'pt2ru' else ru2pt
            rows = [r for r in pivot if (allow_pt or r['label'] != 'PT') and norm(r[kf]) and norm(r[kf]) not in have]
            uniq = sorted({r[kf] for r in rows})
            sys.stderr.write('%s: %d кандидатов, %d уникальных исходников\n' % (direction, len(rows), len(uniq)))
            if not uniq:
                continue
            engine = mt if direction == 'pt2ru' else MT('ru2pt')
            hyp = dict(zip(uniq, cached_run(engine, uniq, os.path.join(DATA, 'mt_cache_%s.tsv' % direction))))
            buckets = collections.defaultdict(list)
            for r in rows:
                sc = chrf(hyp[r[kf]], r[vf])
                if sc >= thr or mult.get((r['pid'], r['rid']), 0) >= 2:
                    buckets[norm(r[kf])].append((sc, r[kf], r[vf]))
            added = 0
            for k, cands in buckets.items():
                if k in have:
                    continue
                best = max(cands, key=lambda c: (c[0], -len(c[2])))
                have[k] = (best[1], best[2])
                tiers[(direction, k)] = 'pivot'
                added += 1
            rep['pivot_added_' + direction] = added
            sys.stderr.write('  добавлено %d\n' % added)

    # --- запись
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, 'phrasebook_tatoeba.tsv')
    n = 0
    if a.cap:
        # чем короче фраза, чем она разговорнее и чем надёжнее ярус — тем выше шанс, что её скажут
        def rank(direction, k):
            return (0 if tiers[(direction, k)] == 'direct' else 1, 0 if conv.get(k) else 1, len(k.split(' ')))
        for direction, table in (('pt2ru', pt2ru), ('ru2pt', ru2pt)):
            keep = set(sorted(table, key=lambda k: rank(direction, k))[:a.cap // 2])
            for k in list(table):
                if k not in keep:
                    del table[k]
        rep['capped_to'] = a.cap
    with open(path, 'w', encoding='utf-8') as f:
        for direction, table in (('pt2ru', pt2ru), ('ru2pt', ru2pt)):
            for k in sorted(table):
                src, dst = table[k]
                src, dst = src.replace('\t', ' '), dst.replace('\t', ' ')
                f.write('%s\t%s\t%s\t%s\t%s\n' % (direction, k, src, dst, tiers[(direction, k)]))
                n += 1
    rep['total'] = n
    rep['pt2ru'], rep['ru2pt'] = len(pt2ru), len(ru2pt)
    rep['bytes'] = os.path.getsize(path)
    with open(os.path.join(a.out, 'report.json'), 'w', encoding='utf-8') as f:
        json.dump(rep, f, ensure_ascii=False, indent=1)
    print(json.dumps(rep, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
