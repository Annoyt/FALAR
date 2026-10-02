#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Полный путь распознавания на телефоне — разбор прогона bench/air/gain_sweep.sh для проверки качества.

gain_sweep подаёт записи комнаты (bench/air/rec) вместо микрофона тем же путём, что живой звук: через
чувствительность, нарезку, шумодав и распознавание приложения, — а bench/air/air_wer.py сводит
услышанное с эталоном по фразам. Здесь построчная таблица air_wer.py (файл, SNR, ошибок, слов,
услышано) из каждого прогона собирается в один JSON — его сравнивает bench/quality/verdict.py.

    python3 bench/quality/asr_device.py <каталог прогона gain_sweep> ВЫХОД.json
"""
import glob
import json
import os
import re
import sys

LINE = re.compile(r"^(\S+)\s+(\S+)\s+(\d+)\s+(\d+)\s{2}(.*)$")


def main():
    src, out = sys.argv[1], sys.argv[2]
    results, summary = [], {}
    for wer_txt in sorted(glob.glob(os.path.join(src, "*", "wer.txt"))):
        run = os.path.basename(os.path.dirname(wer_txt))
        rec = run.split("-a")[0]                      # near-pt-a0-gauto-limit-auto → near-pt
        lang = rec.rsplit("-", 1)[-1]
        text = open(wer_txt, encoding="utf-8").read()
        rows = 0
        for line in text.splitlines():
            m = LINE.match(line)
            if m and m.group(1).endswith(".wav"):
                heard = m.group(5).split(" ⟵ ПУСТО")[0]
                results.append({"rec": rec, "lang": lang, "file": m.group(1), "errors": int(m.group(3)),
                                "words": int(m.group(4)), "hyp": heard})
                rows += 1
        w = re.search(r"^WER ([\d.]+)%", text, re.M)
        lost = re.search(r"не дошло (\d+) \(", text)
        summary[rec] = {"run": run, "files": rows, "wer": float(w.group(1)) if w else None,
                        "lost": int(lost.group(1)) if lost else None}
    if not results:
        sys.exit(f"в {src} нет ни одного разобранного прогона (wer.txt)")
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "results": results}, f, ensure_ascii=False, indent=1)
    print("asr_device: " + " · ".join(f"{k} WER {v['wer']} %, не дошло {v['lost']}" for k, v in summary.items()))


if __name__ == "__main__":
    main()
