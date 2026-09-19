"""Бенчмарк TTS: Piper (офлайн). Меряем RTF, сохраняем wav для прослушивания.

  python tts_bench.py --lang pt --limit 5
  python tts_bench.py --lang ru --engine piper
"""
import argparse
import json
import time
import wave
from pathlib import Path

import poco_limits

poco_limits.apply()
DATA = Path(__file__).parent / "data" / "test_set.json"
OUT = Path("results/tts")

VOICES = {
    "pt": ["voices/pt_BR-faber-medium.onnx"],
    "ru": ["voices/ru_RU-irina-medium.onnx", "voices/ru_RU-dmitri-medium.onnx"],
}


def synth_piper(model_path: str, text: str, wav_path: Path) -> float:
    from piper import PiperVoice
    voice = PiperVoice.load(model_path)
    t0 = time.perf_counter()
    with wave.open(str(wav_path), "wb") as wf:
        voice.synthesize_wav(text, wf)
    return time.perf_counter() - t0


def wav_seconds(path: Path) -> float:
    with wave.open(str(path)) as wf:
        return wf.getnframes() / wf.getframerate()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", choices=["pt", "ru"], required=True)
    ap.add_argument("--limit", type=int, default=5)
    args = ap.parse_args()

    key = "pt_to_ru" if args.lang == "pt" else "ru_to_pt"
    texts = [it[args.lang] for it in json.loads(DATA.read_text())[key]][: args.limit]

    for model in VOICES[args.lang]:
        name = Path(model).stem
        d = OUT / name
        d.mkdir(parents=True, exist_ok=True)
        rtfs = []
        for i, text in enumerate(texts):
            wav_path = d / f"{i:02d}.wav"
            dt = synth_piper(model, text, wav_path)
            dur = wav_seconds(wav_path)
            rtf = dt / dur if dur else 0
            rtfs.append(rtf)
            print(f"[{name}] {dt:4.2f}s gen / {dur:4.2f}s audio  RTF={rtf:.2f}  {text[:50]}")
        avg = sum(rtfs) / len(rtfs)
        print(f"== {name}: avg RTF={avg:.2f} (чем меньше, тем быстрее; <1 = быстрее речи)")


if __name__ == "__main__":
    main()
