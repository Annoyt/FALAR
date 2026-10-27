#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Собирает tools/colab/hymt2_ft.ipynb из tools/colab/hymt2_ft.py: модуль вкладывается в ноутбук целиком (%%writefile),
так что в Colab нужен один файл, а код в нём — тот же, что проверяет tools/colab/test_hymt2_ft.py на ПК.

    python3 tools/colab/build_notebook.py
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent

INTRO = """# Falar · дообучение Hy-MT2-1.8B в Google Colab (T4 или A100)

Hy-MT2-1.8B — модель локального уточнителя Falar. Здесь она учится **переводить фразу, которую исказил распознаватель,
с учётом прошлых реплик разговора, и отвечать только переводом самой фразы**. Зачем: сквозной тест «речь → перевод»
показал, что слух стоит переводу 0,05–0,06 COMET (в шуме до 0,18), а Hy-MT2 уже теряет на нём меньше переводчика
приложения (results/2026-10-27-asr-confidence.md, шаги 5–7).

**Данные — только открытые и синтетические.**
- Субтитры OpenSubtitles pt-BR↔ru (OPUS v2018, с границами фильмов: прошлые реплики фильма — контекст).
- Шум распознавания: фраза озвучивается голосами Piper, сверху — комната и шум (MUSAN, RIRS_NOISES), распознаёт
  parakeet-tdt-0.6b-v3 int8 — тот же, что в приложении.
- Разговоры владельца сюда не попадают; проверка на них — потом, локально на ПК.

**Что получится** — на Google Диске, в папке `falar-hymt2/export`, два файла GGUF Q4_K_M по ~1,1 ГБ:
- дообученный уточнитель;
- исходная модель, сжатая тем же путём, — для честного сравнения.

**Как запустить**
1. Среда выполнения → Сменить среду выполнения → **T4 GPU** или **A100 GPU** (и «Высокий объём RAM», если предложит).
2. Среда выполнения → **Выполнить всё**. Colab попросит доступ к Google Диску: итоги этапов сохраняются туда.
3. Вкладку не закрывать. Если сессия оборвётся — снова «Выполнить всё»: готовые этапы пропускаются.

На T4 ноутбук сам переходит на fp16 (bf16 у T4 нет, а Hy-MT2 в fp16 считает без потерь: проверено) и пачки 8 × 4.
Настройки по умолчанию — под бесплатную T4: 40 тыс. примеров, 25 % с шумом, оценка по 300 фраз; с A100 можно
поднять до 100 тыс. Настройки можно поменять и посреди работы: другой отбор сам отложит прежний в `stale/`.

**Время, примерно** (настройки по умолчанию):

| этап | T4 (2 ядра процессора) | A100 |
|---|---|---|
| корпус | 15 мин | 15 мин |
| LaBSE | 5 мин | 3 мин |
| шум (на процессоре) | 1,5 ч | 20 мин |
| обучение | 1–1,5 ч | 20 мин |
| оценка | 20 мин | 10 мин |
| выгрузка | 25 мин | 10 мин |
| **всего** | **~3,5 ч** | **~1,5 ч** |

Этап шума идёт только на процессоре. Если Colab даёт среду с большим числом ядер (например, хост TPU), этот этап можно
прогнать там, а потом вернуться на T4: всё готовое лежит на Диске.

**Лицензии:**
- Hy-MT2 — Apache-2.0;
- parakeet — CC-BY-4.0;
- MUSAN — CC BY 4.0;
- RIRS_NOISES — Apache-2.0;
- OpenSubtitles (OPUS) — Lison & Tiedemann, 2016; на этих же данных учился и нынешний переводчик OPUS-MT;
- голоса Piper — только для обучающих данных, наружу не идут."""

SETTINGS = """# Что можно менять; остальное — CFG в hymt2_ft.py (ячейка ниже).
SETTINGS = dict(
    work='/content/drive/MyDrive/falar-hymt2',  # итоги этапов — на Google Диске
    n_train=40_000,      # обучающих примеров, поровну pt→ru и ru→pt (с A100 — можно 100_000)
    noisy_share=0.25,    # доля с шумом распознавания: больше — дольше этап «шум» (он на процессоре)
    n_test=300,          # фраз отложенного теста с шумом на направление
    noise='musan',       # 'musan' — шум, музыка, говор (11 ГБ); 'rirs' — только шумы RIRS_NOISES (1,3 ГБ), быстрее
    eval_limit=300,      # фраз в каждом наборе оценки (0 — все: Tatoeba 1000 / 927)
    full_ft=False,       # True — полное дообучение вместо LoRA (только A100 80 ГБ)
    epochs=1,
)"""

ENV = """import os, shutil, subprocess
gpu = subprocess.run(['nvidia-smi', '--query-gpu=name,memory.total', '--format=csv,noheader'], capture_output=True,
                     text=True).stdout.strip() if shutil.which('nvidia-smi') else ''
print('Видеокарта:', gpu or 'нет', '· ядер процессора:', os.cpu_count())
if not gpu:
    print('Видеокарты нет: здесь идут только этапы на процессоре — корпус, отбор, шум, примеры. LaBSE, обучение и '
          'оценка — на T4 или A100: готовое лежит на Диске, после смены среды «Выполнить всё» продолжит.')
elif 'T4' in gpu:
    print('T4: fp16 вместо bf16, пачки 8 × 4; всё примерно втрое-впятеро дольше, чем на A100.')
from google.colab import drive
drive.mount('/content/drive')
%pip -q install "transformers==4.57.6" "peft==0.18.1" "accelerate==1.10.1" "sentence-transformers==5.1.2" "sherpa-onnx==1.13.8" "sacrebleu==2.6.0" soundfile
import transformers, peft
assert transformers.__version__ == '4.57.6', \\
    f'transformers {transformers.__version__}: Среда выполнения → Перезапустить сеанс, затем снова «Выполнить всё»'
print('transformers', transformers.__version__, '· peft', peft.__version__)"""

