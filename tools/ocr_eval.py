#!/usr/bin/env python3
"""Оценка офлайн-OCR на проверочном наборе bench/ocr (32 снимка вывесок, расшифровка в gt.txt).

  .venv/bin/python tools/ocr_eval.py                      # все варианты эталонным конвейером
  .venv/bin/python tools/ocr_eval.py --got dir/           # вывод приложения: dir/p01.txt …
  .venv/bin/python tools/ocr_eval.py --models models/ocr  # модели, которые уезжают в приложение
  … --json файл.json                                       # по снимкам: числа и хэш вывода, без текста
                                                           # (для bench/quality/gate.sh и verdict.py)

Метрики — по словам, а не по строкам: разбивка на строки и их порядок у детектора и у человека
расходятся на многоколоночных вывесках, а для перевода важно, прочитано ли слово.
  слова     — доля слов расшифровки, прочитанных буква в букву, с диакритикой (мультимножества);
  без диакр.— то же, если не считать ошибкой потерянный или лишний знак над буквой;
  точность  — доля прочитанных слов, которые есть в расшифровке (мусор с фона её снижает);
  CER строк — для каждой строки расшифровки ближайшая строка вывода, правки на знак.
Регистр не важен: приложение всё равно приводит прописные к обычному виду перед переводом.
"""
import os, re, sys, json, time, argparse, unicodedata
from collections import Counter

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B = os.path.join(R, 'bench', 'ocr')


def gt():
    out, cur = {}, None
    for line in open(os.path.join(B, 'gt.txt'), encoding='utf-8'):
        line = line.rstrip('\n')
        if line.startswith('# '):
            cur = line[2:].strip(); out[cur] = []
        elif line.strip():
            out[cur].append(line.strip())
    return out


def words(s, strip_accents=False):
    s = unicodedata.normalize('NFC', s.lower())
    if strip_accents:
        s = ''.join(c for c in unicodedata.normalize('NFD', s) if unicodedata.category(c) != 'Mn')
    return re.findall(r'[\w$]+', s)


def lev(a, b):
    if len(a) < len(b): a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def score(ref_lines, got_text):
    ref = ' '.join(ref_lines)
    r1, g1 = Counter(words(ref)), Counter(words(got_text))
    r2, g2 = Counter(words(ref, True)), Counter(words(got_text, True))
    hit = sum((r1 & g1).values()); hit2 = sum((r2 & g2).values())
    got_lines = [l.lower() for l in got_text.split('\n') if l.strip()] or ['']
    ed = sum(min(lev(l.lower(), g) for g in got_lines) for l in ref_lines)
    return dict(n=sum(r1.values()), hit=hit, hit2=hit2, got=sum(g1.values()),
                ed=ed, chars=sum(len(l) for l in ref_lines))


def total(rows):
    s = {k: sum(r[k] for r in rows) for k in ('n', 'hit', 'hit2', 'got', 'ed', 'chars')}
    return dict(words=s['hit'] / s['n'], words_na=s['hit2'] / s['n'],
                prec=s['hit'] / max(1, s['got']), cer=s['ed'] / s['chars'], n=s['n'])


def to_json(path, what, rows, texts, extra=None):
    """По снимкам — только числа и sha1 вывода: по хэшу видно, изменилось ли прочитанное, а самого
    текста в файле нет (эталоны проверки качества лежат и в git)."""
    import hashlib
    res = [dict({k: r[k] for k in ('id', 'n', 'hit', 'hit2', 'got', 'ed', 'chars')},
                sha=hashlib.sha1(texts.get(r['id'], '').encode('utf-8')).hexdigest()) for r in rows]
    out = dict(what=what, results=res, total=total(rows))
    if extra:
        out.update(extra)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)


def phone_like(img, src_max):
    """Снимок так, как его отдаёт приложению jpegOf: уменьшение до src_max по длинной стороне
    и JPEG с качеством 75."""
    import io
    import numpy as np
    from PIL import Image
    im = Image.fromarray(img)
    if max(im.size) > src_max:
        r = src_max / max(im.size); im = im.resize((round(im.size[0] * r), round(im.size[1] * r)), Image.BILINEAR)
    b = io.BytesIO(); im.save(b, 'JPEG', quality=75)
    return np.asarray(Image.open(io.BytesIO(b.getvalue())).convert('RGB'))


