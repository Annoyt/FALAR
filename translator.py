"""Сквозной прототип: микрофон -> VAD -> ASR -> MT -> TTS -> звук.

Режимы:
  python translator.py --direction pt2ru --mt nllb      # слушаем португальскую речь
  python translator.py --direction ru2pt --mt gemma
  python translator.py --direction pt2ru --mt openrouter --model nvidia/nemotron-3-super-120b-a12b:free

Управление: Enter — начать запись, Enter — закончить и перевести, 'q' — выход.
"""
import argparse
import io
import json
import os
import sys
import time
import wave
from pathlib import Path

import numpy as np

import poco_limits
from mt_bench import NLLBEngine, GemmaEngine, OpenRouterEngine, load_glossary

poco_limits.apply()

SAMPLE_RATE = 16000
TTS_VOICE = {"pt": "voices/pt_BR-faber-medium.onnx", "ru": "voices/ru_RU-irina-medium.onnx"}
WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "small")


def record_push_to_talk() -> np.ndarray:
    import sounddevice as sd
    chunks = []
    recording = {"on": False}

    def cb(indata, frames, t, status):
        if recording["on"]:
            chunks.append(indata.copy())

    print("Enter — старт записи...")
    input()
    recording["on"] = True
    with sd.InputStream(samplerate=SAMPLE_RATE, channels=1,
                        dtype="float32", callback=cb):
        print("... запись! Enter — стоп ...")
        input()
        recording["on"] = False
    if not chunks:
        return np.zeros(0, dtype=np.float32)
    return np.concatenate(chunks).flatten()


class Pipeline:
    def __init__(self, direction: str, mt: str, or_model: str):
        self.src, self.tgt = direction.split("2")
        from faster_whisper import WhisperModel
        from piper import PiperVoice
        print("загрузка ASR...", flush=True)
        self.asr = WhisperModel(WHISPER_MODEL, device="cpu", compute_type="int8",
                                cpu_threads=poco_limits.cpu_threads())
        print("загрузка TTS...", flush=True)
        self.tts = PiperVoice.load(TTS_VOICE[self.tgt])
        glossary = load_glossary()
        self.mt = {"nllb": NLLBEngine,
                   "gemma": lambda: GemmaEngine(glossary),
                   "openrouter": lambda: OpenRouterEngine(or_model, glossary)}[mt]()
        print(f"MT: {self.mt.name}")

    def run_once(self, audio: np.ndarray | None = None,
                 wav_path: str | None = None) -> dict:
        t = {}
        t0 = time.perf_counter()
        source = wav_path if wav_path else audio
        segments, _ = self.asr.transcribe(source, language=self.src, beam_size=5)
        text = " ".join(s.text for s in segments).strip()
        t["asr"] = time.perf_counter() - t0
        if not text:
            return {"src": "", "tgt": "", "t": t}
        t0 = time.perf_counter()
        out = self.mt.translate(text, self.src, self.tgt)
        t["mt"] = time.perf_counter() - t0
        t0 = time.perf_counter()
        buf = io.BytesIO()
        with wave.open(buf, "wb") as wf:
            self.tts.synthesize_wav(out, wf)
        t["tts"] = time.perf_counter() - t0
        buf.seek(0)
        with wave.open(buf) as wf:
            frames = wf.readframes(wf.getnframes())
            pcm = np.frombuffer(frames, dtype=np.int16)
            sr = wf.getframerate()
        import sounddevice as sd
        try:
            sd.play(pcm, sr)
            sd.wait()
        except sd.PortAudioError:
            print("(нет аудиоустройства — пропускаю воспроизведение)")
        t["play"] = 0.0
        return {"src": text, "tgt": out, "t": t}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--direction", choices=["pt2ru", "ru2pt"], default="pt2ru")
    ap.add_argument("--mt", choices=["nllb", "gemma", "openrouter"], default="nllb")
    ap.add_argument("--model", default="nvidia/nemotron-3-super-120b-a12b:free")
    ap.add_argument("--wav", help="перевести один wav-файл и выйти (тест без микрофона)")
    args = ap.parse_args()

    if args.mt == "openrouter" and not os.environ.get("OPENROUTER_API_KEY"):
        env = Path(".env")
        if env.exists():
            os.environ["OPENROUTER_API_KEY"] = env.read_text().strip().split("=")[1]

    pipe = Pipeline(args.direction, args.mt, args.model)
    print(f"\nготов: {args.direction} | ASR whisper-{WHISPER_MODEL} | TTS piper\n")

    if args.wav:
        r = pipe.run_once(wav_path=args.wav)
        print(f"  {args.direction[:2]}: {r['src']}")
        print(f"  {args.direction[3:]}: {r['tgt']}")
        print(f"  время: {json.dumps({k: round(v, 2) for k, v in r['t'].items()})}")
        return

    while True:
        try:
            audio = record_push_to_talk()
        except (KeyboardInterrupt, EOFError):
            break
        if len(audio) < SAMPLE_RATE * 0.3:
            print("(слишком коротко)")
            continue
        r = pipe.run_once(audio)
        if not r["src"]:
            print("(речь не распознана)")
            continue
        print(f"  {args.direction[:2]}: {r['src']}")
        print(f"  {args.direction[3:]}: {r['tgt']}")
        print(f"  время: ASR {r['t']['asr']:.1f}s + MT {r['t']['mt']:.1f}s + TTS {r['t']['tts']:.1f}s\n")


if __name__ == "__main__":
    main()
