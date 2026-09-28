# Проверочный набор для чтения снимков

32 снимка бразильских вывесок с Wikimedia Commons, по которым выбирались модели офлайн-чтения и
сверяется приложение ([results/2026-09-28-ocr.md](../../results/2026-09-28-ocr.md)).

- `photos.json` — откуда каждый снимок: адрес миниатюры 1280 px, sha256, страница файла, автор,
  лицензия (CC BY 2.0/3.0/4.0, CC BY-SA 2.0/3.0/4.0, CC0, общественное достояние). Сами снимки в
  git не лежат: `python3 tools/ocr_photos.py` скачивает их в `photos/` и сверяет.
- `gt.txt` — расшифровка текста на снимках вручную: 261 строка, 831 слово. Строки — как на
  вывеске, по порядку чтения; в расшифровку не вошли логотипы, номера машин, мелкий английский
  текст и надписи на заднем плане.
- `runs/` — выводы прогонов, эталон для сравнения с телефоном (`runs/ref`) и данные для
  `OcrCoreTest` (`runs/golden`); не в git, пересобираются:

```
.venv/bin/python tools/ocr_eval.py                                  # все кандидаты
.venv/bin/python tools/ocr_eval.py --only "v6 small det + v5 rec · 960" --save bench/ocr/runs/ref
.venv/bin/python tools/ocr_golden.py                                # данные для OcrCoreTest
.venv/bin/python tools/ocr_overlay.py bench/ocr/photos/p03.jpg      # макет перевода поверх фото
```

Модели-кандидаты лежат в `models/ocr-cand/` (не в git; выбранная пара — в `models/ocr/`).