def run_ref(det, rec, det_max, ids, show=False, src_max=0, kind=None):
    sys.path.insert(0, os.path.join(R, 'tools'))
    import ocr_ref
    o = ocr_ref.Ocr(det, rec, det_max, kind)
    rows, ms, texts = [], [], {}
    G = gt()
    for i in ids:
        img = ocr_ref.load_rgb(os.path.join(B, 'photos', i + '.jpg'))
        if src_max:
            img = phone_like(img, src_max)
        t0 = time.time(); txt, st = o.read(img); ms.append((time.time() - t0) * 1000)
        texts[i] = (txt, ocr_ref.text_of(*o.last))
        r = score(G[i], txt); r['id'] = i; rows.append(r)
        if show:
            print(f'--- {i}: слова {r["hit"]}/{r["n"]}\n{txt}')
    return rows, ms, texts


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--got', help='каталог с выводом приложения: pNN.txt')
    ap.add_argument('--only', help='один вариант: имя из списка')
    ap.add_argument('--show', action='store_true')
    ap.add_argument('--save', help='сохранить вывод варианта --only в каталог')
    ap.add_argument('--src-max', type=int, default=0, help='уменьшить снимок, как jpegOf (0 — как есть)')
    ap.add_argument('--ref', help='с --got: каталог эталона (tools/ocr_eval.py --only … --save) — сверка строка в строку')
    ap.add_argument('--models', help='каталог с det.onnx и rec.onnx (словарь — вшитый в rec.onnx), как в приложении')
    ap.add_argument('--kind', default='v6', help='с --models: разбор карты детектора v6 или v5')
    ap.add_argument('--json', help='записать результат по снимкам (числа и sha1 вывода, без текста)')
    a = ap.parse_args()
    G = gt(); ids = sorted(G)
    if a.got:
        rows = []
        for i in ids:
            p = os.path.join(a.got, i + '.txt')
            txt = open(p, encoding='utf-8').read() if os.path.exists(p) else ''
            r = score(G[i], txt); r['id'] = i; rows.append(r)
        t = total(rows)
        got_texts = {}
        for i in ids:
            p = os.path.join(a.got, i + '.txt')
            got_texts[i] = open(p, encoding='utf-8').read() if os.path.exists(p) else ''
        print(f'{a.got}: слова {t["words"]:.1%} · без диакр. {t["words_na"]:.1%} · точность {t["prec"]:.1%} · CER строк {t["cer"]:.1%} (слов {t["n"]})')
        if a.ref:
            same, diff = 0, []
            for i in ids:
                g = os.path.join(a.got, i + '.txt'); r = os.path.join(a.ref, i + '.txt')
                gt_ = open(g, encoding='utf-8').read() if os.path.exists(g) else None
                rt_ = open(r, encoding='utf-8').read() if os.path.exists(r) else None
                if gt_ is not None and gt_ == rt_: same += 1
                else: diff.append(i)
            if a.json:
                to_json(a.json, 'got ' + a.got, rows, got_texts, dict(same_as_ref=same, differ=diff))
            print(f'сверка с эталоном {a.ref}: совпало строка в строку {same} из {len(ids)}' + (f'; расходятся: {" ".join(diff)}' if diff else ''))
        if a.json and not a.ref:
            to_json(a.json, 'got ' + a.got, rows, got_texts)
        sys.exit()
    if a.models:
        rows, ms, texts = run_ref(a.models, a.models, 960, ids, a.show, a.src_max, kind=a.kind)
        t = total(rows); ms.sort()
        print(f'{a.models}: слова {t["words"]:.1%} · без диакр. {t["words_na"]:.1%} · точность {t["prec"]:.1%} · '
              f'CER строк {t["cer"]:.1%} (слов {t["n"]}) · стол {ms[len(ms)//2]:.0f} мс (медиана)', flush=True)
        if a.json:
            to_json(a.json, 'models ' + a.models, rows, {i: v[0] for i, v in texts.items()})
        if a.save:
            os.makedirs(a.save, exist_ok=True)
            for i, (txt, para) in texts.items():
                open(os.path.join(a.save, i + '.txt'), 'w', encoding='utf-8').write(txt)
                open(os.path.join(a.save, i + '.para'), 'w', encoding='utf-8').write(para)
        sys.exit()
    M = os.path.join(R, 'models', 'ocr-cand')
    variants = {
        'v5 mobile · 960':            (f'{M}/v5m', f'{M}/v5m', 960),
        'v6 small · 960':             (f'{M}/v6s', f'{M}/v6s', 960),
        'v6 tiny det + small rec · 960': (f'{M}/v6t', f'{M}/v6s', 960),
        'v6 small · 1280':            (f'{M}/v6s', f'{M}/v6s', 1280),
        'v5 det + v6 small rec · 960': (f'{M}/v5m', f'{M}/v6s', 960),
        'v6 small det + v5 rec · 960': (f'{M}/v6s', f'{M}/v5m', 960),
        'v6 tiny det + v5 rec · 960':  (f'{M}/v6t', f'{M}/v5m', 960),
    }
    for name, (det, rec, dm) in variants.items():
        if a.only and a.only != name:
            continue
        rows, ms, texts = run_ref(det, rec, dm, ids, a.show, a.src_max)
        t = total(rows)
        ms.sort()
        print(f'{name + (f" · снимок {a.src_max}" if a.src_max else ""):32s} слова {t["words"]:6.1%} · без диакр. {t["words_na"]:6.1%} · точность {t["prec"]:6.1%} · '
              f'CER строк {t["cer"]:6.1%} · стол {ms[len(ms)//2]:.0f} мс (медиана)', flush=True)
        if a.save:
            os.makedirs(a.save, exist_ok=True)
            for i, (txt, para) in texts.items():
                open(os.path.join(a.save, i + '.txt'), 'w', encoding='utf-8').write(txt)
                open(os.path.join(a.save, i + '.para'), 'w', encoding='utf-8').write(para)
            json.dump(rows, open(os.path.join(a.save, 'rows.json'), 'w'), ensure_ascii=False, indent=0)
