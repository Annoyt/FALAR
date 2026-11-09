#!/usr/bin/env python3
"""Декодировать выходы кодировщика parakeet (с NPU или CPU) жадным TDT на ПК и посчитать WER по корпусу.

  asr_wer.py ENC_OUT_DIR [ENC_OUT_DIR …]       # каталоги с имя.f32 ([1024][T'] float32) и имя.len
  asr_wer.py --hyps ФАЙЛ [ФАЙЛ …]              # готовые гипотезы «имя<TAB>текст» — только WER

Декодер — decoder_joint-model.onnx (fp32, istupakov/parakeet-tdt-0.6b-v3-onnx), словарь vocab.txt; цикл —
как в onnx-asr (TDT: длительность = argmax по хвосту выхода). Эталоны — bench/air/corpus/<язык>/<имя>.txt.
Нормализация для WER: нижний регистр, без пунктуации, ё→е.
"""
import os
import re
import sys
import unicodedata
from pathlib import Path

import numpy as np
import onnxruntime as ort

N = Path("/home/not_me/antigravity-workspace/agentstuff/agenttranslator/.stand/npu/parakeet")
CORPUS = Path("/home/not_me/antigravity-workspace/agentstuff/agenttranslator/bench/air/corpus")
vocab, V, BLANK, dj, shp = {}, 0, 0, None, {}


def load():
    """Декодер и словарь — только для разбора выходов кодировщика (режиму --hyps не нужны)."""
    global V, BLANK, dj, shp
    for line in open(N / "vocab.txt", encoding="utf-8"):
        tok, i = line.rstrip("\n").split(" ")
        vocab[int(i)] = tok.replace("▁", " ")
    V = len(vocab)
    BLANK = next(i for i, t in vocab.items() if t == "<blk>")
    dj = ort.InferenceSession(str(N / "decoder_joint-model.onnx"), providers=["CPUExecutionProvider"])
    shp = {x.name: x.shape for x in dj.get_inputs()}


def decode(enc):  # enc: [T, 1024]
    s1 = np.zeros((shp["input_states_1"][0], 1, shp["input_states_1"][2]), np.float32)
    s2 = np.zeros((shp["input_states_2"][0], 1, shp["input_states_2"][2]), np.float32)
    toks, t, emitted = [], 0, 0
    while t < len(enc):
        out, n1, n2 = dj.run(["outputs", "output_states_1", "output_states_2"], {
            "encoder_outputs": enc[t][None, :, None], "targets": [[toks[-1] if toks else BLANK]],
            "target_length": [1], "input_states_1": s1, "input_states_2": s2})
        out = np.squeeze(out)
        tok, step = int(out[:V].argmax()), int(out[V:].argmax())
        if tok != BLANK:
            s1, s2 = n1, n2
            toks.append(tok)
            emitted += 1
        if step > 0:
            t += step
            emitted = 0
        elif tok == BLANK or emitted == 10:
            t += 1
            emitted = 0
    return re.sub(r"\s+", " ", "".join(vocab[i] for i in toks)).strip()


def norm(s):
    s = s.lower().replace("ё", "е")
    s = "".join(c if not unicodedata.category(c).startswith("P") else " " for c in s)
    return s.split()


def wer_counts(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)], len(r)


def score_hyps(files):
    """--hyps ФАЙЛ…: готовые гипотезы «имя<TAB>текст» (напр. bench/npu/npu_asr_pc.sh) → WER той же нормализацией."""
    for f in files:
        tot = {"pt": [0, 0, 0], "ru": [0, 0, 0]}
        for line in Path(f).read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            name, hyp = (line.split("\t", 1) + [""])[:2]
            lang, tat = name.split("_", 1)
            ref = (CORPUS / lang / f"{tat}.txt").read_text(encoding="utf-8").strip()
            e, n = wer_counts(ref, hyp)
            tot[lang][0] += e; tot[lang][1] += n; tot[lang][2] += 1
        print(f, " · ".join(f"{l}: WER {100 * e / max(n, 1):.2f} % ({e}/{n} слов, {c} фраз)" for l, (e, n, c) in tot.items()))


if sys.argv[1:2] == ["--hyps"]:
    score_hyps(sys.argv[2:])
    sys.exit(0)
load()
for out_dir in sys.argv[1:]:
    tot = {"pt": [0, 0, 0], "ru": [0, 0, 0]}
    hyps = {}
    for f in sorted(list(Path(out_dir).glob("*.f32")) + list(Path(out_dir).glob("*.f16"))):
        name = f.stem
        lang, tat = name.split("_", 1)
        L = int(open(f.with_suffix(".len")).read().strip())
        raw = np.fromfile(f, np.float16 if f.suffix == ".f16" else np.float32).astype(np.float32)
        enc = raw.reshape(1024, -1).T[:L]
        hyp = decode(enc)
        ref = (CORPUS / lang / f"{tat}.txt").read_text(encoding="utf-8").strip()
        e, n = wer_counts(ref, hyp)
        tot[lang][0] += e; tot[lang][1] += n; tot[lang][2] += 1
        hyps[name] = hyp
    Path(out_dir, "hyps.tsv").write_text("".join(f"{k}\t{v}\n" for k, v in sorted(hyps.items())), encoding="utf-8")
    print(out_dir, " · ".join(f"{l}: WER {100 * e / max(n, 1):.2f} % ({e}/{n} слов, {c} фраз)" for l, (e, n, c) in tot.items()))
