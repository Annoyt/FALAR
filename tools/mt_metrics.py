#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Оценки перевода для mt_bench: chrF, chrF++, COMET — по наборам, с 95 % интервалом; сравнение двух прогонов.

chrF — совпадение символьных n-грамм; для русского с его окончаниями честнее BLEU, и все прежние
решения приняты по нему. chrF++ — то же плюс пары слов: сильнее учитывает порядок слов. Обе —
sacrebleu; интервал — бутстрэп по предложениям, 1000 выборок.

COMET — Unbabel/wmt22-comet-da (Apache-2.0, 2,3 ГБ, кодировщик XLM-R large). Смотрит и на исходник, и
на эталон и заметно лучше chrF и BLEU совпадает с оценками людей (задача метрик WMT22). Главное для нас: ловит
смысловые ошибки, которые chrF почти не наказывает, — потерянное «не», чужой род, перепутанное «кто
кому». Шкала сжатая: обычные переводы — 0,80–0,95, и разница в 0,01 уже заметна. Модель лежит в
models/comet/wmt22-comet-da, работает в своём окружении .venv-comet (torch для процессора,
unbabel-comet): у comet старые пины (numpy<2, torchmetrics 0.10), которые сломали бы остальные замеры
в .venv. Только на ПК, на телефон не едет. На 8 потоках процессора — около 3,5 пары в секунду:
1000 пар — 5 минут; модель грузится 20 с.

  .venv-comet/bin/python tools/mt_metrics.py results/mt_opus_pt2ru.json     # всё, с COMET
  .venv/bin/python tools/mt_metrics.py --no-comet results/mt_*.json           # только chrF и chrF++
  .venv-comet/bin/python tools/mt_metrics.py --compare A.json B.json           # B против A, парный бутстрэп
  .venv/bin/python tools/mt_metrics.py --table results/mt_*.json              # таблица для отчёта
  .venv/bin/python tools/mt_metrics.py --selftest                             # своя сборка chrF = sacrebleu

Окружение COMET, если его нет:
  uv venv --python python3.12 .venv-comet
  uv pip install --python .venv-comet/bin/python torch --index-url https://download.pytorch.org/whl/cpu
  uv pip install --python .venv-comet/bin/python unbabel-comet
  (модель скачивается из Hugging Face: Unbabel/wmt22-comet-da в models/comet/wmt22-comet-da)
