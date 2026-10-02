#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Вердикт проверки качества: свежий прогон bench/quality/gate.sh против эталонного (bench/quality/baseline).

Перевод и распознавание детерминированы: тот же код и те же модели дают те же строки. Поэтому сначала —
изменилось ли хоть что-то; если нет — «без изменений», считать нечего. Если да — парный бутстрэп по
общим фразам (tools/mt_metrics.py): разница, её 95 % интервал и p.

«Хуже» — разница значима (p < 0,05) и не меньше порога: chrF −0,5, COMET −0,002, WER +0,5 п. п. на
наборе Tatoeba. Пороги — по замеру 02.10: отказ от луча в переводе стоил −1,6 chrF и −0,003 COMET
(results/2026-10-02-mt-metrics.md), такое проверка обязана ловить. 40 фраз из ситуаций показываются,
но не решают: интервал на них ±7–8 chrF.

    python3 bench/quality/verdict.py --base bench/quality/baseline --run <каталог прогона> [--report файл.md]

Код выхода: 0 — не хуже, 1 — хуже, 2 — нечего сравнивать.
"""
import argparse
import json
import os
import sys

R = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(R, "tools"))
import mt_metrics  # noqa: E402

TH = {"chrF": 0.5, "COMET": 0.002, "WER": 0.5}
P = 0.05
N_BOOT = 1000
DIRS = {"pt2ru": "pt → ru", "ru2pt": "ru → pt"}


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def fmt(m, x):
    f = ".4f" if m == "COMET" else ".2f"
    return (f"{m} {x['A']:{f}} → {x['B']:{f}} (Δ {x['delta']:+{f}}, 95 % [{x['ci95'][0]:+{f}}; "
            f"{x['ci95'][1]:+{f}}], p = {x['p']:.3f})").replace(".", ",")


def judge(rows, gate_set):
    """rows — результат mt_metrics.compare; решает набор gate_set (или единственный)."""
    g = rows.get(gate_set) or next(iter(rows.values()), None)
    if not g:
        return "нечего сравнивать"
    worse = [m for m in ("chrF", "COMET") if m in g and g[m]["delta"] <= -TH[m] and g[m]["p"] < P]
    better = [m for m in ("chrF", "COMET") if m in g and g[m]["delta"] >= TH[m] and g[m]["p"] < P]
    return "хуже" if worse else "лучше" if better else "изменилось, но не хуже"


def mt(base_dir, run_dir, out):
    worst = False
    for d, name in DIRS.items():
        bp, cp = os.path.join(base_dir, f"mt_{d}.json"), os.path.join(run_dir, f"mt_{d}.json")
        if not (os.path.exists(bp) and os.path.exists(cp)):
            out.append(f"- перевод {name}: нет {'эталона' if not os.path.exists(bp) else 'прогона'} — пропущено")
            continue
        B, C = load(bp)["results"], load(cp)["results"]
        bmap = {(mt_metrics.set_of(r), r["src"]): r for r in B}
        pairs = [(bmap[(mt_metrics.set_of(r), r["src"])], r) for r in C if (mt_metrics.set_of(r), r["src"]) in bmap]
        changed = [(b, c) for b, c in pairs if b["hyp"] != c["hyp"]]
        if len(pairs) < len(C) or len(pairs) < len(B):
            out.append(f"- перевод {name}: наборы разошлись с эталоном ({len(pairs)} общих из {len(B)} / {len(C)}) — "
                       f"обновите эталон (gate.sh --accept) после проверки")
        if not changed:
            out.append(f"- перевод {name}: **без изменений** — {len(pairs)} из {len(pairs)} переводов те же")
            continue
        rows = mt_metrics.compare(bp, cp)
        v = judge(rows, "tatoeba")
        worst |= v == "хуже"
        out.append(f"- перевод {name}: **{v}** — изменилось {len(changed)} переводов из {len(pairs)}")
        for set_name, x in rows.items():
            parts = "; ".join(fmt(m, x[m]) for m in ("chrF", "COMET") if m in x)
            out.append(f"  - {set_name} (n = {x['n']}): {parts}")
        key = (lambda bc: bc[1]["comet"] - bc[0]["comet"]) if all("comet" in b and "comet" in c for b, c in changed) else None
        if key:
            out.append("  - сильнее всего просели (COMET):")
            for b, c in sorted(changed, key=key)[:5]:
                drop = f"{b['comet']:.3f} → {c['comet']:.3f}".replace(".", ",")
                out.append(f"    - «{c['src']}» — было «{b['hyp']}», стало «{c['hyp']}» (эталон «{c['ref']}»; {drop})")
    return worst


def wer_compare(pairs):
    """Парный бутстрэп по записям: Δ WER, п. п. (B − A), 95 % интервал, p."""
    import numpy as np
    ea = np.array([a["errors"] for a, _ in pairs], dtype=float)
    eb = np.array([b["errors"] for _, b in pairs], dtype=float)
    w = np.array([a["words"] for a, _ in pairs], dtype=float)
    va, vb = 100 * ea.sum() / w.sum(), 100 * eb.sum() / w.sum()
    idx = np.random.default_rng(12345).integers(0, len(pairs), (N_BOOT, len(pairs)))
    deltas = 100 * (eb[idx].sum(1) - ea[idx].sum(1)) / w[idx].sum(1)
    ds = np.sort(deltas)
    p = min(1.0, 2 * min((deltas <= 0).mean(), (deltas >= 0).mean()))
    return {"A": va, "B": vb, "delta": vb - va, "ci95": [ds[N_BOOT // 40], ds[N_BOOT - N_BOOT // 40 - 1]], "p": p}


def asr(base_dir, run_dir, out, kind="pc"):
    """kind="pc" — модель на корпусе Tatoeba (asr_pc.json), "device" — полный путь на телефоне на записях
    комнаты (asr_device.json); фразы сводятся по записи и файлу, решает сумма по языку."""
    name, label = ("asr_pc.json", "ПК") if kind == "pc" else ("asr_device.json", "телефон")
    bp, cp = os.path.join(base_dir, name), os.path.join(run_dir, name)
    if not (os.path.exists(bp) and os.path.exists(cp)):
        out.append(f"- распознавание ({label}): нет {'эталона' if not os.path.exists(bp) else 'прогона'} — пропущено")
        return False
    B, C = load(bp)["results"], load(cp)["results"]
    key = lambda r: (r.get("rec", ""), r["file"])
    worst = False
    for lang in ("pt", "ru"):
        bmap = {key(r): r for r in B if r["lang"] == lang}
        pairs = [(bmap[key(r)], r) for r in C if r["lang"] == lang and key(r) in bmap]
        if not pairs:
            continue
        recs = sorted({k[0] for k in map(key, (c for _, c in pairs)) if k[0]})
        where = f" ({', '.join(recs)})" if recs else ""
        changed = [(b, c) for b, c in pairs if b["hyp"] != c["hyp"] or b["errors"] != c["errors"]]
        if not changed:
            out.append(f"- распознавание {lang}, {label}{where}: **без изменений** — {len(pairs)} из {len(pairs)} фраз те же")
            continue
        x = wer_compare(pairs)
        v = "хуже" if x["delta"] >= TH["WER"] and x["p"] < P else \
            "лучше" if x["delta"] <= -TH["WER"] and x["p"] < P else "изменилось, но не хуже"
        worst |= v == "хуже"
        out.append(f"- распознавание {lang}, {label}{where}: **{v}** — изменилось {len(changed)} фраз из {len(pairs)}; "
                   f"WER {x['A']:.2f} → {x['B']:.2f} % (Δ {x['delta']:+.2f} п. п., 95 % [{x['ci95'][0]:+.2f}; "
                   f"{x['ci95'][1]:+.2f}], p = {x['p']:.3f})".replace(".", ","))
        for b, c in sorted(changed, key=lambda bc: bc[0]["errors"] - bc[1]["errors"])[:5]:
            ref = f" (эталон «{c['ref']}»)" if "ref" in c else ""
            out.append(f"    - {c.get('rec', '') + ' ' if c.get('rec') else ''}{c['file']}: было «{b['hyp']}», "
                       f"стало «{c['hyp']}»{ref}")
    return worst


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--run", required=True)
    ap.add_argument("--stages", default="mt asr", help="mt asr device — какие этапы сравнивать")
    ap.add_argument("--report", default="")
    a = ap.parse_args()
    out, worst = [], False
    if "mt" in a.stages:
        worst |= mt(a.base, a.run, out)
    if "asr" in a.stages:
        worst |= asr(a.base, a.run, out, "pc")
    if "device" in a.stages:
        worst |= asr(a.base, a.run, out, "device")
    if not out:
        print("нечего сравнивать")
        return 2
    text = "\n".join(out)
    print(text)
    print("\nИТОГ: " + ("ХУЖЕ эталона — см. выше" if worst else "не хуже эталона"))
    if a.report:
        with open(a.report, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    return 1 if worst else 0


if __name__ == "__main__":
    sys.exit(main())
