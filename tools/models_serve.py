#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Локальный источник моделей для стенда: раздаёт файлы из манифеста по имени, как их ждёт
приложение с `--es modelsbase http://127.0.0.1:8765` (адрес Hugging Face заменяется на
base/<имя файла>). Понимает Range (докачка), умеет ломаться по команде — чтобы проверять
на телефоне обрыв, битый файл и 404 без Hugging Face и без ожидания живых сбоев.

  python3 tools/models_serve.py                       # models/ + repo_path из манифеста, порт 8765
  python3 tools/models_serve.py --corrupt gtcrn_simple.onnx        # этот файл отдаётся с испорченным байтом
  python3 tools/models_serve.py --drop encoder.int8.onnx:3000000   # первый запрос обрывается после N байт
  python3 tools/models_serve.py --missing common_words.txt         # 404
  python3 tools/models_serve.py --slow 20                          # пауза мс на каждые 64 КБ (для «стоп» на середине)

Архивы голосов берутся из models/.zips/<имя>.zip (скачать с HF один раз). Телефон достаёт
сервер через `adb reverse tcp:8765 tcp:8765`. Каждый запрос печатается: имя, Range, отдано байт.
"""
import argparse, json, os, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def build_map():
    m = json.load(open(os.path.join(ROOT, "models", "manifest.json"), encoding="utf-8"))
    files = {}
    for e in m["files"]:
        src = e["source"]; name = src["file"] if src["type"] == "hf" else src["url"].rsplit("/", 1)[-1]
        files[name] = os.path.join(ROOT, e["repo_path"]) if e.get("repo_path") else os.path.join(ROOT, "models", e["path"])
    for a in m["archives"]:
        name = a["source"]["file"]; files[name] = os.path.join(ROOT, "models", ".zips", name)
    return files

class H(BaseHTTPRequestHandler):
    files = {}; corrupt = set(); missing = set(); drop = {}; dropped = set(); slow = 0
    def log_message(self, *a): pass
    def do_GET(self):
        name = self.path.lstrip("/").split("?")[0]
        p = self.files.get(name)
        if p is None or name in self.missing or not os.path.isfile(p):
            self.send_response(404); self.end_headers(); print(f"{name}: 404", flush=True); return
        size = os.path.getsize(p); rng = self.headers.get("Range"); start = 0; code = 200
        if rng and rng.startswith("bytes="):
            start = int(rng[6:].split("-")[0])
            if start >= size: self.send_response(416); self.end_headers(); print(f"{name}: 416 {rng}", flush=True); return
            code = 206
        self.send_response(code)
        self.send_header("Content-Length", str(size - start)); self.send_header("Content-Type", "application/octet-stream")
        if code == 206: self.send_header("Content-Range", f"bytes {start}-{size - 1}/{size}")
        self.end_headers()
        cut = None
        if name in self.drop and name not in self.dropped: cut = self.drop[name]; self.dropped.add(name)
        sent = 0
        try:
            with open(p, "rb") as f:
                f.seek(start)
                while True:
                    n = 1 << 16
                    if cut is not None and start + sent + n > cut: n = max(0, cut - start - sent)
                    if n == 0: break
                    c = f.read(n)
                    if not c: break
                    if name in self.corrupt and start + sent == 0: c = bytes([c[0] ^ 0xFF]) + c[1:]   # один байт — хэш не сойдётся
                    self.wfile.write(c); sent += len(c)
                    if self.slow: time.sleep(self.slow / 1000)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            if cut is not None:                       # обрыв: закрыть сокет, не дописав Content-Length
                try: self.connection.shutdown(2); self.connection.close()
                except Exception: pass
        print(f"{name}: {code} {rng or ''} отдано {sent}{' ОБРЫВ' if cut is not None else ''}{' БИТЫЙ' if name in self.corrupt else ''}", flush=True)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--corrupt", action="append", default=[]); ap.add_argument("--missing", action="append", default=[])
    ap.add_argument("--drop", action="append", default=[], help="имя:байт"); ap.add_argument("--slow", type=int, default=0)
    a = ap.parse_args()
    H.files = build_map(); H.corrupt = set(a.corrupt); H.missing = set(a.missing); H.slow = a.slow
    H.drop = {k: int(v) for k, v in (d.split(":") for d in a.drop)}
    absent = [n for n, p in H.files.items() if not os.path.isfile(p)]
    if absent: print("нет локально (будет 404):", ", ".join(absent), file=sys.stderr)
    print(f"источник моделей на 127.0.0.1:{a.port}, файлов {len(H.files) - len(absent)}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", a.port), H).serve_forever()

if __name__ == "__main__": main()