"""
import argparse
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COMET_CKPT = ROOT / "models" / "comet" / "wmt22-comet-da" / "checkpoints" / "model.ckpt"
COMET_PY = ROOT / ".venv-comet" / "bin" / "python"
N_BOOT = 1000
SEED = 12345
# Какой набор — заголовок прогона: большой, если есть
MAIN_SETS = ("tatoeba", "situations")


def set_of(r):
    """Старые прогоны (до 02.10.2026) — только 40 фраз data/test_set.json."""
    return r.get("set", "situations")


def groups(results):
    out = {}
    for r in results:
        out.setdefault(set_of(r), []).append(r)
    return out


def _rng():
    import numpy as np
    return np.random.default_rng(SEED)


def _chrf_stats(hyps, refs, word_order):
    import numpy as np
    from sacrebleu.metrics import CHRF
    m = CHRF(word_order=word_order)
    return m, np.array(m._extract_corpus_statistics(hyps, [refs]), dtype="float64")


def chrf(hyps, refs, word_order=0, n_boot=N_BOOT):
    """Корпусный chrF (word_order=2 — chrF++) и половина 95 % интервала бутстрэпа."""
    m, st = _chrf_stats(hyps, refs, word_order)
    score = m._compute_score_from_stats(st.sum(0)).score
    if len(hyps) < 2 or n_boot < 2:
        return round(score, 2), 0.0
    idx = _rng().integers(0, len(hyps), (n_boot, len(hyps)))
    bs = sorted(m._compute_score_from_stats(st[i].sum(0)).score for i in idx)
    lo, hi = bs[n_boot // 40], bs[n_boot - n_boot // 40 - 1]
    return round(score, 2), round((hi - lo) / 2, 2)


def mean_ci(xs, n_boot=N_BOOT):
    import numpy as np
    a = np.asarray(xs, dtype="float64")
    if len(a) < 2:
        return round(float(a.mean()), 4), 0.0
    bs = np.sort(a[_rng().integers(0, len(a), (n_boot, len(a)))].mean(1))
    lo, hi = bs[n_boot // 40], bs[n_boot - n_boot // 40 - 1]
    return round(float(a.mean()), 4), round(float(hi - lo) / 2, 4)


def summarize(results):
    out = {}
    for name, rs in groups(results).items():
        hyps, refs = [r["hyp"] for r in rs], [r["ref"] for r in rs]
        d = {"n": len(rs)}
        d["chrf"], d["chrf_ci"] = chrf(hyps, refs)
        d["chrfpp"], d["chrfpp_ci"] = chrf(hyps, refs, 2)
        if all("comet" in r for r in rs):
            d["comet"], d["comet_ci"] = mean_ci([r["comet"] for r in rs])
        out[name] = d
    return out


class Comet:
    """Модель грузится один раз (~20 с) на все файлы."""

    def __init__(self, threads=0):
        import logging
        import warnings
        import torch
        warnings.filterwarnings("ignore")
        torch.set_num_threads(threads or max(1, min(8, (os.cpu_count() or 2) - 2)))
        from comet import load_from_checkpoint
        # lightning настраивает свои журналы при импорте — глушить после него
        for name in ("pytorch_lightning", "lightning", "lightning.pytorch", "lightning_fabric", "comet"):
            logging.getLogger(name).setLevel(logging.ERROR)
        if not COMET_CKPT.exists():
            sys.exit(f"нет модели COMET: {COMET_CKPT} (см. начало {__file__})")
        self.model = load_from_checkpoint(str(COMET_CKPT))

    def score(self, results, batch=32):
        data = [{"src": r["src"], "mt": r["hyp"], "ref": r["ref"]} for r in results]
        out = self.model.predict(data, batch_size=batch, gpus=0, progress_bar=False)
        for r, s in zip(results, out.scores):
            r["comet"] = round(float(s), 4)


def headline(summary):
    """Верхние chrf/chrfpp/comet — по главному набору: так прежние сводки читаются как раньше."""
    sets = summary.get("sets", {})
    main = next((s for s in MAIN_SETS if s in sets), next(iter(sets), None))
    if main is None:
        return
    summary["set"] = main
    for k in ("chrf", "chrfpp", "comet"):
        if k in sets[main]:
            summary[k] = sets[main][k]


def rescore(path, comet=None, force=False):
    """Пересчитать оценки в файле прогона; comet — экземпляр Comet или None (тогда без COMET)."""
    path = Path(path)
    text = path.read_text()
    d = json.loads(text)
    res = d["results"]
    # отступ — как был в файле: старые прогоны писались с отступом 1, иначе дифф — весь файл
    second = text.split("\n", 2)[1] if text.startswith("{\n") else ""
    indent = (len(second) - len(second.lstrip(" "))) or 2
    if comet is not None:
        todo = res if force else [r for r in res if "comet" not in r]
        if todo:
            comet.score(todo)
    s = d.setdefault("summary", {})
    s["sets"] = summarize(res)
    headline(s)
    path.write_text(json.dumps(d, ensure_ascii=False, indent=indent))
    return s


def line(name, s):
    parts = []
    for set_name, x in s.get("sets", {}).items():
        c = f" COMET {x['comet']:.4f}±{x['comet_ci']:.4f}" if "comet" in x else ""
        parts.append(f"{set_name} n={x['n']}: chrF {x['chrf']:.1f}±{x['chrf_ci']:.1f} "
                     f"chrF++ {x['chrfpp']:.1f}±{x['chrfpp_ci']:.1f}{c}")
    return f"{name}: " + "; ".join(parts)


# ---------------------------------------------------------------- сравнение двух прогонов
def compare(a_path, b_path, n_boot=N_BOOT, since=""):
    """Парный бутстрэп по общим исходникам каждого набора: Δ = B − A, 95 % интервал, p (двусторонний)."""
    import numpy as np
    A, B = (fresh(json.loads(Path(p).read_text())["results"], since) for p in (a_path, b_path))
    out = {}
    ga, gb = groups(A), groups(B)
    for name in ga:
        if name not in gb:
            continue
        bmap = {r["src"]: r for r in gb[name]}
        pairs = [(r, bmap[r["src"]]) for r in ga[name] if r["src"] in bmap]
        if len(pairs) < 2:
            continue
        refs = [a["ref"] for a, _ in pairs]
        idx = _rng().integers(0, len(pairs), (n_boot, len(pairs)))
        rows = {}
        for metric, wo in (("chrF", 0), ("chrF++", 2)):
            m, sa = _chrf_stats([a["hyp"] for a, _ in pairs], refs, wo)
            _, sb = _chrf_stats([b["hyp"] for _, b in pairs], refs, wo)
            va = m._compute_score_from_stats(sa.sum(0)).score
            vb = m._compute_score_from_stats(sb.sum(0)).score
            deltas = np.array([m._compute_score_from_stats(sb[i].sum(0)).score
                               - m._compute_score_from_stats(sa[i].sum(0)).score for i in idx])
            rows[metric] = (va, vb, deltas)
        if all("comet" in a and "comet" in b for a, b in pairs):
            ca = np.array([a["comet"] for a, _ in pairs])
            cb = np.array([b["comet"] for _, b in pairs])
            rows["COMET"] = (ca.mean(), cb.mean(), (cb - ca)[idx].mean(1))
        res = {}
        for metric, (va, vb, deltas) in rows.items():
            ds = np.sort(deltas)
            lo, hi = ds[n_boot // 40], ds[n_boot - n_boot // 40 - 1]
            p = min(1.0, 2 * min((deltas <= 0).mean(), (deltas >= 0).mean()))
            res[metric] = {"A": round(float(va), 4), "B": round(float(vb), 4), "delta": round(float(vb - va), 4),
                           "ci95": [round(float(lo), 4), round(float(hi), 4)], "p": round(float(p), 4)}
        out[name] = {"n": len(pairs), **res}
    return out


def fresh(results, since):
    """Только пары Tatoeba не старше since (для моделей, обученных позже отсечки набора)."""
    return [r for r in results if r.get("added", "") >= since] if since else results


def table(files, since=""):
    """Markdown-таблица по файлам прогонов: оценки каждого набора с полуширинами интервалов."""
    rows = ["| прогон | набор | n | chrF | chrF++ | COMET |", "|---|---|---|---|---|---|"]
    for f in files:
        d = json.loads(Path(f).read_text())
        sets = summarize(fresh(d["results"], since)) if since else d.get("summary", {}).get("sets", {})
        for name, x in sets.items():
            c = f"{x['comet']:.4f} ± {x['comet_ci']:.4f}" if "comet" in x else "—"
            rows.append(f"| {Path(f).stem.removeprefix('mt_')} | {name} | {x['n']} | {x['chrf']:.1f} ± {x['chrf_ci']:.1f} "
                        f"| {x['chrfpp']:.1f} ± {x['chrfpp_ci']:.1f} | {c} |")
    return "\n".join(rows)


def selftest():
    """Своя сборка chrF (статистики sacrebleu по предложениям) должна давать то же, что sacrebleu, —
    иначе после обновления sacrebleu врали бы и интервалы, и сравнения."""
    import tempfile
    import sacrebleu
    hyps = ["Мне это нравится.", "Привет, всё хорошо? Сколько стоит это?", "Он ушёл домой."]
    refs = ["Мне это не нравится.", "Привет, как дела? Сколько это стоит?", "Он пошёл домой."]
    for wo in (0, 2):
        mine = chrf(hyps, refs, wo, n_boot=1)[0]
        theirs = round(sacrebleu.corpus_chrf(hyps, [refs], word_order=wo).score, 2)
        assert mine == theirs, f"chrF word_order={wo}: {mine} ≠ sacrebleu {theirs}"
    a = [{"src": str(i), "ref": r, "hyp": h, "comet": 0.9} for i, (h, r) in enumerate(zip(hyps, refs))]
    b = [dict(x, hyp=x["ref"], comet=0.95) for x in a]
    with tempfile.TemporaryDirectory() as t:
        pa, pb = Path(t, "a.json"), Path(t, "b.json")
        pa.write_text(json.dumps({"results": a}))
        pb.write_text(json.dumps({"results": b}))
        same = compare(pa, pa)["situations"]
        assert all(same[m]["delta"] == 0 and same[m]["p"] == 1 for m in ("chrF", "chrF++", "COMET")), same
        better = compare(pa, pb)["situations"]
        assert better["chrF"]["B"] == 100 and better["chrF"]["delta"] > 0, better
        assert abs(better["COMET"]["delta"] - 0.05) < 1e-9, better
    print("mt_metrics: самопроверка прошла")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("files", nargs="*")
    ap.add_argument("--selftest", action="store_true", help="сверить свою сборку chrF с sacrebleu")
    ap.add_argument("--no-comet", action="store_true", help="только chrF и chrF++")
    ap.add_argument("--force", action="store_true", help="COMET заново и там, где уже посчитан")
    ap.add_argument("--compare", action="store_true", help="два файла: B против A")
    ap.add_argument("--table", action="store_true", help="только напечатать таблицу оценок по файлам")
    ap.add_argument("--since", default="", help="--table/--compare: только пары Tatoeba не старше даты ГГГГ-ММ-ДД")
    ap.add_argument("--threads", type=int, default=0)
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if not a.files:
        ap.error("нужен хотя бы один файл прогона")
    if a.table:
        return print(table(a.files, a.since))
    if a.compare:
        if len(a.files) != 2:
            sys.exit("--compare: нужно ровно два файла, A и B")
        res = compare(*a.files, since=a.since)
        for name, x in res.items():
            print(f"{name} (n={x['n']}): B − A")
            for metric in ("chrF", "chrF++", "COMET"):
                if metric in x:
                    m = x[metric]
                    f = ".4f" if metric == "COMET" else ".2f"
                    print(f"  {metric:7s} A {m['A']:{f}}  B {m['B']:{f}}  Δ {m['delta']:+{f}}  "
                          f"95% [{m['ci95'][0]:+{f}}; {m['ci95'][1]:+{f}}]  p={m['p']:.3f}")
        return
    comet = None if a.no_comet else Comet(a.threads)
    for f in a.files:
        print(line(Path(f).name, rescore(f, comet, a.force)), flush=True)


if __name__ == "__main__":
    main()
