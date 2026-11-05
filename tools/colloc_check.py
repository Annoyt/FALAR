#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Перевод сочетаний «Фраз» переводчиком приложения: файл владельцу и числа (results/2026-11-05-learn-cards.md).
Входы — наборы bench/apk/cards_sim.sh (-Dsampleout) и выход bench/quality/MtRun.java по ним. Данные — разговоры
владельца: файл пишется рядом с ними (private/…), в вывод идут только числа.
    python3 -I tools/colloc_check.py <выход MtRun.json> <файл владельцу.txt> <набор.json>=<имя> …
"""
import json
import re
import sys


def words(s):
    return [w for w in re.findall(r'[^\W\d_]+', s.lower()) if len(w) >= 4]


def main():
    out, txt, sets = sys.argv[1], sys.argv[2], sys.argv[3:]
    res = json.load(open(out, encoding='utf-8'))['results']
    meta = {}
    for a in sets:
        path, name = a.rsplit('=', 1)
        for it in json.load(open(path, encoding='utf-8'))['pt_to_ru']:
            meta[(name, it['pt'])] = it
    lines, stat = [], dict(n=0, empty=0, unk=0, same=0, agree=0)
    for r in res:
        m = meta[(r['set'], r['src'])]
        hyp = r['hyp'] or ''
        stat['n'] += 1
        stat['empty'] += not hyp.strip()
        stat['unk'] += '<unk>' in hyp
        stat['same'] += hyp.strip().lower() == r['src'].strip().lower()
        stems = {w[:5] for w in words(r['ref'] or '')}
        stat['agree'] += any(w[:5] in stems for w in words(hyp))
        lines.append(f"{r['set']} · {m['n']} раз в {m['chats']} разговорах\n"
                     f"  {r['src']}  →  {hyp}\n"
                     f"  пример ({'собеседник' if m['heard'] else 'вы'}): {m['ex']}\n"
                     f"           {r['ref']}\n")
    with open(txt, 'w', encoding='utf-8') as f:
        f.write('Сочетания «Фраз»: 100 случайных, перевод — переводчик приложения (луч 4), пример — из разговора.\n'
                'Смотрите: осмысленно ли само сочетание и верно ли оно переведено.\n\n')
        f.write('\n'.join(lines))
    n = max(1, stat['n'])
    print(f"сочетаний {stat['n']}: перевод пустой {stat['empty']}, с <unk> {stat['unk']}, повторяет исходник {stat['same']}; "
          f"делит слово с переводом своего предложения {stat['agree']} ({100 * stat['agree'] / n:.0f} %)")
    print(f'файл владельцу — {txt}')


if __name__ == '__main__':
    main()
