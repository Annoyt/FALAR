# models — всё, что уезжает на устройство

**Правило: пока нет релизной версии, всё, что лежит на телефоне, лежит и здесь.**
Телефон — только цель выката, а не хранилище. Место на устройстве проектной метрикой не является
и не измеряется; если там не хватает места, это решается на самом телефоне, а не урезанием проекта.

Проверка зеркала — сравнить дерево на устройстве с этим каталогом:

```bash
bash bench/apk/push_models.sh     # зальёт всё нужное и скажет, чего не хватает
```

## Что уезжает на телефон

| каталог | размер | что это | откуда взялось |
|---|---|---|---|
| `asr_multi/` | 640 МБ | parakeet-tdt-0.6b-v3 int8, распознавание обоих языков | HF `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` |
| `opus-onnx/pt2ru-int8/`, `ru2pt-int8/` | 562 МБ каждый | перевод, int8 ONNX | экспорт optimum-onnx из `Helsinki-NLP/opus-mt-tc-big-*` |
| `mt/pt2ru/`, `mt/ru2pt/` | 6,9 МБ | свой токенизатор: пьесы SentencePiece + vocab Marian | собрано из `source.spm` и `vocab.json` модели |
| `tts_ru/`, `tts_pt/` | 79 МБ каждый | Piper dmitri и faber + tokens.txt + espeak-ng-data | тарболы sherpa-onnx `vits-piper-*` |
| `vad/` | 636 КБ | silero VAD | sherpa-onnx |
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
