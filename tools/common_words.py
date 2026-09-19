#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Словарь обычных слов языка: data/common_words.txt.

Приложение читает его в WordList и использует в двух местах:
  * защита списка своих слов от подмены обычного слова именем (results/2026-09-12-wordlist.md);
  * проверка «похоже ли распознанное на ожидаемый язык» (results/2026-09-12-langgate.md).

Источник — тот же корпус пар, что и разговорник: data/tatoeba/phrasebook_tatoeba.tsv.
Брать полный корпус Tatoeba нельзя: там европейский португальский, а словарь должен описывать
тот язык, который приложение ждёт на входе.

Порог частоты 2: слово, встреченное в корпусе единожды, чаще опечатка, чем язык.

   python3 tools/common_words.py [--min-freq 2] [--out data/common_words.txt]

История: первая версия файла собиралась вручную и отбрасывала слова короче трёх букв —
для защиты имён это было безразлично (isCommon и так не судит о коротких), а проверку языка
это молча ломало: «Sim», «Да», «Oi» не находились в словаре. Поэтому здесь длина не режется.
"""
import argparse
import collections
import os
import re
import sys

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(R, 'data', 'tatoeba', 'phrasebook_tatoeba.tsv')
WORD = re.compile(r'[^\W\d_]+', re.U)
CYR = re.compile(r'^[а-яё]+$')
LAT = re.compile(r'^[a-zà-öø-ÿ]+$', re.I)

# Короткие слова требуют отдельного порога. Настоящие («eu», «o», «я», «не») встречаются
# тысячами, а латинские огрызки из формул и сокращений — единицами; общий порог 2 пропускал
# и то и другое, и одиночная буква начинала считаться словом языка.
MIN_LEN1 = 20
MIN_LEN2 = 5


def keep(lang, w, n, min_freq):
    if not (CYR if lang == 'ru' else LAT).match(w):
        return False                      # чужой алфавит: в русской стороне корпуса полно латиницы
    if len(w) == 1:
        return n >= MIN_LEN1
    if len(w) == 2:
        return n >= MIN_LEN2
    return n >= min_freq


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', default=SRC)
    ap.add_argument('--out', default=os.path.join(R, 'data', 'common_words.txt'))
    ap.add_argument('--min-freq', type=int, default=2)
    a = ap.parse_args()

    cnt = {'pt': collections.Counter(), 'ru': collections.Counter()}
    rows = 0
    for line in open(a.src, encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 4:
            continue
        rows += 1
        direction, src_text, dst_text = p[0], p[2], p[3]
        # В строке лежат обе стороны пары: ключ направления говорит, где какой язык.
        pt, ru = (src_text, dst_text) if direction == 'pt2ru' else (dst_text, src_text)
        for w in WORD.findall(pt.lower()):
            cnt['pt'][w] += 1
        for w in WORD.findall(ru.lower().replace('ё', 'е')):
            cnt['ru'][w] += 1

    if not rows:
        sys.exit('не прочитано ни одной строки из %s' % a.src)

    with open(a.out, 'w', encoding='utf-8') as f:
        total = {}
        for lang in ('pt', 'ru'):
            kept = [(w, n) for w, n in cnt[lang].items() if keep(lang, w, n, a.min_freq)]
            kept.sort()
            total[lang] = len(kept)
            for w, n in kept:
                f.write('%s %s %d\n' % (lang, w, n))
    print('строк корпуса %d · pt %d слов · ru %d слов → %s'
          % (rows, total['pt'], total['ru'], a.out))
    for lang in ('pt', 'ru'):
        short = sorted((w for w, n in cnt[lang].items() if keep(lang, w, n, a.min_freq) and len(w) <= 2),
                       key=lambda w: -cnt[lang][w])
        print('  %s: короче трёх букв — %d: %s' % (lang, len(short), ' '.join(short)))


if __name__ == '__main__':
    main()
