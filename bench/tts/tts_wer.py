#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Разборчивость и скорость озвучки по прогону TtsBench (каталог tts_bench с телефона): каждый WAV распознаёт тот же
parakeet-tdt-0.6b-v3 int8, что приложение (sherpa-onnx, как bench/quality/asr_pc.py), WER — против текста фразы
(нормализация bench/asr2/wer2.py). Время — с телефона: до первого звука и синтез на секунду звука (RTF).

    .venv/bin/python bench/tts/tts_wer.py КАТАЛОГ [КАТАЛОГ …]     # итог — КАТАЛОГ/wer.json и таблица в вывод
"""
import csv
import json
import os
import statistics
import sys
import wave

import numpy as np
from scipy.signal import resample_poly

R = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(R, "bench", "asr2"))
from wer2 import wer  # noqa: E402
MAIN = os.path.join(R, "models") if os.path.isdir(os.path.join(R, "models", "asr_multi")) else \
    os.path.join(os.path.dirname(os.path.dirname(R)), "models")      # из рабочей копии .worktrees/* — модели основной
MODEL = os.path.join(os.environ.get("FALAR_MODELS") or MAIN, "asr_multi")


def samples16(path):
    with wave.open(path) as w:
        rate, ch, sw, n = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        raw = w.readframes(n)
    x = (np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768) if sw == 2 else \
        np.frombuffer(raw, dtype=np.float32) if sw == 4 else (np.frombuffer(raw, dtype=np.uint8).astype(np.float32) - 128) / 128
    if ch > 1: x = x.reshape(-1, ch).mean(axis=1)
    if rate != 16000:
        g = np.gcd(rate, 16000); x = resample_poly(x, 16000 // g, rate // g).astype(np.float32)
    return x


def main():
    import sherpa_onnx
    m = lambda n: os.path.join(MODEL, n)
    rec = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=m("encoder.int8.onnx"), decoder=m("decoder.int8.onnx"), joiner=m("joiner.int8.onnx"),
        tokens=m("tokens.txt"), num_threads=4, sample_rate=16000, feature_dim=80,
        decoding_method="greedy_search", model_type="nemo_transducer")
    for d in sys.argv[1:]:
        rows = list(csv.DictReader(open(os.path.join(d, "results.tsv"), encoding="utf-8"), delimiter="\t", quoting=csv.QUOTE_NONE))
        out, groups = [], {}
        for r in rows:
            st = rec.create_stream(); st.accept_waveform(16000, samples16(os.path.join(d, r["file"]))); rec.decode_stream(st)
            hyp = st.result.text.strip(); e, n = wer(r["text"], hyp)
            out.append({**r, "hyp": hyp, "errors": e, "words": n})
            groups.setdefault((r["engine"], r["voice"], r["lang"]), []).append(out[-1])
        summary = []
        for (eng, voice, lang), rs in groups.items():
            e, n = sum(x["errors"] for x in rs), sum(x["words"] for x in rs)
            first = [int(x["first_ms"]) for x in rs]; tot = sum(int(x["total_ms"]) for x in rs); aud = sum(int(x["audio_ms"]) for x in rs)
            summary.append({"engine": eng, "voice": voice, "lang": lang, "n": len(rs), "wer": round(100 * e / n, 2), "errors": e, "words": n,
                            "first_ms_median": statistics.median(first), "first_ms_max": max(first), "rtf": round(tot / aud, 3) if aud else None,
                            "audio_s": round(aud / 1000, 1), "rate": rs[0]["rate"]})
        json.dump({"summary": summary, "results": out}, open(os.path.join(d, "wer.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(f"== {d}")
        print(f"{'движок':8} {'голос':52} {'яз':3} {'фраз':>4} {'WER %':>6} {'до звука, мс':>13} {'макс':>6} {'RTF':>6} {'Гц':>6}")
        for s in sorted(summary, key=lambda s: (s["lang"], s["engine"])):
            print(f"{s['engine']:8} {s['voice'][:52]:52} {s['lang']:3} {s['n']:4} {s['wer']:6.2f} {s['first_ms_median']:13.0f} {s['first_ms_max']:6} {s['rtf']:6.3f} {s['rate']:>6}")


if __name__ == "__main__":
    main()
