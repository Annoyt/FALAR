# models — всё, что уезжает на устройство

**Правило: пока нет релизной версии, всё, что лежит на телефоне, лежит и здесь.**
Телефон — только цель выката, а не хранилище. Место на устройстве проектной метрикой не является
и не измеряется; если там не хватает места, это решается на самом телефоне, а не урезанием проекта.

Проверка зеркала — сравнить дерево на устройстве с этим каталогом:

```bash
bash bench/apk/push_models.sh     # зальёт всё нужное и скажет, чего не хватает
```

## Манифест и загрузка

`models/manifest.json` — единственный список того, что уезжает на телефон: путь на устройстве,
размер, sha256, обязательность (`core`/`optional`), лицензия и источник. Чужие модели
(parakeet, CAM++, Hy-MT2, silero, GTCRN) берутся из исходных репозиториев Hugging Face и релизов
sherpa-onnx; наши производные файлы (экспорт OPUS-MT в int8, токенизатор, голоса Piper в zip,
корпус Tatoeba, затравка) лежат в `youannoingme/agenttranslator-models` на Hugging Face
(`tools/models_publish.sh` их туда выкладывает). Собрать каталог на ПК:

```bash
python3 tools/models_fetch.py          # обязательное, ~1,9 ГиБ
python3 tools/models_fetch.py --all    # плюс LLM, отпечаток голоса, корпус, ещё ~1,1 ГиБ
python3 tools/models_fetch.py --check  # только сверить хэши
```

Все хэши в манифесте сверены с исходными репозиториями 19.09.2026: parakeet, CAM++, Hy-MT2
Q4_K_M, silero и GTCRN на устройстве совпадают с опубликованными байт в байт, голоса Piper —
с тарболами sherpa-onnx. Приложение с 0.21 качает по этому же манифесту само, при первом запуске.

## Что уезжает на телефон

| каталог | размер | что это | откуда взялось |
|---|---|---|---|
| `asr_multi/` | 640 МБ | parakeet-tdt-0.6b-v3 int8, распознавание обоих языков | HF `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` |
| `mt/pt2ru/`, `mt/ru2pt/` | 562 МБ каждый | перевод, int8 ONNX (три файла) + свой токенизатор: пьесы SentencePiece и vocab Marian | экспорт optimum-onnx из `Helsinki-NLP/opus-mt-tc-big-pt-zle` и `-zle-pt`; с 0.20 лежит там же, где на телефоне |
| `tts_ru/`, `tts_pt/` | 79 МБ каждый | Piper dmitri и faber + tokens.txt + espeak-ng-data | тарболы sherpa-onnx `vits-piper-*` |
| `silero_vad.onnx` | 636 КБ | silero VAD, в корне каталога, как на телефоне | релиз sherpa-onnx `asr-models` |
| `speaker/` | 29 МБ | 3D-Speaker CAM++, отпечаток голоса | HF `csukuangfj/speaker-embedding-models` |
| `denoiser/` | 528 КБ | GTCRN; **в конвейере выключен** — ухудшает распознавание | релиз sherpa-onnx `speech-enhancement-models` |
| `llm/` | 1,1 ГБ | Hy-MT2-1.8B Q4_K_M, контекстный уточнитель | HF `tencent/Hy-MT2-1.8B` |
| `openrouter.json` | — | ключ и список `:free`-моделей для кнопки «получше» | создать вручную, в репозиторий не класть |

Не из этого каталога, но тоже уезжает: `data/tatoeba/phrasebook_tatoeba.tsv` (24 МБ),
`data/common_words.txt` (816 КБ), `bench/apk/phrasebook_seed.json`.

## Что остаётся только на телефоне

Это данные, которые приложение создаёт само, — их в зеркале быть не должно:

- `learned.json` — выученные фразы и счётчики повторов
- `wordlist.json` — список своих слов, добавленный пользователем
- `speaker_profiles.json` — записанные профили голосов
- `cache/*.wav` — кэш синтезированного звука

## Что в проекте есть, но на телефон не уезжает

`opus-hf/` (922 МБ) — исходные веса Marian, из них сделан экспорт в ONNX.
`opus-ct2/` (467 МБ) — CTranslate2-сборка, нужна для замеров на компьютере
(`tools/placeholder_probe.py`, сверка пивота в `tools/tatoeba_phrasebook.py`).
`bergamot/` (181 МБ) — отброшенный по качеству движок, оставлен для воспроизводимости замера.
`opus-onnx/pt2ru/`, `ru2pt/` — fp32-версии, из которых квантованы int8.

Итого каталог ~8,9 ГБ, из них на телефон уезжает ~2,9 ГБ.
