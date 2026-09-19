#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Счёт WER для прогона через воздух (bench/air/run_air.sh).

Норму текста и расстояние берём из bench/asr2/wer2.py — та же реализация, что считала
базу по файлу, иначе два числа окажутся несравнимыми по причине, не имеющей отношения к звуку.

Сопоставление. В режиме file порядок строк совпадает с порядком файлов: соак пишет маркер
с именем. В режиме air имени нет — есть только время, поэтому каждый услышанный сегмент
относим к тому файлу, чьё окно проигрывания он попал. Часы двух телефонов сведены к часам
компьютера поправками из *.offset. Несколько сегментов на один файл — VAD разрезал фразу,
склеиваем по порядку; ноль сегментов — фраза потеряна целиком, и это считается как полный
пропуск, а не выбрасывается из подсчёта.
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'asr2'))
from wer2 import norm, wer  # noqa: E402

B = os.path.dirname(os.path.abspath(__file__))
REF = os.path.join(B, 'corpus')       # корпус замера через воздух; asr2/ref остаётся историческим

COLS = ['ts', 'kind', 'dir', 'asr', 'mt', 'tag', 'dur_ms', 'seg_db', 'noise_db', 'snr_db',
        'seg_at', 'asr_ms', 'mt_ms', 'feed_pos']
SEG_KINDS = {'asr', 'silence', 'skip_short', 'skip_lang', 'skip_self'}


