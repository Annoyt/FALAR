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

Чтение снимков (набор bench/ocr: 32 вывески, 831 слово) тоже детерминировано; сравнение — по снимкам:
доля слов, прочитанных буква в букву, и CER строк (tools/ocr_eval.py). «Хуже» — значимо и не меньше
порога: слова −1 п. п. (около 8 слов из 831) или CER +0,5 п. п.; и без всякой значимости — меньше 90 %
слов (TESTS C9) или, на телефоне, меньше 28 из 32 снимков строка в строку со столом на тех же пикселях
(test_ocr_device.sh O2). В файлах — только числа и хэш прочитанного, текста нет.

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

TH = {"chrF": 0.5, "COMET": 0.002, "WER": 0.5, "words": 1.0, "CER": 0.5}
OCR_FLOOR = 90.0       # доля слов, ниже которой чтение снимков негодно при любой разнице (TESTS C9)
OCR_SAME_FLOOR = 28    # телефон на тех же пикселях, что эталон стола: строка в строку из 32 (O2)
P = 0.05
N_BOOT = 1000
DIRS = {"pt2ru": "pt → ru", "ru2pt": "ru → pt"}


def dec(x, f=".2f"):
    """Число с запятой. Запятая — только в числах: замена по всей строке портила «п. п.»."""
    return format(x, f).replace(".", ",")


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


def asr(base_dir, run_dir, out, kind="pc", device_base=""):
    """kind="pc" — модель на корпусе Tatoeba (asr_pc.json), "device" — полный путь на телефоне на записях
    комнаты (asr_device.json); фразы сводятся по записи и файлу, решает сумма по языку."""
    name, label = ("asr_pc.json", "ПК") if kind == "pc" else ("asr_device.json", "телефон")
    bp = os.path.join(device_base if kind == "device" and device_base else base_dir, name)
    cp = os.path.join(run_dir, name)
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
                   f"WER {dec(x['A'])} → {dec(x['B'])} % (Δ {dec(x['delta'], '+.2f')} п. п., 95 % [{dec(x['ci95'][0], '+.2f')}; "
                   f"{dec(x['ci95'][1], '+.2f')}], p = {dec(x['p'], '.3f')})")
        for b, c in sorted(changed, key=lambda bc: bc[0]["errors"] - bc[1]["errors"])[:5]:
            ref = f" (эталон «{c['ref']}»)" if "ref" in c else ""
            out.append(f"    - {c.get('rec', '') + ' ' if c.get('rec') else ''}{c['file']}: было «{b['hyp']}», "
                       f"стало «{c['hyp']}»{ref}")
    return worst


