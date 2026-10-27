#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Перепроверка прогона mt_bench: заново перевести случайную выборку фраз из файла прогона и сверить
с записанным переводом. На ПК владельца (андервольт) ошибки бывают тихими — неверный вывод без сбоя, —
поэтому после долгого прогона выборка должна совпасть целиком. Печатает только счёт, без текстов:
годится и для частных наборов. Код выхода 1, если совпало не всё.

llama.cpp переиспользует кэш общего начала с прошлой подсказкой, и на видеокарте от этого чуть меняются
числа — жадный перевод случайной выборки может разойтись на редкой фразе без всякой поломки. --ordered
берёт первые N фраз в порядке прогона со свежей загрузки — история кэша та же, совпасть должно всё
(по одному файлу на запуск).
Gemma переводит с температурой 0,1 и случайным зерном — её прогон не повторяется в принципе.

    POCO_PROFILE=poco-gpu PYTHONPATH=~/tools/llama-cuda .venv/bin/python tools/mt_recheck.py --engine hymt --gpu ФАЙЛ…
"""
import argparse
import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import mt_bench  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--engine", required=True, choices=["nllb", "gemma", "hymt"])
    ap.add_argument("--gpu", action="store_true", help="как прогон с --gpu (сверять на том же устройстве)")
    ap.add_argument("--n", type=int, default=60)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--ordered", action="store_true", help="первые N фраз в порядке прогона, а не случайные")
    a = ap.parse_args()
    if a.ordered and len(a.files) > 1:
        sys.exit("--ordered: по одному файлу на запуск (иначе история кэша второго файла другая)")
    mt_bench.GPU = a.gpu
    if a.gpu and mt_bench.PROFILE == "poco-cpu":
        sys.exit("--gpu: нужен POCO_PROFILE=poco-gpu (см. mt_bench.py)")
    engine = {"nllb": lambda: mt_bench.NLLBEngine(),
              "gemma": lambda: mt_bench.GemmaEngine(mt_bench.load_glossary()),
              "hymt": lambda: mt_bench.HyMtEngine()}[a.engine]()
    bad = 0
    for f in a.files:
        d = json.loads(Path(f).read_text())
        s = d["summary"]
        if s.get("engine") != engine.name:
            sys.exit(f"{Path(f).name}: движок {s.get('engine')}, а не {engine.name}")
        src, tgt = s["direction"].split("2")
        rs = d["results"][:a.n] if a.ordered else random.Random(a.seed).sample(d["results"], min(a.n, len(d["results"])))
        same = sum(engine.translate(r["src"], src, tgt) == r["hyp"] for r in rs)
        bad += same != len(rs)
        print(f"{Path(f).name}: совпало {same} из {len(rs)}", flush=True)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
