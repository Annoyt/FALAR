"""Живой двунаправленный переводчик для TWS-наушников.

Микрофон -> DSP (gain/шумодав/компрессор) -> VAD -> ASR (автоязык pt/ru)
-> MT -> TTS -> pan в нужный канал наушника.

  Левый наушник  — собеседнику 1 (по умолчанию русский)
  Правый наушник — собеседнику 2 (по умолчанию португальский)

Запуск:
  python live_translator.py --mt nllb
  python live_translator.py --mt gemma --sensitivity 0.02 --gain 2.0
  python live_translator.py --left-lang ru --right-lang pt --no-noise-reduce
Тест без железа:
  python live_translator.py --simulate in_pt.wav in_ru.wav --out out.wav
"""
import argparse
import io
import json
import os
import time
import wave
from pathlib import Path

import numpy as np

import poco_limits
from audio_dsp import DSPConfig, AdaptiveVAD, pan_stereo, preprocess_mic
from mt_bench import NLLBEngine, GemmaEngine, OpenRouterEngine, load_glossary

poco_limits.apply()

SR = 16000
CHUNK = 480  # 30 мс
VOICES = {"pt": "voices/pt_BR-faber-medium.onnx", "ru": "voices/ru_RU-irina-medium.onnx"}
WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "small")


class LiveTranslator:
    def __init__(self, mt_engine: str, or_model: str, cfg: DSPConfig,
                 left_lang: str, right_lang: str):
        from faster_whisper import WhisperModel
        from piper import PiperVoice
        self.cfg = cfg
        # pan: перевод НА left_lang идёт в левый канал, НА right_lang — в правый
        self.pan_for_target = {left_lang: -1.0, right_lang: 1.0}
        print("загрузка ASR...", flush=True)
        self.asr = WhisperModel(WHISPER_MODEL, device="cpu", compute_type="int8",
                                cpu_threads=poco_limits.cpu_threads())
        print("загрузка TTS (оба голоса)...", flush=True)
        self.tts = {lang: PiperVoice.load(p) for lang, p in VOICES.items()}
        glossary = load_glossary()
        self.mt = {"nllb": NLLBEngine,
                   "gemma": lambda: GemmaEngine(glossary),
                   "openrouter": lambda: OpenRouterEngine(or_model, glossary)}[mt_engine]()

    def detect_and_transcribe(self, audio: np.ndarray):
        segments, info = self.asr.transcribe(audio, beam_size=5)
        text = " ".join(s.text for s in segments).strip()
        lang = info.language
        return lang, text

    def synth_stereo(self, text: str, lang: str) -> tuple[np.ndarray, int]:
        buf = io.BytesIO()
        with wave.open(buf, "wb") as wf:
            self.tts[lang].synthesize_wav(text, wf)
        buf.seek(0)
        with wave.open(buf) as wf:
            pcm = np.frombuffer(wf.readframes(wf.getnframes()),
                                dtype=np.int16).astype(np.float32) / 32768.0
            sr = wf.getframerate()
        return pan_stereo(pcm, self.pan_for_target[lang]), sr

    def process_utterance(self, audio: np.ndarray) -> dict | None:
        t0 = time.perf_counter()
        audio = preprocess_mic(audio, SR, self.cfg)
        lang, text = self.detect_and_transcribe(audio)
        t_asr = time.perf_counter() - t0
        if not text or lang not in ("pt", "ru"):
            return None
        tgt = "ru" if lang == "pt" else "pt"
        t0 = time.perf_counter()
        out = self.mt.translate(text, lang, tgt)
        t_mt = time.perf_counter() - t0
        t0 = time.perf_counter()
        stereo, sr = self.synth_stereo(out, tgt)
        t_tts = time.perf_counter() - t0
        return {"src_lang": lang, "src": text, "tgt_lang": tgt, "tgt": out,
                "stereo": stereo, "sr": sr,
                "t": {"asr": t_asr, "mt": t_mt, "tts": t_tts}}


