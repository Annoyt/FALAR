#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Собирает каталог models/ по манифесту: что есть и совпало по sha256 — оставляет, чего нет —
скачивает из источника (Hugging Face или прямой URL), архивы распаковывает и проверяет.

  python3 tools/models_fetch.py            # только обязательное (tier=core)
  python3 tools/models_fetch.py --all      # плюс необязательное: LLM, отпечаток голоса, корпус
  python3 tools/models_fetch.py --check    # ничего не качать, только сверить, что лежит

Раскладка на диске та же, что на телефоне (см. device_dir в манифесте), поэтому после этого
bench/apk/push_models.sh заливает каталог как есть. Приложение с 0.21 качает по тому же манифесту само.
"""
import hashlib, json, os, sys, urllib.request, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAN = os.path.join(ROOT, "models", "manifest.json")
OUT = os.path.join(ROOT, "models")

def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""): h.update(c)
    return h.hexdigest()

def src_url(s):
    if s["type"] == "hf": return f"https://huggingface.co/{s['repo']}/resolve/{s.get('revision', 'main')}/{s['file']}"
    return s["url"]

def fetch(u, dst, size):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    tmp = dst + ".part"; have = os.path.getsize(tmp) if os.path.exists(tmp) else 0
    req = urllib.request.Request(u, headers={"User-Agent": "agenttranslator-models-fetch"})
    if have: req.add_header("Range", f"bytes={have}-")
    with urllib.request.urlopen(req) as r, open(tmp, "ab" if have and r.status == 206 else "wb") as f:
        if have and r.status != 206: have = 0
        done = have
        while True:
            c = r.read(1 << 20)
            if not c: break
            f.write(c); done += len(c)
            print(f"\r  {done * 100 // max(1, size):3d}% {dst[len(OUT) + 1:]}", end="", flush=True)
    print()
    os.replace(tmp, dst)

def main():
    want_all = "--all" in sys.argv; check_only = "--check" in sys.argv
    m = json.load(open(MAN, encoding="utf-8"))
    bad = 0
    for e in m["files"]:
        if e["tier"] != "core" and not want_all: continue
        # затравка и корпус лежат в самом репозитории (repo_path), остальное — в models/ под путём устройства
        dst = os.path.join(ROOT, e["repo_path"]) if e.get("repo_path") else os.path.join(OUT, e["path"])
        ok = os.path.exists(dst) and os.path.getsize(dst) == e["size"] and sha256(dst) == e["sha256"]
        if ok: print("  ок  ", e["path"]); continue
        if check_only: print("  НЕТ ", e["path"]); bad += 1; continue
        print("  качаю", e["path"], "из", src_url(e["source"]))
        fetch(src_url(e["source"]), dst, e["size"])
        if sha256(dst) != e["sha256"]: print("  ХЭШ НЕ СОШЁЛСЯ:", e["path"]); os.remove(dst); bad += 1
    for a in m["archives"]:
        if a["tier"] != "core" and not want_all: continue
        good = all(os.path.exists(os.path.join(OUT, c["path"])) and sha256(os.path.join(OUT, c["path"])) == c["sha256"] for c in a["check"])
        if good: print("  ок  ", a["path"] + "/"); continue
        if check_only: print("  НЕТ ", a["path"] + "/"); bad += 1; continue
        z = os.path.join(OUT, a["path"] + ".zip")
        print("  качаю", a["path"] + ".zip", "из", src_url(a["source"]))
        fetch(src_url(a["source"]), z, a["size"])
        if sha256(z) != a["sha256"]: print("  ХЭШ НЕ СОШЁЛСЯ:", z); os.remove(z); bad += 1; continue
        with zipfile.ZipFile(z) as zf: zf.extractall(os.path.join(OUT, a["unpack_to"]))
        os.remove(z)
        for c in a["check"]:
            if sha256(os.path.join(OUT, c["path"])) != c["sha256"]: print("  ХЭШ НЕ СОШЁЛСЯ после распаковки:", c["path"]); bad += 1
    print("готово" if not bad else f"проблем: {bad}")
    sys.exit(1 if bad else 0)

if __name__ == "__main__": main()