def rows(path, offset):
    out = []
    if not os.path.exists(path):
        return out
    for line in open(path, encoding='utf-8', errors='replace'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 2 or not p[0].isdigit():
            continue
        r = dict(zip(COLS, p))
        r['ts'] = int(p[0]) - offset          # к часам компьютера
        if r.get('seg_at', '').isdigit():
            r['seg_at'] = int(r['seg_at']) - offset
        out.append(r)
    return out


def speakers(lang):
    """tat_<id> -> диктор. Нужен, чтобы перекос корпуса по голосам был виден в самом выводе."""
    import bz2
    code = {'pt': 'por', 'ru': 'rus'}[lang]
    p = os.path.join(B, '..', '..', 'data', 'tatoeba', 'raw', '%s_sentences_with_audio.tsv.bz2' % code)
    out = {}
    if not os.path.exists(p):
        return out
    with bz2.open(p, 'rt', encoding='utf-8') as f:
        for line in f:
            c = line.rstrip('\n').split('\t')
            if len(c) >= 3:
                out.setdefault('tat_' + c[0], c[2])
    return out


def num(r, k, d=0.0):
    try:
        return float(r.get(k, ''))
    except (TypeError, ValueError):
        return d


def pair_file(lis):
    """Режим file: маркер soak с именем, дальше строки этого файла до следующего маркера."""
    out, cur = [], None
    for r in lis:
        if r['kind'] == 'soak':
            cur = (r['dir'], [])       # в маркере имя файла лежит в колонке dir
            out.append(cur)
        elif r['kind'] in SEG_KINDS and cur is not None:
            cur[1].append(r)
    return out


def pair_air(lis, ply, gap_ms):
    """Режим air: сегмент относим к файлу, в чьё окно попал его конец."""
    plays = [r for r in ply if r['kind'] == 'play']
    # Паузу берём из данных, а не из ключа командной строки: ключ счётчику могли не передать,
    # и тогда окна строились бы по умолчанию 4000 мс независимо от того, чем играли.
    for r in ply:
        if r['kind'] == 'play_begin':
            try:
                gap_ms = int(float(r.get('tag', gap_ms)))
            except (TypeError, ValueError):
                pass
            break
    # строка play: ts | play | имя | длительность_мс | дБ | усиление | частота
    wins = []
    for k, r in enumerate(plays):
        name, dur = r['dir'], num(r, 'asr')
        start = r['ts']
        end = start + dur + gap_ms
        if k + 1 < len(plays):
            end = min(end, plays[k + 1]['ts'])
        wins.append([name, start, end, []])
    lost = 0
    for r in lis:
        if r['kind'] not in SEG_KINDS:
            continue
        at = r['seg_at'] if isinstance(r['seg_at'], int) else r['ts']
        hit = None
        for w in wins:
            if w[1] <= at <= w[2]:
                hit = w
                break
        if hit is None:
            lost += 1
        else:
            hit[3].append(r)
    return [(w[0], w[3]) for w in wins], lost


def pair_replay(lis, rec_lis, rec_ply, gap_ms):
    """Повтор записи: время сегмента переводим в положение внутри записи и сравниваем с тем,
    что в этот момент играло при записи. Якоря: raw_begin в журнале записи и feed_begin
    в журнале повтора; при подаче быстрее реального времени положение умножается на скорость."""
    raw0 = next((r['ts'] for r in rec_lis if r['kind'] == 'raw_begin'), None)
    feed = next((r for r in lis if r['kind'] == 'feed_begin'), None)
    if raw0 is None or feed is None:
        raise SystemExit('нет якорей: raw_begin в записи и feed_begin в повторе')
    try:
        speed = max(1, int(float(feed.get('mt', 1))))       # строка feed_begin: имя, отсчётов, скорость
    except (TypeError, ValueError):
        speed = 1
    f0 = feed['ts']
    shifted, by_clock = [], 0
    for r in lis:
        if r['kind'] not in SEG_KINDS:
            continue
        q = dict(r)
        pos = r.get('feed_pos', '')
        if pos and pos.isdigit():
            # Точное положение: счёт отсчётов, пройденных через VAD. Часы для этого не годятся —
            # подача идёт «примерно ×N», и ошибка скорости копится в десятки секунд к концу записи.
            q['seg_at'] = raw0 + int(pos) // 16
        else:
            at = r['seg_at'] if isinstance(r['seg_at'], int) else r['ts']
            q['seg_at'] = raw0 + (at - f0) * speed
            by_clock += 1
        shifted.append(q)
    if by_clock:
        print('внимание: %d сегментов привязаны по часам, а не по отсчётам — сборка старая' % by_clock)
    return pair_air(shifted, rec_ply, gap_ms)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dir')
    ap.add_argument('--lang', required=True)
    ap.add_argument('--mode', default='air')
    ap.add_argument('--gap', type=int, default=4000)
    ap.add_argument('--rec', help='каталог записи, к которой относится повтор')
    a = ap.parse_args()

    def off(n, d=None):
        p = os.path.join(d or a.dir, n)
        return int(open(p).read().strip()) if os.path.exists(p) else 0

    lis = rows(os.path.join(a.dir, 'listener.tsv'), off('listener.offset'))
    if a.mode == 'file':
        pairs, lost = pair_file(lis), 0
    elif a.mode == 'replay':
        if not a.rec:
            raise SystemExit('для --mode replay нужен --rec')
        pairs, lost = pair_replay(
            lis,
            rows(os.path.join(a.rec, 'listener.tsv'), off('listener.offset', a.rec)),
            rows(os.path.join(a.rec, 'player.tsv'), off('player.offset', a.rec)),
            a.gap)
    else:
        pairs, lost = pair_air(lis, rows(os.path.join(a.dir, 'player.tsv'), off('player.offset')), a.gap)

    spk = speakers(a.lang)
    err = tot = 0
    miss = split = 0
    clean = []          # (ошибок, слов) по фразам, которым досталcя ровно свой сегмент
    merged = 0          # услышано заметно длиннее эталона — в сегмент попала и соседняя реплика
    nothing = 0         # до распознавания не дошло ничего
    snrs, lvls = [], []
    per = {}                                   # диктор -> [ошибок, слов]
    print('%-18s %6s %5s %6s  %s' % ('файл', 'SNR', 'ош', 'слов', 'услышано'))
    for name, segs in pairs:
        rp = os.path.join(REF, a.lang, name[:-4] + '.txt')
        if not os.path.exists(rp):
            continue
        ref = open(rp, encoding='utf-8').read().strip()
        heard = ' '.join(s['asr'] for s in segs if s['kind'] == 'asr' and s.get('asr'))
        kinds = [s['kind'] for s in segs]
        if not segs:
            miss += 1
        if len([k for k in kinds if k == 'asr']) > 1:
            split += 1
        vals = [(num(s, 'snr_db'), num(s, 'seg_db')) for s in segs
                if s['kind'] == 'asr' and s.get('snr_db')]      # пусто = оценки фона нет
        snrs += [v[0] for v in vals]
        lvls += [v[1] for v in vals]
        e, t = wer(ref, heard)
        err += e
        tot += t
        if not heard:
            nothing += 1                           # сегмента не было вовсе либо он оказался пустым
        elif len(norm(heard)) > len(norm(ref)) * 1.6:
            merged += 1
        else:
            clean.append((e, t))
        who = spk.get(name[:-4], '?')
        p = per.setdefault(who, [0, 0])
        p[0] += e
        p[1] += t
        snr = max((v[0] for v in vals), default=None)
        note = '' if heard else ' ⟵ ПУСТО ' + ('/'.join(kinds) if kinds else 'сегмента нет')
        print('%-18s %6s %5d %6d  %s%s'
              % (name[:18], '—' if snr is None else '%.1f' % snr, e, t, heard[:60], note))

    print()
    if not tot:
        print('нечего считать: ни один файл не сопоставлен')
        return
    print('WER %.1f%%  (%d ошибок из %d слов, файлов %d)' % (100 * err / tot, err, tot, len(pairs)))
    print('потеряно целиком: %d · разрезано VAD: %d · сегментов вне окон: %d' % (miss, split, lost))
    # Общий процент через воздух смешивает два разных провала: нарезку и распознавание.
    # Без этого разделения он выглядит как «модель плохо слышит», хотя модель почти не при чём.
    n = len(pairs)
    ce = sum(x[0] for x in clean)
    ct = sum(x[1] for x in clean)
    if n:
        print('нарезка: не дошло %d (%.0f%%) · склеено с соседней %d (%.0f%%) · чисто %d (%.0f%%)'
              % (nothing, 100 * nothing / n, merged, 100 * merged / n, len(clean), 100 * len(clean) / n))
    if ct:
        print('распознавание на чисто нарезанных: WER %.1f%% (%d из %d слов), без ошибок %d из %d'
              % (100 * ce / ct, ce, ct, sum(1 for x in clean if x[0] == 0), len(clean)))
    if snrs:
        print('уровень речи %.1f dBFS (мин %.1f), SNR %.1f дБ (мин %.1f)'
              % (sum(lvls) / len(lvls), min(lvls), sum(snrs) / len(snrs), min(snrs)))
    # Корпус Tatoeba перекошен по дикторам, поэтому n реплик — это не n независимых наблюдений.
    # Печатаем разбивку, чтобы перекос был виден прямо в числе.
    if len(per) > 1:
        print('по дикторам:', ' · '.join(
            '%s %.0f%% (%d сл.)' % (w, 100 * v[0] / v[1], v[1])
            for w, v in sorted(per.items(), key=lambda x: -x[1][1]) if v[1]))


if __name__ == '__main__':
    main()