def run_live(tr: LiveTranslator):
    import sounddevice as sd
    vad = AdaptiveVAD(tr.cfg, SR)
    print("\n=== LIVE: говорите, перевод пойдёт в наушники ===")
    print(f"левый канал: {min(tr.pan_for_target, key=tr.pan_for_target.get)}, "
          f"правый: {max(tr.pan_for_target, key=tr.pan_for_target.get)}")
    print("Ctrl+C — выход\n")

    def callback(indata, frames, t, status):
        utter = vad.feed(indata[:, 0].copy())
        if utter is not None:
            pending.append(utter)

    pending = []
    with sd.InputStream(samplerate=SR, channels=1, dtype="float32",
                        blocksize=CHUNK, callback=callback):
        try:
            while True:
                while pending:
                    r = tr.process_utterance(pending.pop(0))
                    if not r:
                        print("(не pt/ru или тишина)")
                        continue
                    print(f"  {r['src_lang']}: {r['src']}")
                    print(f"  {r['tgt_lang']}: {r['tgt']}  "
                          f"[{'L' if tr.pan_for_target[r['tgt_lang']] < 0 else 'R'}]")
                    print(f"  {json.dumps({k: round(v, 2) for k, v in r['t'].items()})}\n")
                    pcm16 = (np.clip(r["stereo"], -1, 1) * 32767).astype(np.int16)
                    sd.play(pcm16, r["sr"])
                    sd.wait()
                time.sleep(0.02)
        except KeyboardInterrupt:
            print("\nвыход")


def run_simulate(tr: LiveTranslator, wavs: list[str], out_path: str):
    outs, sr_out = [], None
    for w in wavs:
        with wave.open(w) as wf:
            pcm = np.frombuffer(wf.readframes(wf.getnframes()),
                                dtype=np.int16).astype(np.float32) / 32768.0
        r = tr.process_utterance(pcm)
        if not r:
            print(f"{w}: речь не распознана")
            continue
        print(f"{w}\n  {r['src_lang']}: {r['src']}\n  {r['tgt_lang']}: {r['tgt']}"
              f"  [{'L' if tr.pan_for_target[r['tgt_lang']] < 0 else 'R'}]")
        sr_out = r["sr"]
        outs.append(r["stereo"])
        outs.append(np.zeros((int(sr_out * 0.5), 2), dtype=np.float32))
    if outs:
        stereo = (np.clip(np.concatenate(outs), -1, 1) * 32767).astype(np.int16)
        with wave.open(out_path, "wb") as wf:
            wf.setnchannels(2)
            wf.setsampwidth(2)
            wf.setframerate(sr_out)
            wf.writeframes(stereo.tobytes())
        print(f"стерео-результат -> {out_path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mt", choices=["nllb", "gemma", "openrouter"], default="nllb")
    ap.add_argument("--model", default="nvidia/nemotron-3-super-120b-a12b:free")
    ap.add_argument("--left-lang", choices=["pt", "ru"], default="ru")
    ap.add_argument("--right-lang", choices=["pt", "ru"], default="pt")
    ap.add_argument("--gain", type=float, default=1.0, help="усиление микрофона")
    ap.add_argument("--sensitivity", type=float, default=0.01,
                    help="порог VAD (меньше = чувствительнее)")
    ap.add_argument("--no-noise-reduce", action="store_true")
    ap.add_argument("--no-compressor", action="store_true")
    ap.add_argument("--comp-ratio", type=float, default=4.0)
    ap.add_argument("--simulate", nargs="+", metavar="WAV")
    ap.add_argument("--out", default="results/live_sim.wav")
    args = ap.parse_args()

    if args.mt == "openrouter" and not os.environ.get("OPENROUTER_API_KEY"):
        env = Path(".env")
        if env.exists():
            os.environ["OPENROUTER_API_KEY"] = env.read_text().strip().split("=")[1]

    cfg = DSPConfig(input_gain=args.gain, vad_threshold=args.sensitivity,
                    noise_reduce=not args.no_noise_reduce,
                    compressor=not args.no_compressor,
                    comp_ratio=args.comp_ratio)
    tr = LiveTranslator(args.mt, args.model, cfg, args.left_lang, args.right_lang)
    if args.simulate:
        run_simulate(tr, args.simulate, args.out)
    else:
        run_live(tr)


if __name__ == "__main__":
    main()
