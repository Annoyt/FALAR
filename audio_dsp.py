"""DSP-цепочка для живого микрофона: входной gain, шумоподавление,
компрессор, адаптивный VAD, panning TTS-вывода по каналам TWS.

Все функции работают с float32 [-1, 1], sample_rate=16000 (вход) /
родная частота Piper (выход).
"""
from dataclasses import dataclass

import numpy as np


@dataclass
class DSPConfig:
    input_gain: float = 1.0        # линейное усиление микрофона (чувствительность)
    vad_threshold: float = 0.01    # порог RMS для детекции речи (чувствительность VAD)
    vad_silence_sec: float = 0.8   # тишина, завершающая фразу
    vad_min_speech_sec: float = 0.3
    noise_reduce: bool = True      # спектральное шумоподавление (noisereduce)
    noise_prop_decrease: float = 0.8
    compressor: bool = True
    comp_threshold_db: float = -20.0
    comp_ratio: float = 4.0
    comp_attack_ms: float = 5.0
    comp_release_ms: float = 100.0
    comp_makeup_db: float = 6.0


def apply_gain(x: np.ndarray, gain: float) -> np.ndarray:
    return np.clip(x * gain, -1.0, 1.0)


def noise_suppress(x: np.ndarray, sr: int, prop_decrease: float = 0.8) -> np.ndarray:
    import noisereduce as nr
    return nr.reduce_noise(y=x, sr=sr, prop_decrease=prop_decrease,
                           stationary=False).astype(np.float32)


def compress(x: np.ndarray, sr: int, threshold_db: float = -20.0,
             ratio: float = 4.0, attack_ms: float = 5.0,
             release_ms: float = 100.0, makeup_db: float = 6.0) -> np.ndarray:
    """Простой RMS-компрессор с сглаженной огибающей."""
    if len(x) == 0:
        return x
    eps = 1e-9
    level = np.abs(x)
    a_att = np.exp(-1.0 / (sr * attack_ms / 1000.0))
    a_rel = np.exp(-1.0 / (sr * release_ms / 1000.0))
    env = np.empty_like(level)
    e = 0.0
    for i, v in enumerate(level):
        a = a_att if v > e else a_rel
        e = a * e + (1 - a) * v
        env[i] = e
    env_db = 20 * np.log10(env + eps)
    over = env_db - threshold_db
    gain_db = np.where(over > 0, -over * (1 - 1 / ratio), 0.0) + makeup_db
    return np.clip(x * (10 ** (gain_db / 20)), -1.0, 1.0)


class AdaptiveVAD:
    """Энергетический VAD с адаптацией порога к уровню шума."""

    def __init__(self, cfg: DSPConfig, sr: int = 16000, frame_ms: int = 30):
        self.cfg = cfg
        self.sr = sr
        self.frame = int(sr * frame_ms / 1000)
        self.noise_floor = cfg.vad_threshold / 3

    def frame_rms(self, x: np.ndarray) -> float:
        return float(np.sqrt(np.mean(x ** 2) + 1e-12))

    def is_speech(self, frame: np.ndarray) -> bool:
        rms = self.frame_rms(frame)
        threshold = max(self.cfg.vad_threshold, self.noise_floor * 3)
        if rms > threshold:
            return True
        self.noise_floor = 0.95 * self.noise_floor + 0.05 * rms
        return False

    def feed(self, chunk: np.ndarray):
        """Генератор: отдаёт завершённую речевую фразу (np.ndarray) или None."""
        if not hasattr(self, "_buf"):
            self._buf = []
            self._sil = 0.0
            self._speaking = False
        self._buf.append(chunk)
        if self.is_speech(chunk):
            self._speaking = True
            self._sil = 0.0
            return None
        if self._speaking:
            self._sil += len(chunk) / self.sr
            if self._sil >= self.cfg.vad_silence_sec:
                audio = np.concatenate(self._buf)
                self._buf, self._speaking, self._sil = [], False, 0.0
                if len(audio) / self.sr >= self.cfg.vad_min_speech_sec:
                    return audio
        return None


def pan_stereo(mono: np.ndarray, pan: float) -> np.ndarray:
    """pan: -1.0 = полностью левый канал, +1.0 = правый. Equal-power."""
    theta = (pan + 1) * np.pi / 4
    left = mono * np.cos(theta)
    right = mono * np.sin(theta)
    return np.stack([left, right], axis=-1).astype(np.float32)


def preprocess_mic(x: np.ndarray, sr: int, cfg: DSPConfig) -> np.ndarray:
    """Полная входная цепочка: gain -> шумодав -> компрессор."""
    x = apply_gain(x, cfg.input_gain)
    if cfg.noise_reduce:
        x = noise_suppress(x, sr, cfg.noise_prop_decrease)
    if cfg.compressor:
        x = compress(x, sr, cfg.comp_threshold_db, cfg.comp_ratio,
                     cfg.comp_attack_ms, cfg.comp_release_ms, cfg.comp_makeup_db)
    return x
