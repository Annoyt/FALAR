#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Распознавание на столе той же моделью и с теми же настройками, что Engine.offline и Engine.asr на
телефоне: parakeet-tdt-0.6b-v3 int8 из models/asr_multi, nemo_transducer, greedy_search, 80 признаков,
16 кГц, 4 потока, без добавочной тишины. Библиотека та же — sherpa-onnx (здесь Python, на телефоне —
её Java API). Записи — bench/air/corpus: живые голоса Tatoeba, 82 португальских и 80 русских, эталон —
.txt рядом. Нарезку, чувствительность и шумодав (TranslatorService) этот этап не проверяет — это делает
этап на телефоне (bench/quality/gate.sh --device) на записях комнаты.

    .venv/bin/python bench/quality/asr_pc.py ВЫХОД.json
"""
import glob
import json
import os
import sys
import time
import wave

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(R, "bench", "asr2"))
from wer2 import wer  # noqa: E402  — одна норма и один WER на все стенды распознавания

CORPUS = os.path.join(R, "bench", "air", "corpus")
MODEL = os.path.join(R, "models", "asr_multi")


def samples(path):
    with wave.open(path) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768


def main():
    import sherpa_onnx
    out = sys.argv[1]
    m = lambda n: os.path.join(MODEL, n)
    rec = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=m("encoder.int8.onnx"), decoder=m("decoder.int8.onnx"), joiner=m("joiner.int8.onnx"),
        tokens=m("tokens.txt"), num_threads=4, sample_rate=16000, feature_dim=80,
        decoding_method="greedy_search", model_type="nemo_transducer")
    results, t0 = [], time.time()
    for lang in ("pt", "ru"):
        for wav in sorted(glob.glob(os.path.join(CORPUS, lang, "*.wav"))):
            ref = open(wav[:-4] + ".txt", encoding="utf-8").read().strip()
            st = rec.create_stream()
            st.accept_waveform(16000, samples(wav))
            rec.decode_stream(st)
            hyp = st.result.text.strip()
            e, n = wer(ref, hyp)
            results.append({"lang": lang, "file": os.path.basename(wav), "ref": ref, "hyp": hyp, "errors": e, "words": n})
    summary = {"engine": "parakeet-tdt-0.6b-v3-int8 (sherpa-onnx, как Engine.asr)", "sec": round(time.time() - t0, 1)}
    for lang in ("pt", "ru"):
        rs = [r for r in results if r["lang"] == lang]
        e, n = sum(r["errors"] for r in rs), sum(r["words"] for r in rs)
        summary[lang] = {"n": len(rs), "words": n, "errors": e, "wer": round(100 * e / n, 2) if n else None}
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "results": results}, f, ensure_ascii=False, indent=1)
    print(f"asr_pc: pt WER {summary['pt']['wer']} % ({summary['pt']['n']} записей), "
          f"ru WER {summary['ru']['wer']} % ({summary['ru']['n']}), {summary['sec']} с")


if __name__ == "__main__":
    main()