def ocr_compare(pairs):
    """Парный бутстрэп по снимкам: Δ доли слов и Δ CER строк, п. п. (B − A), 95 % интервалы, p."""
    import numpy as np
    col = lambda side, k: np.array([(a if side == 0 else b)[k] for a, b in pairs], dtype=float)
    idx = np.random.default_rng(12345).integers(0, len(pairs), (N_BOOT, len(pairs)))

    def stat(xa, xb, d):
        va, vb = 100 * xa.sum() / d.sum(), 100 * xb.sum() / d.sum()
        deltas = 100 * (xb[idx].sum(1) - xa[idx].sum(1)) / d[idx].sum(1)
        ds = np.sort(deltas)
        p = min(1.0, 2 * min((deltas <= 0).mean(), (deltas >= 0).mean()))
        return {"A": va, "B": vb, "delta": vb - va, "ci95": [ds[N_BOOT // 40], ds[N_BOOT - N_BOOT // 40 - 1]], "p": p}
    return stat(col(0, "hit"), col(1, "hit"), col(0, "n")), stat(col(0, "ed"), col(1, "ed"), col(0, "chars"))


def ocr(base_dir, run_dir, out, kind="pc", device_base=""):
    """kind="pc" — модели приложения на ПК (ocr_pc.json, эталон в git), "device" — приложение на телефоне
    (ocr_device.json и ocr_device_png.json, эталон вне git, как у записей комнаты)."""
    name, label = ("ocr_pc.json", "ПК, модели приложения") if kind == "pc" else ("ocr_device.json", "телефон")
    bp = os.path.join(device_base if kind == "device" and device_base else base_dir, name)
    cp = os.path.join(run_dir, name)
    if not (os.path.exists(bp) and os.path.exists(cp)):
        out.append(f"- чтение снимков ({label}): нет {'эталона' if not os.path.exists(bp) else 'прогона'} — пропущено")
        return False
    B, C = load(bp)["results"], load(cp)["results"]
    bmap = {r["id"]: r for r in B}
    pairs = [(bmap[r["id"]], r) for r in C if r["id"] in bmap]
    if len(pairs) < len(B) or len(pairs) < len(C):
        out.append(f"- чтение снимков ({label}): наборы разошлись с эталоном ({len(pairs)} общих из {len(B)} / {len(C)})")
    words = 100 * sum(c["hit"] for _, c in pairs) / max(1, sum(c["n"] for _, c in pairs))
    changed = [(b, c) for b, c in pairs if b["sha"] != c["sha"]]
    worst = False
    if not changed:
        v, line = "без изменений", f"все снимки прочитаны так же ({len(pairs)}); слов {dec(words, '.1f')} %"
    else:
        w, e = ocr_compare(pairs)
        worse = (w["delta"] <= -TH["words"] and w["p"] < P) or (e["delta"] >= TH["CER"] and e["p"] < P)
        better = (w["delta"] >= TH["words"] and w["p"] < P) or (e["delta"] <= -TH["CER"] and e["p"] < P)
        v = "хуже" if worse else "лучше" if better else "изменилось, но не хуже"
        line = (f"снимков с изменениями: {len(changed)} из {len(pairs)}; слова {dec(w['A'])} → {dec(w['B'])} % "
                f"(Δ {dec(w['delta'], '+.2f')} п. п., 95 % [{dec(w['ci95'][0], '+.2f')}; {dec(w['ci95'][1], '+.2f')}], "
                f"p = {dec(w['p'], '.3f')}); CER строк {dec(e['A'])} → {dec(e['B'])} % "
                f"(Δ {dec(e['delta'], '+.2f')} п. п., p = {dec(e['p'], '.3f')})")
    if words < OCR_FLOOR:
        v, line = "хуже", line + f" — ниже порога {OCR_FLOOR:.0f} % слов"
    if kind == "device":
        png = os.path.join(run_dir, "ocr_device_png.json")
        if os.path.exists(png):
            same = load(png).get("same_as_ref")
            if same is not None:
                line += f"; на тех же пикселях со столом строка в строку {same} из {len(pairs)}"
                if same < OCR_SAME_FLOOR:
                    v, line = "хуже", line + f" (нужно не меньше {OCR_SAME_FLOOR})"
    worst = v == "хуже"
    out.append(f"- чтение снимков, {label}: **{v}** — {line}")
    for b, c in sorted(changed, key=lambda bc: bc[1]["hit"] - bc[0]["hit"])[:5]:
        out.append(f"    - {c['id']}: слов {b['hit']} → {c['hit']} из {c['n']}, правок на знак {b['ed']} → {c['ed']}")
    return worst


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--run", required=True)
    ap.add_argument("--device-base", default="", help="эталон телефона — вне git (записи комнаты личные)")
    ap.add_argument("--stages", default="mt asr", help="mt asr device ocr ocr-device — какие этапы сравнивать")
    ap.add_argument("--report", default="")
    a = ap.parse_args()
    out, worst = [], False
    if "mt" in a.stages:
        worst |= mt(a.base, a.run, out)
    if "asr" in a.stages:
        worst |= asr(a.base, a.run, out, "pc")
    stages = a.stages.split()
    if "device" in stages:
        worst |= asr(a.base, a.run, out, "device", a.device_base)
    if "ocr" in stages:
        worst |= ocr(a.base, a.run, out, "pc")
    if "ocr-device" in stages:
        worst |= ocr(a.base, a.run, out, "device", a.device_base)
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
