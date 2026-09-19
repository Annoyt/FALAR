"""
Модуль кэширования фраз для мгновенного перевода.

Использует предзагруженный словарь + динамический кэш,
который пополняется каждым новым переводом.
Поддерживает нечёткое совпадение (fuzzy matching).
"""

import json
import logging
from pathlib import Path
from difflib import SequenceMatcher

import config

logger = logging.getLogger(__name__)


class PhraseCache:
    """Двунаправленный кэш фраз PT↔RU с нечётким поиском."""

    def __init__(self):
        self._static_pt_ru: dict[str, str] = {}
        self._static_ru_pt: dict[str, str] = {}
        self._dynamic_pt_ru: dict[str, str] = {}
        self._dynamic_ru_pt: dict[str, str] = {}
        self._load_static_dictionary()
        self._load_dynamic_cache()

    # ─── Загрузка ───────────────────────────────────────────────────

    def _load_static_dictionary(self) -> None:
        """Загрузить предзаписанный словарь из JSON."""
        dict_path = config.PHRASE_DICT_PATH
        if not dict_path.exists():
            logger.warning("Словарь фраз не найден: %s", dict_path)
            return

        with open(dict_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        self._static_pt_ru = {
            self._normalize(k): v
            for k, v in data.get("pt_to_ru", {}).items()
        }
        self._static_ru_pt = {
            self._normalize(k): v
            for k, v in data.get("ru_to_pt", {}).items()
        }
        total = len(self._static_pt_ru) + len(self._static_ru_pt)
        logger.info("Загружено %d фраз из словаря", total)

    def _load_dynamic_cache(self) -> None:
        """Загрузить динамический кэш (выученные фразы)."""
        cache_path = config.PHRASE_CACHE_PATH
        if not cache_path.exists():
            return

        with open(cache_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        self._dynamic_pt_ru = data.get("pt_to_ru", {})
        self._dynamic_ru_pt = data.get("ru_to_pt", {})
        total = len(self._dynamic_pt_ru) + len(self._dynamic_ru_pt)
        logger.info("Загружено %d фраз из кэша", total)

    def _save_dynamic_cache(self) -> None:
        """Сохранить динамический кэш на диск."""
        cache_path = config.PHRASE_CACHE_PATH
        cache_path.parent.mkdir(parents=True, exist_ok=True)

        data = {
            "pt_to_ru": self._dynamic_pt_ru,
            "ru_to_pt": self._dynamic_ru_pt,
        }
        with open(cache_path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

    # ─── Нормализация ───────────────────────────────────────────────

    @staticmethod
    def _normalize(text: str) -> str:
        """Нормализация текста: lowercase, strip, убрать лишние пробелы."""
        return " ".join(text.lower().strip().split())

    @staticmethod
    def _similarity(a: str, b: str) -> float:
        """Коэффициент схожести двух строк (0.0 — 1.0)."""
        return SequenceMatcher(None, a, b).ratio()

    # ─── Поиск ──────────────────────────────────────────────────────

    def lookup(
        self,
        text: str,
        direction: str,
        fuzzy_threshold: float = 0.85,
    ) -> str | None:
        """
        Поиск фразы в кэше.

        Args:
            text: Исходный текст
            direction: "pt_to_ru" или "ru_to_pt"
            fuzzy_threshold: Минимальный коэффициент совпадения для fuzzy match

        Returns:
            Перевод или None, если не найдено.
        """
        normalized = self._normalize(text)

        if direction == "pt_to_ru":
            dicts = [self._static_pt_ru, self._dynamic_pt_ru]
        elif direction == "ru_to_pt":
            dicts = [self._static_ru_pt, self._dynamic_ru_pt]
        else:
            logger.error("Неизвестное направление: %s", direction)
            return None

        # Точное совпадение
        for d in dicts:
            if normalized in d:
                logger.debug("Кэш HIT (exact): '%s'", normalized)
                return d[normalized]

        # Нечёткое совпадение
        if config.CACHE_FUZZY_MATCH:
            best_match: str | None = None
            best_score: float = 0.0

            for d in dicts:
                for key, value in d.items():
                    score = self._similarity(normalized, key)
                    if score > best_score and score >= fuzzy_threshold:
                        best_score = score
                        best_match = value

            if best_match is not None:
                logger.debug(
                    "Кэш HIT (fuzzy, score=%.2f): '%s'",
                    best_score,
                    normalized,
                )
                return best_match

        logger.debug("Кэш MISS: '%s'", normalized)
        return None

    # ─── Добавление ─────────────────────────────────────────────────

    def add(self, source: str, translation: str, direction: str) -> None:
        """
        Добавить перевод в динамический кэш.

        Args:
            source: Исходный текст
            translation: Переведённый текст
            direction: "pt_to_ru" или "ru_to_pt"
        """
        normalized = self._normalize(source)

        if direction == "pt_to_ru":
            target_dict = self._dynamic_pt_ru
        elif direction == "ru_to_pt":
            target_dict = self._dynamic_ru_pt
        else:
            return

        # Проверить лимит кэша
        total = len(self._dynamic_pt_ru) + len(self._dynamic_ru_pt)
        if total >= config.CACHE_MAX_SIZE:
            logger.warning("Кэш заполнен (%d фраз), новые не добавляются", total)
            return

        target_dict[normalized] = translation
        self._save_dynamic_cache()
        logger.debug("Кэш ADD: '%s' → '%s'", normalized, translation)

    # ─── Статистика ─────────────────────────────────────────────────

    def stats(self) -> dict:
        """Статистика кэша."""
        return {
            "static_pt_ru": len(self._static_pt_ru),
            "static_ru_pt": len(self._static_ru_pt),
            "dynamic_pt_ru": len(self._dynamic_pt_ru),
            "dynamic_ru_pt": len(self._dynamic_ru_pt),
            "total": (
                len(self._static_pt_ru)
                + len(self._static_ru_pt)
                + len(self._dynamic_pt_ru)
                + len(self._dynamic_ru_pt)
            ),
        }
