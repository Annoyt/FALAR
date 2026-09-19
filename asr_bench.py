"""Бенчмарк ASR (faster-whisper, int8, CPU, лимиты POCO).

Аудио синтезируется Piper'ом из тест-набора (синтетика проще живой речи —
реальные цифры на телефоне будут чуть хуже, это учитываем в отчёте).

  python asr_bench.py --lang pt --model small
  python asr_bench.py --lang ru --model large-v3-turbo
"""
import argparse
import json
import subprocess
import tempfile
import time
from pathlib import Path

import poco_limits

poco_limits.apply()
DATA = Path(__file__).parent / "data" / "test_set.json"

PIPER_VOICE = {"pt": "voices/pt_BR-faber-medium.onnx",
               "ru": "voices/ru_RU-irina-medium.onnx"}
WHISPER_LANG = {"pt": "pt", "ru": "ru"}


def synth(text: str, lang: str, wav_path: Path):
    subprocess.run([".venv/bin/python", "-c", f"""
import wave
from piper import PiperVoice
v = PiperVoice.load({PIPER_VOICE[lang]!r})
with wave.open({str(wav_path)!r}, 'wb') as wf:
    v.synthesize_wav({text!r}, wf)
"""], check=True)


def cer(ref: str, hyp: str) -> float:
    import sacrebleu
    return sacrebleu.corpus_chrf([hyp], [[ref]], char_order=0).score


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", choices=["pt", "ru"], required=True)
    ap.add_argument("--model", default="small")
    ap.add_argument("--limit", type=int, default=10)
    args = ap.parse_args()

    key = "pt_to_ru" if args.lang == "pt" else "ru_to_pt"
    items = json.loads(DATA.read_text())[key][: args.limit]

    from faster_whisper import WhisperModel
    t0 = time.perf_counter()
    model = WhisperModel(args.model, device="cpu", compute_type="int8",
                         cpu_threads=poco_limits.cpu_threads())
    print(f"model {args.model} loaded in {time.perf_counter()-t0:.1f}s")

    results, rtfs = [], []
    with tempfile.TemporaryDirectory() as tmp:
        for i, it in enumerate(items):
            wav = Path(tmp) / f"{i}.wav"
            synth(it[args.lang], args.lang, wav)
            t0 = time.perf_counter()
            segments, info = model.transcribe(str(wav), language=WHISPER_LANG[args.lang])
            hyp = " ".join(s.text for s in segments).strip()
            dt = time.perf_counter() - t0
            rtf = dt / (info.duration or 1)
            rtfs.append(rtf)
            results.append({"ref": it[args.lang], "hyp": hyp, "rtf": round(rtf, 2)})
            print(f"[RTF {rtf:4.2f}] {it[args.lang]}\n  -> {hyp}")

    import sacrebleu
    chrf = sacrebleu.corpus_chrf([r["hyp"] for r in results],
                                 [[r["ref"]] for r in results])
    summary = {"model": args.model, "lang": args.lang,
               "chrf_vs_text": round(chrf.score, 2),
               "rtf_avg": round(sum(rtfs) / len(rtfs), 2), "n": len(items)}
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    out = Path("results") / f"asr_{args.model}_{args.lang}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps({"summary": summary, "results": results},
                              ensure_ascii=False, indent=2))
    print(f"saved -> {out}")


if __name__ == "__main__":
    main()
