#!/usr/bin/env python3
"""Задания для стенда Gemini Nano и разбор его ответов в формат прогонов mt_metrics.py.

  nano_jobs.py make --task tr|pe --dir pt2ru|ru2pt [--set tatoeba|situations|all] [--n N] [--seed S] OUT.tsv
  nano_jobs.py collect --task tr|pe --dir pt2ru|ru2pt OUT.tsv RUN.json

tr — Nano переводит сам; pe — Nano правит черновик приложения (OPUS-MT из bench/quality/baseline/mt_<dir>.json,
это вывод настоящего Engine.translate). Только публичные наборы: Tatoeba (data/mt_test/tatoeba.json) и
40 «ситуаций» (data/test_set.json) — никаких разговоров владельца. Запросы — по-английски: так маленькие
модели держат инструкцию надёжнее, а язык ответа задан явно.
"""
import argparse
import json
import random
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
LANG = {"pt": "Brazilian Portuguese", "ru": "Russian"}
SHORT = {"pt": "Portuguese", "ru": "Russian"}


def pairs(direction):
    """Пары из прогона приложения: src, ref, hyp (черновик OPUS), set, id, added."""
    base = json.loads((ROOT / f"bench/quality/baseline/mt_{direction}.json").read_text())
    return base["results"]


def prompt(task, direction, src, draft=None):
    s, t = direction[:2], direction[3:]
    if task == "tr":
        return (f"Translate the following {LANG[s]} text into {LANG[t]}. Reply with the {SHORT[t]} translation only, "
                f"without quotes or explanations.\n\n{SHORT[s]}: {src}\n{SHORT[t]}:")
    return (f"You are a professional {SHORT['pt']}-{SHORT['ru']} translator. Below is a {LANG[s]} sentence and a draft "
            f"{LANG[t]} translation made by a machine. If the draft is correct and natural, repeat it unchanged. "
            f"Otherwise fix it. Reply with the final {SHORT[t]} translation only.\n\n"
            f"{SHORT[s]}: {src}\nDraft {SHORT[t]}: {draft}\nFinal {SHORT[t]}:")


def esc(s):
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


def unesc(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            d = s[i + 1]
            out.append("\n" if d == "n" else "\t" if d == "t" else d)
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def clean(text, direction):
    """Ответ модели → одна строка перевода: без меток «Russian:», кавычек и пояснений после пустой строки."""
    t = text.strip()
    t = re.split(r"\n\s*\n", t)[0].strip()                     # пояснения после пустой строки
    t = re.sub(r"^(\*\*)?(final |draft )?(russian|portuguese|translation)(\*\*)?\s*:\s*", "", t, flags=re.I)
    if len(t) >= 2 and t[0] in "\"«“'" and t[-1] in "\"»”'":
        t = t[1:-1].strip()
    return t


def make(a):
    rows = [r for r in pairs(a.dir) if a.set == "all" or r["set"] == a.set]
    if a.n and a.n < len(rows):
        rows = random.Random(a.seed).sample(rows, a.n)
    with open(a.out, "w") as f:
        for r in rows:
            f.write(f"{r['set']}:{r['id']}\t{esc(prompt(a.task, a.dir, r['src'], r['hyp']))}\n")
    print(f"{len(rows)} заданий → {a.out}")


def collect(a):
    by_id = {f"{r['set']}:{r['id']}": r for r in pairs(a.dir)}
    res, errors, codes = [], 0, {}
    for line in Path(a.out).read_text().splitlines():
        p = line.split("\t", 4)
        if len(p) < 5:
            continue
        key, ms, code, fin, text = p
        r = by_id[key]
        if code != "0":
            errors += 1
            codes[code] = codes.get(code, 0) + 1
            hyp = ""
        else:
            hyp = clean(unesc(text), a.dir)
        res.append({"id": r["id"], "set": r["set"], "src": r["src"], "ref": r["ref"], "hyp": hyp,
                    "added": r.get("added", ""), "sec": int(ms) / 1000, "raw": unesc(text), "finish": fin,
                    "draft": r["hyp"]})
    lat = sorted(x["sec"] for x in res if x["hyp"])
    summary = {"engine": f"gemini-nano-{a.task}", "direction": a.dir, "n": len(res), "errors": errors,
               "error_codes": codes, "unchanged_vs_draft": sum(x["hyp"] == x["draft"] for x in res),
               "lat_avg": round(sum(lat) / len(lat), 2) if lat else None,
               "lat_p50": lat[len(lat) // 2] if lat else None,
               "lat_p95": lat[int(len(lat) * 0.95)] if lat else None}
    Path(a.run).write_text(json.dumps({"summary": summary, "results": res}, ensure_ascii=False, indent=1))
    print(json.dumps(summary, ensure_ascii=False))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("make")
    m.add_argument("--task", choices=["tr", "pe"], required=True)
    m.add_argument("--dir", choices=["pt2ru", "ru2pt"], required=True)
    m.add_argument("--set", default="tatoeba", choices=["tatoeba", "situations", "all"])
    m.add_argument("--n", type=int, default=0)
    m.add_argument("--seed", type=int, default=20261003)
    m.add_argument("out")
    c = sub.add_parser("collect")
    c.add_argument("--task", choices=["tr", "pe"], required=True)
    c.add_argument("--dir", choices=["pt2ru", "ru2pt"], required=True)
    c.add_argument("out")
    c.add_argument("run")
    a = ap.parse_args()
    {"make": make, "collect": collect}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
