#!/usr/bin/env python3
"""
Скрипт загрузки моделей для AgentTranslator.

Скачивает и подготавливает:
1. Whisper (STT) — через faster-whisper (автоматически)
2. NLLB-600M (Translation) — через CTranslate2
3. Piper TTS голоса — из HuggingFace

Запуск: python download_models.py
Требуется интернет. Общий размер: ~1 GB.
"""

import logging
import subprocess
import sys
from pathlib import Path
from urllib.request import urlretrieve

import config

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger(__name__)


def download_file(url: str, dest: Path, description: str) -> None:
    """Скачать файл с прогрессом."""
    if dest.exists():
        logger.info("✅ %s уже скачан: %s", description, dest.name)
        return

    dest.parent.mkdir(parents=True, exist_ok=True)
    logger.info("⏬ Скачиваю %s...", description)
    logger.info("   URL: %s", url)

    def progress_hook(block_num: int, block_size: int, total_size: int) -> None:
        if total_size > 0:
            downloaded = block_num * block_size
            pct = min(100, downloaded * 100 // total_size)
            mb_done = downloaded / (1024 * 1024)
            mb_total = total_size / (1024 * 1024)
            print(
                f"\r   [{pct:3d}%] {mb_done:.1f} / {mb_total:.1f} MB",
                end="",
                flush=True,
            )

    urlretrieve(url, str(dest), reporthook=progress_hook)
    print()  # Новая строка после прогресс-бара
    logger.info("✅ %s скачан: %s", description, dest.name)


def download_whisper() -> None:
    """Скачать модель Whisper через faster-whisper (автоматическая загрузка)."""
    logger.info("=" * 60)
    logger.info("📢 Whisper STT (модель: %s)", config.WHISPER_MODEL_SIZE)
    logger.info("=" * 60)
    logger.info(
        "Модель Whisper скачается автоматически при первом запуске "
        "faster-whisper. Проверяю доступность библиотеки..."
    )

    try:
        from faster_whisper import WhisperModel

        logger.info("✅ faster-whisper установлен")

        # Инициализация вызывает скачивание модели
        logger.info(
            "⏬ Скачиваю/проверяю модель whisper-%s...",
            config.WHISPER_MODEL_SIZE,
        )
        _model = WhisperModel(
            config.WHISPER_MODEL_SIZE,
            device="cpu",
            compute_type=config.WHISPER_COMPUTE_TYPE,
        )
        logger.info("✅ Whisper %s готов к работе", config.WHISPER_MODEL_SIZE)
        del _model  # Освободить память

    except ImportError:
        logger.error(
            "❌ faster-whisper не установлен. Выполни: pip install faster-whisper"
        )
    except Exception as exc:
        logger.error("❌ Ошибка загрузки Whisper: %s", exc)


def download_nllb() -> None:
    """Скачать модель NLLB для перевода через CTranslate2."""
    logger.info("=" * 60)
    logger.info("🌐 NLLB Translation (модель: %s)", config.NLLB_MODEL_NAME)
    logger.info("=" * 60)

    try:
        import ctranslate2

        logger.info("✅ ctranslate2 установлен")
    except ImportError:
        logger.error(
            "❌ ctranslate2 не установлен. Выполни: pip install ctranslate2"
        )
        return

    # Проверяем, есть ли уже конвертированная модель
    ct2_model_dir = config.NLLB_MODEL_DIR
    if (ct2_model_dir / "model.bin").exists():
        logger.info("✅ NLLB модель уже подготовлена: %s", ct2_model_dir)
        return

    ct2_model_dir.mkdir(parents=True, exist_ok=True)

    # Скачиваем через ct2-opus-mt-converter или вручную
    logger.info(
        "⏬ Скачиваю и конвертирую NLLB-600M в формат CTranslate2..."
    )
    logger.info("   Это может занять несколько минут...")

    try:
        subprocess.run(
            [
                sys.executable,
                "-m",
                "ctranslate2.converters.transformers",
                "--model",
                config.NLLB_MODEL_NAME,
                "--output_dir",
                str(ct2_model_dir),
                "--quantization",
                config.NLLB_COMPUTE_TYPE,
            ],
            check=True,
            capture_output=False,
        )
        logger.info("✅ NLLB модель готова: %s", ct2_model_dir)
    except subprocess.CalledProcessError as exc:
        logger.error("❌ Ошибка конвертации NLLB: %s", exc)
        logger.info(
            "💡 Попробуй вручную:\n"
            "   ct2-transformers-converter "
            "--model facebook/nllb-200-distilled-600M "
            "--output_dir %s "
            "--quantization int8",
            ct2_model_dir,
        )
    except FileNotFoundError:
        logger.error(
            "❌ ct2-transformers-converter не найден. "
            "Установи: pip install ctranslate2 transformers sentencepiece"
        )


def download_piper_voices() -> None:
    """Скачать голоса Piper TTS."""
    logger.info("=" * 60)
    logger.info("🔊 Piper TTS голоса")
    logger.info("=" * 60)

    piper_dir = config.PIPER_MODELS_DIR
    piper_dir.mkdir(parents=True, exist_ok=True)

    for lang, voice_info in config.PIPER_VOICES.items():
        model_path = piper_dir / voice_info["model"]
        config_path = piper_dir / voice_info["config"]

        download_file(
            voice_info["url"],
            model_path,
            f"Piper голос [{lang}] модель",
        )
        download_file(
            voice_info["config_url"],
            config_path,
            f"Piper голос [{lang}] конфиг",
        )


def download_silero_vad() -> None:
    """Скачать модель Silero VAD (Voice Activity Detection)."""
    logger.info("=" * 60)
    logger.info("🎙 Silero VAD (детектор речи)")
    logger.info("=" * 60)

    try:
        import torch

        logger.info("✅ PyTorch установлен")

        # Silero VAD скачивается автоматически через torch.hub
        logger.info("⏬ Скачиваю Silero VAD...")
        _model, _utils = torch.hub.load(
            repo_or_dir="snakers4/silero-vad",
            model="silero_vad",
            force_reload=False,
        )
        logger.info("✅ Silero VAD готов")
        del _model, _utils

    except ImportError:
        logger.warning(
            "⚠️ PyTorch не установлен. VAD будет недоступен.\n"
            "   Для установки: pip install torch --index-url "
            "https://download.pytorch.org/whl/cpu"
        )
    except Exception as exc:
        logger.warning("⚠️ Ошибка загрузки Silero VAD: %s", exc)
        logger.info(
            "   VAD опционален — переводчик будет работать и без него "
            "(но с большей задержкой)"
        )


def verify_installation() -> None:
    """Проверить, что всё на месте."""
    logger.info("=" * 60)
    logger.info("🔍 Проверка установки")
    logger.info("=" * 60)

    checks = [
        ("faster-whisper", "from faster_whisper import WhisperModel"),
        ("ctranslate2", "import ctranslate2"),
        ("sentencepiece", "import sentencepiece"),
        ("numpy", "import numpy"),
        ("requests", "import requests"),
    ]

    all_ok = True
    for name, import_stmt in checks:
        try:
            exec(import_stmt)  # noqa: S102
            logger.info("  ✅ %s", name)
        except ImportError:
            logger.error("  ❌ %s — pip install %s", name, name)
            all_ok = False

    # Проверить файлы Piper
    for lang, voice in config.PIPER_VOICES.items():
        model_path = config.PIPER_MODELS_DIR / voice["model"]
        if model_path.exists():
            size_mb = model_path.stat().st_size / (1024 * 1024)
            logger.info("  ✅ Piper [%s] — %.1f MB", lang, size_mb)
        else:
            logger.error("  ❌ Piper [%s] — не найден", lang)
            all_ok = False

    # Проверить NLLB
    nllb_bin = config.NLLB_MODEL_DIR / "model.bin"
    if nllb_bin.exists():
        size_mb = nllb_bin.stat().st_size / (1024 * 1024)
        logger.info("  ✅ NLLB — %.1f MB", size_mb)
    else:
        logger.error("  ❌ NLLB — не найден")
        all_ok = False

    # Проверить словарь
    if config.PHRASE_DICT_PATH.exists():
        logger.info("  ✅ Словарь фраз")
    else:
        logger.warning("  ⚠️ Словарь фраз не найден (не критично)")

    if all_ok:
        logger.info("\n🎉 Всё готово! Запускай: python translator.py --from pt --to ru")
    else:
        logger.warning(
            "\n⚠️ Некоторые компоненты отсутствуют. "
            "Установи недостающие и запусти скрипт снова."
        )


def main() -> None:
    """Основная функция загрузки."""
    logger.info("🚀 AgentTranslator — Загрузка моделей")
    logger.info("   Общий размер: ~1 GB")
    logger.info("   Требуется интернет-соединение\n")

    download_whisper()
    print()
    download_nllb()
    print()
    download_piper_voices()
    print()
    download_silero_vad()
    print()
    verify_installation()


if __name__ == "__main__":
    main()
