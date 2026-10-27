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
    ap.add_argument("--context", choices=["none", "app", "empty"], default="none", help="hymt: как у прогона (mt_bench --context)")
    ap.add_argument("--setfile", default="", help="набор прогона — для --context app (прошлые реплики, поле ctx)")
    a = ap.parse_args()
    if a.ordered and len(a.files) > 1:
        sys.exit("--ordered: по одному файлу на запуск (иначе история кэша второго файла другая)")
    mt_bench.GPU = a.gpu
    if a.gpu and mt_bench.PROFILE == "poco-cpu":
        sys.exit("--gpu: нужен POCO_PROFILE=poco-gpu (см. mt_bench.py)")
    engine = {"nllb": lambda: mt_bench.NLLBEngine(),
              "gemma": lambda: mt_bench.GemmaEngine(mt_bench.load_glossary()),
              "hymt": lambda: mt_bench.HyMtEngine(a.context)}[a.engine]()
    bad = 0
    for f in a.files:
        d = json.loads(Path(f).read_text())
        s = d["summary"]
        if s.get("engine") != engine.name:
            sys.exit(f"{Path(f).name}: движок {s.get('engine')}, а не {engine.name}")
        src, tgt = s["direction"].split("2")
        res = d["results"]
        ctx = [[] for _ in res]
        if a.setfile:   # контекст — по месту фразы в наборе: одна и та же фраза бывает в разных разговорах
            key = "pt_to_ru" if s["direction"] == "pt2ru" else "ru_to_pt"
            items = json.loads(Path(a.setfile).read_text())[key]
            assert len(items) == len(res) and all(it[src] == r["src"] for it, r in zip(items, res)), \
                f"{Path(f).name}: порядок фраз разошёлся с набором"
            ctx = [it.get("ctx", []) for it in items]
        ix = list(range(min(a.n, len(res)))) if a.ordered else random.Random(a.seed).sample(range(len(res)), min(a.n, len(res)))
        if getattr(engine, "wants_ctx", False):
            same = sum(engine.translate(res[i]["src"], src, tgt, ctx=ctx[i]) == res[i]["hyp"] for i in ix)
        else:
            same = sum(engine.translate(res[i]["src"], src, tgt) == res[i]["hyp"] for i in ix)
        rs = ix
        bad += same != len(rs)
        print(f"{Path(f).name}: совпало {same} из {len(rs)}", flush=True)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
