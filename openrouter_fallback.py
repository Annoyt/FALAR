"""
OpenRouter API fallback для гибридного перевода.

Используется когда:
1. Локальная модель (NLLB) не уверена в переводе
2. Есть интернет-соединение
3. Нужно более высокое качество перевода

Приоритет: бесплатные модели → дешёвые платные → ошибка.
Расход при Gemini Flash Lite: ~$0.02/1M токенов.
На $20 бюджета ≈ 400 000 предложений.
"""

import json
import logging
import time
from typing import Optional

import requests

import config

logger = logging.getLogger(__name__)


SYSTEM_PROMPTS = {
    "pt_to_ru": (
        "You are a translator. Translate the following text from "
        "Brazilian Portuguese to Russian. Output ONLY the translation, "
        "nothing else. No explanations, no quotes, no prefixes."
    ),
    "ru_to_pt": (
        "You are a translator. Translate the following text from "
        "Russian to Brazilian Portuguese. Output ONLY the translation, "
        "nothing else. No explanations, no quotes, no prefixes."
    ),
}

# Имена языков для логов
DIRECTION_NAMES = {
    "pt_to_ru": "PT-BR → RU",
    "ru_to_pt": "RU → PT-BR",
}


class OpenRouterTranslator:
    """Переводчик через OpenRouter API с автоматическим выбором модели."""

    def __init__(self, api_key: str | None = None):
        self.api_key = api_key or config.OPENROUTER_API_KEY
        self._available = bool(self.api_key)
        self._request_count = 0
        self._total_tokens = 0
        self._errors = 0

        if not self._available:
            logger.warning(
                "OpenRouter API ключ не задан. "
                "Гибридный режим отключён. "
                "Задайте OPENROUTER_API_KEY в переменных среды или config.py"
            )

    @property
    def is_available(self) -> bool:
        """Доступен ли API."""
        return self._available

    def translate(
        self,
        text: str,
        direction: str,
        preferred_model: str | None = None,
    ) -> Optional[str]:
        """
        Перевести текст через OpenRouter API.

        Args:
            text: Текст для перевода.
            direction: "pt_to_ru" или "ru_to_pt".
            preferred_model: Конкретная модель (или None для автовыбора).

        Returns:
            Переведённый текст или None при ошибке.
        """
        if not self._available:
            return None

        system_prompt = SYSTEM_PROMPTS.get(direction)
        if not system_prompt:
            logger.error("Неизвестное направление перевода: %s", direction)
            return None

        # Определяем порядок моделей для попыток
        models_to_try = self._get_models_order(preferred_model)

        for model in models_to_try:
            result = self._try_translate(text, system_prompt, model)
            if result is not None:
                dir_name = DIRECTION_NAMES.get(direction, direction)
                logger.info(
                    "[OpenRouter/%s] %s: '%s' → '%s'",
                    model.split("/")[-1],
                    dir_name,
                    text[:50],
                    result[:50],
                )
                return result

        logger.error(
            "Все модели OpenRouter недоступны. Перевод не выполнен."
        )
        return None

    def _get_models_order(
        self, preferred: str | None
    ) -> list[str]:
        """Построить список моделей в порядке приоритета."""
        models: list[str] = []
        if preferred:
            models.append(preferred)
        models.extend(config.OPENROUTER_FREE_MODELS)
        models.extend(config.OPENROUTER_PAID_MODELS)
        # Убрать дубликаты, сохранив порядок
        seen: set[str] = set()
        unique: list[str] = []
        for m in models:
            if m not in seen:
                seen.add(m)
                unique.append(m)
        return unique

    def _try_translate(
        self,
        text: str,
        system_prompt: str,
        model: str,
    ) -> Optional[str]:
        """Попытка перевода через конкретную модель."""
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
            "HTTP-Referer": "https://github.com/agenttranslator",
            "X-Title": "AgentTranslator",
        }

        payload = {
            "model": model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": text},
            ],
            "max_tokens": config.OPENROUTER_MAX_TOKENS,
            "temperature": config.OPENROUTER_TEMPERATURE,
        }

        try:
            start = time.monotonic()
            response = requests.post(
                config.OPENROUTER_API_URL,
                headers=headers,
                json=payload,
                timeout=config.OPENROUTER_TIMEOUT,
            )
            elapsed = time.monotonic() - start

            if response.status_code == 429:
                logger.warning(
                    "[OpenRouter/%s] Rate limit, пробуем следующую модель",
                    model,
                )
                return None

            response.raise_for_status()
            data = response.json()

            # Извлечь перевод
            translation = (
                data.get("choices", [{}])[0]
                .get("message", {})
                .get("content", "")
                .strip()
            )

            if not translation:
                logger.warning("[OpenRouter/%s] Пустой ответ", model)
                return None

            # Обновить статистику
            self._request_count += 1
            usage = data.get("usage", {})
            self._total_tokens += usage.get("total_tokens", 0)

            logger.debug(
                "[OpenRouter/%s] %.1fs, %d tokens",
                model,
                elapsed,
                usage.get("total_tokens", 0),
            )

            return translation

        except requests.exceptions.Timeout:
            logger.warning(
                "[OpenRouter/%s] Таймаут (%ds)",
                model,
                config.OPENROUTER_TIMEOUT,
            )
            return None

        except requests.exceptions.ConnectionError:
            logger.debug("[OpenRouter/%s] Нет соединения", model)
            self._available = False  # Пометить как недоступный
            return None

        except Exception as exc:
            self._errors += 1
            logger.error("[OpenRouter/%s] Ошибка: %s", model, exc)
            return None

    def stats(self) -> dict:
        """Статистика использования API."""
        return {
            "available": self._available,
            "requests": self._request_count,
            "total_tokens": self._total_tokens,
            "errors": self._errors,
            "estimated_cost_usd": self._total_tokens * 0.00005 / 1000,
        }
