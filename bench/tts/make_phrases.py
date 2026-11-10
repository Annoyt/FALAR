#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Фразы стенда озвучки (TtsBench): по 30 русских и португальских предложений носителей из проверочного набора Tatoeba
(data/mt_test/tatoeba.json — CC-BY 2.0 FR, публичный), 20–90 знаков, без цифр (числа — отдельная проверка), с
постоянным зерном: прогоны разных движков и дней сравнимы.

  python3 bench/tts/make_phrases.py > bench/tts/phrases.tsv
"""
import json
import random
import re
from pathlib import Path

R = Path(__file__).resolve().parent.parent.parent
d = json.loads((R / "data/mt_test/tatoeba.json").read_text(encoding="utf-8"))
ok = lambda s: 20 <= len(s) <= 90 and not re.search(r"\d", s)
rng = random.Random(20261110)
ru = sorted({p["ru"] for p in d["ru_to_pt"] if ok(p["ru"])})
pt = sorted({p["pt"] for p in d["pt_to_ru"] if ok(p["pt"])})
print("# Tatoeba (https://tatoeba.org), CC-BY 2.0 FR — предложения носителей из data/mt_test/tatoeba.json; bench/tts/make_phrases.py")
for lang, pool in (("ru", ru), ("pt", pt)):
    for s in rng.sample(pool, 30):
        print(f"{lang}\t{s}")
