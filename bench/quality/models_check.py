#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Те ли модели лежат в models/, что уедут на телефон: размер и sha256 против models/manifest.json.

Проверка качества на столе мерит то, что лежит на диске; если файл модели разошёлся с манифестом, она
мерила бы не то, что получит пользователь. sha256 больших файлов запоминается по (путь, размер, время
изменения) в ~/.cache/falar-stand/sha256.json, чтобы не считать 1,4 ГБ при каждом запуске.

    python3 bench/quality/models_check.py <каталог models> mt/ asr_multi/
"""
import hashlib
import json
import os
import sys


def main():
    models, prefixes = sys.argv[1], tuple(sys.argv[2:])
    man = json.load(open(os.path.join(models, "manifest.json"), encoding="utf-8"))
    cache_path = os.path.join(os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache")), "falar-stand", "sha256.json")
    try:
        cache = json.load(open(cache_path))
    except (OSError, ValueError):
        cache = {}
    bad, n = [], 0
    for f in man["files"]:
        if not f["path"].startswith(prefixes):
            continue
        n += 1
        p = os.path.join(models, f["path"])
        if not os.path.exists(p):
            bad.append(f"нет файла {f['path']}")
            continue
        st = os.stat(p)
        if st.st_size != f["size"]:
            bad.append(f"{f['path']}: размер {st.st_size}, в манифесте {f['size']}")
            continue
        k = f"{os.path.realpath(p)}|{st.st_size}|{int(st.st_mtime)}"
        if k not in cache:
            h = hashlib.sha256()
            with open(p, "rb") as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b""):
                    h.update(chunk)
            cache[k] = h.hexdigest()
        if cache[k] != f["sha256"]:
            bad.append(f"{f['path']}: sha256 не совпадает с манифестом")
    os.makedirs(os.path.dirname(cache_path), exist_ok=True)
    json.dump(cache, open(cache_path, "w"))
    if not n:
        bad.append(f"в манифесте нет файлов {', '.join(prefixes)}")
    for b in bad:
        print("  модели: " + b)
    print(f"модели: {n - len(bad)} из {n} файлов совпадают с манифестом" if not bad else "")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
