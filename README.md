# 🎧 AgentTranslator — Офлайн PT-BR ↔ RU переводчик для Poco X7 Pro

Полностью локальный синхронный переводчик для общения на бразильском
португальском и русском языке. Работает офлайн на телефоне.

## Быстрый старт: RTranslator (5 минут)

### 1. Установка
```
Google Play → поиск "RTranslator"
или
GitHub: https://github.com/niedev/RTranslator/releases → скачать APK
```

### 2. Первый запуск (нужен Wi-Fi)
1. Открыть приложение
2. Перейти в **Settings → Language Packs**
3. Скачать: **Portuguese (Brazil)** (~500 MB)
4. Скачать: **Russian** (~500 MB)
5. Дождаться загрузки моделей

### 3. Настройка TTS (озвучка)
```
Настройки Android → Спец. возможности → Синтез речи
→ Предпочитаемый движок: Google TTS
→ Скачать голоса: Português (Brasil) + Русский
```

**Для лучшего качества голоса** установи VoxSherpa из Play Store:
```
Play Store → VoxSherpa → Установить
→ Скачать голоса: pt_BR (faber) + ru_RU (irina)
→ Настройки Android → Синтез речи → Выбрать VoxSherpa
```

### 4. Подключение наушника
1. Подключить Bluetooth наушник к телефону
2. В RTranslator: Settings → Audio Output → Bluetooth

### 5. Использование

**Walkie-Talkie (один телефон):**
1. Открыть RTranslator → Walkie-Talkie
2. Выбрать языки: Portuguese (Brazil) ↔ Russian
3. Говорите по очереди — приложение автоматически определяет язык
4. Перевод озвучивается в наушнике

**Conversation (два телефона):**
1. На обоих телефонах открыть RTranslator → Conversation
2. Соединить телефоны по Bluetooth
3. Каждый говорит на своём языке — перевод приходит в наушник собеседника

---

## Продвинутый вариант: Termux + Python

Если хочешь больше контроля — используй скрипты из этого проекта в Termux.

### Установка Termux
```bash
# Установить Termux из F-Droid (НЕ из Play Store — там устаревшая версия)
# https://f-droid.org/packages/com.termux/

# После установки — дать разрешение на микрофон:
termux-setup-storage
```

### Установка зависимостей
```bash
# Обновить пакеты
pkg update && pkg upgrade -y

# Установить базовые инструменты
pkg install python ffmpeg cmake git wget -y

# Установить Python-библиотеки
pip install faster-whisper ctranslate2 sentencepiece requests piper-tts sounddevice numpy

# Скачать модели (один раз, ~1 GB)
python download_models.py
```

### Запуск переводчика
```bash
# Запуск в режиме PT→RU (слушает португальский, говорит по-русски)
python translator.py --from pt --to ru

# Запуск в режиме RU→PT (слушает русский, говорит по-португальски)
python translator.py --from ru --to pt

# Запуск с гибридным режимом (офлайн + OpenRouter fallback)
python translator.py --from pt --to ru --hybrid --api-key YOUR_OPENROUTER_KEY
```

---

## Структура проекта

```
agenttranslator/
├── README.md                  # Этот файл
├── translator.py              # Основной скрипт переводчика
├── download_models.py         # Скрипт загрузки моделей
├── openrouter_fallback.py     # Модуль OpenRouter API
├── phrase_cache.py            # Локальный словарь-кэш
├── config.py                  # Конфигурация
├── data/
│   └── phrase_dictionary.json # Словарь частых фраз PT↔RU
└── models/                    # Модели (создаётся после download_models.py)
    ├── whisper-small/
    ├── nllb-600m/
    └── piper/
```

## Лицензия
MIT — для личного использования. Модели NLLB — CC BY-NC 4.0.
