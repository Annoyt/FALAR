#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Корпус живых записей для замеров распознавания: bench/asr2/ref/{pt,ru}/.

Зачем. Все замеры WER до сих пор шли на 12 португальских и 10 русских записях — 156 слов
на оба языка. Разница в одну ошибку сдвигает проценты на полтора пункта, и на таком наборе
нельзя отличить «воздух испортил распознавание» от случайности. Плюс коротких реплик там нет
вовсе, а именно они оказались самым больным местом проверки языка.

Синтезированный голос для этого не годится: замер отпечатка голоса уже показал, что TTS
не несёт индивидуальных признаков (results/2026-09-12-asr-upgrade.md). Нужны живые люди.

Откуда. Tatoeba: списки предложений с озвучкой пересекаются с нашим же набором прямых пар
pt↔ru (data/tatoeba/pairs_direct.tsv), уже отфильтрованным по бразильскому варианту. Так
корпус остаётся тем же языком, что приложение ждёт на входе, и у каждой записи есть перевод.

   python3 tools/tatoeba_audio.py --lang pt --short 12 --medium 18

Записи кладутся как <id>.wav (16 кГц моно) рядом с <id>.txt — эталонным текстом.
Лицензии записей пишутся в ref/<lang>/LICENSES.tsv: у части файлов CC BY-NC 4.0, поэтому
корпус остаётся стендовым и в сборку приложения не попадает.
"""
import argparse
import bz2
import collections
import os
import re
import subprocess
import sys
import urllib.request

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(R, 'data', 'tatoeba', 'raw')
REF = os.path.join(R, "bench", "air", "corpus")
URL = 'https://tatoeba.org/audio/download/%s'
CODE = {'pt': 'por', 'ru': 'rus'}


def audio_index(lang):
    """sentence_id -> (audio_id, лицензия). Одно предложение может иметь несколько начиток."""
    out = {}
    p = os.path.join(RAW, '%s_sentences_with_audio.tsv.bz2' % CODE[lang])
    if not os.path.exists(p):
        sys.exit('нет %s — скачай с downloads.tatoeba.org/exports/per_language/' % p)
    with bz2.open(p, 'rt', encoding='utf-8') as f:
        for line in f:
            c = line.rstrip('\n').split('\t')
            if len(c) >= 2 and c[0] not in out:
                out[c[0]] = (c[1], c[3] if len(c) > 3 else '')
    return out


def pairs():
    """Прямые пары с номерами предложений: pid — португальское, rid — русское."""
    out = []
    p = os.path.join(R, 'data', 'tatoeba', 'pairs_direct.tsv')
    with open(p, encoding='utf-8') as f:
        head = f.readline().rstrip('\n').split('\t')
        i = {n: k for k, n in enumerate(head)}
        for line in f:
            c = line.rstrip('\n').split('\t')
            if len(c) < len(head):
                continue
            out.append((c[i['pt']], c[i['ru']], c[i['pid']], c[i['rid']], c[i['label']]))
    return out


def words(s):
    return [w for w in re.split(r'[^\w]+', s, flags=re.U) if w]


def fetch(audio_id, dst):
    tmp = dst + '.mp3'
    req = urllib.request.Request(URL % audio_id, headers={'User-Agent': 'agenttranslator-bench'})
    with urllib.request.urlopen(req, timeout=30) as r, open(tmp, 'wb') as f:
        f.write(r.read())
    # 16 кГц моно — то, что читает приложение; sherpa ресемплирует сам, но так файлы легче
    # и одинаковы по формату со старой частью корпуса.
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', tmp,
                    '-ac', '1', '-ar', '16000', dst], check=True)
    os.remove(tmp)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--lang', required=True, choices=['pt', 'ru'])
    ap.add_argument('--short', type=int, default=12, help='реплик в 1–3 слова')
    ap.add_argument('--medium', type=int, default=18, help='реплик в 4–10 слов')
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()

    idx = audio_index(a.lang)
    have = {f[:-4] for f in os.listdir(os.path.join(REF, a.lang)) if f.endswith('.wav')}
    picked = {'short': [], 'medium': []}
    seen_text = set()
    for pt, ru, pid, rid, label in pairs():
        sid, text = (pid, pt) if a.lang == 'pt' else (rid, ru)
        if a.lang == 'pt' and label != 'BR':
            continue                       # для португальского берём только бразильские
        if sid not in idx or ('tat_' + sid) in have:
            continue
        key = text.lower()
        if key in seen_text:
            continue
        n = len(words(text))
        bucket = 'short' if 1 <= n <= 3 else ('medium' if 4 <= n <= 10 else None)
        if bucket and len(picked[bucket]) < getattr(a, bucket):
            picked[bucket].append((sid, text, idx[sid][0], idx[sid][1]))
            seen_text.add(key)
        if len(picked['short']) >= a.short and len(picked['medium']) >= a.medium:
            break

    print('%s: коротких %d, средних %d' % (a.lang, len(picked['short']), len(picked['medium'])))
    lic = collections.Counter()
    rows = picked['short'] + picked['medium']
    if a.dry_run:
        for sid, text, aid, li in rows:
            print('  %-9s %-2d %s' % (sid, len(words(text)), text))
        return
    out = os.path.join(REF, a.lang)
    got = 0
    for sid, text, aid, li in rows:
        dst = os.path.join(out, 'tat_%s.wav' % sid)
        try:
            fetch(aid, dst)
        except Exception as e:                      # пропуск одной записи не должен ронять сбор
            print('  не вышло %s: %s' % (sid, e))
            continue
        open(os.path.join(out, 'tat_%s.txt' % sid), 'w', encoding='utf-8').write(text + '\n')
        lic[li] += 1
        got += 1
    with open(os.path.join(out, 'LICENSES.tsv'), 'a', encoding='utf-8') as f:
        for sid, text, aid, li in rows:
            f.write('tat_%s\t%s\t%s\n' % (sid, aid, li or 'не указана'))
    print('  скачано %d · лицензии: %s' % (got, dict(lic)))


if __name__ == '__main__':
    main()