IMPORT = """import importlib, sys
sys.path.insert(0, '/content')
import hymt2_ft as H
importlib.reload(H)
cfg = dict(H.CFG, **SETTINGS, _set=tuple(SETTINGS))
print({k: cfg[k] for k in SETTINGS})"""

STAGES = [
    ('corpus', '## 4. Корпус\nСубтитры pt-BR↔ru (архив 741 МБ), чистка (песни, звуки, титры, два говорящих в строке), '
     'прошлые реплики фильма — контекст. Фразы наборов проверки качества (Tatoeba, ситуации) исключены. Фильмы делятся '
     'на обучение, проверку и тест: тестовых фильмов в обучении нет. ~15 мин.'),
    ('labse', '## 5. LaBSE\nОтсев плохо выровненных пар субтитров по смысловому сходству. ~5 мин.'),
    ('select', '## 6. Отбор\nОбучающие, проверочные и тестовые фразы; у доли обучающих и у всех тестовых — шум '
     'распознавания.'),
    ('noisy', '## 7. Шум распознавания\nОзвучка Piper (6 голосов pt-BR, 4 ru) → комната и шум (0–20 dB) → parakeet int8. '
     'Слишком испорченное (WER выше 0,6) и распознанное чужим алфавитом в обучение не идёт: учим восстанавливать, а '
     'не сочинять. Идёт на процессоре кусками по 2000 фраз — каждый сразу на Диск; скорость видна после первого куска.'),
    ('sft', '## 8. Примеры\nЧат Hy-MT2: простая подсказка или подсказка уточнителя приложения с прошлыми репликами '
     '(как TranslatorService.hyPrompt); ответ — только перевод фразы.'),
    ('train', '## 9. Обучение\nLoRA r 64 на всех линейных слоях, lr 2e-4, косинус до 10 % (рекомендации Tencent для 1.8B); '
     'потери — только на ответе. На T4 — fp16, на A100 — bf16. Контрольные точки — на Диск каждые 500 шагов: '
     'после обрыва обучение продолжится с последней.'),
    ('evaluate', '## 10. Оценка: до и после\nTatoeba и ситуации (эталон от людей) и отложенный тест с шумом (фильмы, '
     'которых не было в обучении): распознанное с простой подсказкой и с подсказкой уточнителя, и сказанное — потолок. '
     'COMET и chrF, парный бутстрэп. ~20 мин.'),
    ('export', '## 11. Выгрузка\nСлияние LoRA и GGUF Q4_K_M (llama.cpp v0.7.0) — и исходная модель тем же путём. ~10 мин.'),
]

OUTRO = """## Что дальше — на ПК

1. С Google Диска скачайте папку `falar-hymt2/export` (два `.gguf`) и файл `falar-hymt2/eval/summary.md`.
2. Положите их на ПК в `~/.cache/falar-stand/hymt2-ft/` и скажите Claude. Он прогонит локально:
   - сквозной тест «речь → перевод» на записях комнаты;
   - наборы проверки качества;
   - ваши настоящие фразы — только локально.

   Сравнение пойдёт с исходной моделью, сжатой тем же путём, по критериям, записанным до замера.
3. На телефон — только если пройдёт."""


def md(text):
    return dict(cell_type='markdown', metadata={}, source=lines(text))


def code(text):
    return dict(cell_type='code', metadata={}, execution_count=None, outputs=[], source=lines(text))


def lines(text):
    xs = text.split('\n')
    return [x + '\n' for x in xs[:-1]] + [xs[-1]]


def build():
    module = (HERE / 'hymt2_ft.py').read_text(encoding='utf-8')
    cells = [md(INTRO), md('## 1. Настройки'), code(SETTINGS), md('## 2. Окружение\nВидеокарта, Google Диск, '
             'библиотеки нужных версий (transformers 4.57.6 — как у модели; с 5.x Hy-MT2 работает не так).'), code(ENV),
             md('## 3. Код конвейера\nТот же файл, что `tools/colab/hymt2_ft.py` в репозитории Falar.'),
             code('%%writefile hymt2_ft.py\n' + module.rstrip('\n')), code(IMPORT)]
    for name, text in STAGES:
        cells += [md(text), code(f'H.{name}(cfg)')]
    cells += [code("from IPython.display import Markdown, display\n"
                   "display(Markdown(open(f\"{cfg['work']}/eval/summary.md\", encoding='utf-8').read()))\n"
                   "for f in sorted(__import__('pathlib').Path(cfg['work'], 'export').glob('*.gguf')):\n"
                   "    print(f, f'{f.stat().st_size / 2 ** 30:.2f} ГБ')"), md(OUTRO)]
    nb = dict(nbformat=4, nbformat_minor=0, cells=cells,
              metadata=dict(accelerator='GPU', colab=dict(provenance=[], gpuType='A100', machine_shape='hm'),
                            kernelspec=dict(name='python3', display_name='Python 3'), language_info=dict(name='python')))
    out = HERE / 'hymt2_ft.ipynb'
    out.write_text(json.dumps(nb, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    return out


if __name__ == '__main__':
    print(build())
