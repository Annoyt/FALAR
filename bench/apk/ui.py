#!/usr/bin/env python3
"""Дерево экрана (uiautomator dump) для стендовых скриптов: найти вид по id, тексту или подписи
(content-desc), его границы и центр, включён ли он и выбран ли; все тексты экрана; дети вида.

  python3 ui.py find  <xml> (--rid R | --text T | --desc D | --class C) [--prefix | --contains] [--all]
      → «x0 y0 x1 y1 cx cy enabled selected» на каждое совпадение (без --all — первое)
  python3 ui.py texts <xml>          → все непустые text и content-desc, по одному в строке
  python3 ui.py kids  <xml> --rid R  → «x0 y0 x1 y1 класс» прямых детей вида
  python3 ui.py sub   <xml> --rid R  → тексты внутри вида, по одному в строке
  python3 ui.py up    <xml> --text T [--levels N]  → тексты внутри N-го предка вида (по умолчанию 1):
      строка модуля, карточка реплики — всё, что рядом с найденной подписью

Текст сравнивается без учёта регистра: кнопки диалогов Android пишет прописными. Невидимых видов
(INVISIBLE, GONE) в дереве нет — uiautomator их не отдаёт, и «не нашлось» значит «не видно».
"""
import re
import sys
import xml.etree.ElementTree as ET


def nodes(path):
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return None, []
    return root, list(root.iter('node'))


def box(n):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds', ''))
    return tuple(map(int, m.groups())) if m else None


def match(val, want, how):
    v, w = (val or '').lower(), want.lower()
    return v.startswith(w) if how == 'prefix' else w in v if how == 'contains' else v == w


def main(a):
    if len(a) < 2:
        print(__doc__); return 2
    cmd, path, rest = a[0], a[1], a[2:]
    root, ns = nodes(path)
    if cmd == 'texts':
        for n in ns:
            for k in ('text', 'content-desc'):
                if n.get(k):
                    print(n.get(k).replace('\n', ' '))
        return 0
    how = 'prefix' if '--prefix' in rest else 'contains' if '--contains' in rest else 'exact'
    key = None
    for flag, attr in (('--rid', 'resource-id'), ('--text', 'text'), ('--desc', 'content-desc'), ('--class', 'class')):
        if flag in rest:
            key = (attr, rest[rest.index(flag) + 1])
    if key is None:
        print(__doc__); return 2
    attr, want = key
    if attr == 'resource-id' and ':id/' not in want:
        want = 'app.falar:id/' + want
    hits = [n for n in ns if (n.get(attr) == want if attr == 'resource-id' else match(n.get(attr), want, how))]
    if cmd == 'up':
        if not hits: return 1
        parent = {c: p for p in root.iter('node') for c in p.findall('node')}
        n = hits[0]
        for _ in range(int(rest[rest.index('--levels') + 1]) if '--levels' in rest else 1):
            n = parent.get(n, n)
        for c in n.iter('node'):
            if c.get('text'): print(c.get('text').replace('\n', ' '))
        return 0
    if cmd == 'sub':
        if not hits: return 1
        for c in hits[0].iter('node'):
            if c.get('text'): print(c.get('text').replace('\n', ' '))
        return 0
    if cmd == 'kids':
        if not hits: return 1
        for c in hits[0].findall('node'):
            b = box(c)
            if b: print(*b, c.get('class', ''))
        return 0
    if cmd != 'find':
        print(__doc__); return 2
    for n in hits if '--all' in rest else hits[:1]:
        b = box(n)
        if b:
            print(*b, (b[0] + b[2]) // 2, (b[1] + b[3]) // 2, n.get('enabled', ''), n.get('selected', ''))
    return 0 if hits else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
