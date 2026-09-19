#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""WER по логам bench/asr2/run.sh. Offline-логи — JSON в stdout по порядку файлов,
streaming — путь и следом JSON в stderr."""
import json, os, re, sys, unicodedata

B = os.path.dirname(os.path.abspath(__file__))
REF = os.path.join(B, 'ref')


def norm(s):
    s = unicodedata.normalize('NFC', s.lower()).replace('ё', 'е')
    return re.sub(r'[^\w\s]', ' ', s).split()


def wer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        p = d[:]
        d[0] = i
        for j in range(1, len(h) + 1):
            d[j] = min(p[j] + 1, d[j - 1] + 1, p[j - 1] + (r[i - 1] != h[j - 1]))
    return d[len(h)], len(r)


def wavs(lang):
    return sorted(f for f in os.listdir(os.path.join(REF, lang)) if f.endswith('.wav'))


def parse(name, lang):
    out = os.path.join(B, 'logs', name + '.out')
    err = os.path.join(B, 'logs', name + '.err')
    texts, rtf = [], []
    body = open(out, encoding='utf-8', errors='replace').read()
    eb = open(err, encoding='utf-8', errors='replace').read() if os.path.exists(err) else ''
    rtf = [float(x) for x in re.findall(r'RTF\)?\s*=\s*[\d.]+\s*/\s*[\d.]+\s*=\s*([\d.]+)', body + eb)]
    js = re.findall(r'"text"\s*:\s*"((?:[^"\\]|\\.)*)"', body)
    if js:                                        # offline: порядок совпадает с порядком файлов
        return dict(zip(wavs(lang), [json.loads('"' + t + '"') for t in js])), rtf
    pairs, cur = {}, None
    for line in eb.splitlines():                  # streaming: путь, затем JSON
        m = re.match(r'^/data/local/tmp/sh/audio/\w+/(.+\.wav)\s*$', line.strip())
        if m:
            cur = m.group(1)
            continue
        t = re.search(r'"text"\s*:\s*"((?:[^"\\]|\\.)*)"', line)
        if t and cur:
            pairs[cur] = json.loads('"' + t.group(1) + '"')
            cur = None
    return pairs, rtf


if __name__ == '__main__':          # норму и WER импортирует bench/air/air_wer.py — одна реализация на оба стенда
    print('%-22s %7s %7s %7s  %s' % ('вариант', 'WER', 'ошибок', 'слов', 'RTF'))
    for name in sys.argv[1:]:
        lang = 'pt' if name.startswith('pt') else 'ru'
        hyps, rtf = parse(name, lang)
        err = tot = 0
        bad = []
        for fn, hyp in hyps.items():
            rp = os.path.join(REF, lang, fn[:-4] + '.txt')
            if not os.path.exists(rp):
                continue
            ref = open(rp, encoding='utf-8').read().strip()
            e, t = wer(ref, hyp)
            err += e
            tot += t
            if e:
                bad.append((e, ref, hyp))
        if not tot:
            print('%-22s   нет сопоставленных файлов' % name)
            continue
        print('%-22s %6.1f%% %7d %7d  %s' % (name, 100 * err / tot, err, tot,
              ('%.2f' % (sum(rtf) / len(rtf))) if rtf else '—'))
        if '--show' in sys.argv:
            for e, r, h in sorted(bad, reverse=True)[:4]:
                print('      %d | %s\n        | %s' % (e, r, h))
