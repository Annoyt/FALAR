"""
Конфигурация AgentTranslator.

Все пути, языковые коды и настраиваемые параметры вынесены сюда.
"""

import os
from pathlib import Path

# ─── Пути ───────────────────────────────────────────────────────────
BASE_DIR = Path(__file__).parent
MODELS_DIR = BASE_DIR / "models"
DATA_DIR = BASE_DIR / "data"
CACHE_DIR = BASE_DIR / "cache"

WHISPER_MODEL_DIR = MODELS_DIR / "whisper-small"
NLLB_MODEL_DIR = MODELS_DIR / "nllb-600m"
PIPER_MODELS_DIR = MODELS_DIR / "piper"

PHRASE_DICT_PATH = DATA_DIR / "phrase_dictionary.json"
PHRASE_CACHE_PATH = CACHE_DIR / "learned_phrases.json"

# ─── Whisper STT ────────────────────────────────────────────────────
WHISPER_MODEL_SIZE = "small"  # tiny, base, small, medium, large-v3-turbo
WHISPER_COMPUTE_TYPE = "int8"  # float16, int8, int8_float16
WHISPER_BEAM_SIZE = 5
WHISPER_VAD_FILTER = True  # Silero VAD — фильтр тишины

# ─── NLLB Translation ──────────────────────────────────────────────
# Коды языков NLLB (flores-200)
LANG_CODES = {
    "pt": "por_Latn",   # Португальский (латиница)
    "ru": "rus_Cyrl",   # Русский (кириллица)
    "en": "eng_Latn",   # Английский (запасной)
}

NLLB_MODEL_NAME = "facebook/nllb-200-distilled-600M"
NLLB_COMPUTE_TYPE = "int8"
NLLB_MAX_LENGTH = 512  # Максимальная длина перевода в токенах

# ─── Piper TTS ──────────────────────────────────────────────────────
PIPER_VOICES = {
    "pt": {
        "model": "pt_BR-faber-medium.onnx",
        "config": "pt_BR-faber-medium.onnx.json",
        "url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx.json",
    },
    "ru": {
        "model": "ru_RU-irina-medium.onnx",
        "config": "ru_RU-irina-medium.onnx.json",
        "url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/irina/medium/ru_RU-irina-medium.onnx",
        "config_url": "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/irina/medium/ru_RU-irina-medium.onnx.json",
    },
}

PIPER_SAMPLE_RATE = 22050
PIPER_SPEAKER_ID = None  # None = default speaker

# ─── VAD (Voice Activity Detection) ────────────────────────────────
VAD_THRESHOLD = 0.5        # Порог срабатывания (0.0 — 1.0)
VAD_MIN_SPEECH_MS = 250    # Минимальная длительность речи (мс)
VAD_MIN_SILENCE_MS = 1000  # Пауза для завершения фразы (мс)
AUDIO_SAMPLE_RATE = 16000  # Частота дискретизации для записи
AUDIO_CHUNK_SIZE = 512     # Размер аудио-чанка (сэмплы)

# ─── OpenRouter (гибридный режим) ──────────────────────────────────
OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions"
OPENROUTER_API_KEY = os.environ.get("OPENROUTER_API_KEY", "")

# Бесплатные модели (приоритет)
OPENROUTER_FREE_MODELS = [
    "google/gemma-3n-e4b:free",
    "meta-llama/llama-3.1-8b-instruct:free",
    "mistralai/mistral-7b-instruct:free",
]

# Платные, но дешёвые (fallback)
OPENROUTER_PAID_MODELS = [
    "google/gemini-2.0-flash-lite",   # $0.02/$0.05 per 1M tokens
    "deepseek/deepseek-chat-v3",      # $0.07/$0.16 per 1M tokens
]

OPENROUTER_MAX_TOKENS = 256
OPENROUTER_TEMPERATURE = 0.1  # Низкая для точного перевода
OPENROUTER_TIMEOUT = 10       # Секунд на ответ (при слабом интернете)

# ─── Кэш фраз ──────────────────────────────────────────────────────
CACHE_ENABLED = True
CACHE_MAX_SIZE = 10000    # Максимум фраз в кэше
CACHE_FUZZY_MATCH = True  # Нечёткое совпадение (расстояние Левенштейна ≤ 2)

# ─── Общие ──────────────────────────────────────────────────────────
LOG_LEVEL = "INFO"         # DEBUG, INFO, WARNING, ERROR
CONTINUOUS_MODE = True     # Непрерывное слушание (True) или push-to-talk (False)
PLAY_BEEP_ON_READY = True  # Звуковой сигнал готовности
