#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сквозной набор «речь → перевод» из записей комнаты (results/2026-10-27-asr-confidence.md, шаг 6).

В перевод идёт то, что распознал parakeet на записях комнаты: кэш tools/asr_confidence.py
(results/asr_conf/<запись>.json; нарезка — близнец приложения, двухканальные — по выровненной сумме). Образец —
перевод от людей из Tatoeba: прямая связь pt↔ru у предложения, которое звучало (корпус записей
bench/air/corpus, имена tat_<id>). Окно фразы — как у asr_confidence. Если в окне несколько кусков, их тексты
склеиваются (в приложении это были бы отдельные реплики). Окна без кусков — потерянные фразы: в набор не идут,
считаются отдельно.

Выход — results/asr_conf/e2e/ (вне git: распознанное с записей комнаты не выносится):
  <запись>.json        ключи pt_to_ru / ru_to_pt, как у MtRun и mt_bench: поле языка исходника — распознанное,
                       языка перевода — образец, true — что сказано на самом деле;
  <запись>.clean.json  то же, но исходник — сказанное: потолок без ошибок слуха.

    python3 tools/e2e_set.py [запись …]        (по умолчанию — 14 записей шага 1)
"""
import argparse
import json
import os
import subprocess
import sys

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
import asr_confidence as ac  # noqa: E402
import mt_testset as mts  # noqa: E402

SR = 16000
OUT = os.path.join(R, 'results', 'asr_conf', 'e2e')
RECS = ['near-pt', 'far-pt', 'noisy-pt', 'nearbg45-pt', 'nearbg40-pt', 'near-ru', 'far-ru', 'nearbg40-ru',
        'st-laptop-near-pt', 'st-laptop-end-pt', 'st-laptop-bottom-pt',
        'st-laptop-near-ru', 'st-laptop-end-ru', 'st-laptop-bottom-ru']


def raw_dir():
    """data/tatoeba/raw — не в git: в рабочем дереве или в основной копии."""
    own = os.path.join(R, 'data', 'tatoeba', 'raw')
    if os.path.isdir(own):
        return own
    common = subprocess.run(['git', '-C', R, 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                            capture_output=True, text=True).stdout.strip()
    return os.path.join(os.path.dirname(common), 'data', 'tatoeba', 'raw')


def references(raw):
    """(язык, id сказанного) -> перевод от людей на другой язык: прямая связь pt↔ru, у нескольких — меньший id."""
    por = mts.sentences(os.path.join(raw, 'por_sentences_detailed.tsv'))
    rus = mts.sentences(os.path.join(raw, 'rus_sentences_detailed.tsv'))
    links = {}
    with mts.opentext(os.path.join(raw, 'por-rus_links.tsv')) as f:
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < 2:
                continue
            a, b = p[0], p[1]
            for x, y in ((a, b), (b, a)):
                if x in por and y in rus:
                    links.setdefault(('pt', x), set()).add(y)
                if x in rus and y in por:
                    links.setdefault(('ru', x), set()).add(y)
    other = {'pt': rus, 'ru': por}
    return {k: other[k[0]][min(v, key=int)][0] for k, v in links.items()}


def build(name, refs):
    cache = json.load(open(os.path.join(R, 'results', 'asr_conf', name + '.json'), encoding='utf-8'))
    _, _, plays, gap, lang = ac.load(name)
    tgt = 'ru' if lang == 'pt' else 'pt'
    wins = []
    for k, (pn, at, dur) in enumerate(plays):
        end = at + dur + gap / 1000.0
        if k + 1 < len(plays):
            end = min(end, plays[k + 1][1])
        wins.append([pn, at, end, []])
    for s, r in zip(cache['segs'], cache['asr']):
        hit = next((w for w in wins if w[1] <= s[2] / SR <= w[2]), None)
        if hit is not None and r[0]:
            hit[3].append(r[0])
    asr, clean, lost, noref = [], [], 0, 0
    for pn, _, _, texts in wins:
        sid = pn[:-4].split('_')[-1]
        ref = refs.get((lang, sid))
        if ref is None:
            noref += 1
            continue
        true = open(os.path.join(R, 'bench', 'air', 'corpus', lang, pn[:-4] + '.txt'), encoding='utf-8').read().strip()
        if not texts:
            lost += 1
            continue
        asr.append({lang: ' '.join(texts), tgt: ref, 'true': true, 'nseg': len(texts)})
        clean.append({lang: true, tgt: ref, 'true': true})
    key = f'{lang}_to_{tgt}'
    for suffix, items in (('', asr), ('.clean', clean)):
        path = os.path.join(OUT, f'{name}{suffix}.json')
        json.dump({key: items}, open(path + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        os.replace(path + '.tmp', path)
    return len(wins), noref, lost, len(asr), sum(1 for it in asr if it['nseg'] > 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('recs', nargs='*', default=RECS)
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    refs = references(raw_dir())
    print('запись · окон фраз · без перевода в Tatoeba · потеряно слухом · в наборе (из них склеено из нескольких кусков)')
    for name in a.recs:
        n, noref, lost, kept, multi = build(name, refs)
        print(f'{name:20s} {n:4d} · {noref:3d} · {lost:3d} · {kept:4d} ({multi})', flush=True)


if __name__ == '__main__':
    main()
