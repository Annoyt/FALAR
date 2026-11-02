#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Собирает tools/colab/parakeet_ft.ipynb из tools/colab/parakeet_ft.py: модуль вкладывается в ноутбук целиком
(%%writefile), этапы идут в отдельном окружении Python 3.12 с NeMo.

    python3 tools/colab/build_parakeet_notebook.py
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
NEMO = 'nemo_toolkit[asr]==2.7.3'
VENV = '/content/nemo-venv'

INTRO = """# Falar · дообучение распознавателя parakeet-tdt-0.6b-v3 в Google Colab

parakeet-tdt-0.6b-v3 — распознаватель речи Falar, один на португальский и русский. Здесь он дообучается **слышать
бразильский португальский в шуме — и не забывать русский**.

**Почему так.** Готовое дообучение «только pt-BR» (TAGARELA) на записях комнаты дало на португальском −1,2 п.п.
(незначимо), а русский стёрло: WER 113 %. Поэтому русский учится вместе с португальским, а главный упор — на шум. К
речи на лету подмешиваются шум, музыка, говор нескольких голосов (как в кафе) и эхо комнаты. Каша в разговорах
владельца — как раз в шуме.

**Данные — открытые; разговоры владельца сюда не попадают.**
- Португальский: CORAA v1.1 (только pt-BR, без замечаний оценщиков; CC BY-NC-ND 4.0) и CORAA-MUPE (интервью со
  знаками).
- Русский: Golos farfield (дальний микрофон, лицензия Сбера, ~10 ч), SOVA RuDevices (речь на устройствах, CC BY 4.0)
  и FLEURS ru (CC BY 4.0). Русского меньше португальского, поэтому его фразы повторяются, пока его не станет треть
  часов обучения: иначе модель его забывает.
- Отложенные тесты: CORAA, MUPE, FLEURS pt-BR и ru, Golos farfield, SOVA.
- Шум: MUSAN (CC BY 4.0), комнаты RIRS_NOISES (Apache-2.0).

parakeet пишет со знаками и заглавными, и это нельзя потерять: от знаков зависит перевод. Где в данных их нет (CORAA,
Golos), слова берутся из разметки, а знаки и заглавные — из ответа самого parakeet. Междометия («ãh», «э-э»)
убираются.

**Что получится** — на Google Диске, в `falar-parakeet/export`: `encoder.int8.onnx`, `decoder.int8.onnx`,
`joiner.int8.onnx`, `tokens.txt`. Это та же форма, что у модели в приложении (sherpa-onnx), около 650 МБ. И таблица
до/после в `falar-parakeet/eval/summary.md`.

**Как запустить.** Правило: всё для обучения сначала ложится на Google Диск, а видеокарта только учит. 02.11 звук и
шум заново качались на каждой новой машине Colab и съели часы A100.
1. **Подготовка — один раз, без видеокарты.** Среда выполнения → Сменить среду выполнения → **CPU** (лучше «Высокий
   объём RAM»: больше ядер) → **Выполнить всё**. Час без видеокарты в Colab в десятки раз дешевле часа A100: расход
   единиц в час виден при выборе среды. Colab попросит доступ к Google Диску. Данные и шум соберутся и лягут архивом в
   `falar-parakeet/shards`, исходная модель — в `falar-parakeet/base`; этапы на видеокарте пропустятся.
2. **Обучение.** Сменить среду выполнения → **A100 GPU** (или **L4** — пачки сами станут меньше, всё вдвое дольше) →
   **Выполнить всё**. Звук, шум и модель копируются с Диска за минуты, дальше — разметка, обучение, оценка, выгрузка.
3. Вкладку не закрывать до последней ячейки: она ждёт, пока всё запишется на Диск. Если сессия оборвётся — снова
   «Выполнить всё»: готовые этапы пропускаются, звук и шум снова берутся из архива. Контрольные точки обучения (по
   ~7 ГБ) лежат на диске машины Colab, не на Google Диске: на той же машине обучение продолжится с последней точки,
   на новой — начнётся сначала. Не хватило видеопамяти — обучение само продолжит с последней точки с пачкой меньше.

**Журналы:** вывод каждого этапа пишется ещё и в `falar-parakeet/logs/<этап>.log` на Диске. Если этап упадёт, «Выполнить
всё» остановится на нём, а журнал останется, даже если закрыть Colab.

**Место на Google Диске:** около 25 ГБ — архив звука и шума (~20 ГБ, точный размер — в журнале `pack`), исходная модель
2,4 ГБ, дообученная 2,5 ГБ, выгрузка 0,65 ГБ, списки фраз и таблица.

**Время на A100:**

| этап | время | откуда |
|---|---|---|
| окружение (NeMo в Python 3.12) | 5 мин | оценка |
| данные: ~200 ч звука потоком с Hugging Face | 25 мин | Colab 02.11 |
| шум: MUSAN 11 ГБ и RIRS 1,3 ГБ с openslr | 1 ч 40 мин | Colab 02.11 |
| архив на Диск и с Диска на новую машину | минуты | первый замер — в журналах `pack`, `data`, `noise` |
| разметка (parakeet по всем фразам) | 15 мин | оценка |
| обучение (5000 шагов, проверка каждые 500) | ~1 ч 10 мин | Colab 02.11: 2800 шагов за 38 мин, ещё без корзин по длине |
| оценка | 15 мин | оценка |
| выгрузка | 10 мин | оценка |

Проверка всего пути за ~20 минут — `PROFILE = 'smoke'` (малые данные, 20 шагов). Она годится и на T4."""

SETTINGS = """# Объём — по видеокарте сам (PROFILE='auto'): A100 80 ГБ — пачки по 900 с звука, 40 ГБ — 400, L4 — 200, T4 — 150.
# 'smoke' — проверка всего пути на малых данных. Отдельный ключ в SETTINGS сильнее профиля. Остальное — CFG в
# parakeet_ft.py (ячейка ниже).
PROFILE = 'auto'
SETTINGS = dict(
    work='/content/drive/MyDrive/falar-parakeet',   # итоги этапов — на Google Диске
    hours=dict(coraa=70, mupe=70, golos_far=60, sova=50, fleurs_ru=8),   # часов обучения по источникам
    max_steps=5000,
    lr=3e-5,
)
import json
json.dump(dict(SETTINGS, profile=PROFILE), open('/content/parakeet_cfg.json', 'w'), ensure_ascii=False)
WORK = SETTINGS['work']   # журналы этапов — в WORK/logs на Диске: вывод не теряется, если закрыть Colab"""

ENV = f"""import os, shutil, subprocess
gpu = subprocess.run(['nvidia-smi', '--query-gpu=name,memory.total', '--format=csv,noheader'], capture_output=True,
                     text=True).stdout.strip() if shutil.which('nvidia-smi') else ''
GPU = bool(gpu)
print('Видеокарта:', gpu or 'нет', '· ядер процессора:', os.cpu_count())
if not GPU:
    print('Без видеокарты — подготовка: данные, шум и архив на Google Диск. Этапы на видеокарте пропустятся.')
from google.colab import drive
drive.mount('/content/drive')
# numba (потеря TDT) — с NVVM того же CUDA, что у драйвера Colab: системный, а не случайный из пакетов
if os.path.isdir('/usr/local/cuda'):
    os.environ.setdefault('CUDA_HOME', '/usr/local/cuda')
print('CUDA_HOME =', os.environ.get('CUDA_HOME', '(нет)'))
# NeMo — в своём окружении Python 3.12: в Colab новее Python, и не всё из NeMo под него собрано
if not os.path.exists('{VENV}/bin/python'):
    !pip -q install uv
    !uv venv --python 3.12 {VENV}
# установка — каждый раз: uv быстро проверит готовое и поправит версии (cuda-bindings < 13: драйвер Colab — CUDA 12)
!uv pip install --python {VENV}/bin/python "{NEMO}" "huggingface_hub>=0.34,<1.0" "numba==0.61.2" "numpy<2.3" "cuda-bindings<13" "datasets>=3" soundfile librosa "onnx>=1.17" "onnxruntime>=1.20"
!{VENV}/bin/python -c "import torch, nemo; print('NeMo', nemo.__version__, '· torch', torch.__version__, '· CUDA', torch.cuda.is_available())\""""

# этапы: имя, нужна ли видеокарта, описание
STAGES = [
    ('data', False, '## 4. Данные\nCORAA (только pt-BR, без замечаний оценщиков), CORAA-MUPE, Golos farfield, SOVA, FLEURS '
     'ru — потоком с Hugging Face; звук — 16 кГц FLAC на диск машины Colab. Отложенные тесты — из своих частей каждого '
     'набора. Каждый собранный источник сразу ложится на Диск. На новой машине звук берётся из архива на Диске (этап 6), '
     'а чего там нет — заново потоком. Строка хода — раз в минуту.'),
    ('noise', False, '## 5. Шум\nMUSAN (шум, музыка) и говор: смесь 3–6 голосов MUSAN, как в кафе. Комнаты — RIRS_NOISES. '
     'На новой машине — из архива на Диске, без загрузки с openslr.'),
    ('pack', False, '## 6. Архив на Google Диск\nЗвук, шум и исходная модель — на Диск, один раз: частями tar по 1 ГБ (Диск '
     'в Colab медлен на множестве мелких файлов), со списком размеров для сверки. Уже лежащее второй раз не кладётся.'),
    ('labels', True, '## 7. Разметка\nparakeet распознаёт каждую фразу. Где в данных нет знаков, слова берутся из разметки, '
     'а знаки и заглавные — из его ответа. Фраза отбрасывается, если он расходится с разметкой больше чем на половину '
     'слов или пишет цифрами то, что в разметке словами.'),
    ('train', True, '## 8. Обучение\nВся модель, bf16, AdamW, разогрев и косинус. Шум подмешивается к 60 % фраз (0–20 дБ), '
     'эхо — к 30 %. Проверка каждые 500 шагов на отложенных фразах обоих языков; лучшая модель — на Диск.'),
    ('evaluate', True, '## 9. Оценка: до и после\nWER на отложенных тестах — чистых и с шумом (5 и 10 дБ, один и тот же '
     'шум для обеих моделей), без учёта знаков и регистра; разница — с 95 % интервалом.'),
    ('export', True, '## 10. Выгрузка\nONNX для sherpa-onnx — как в приложении: кодировщик, декодер, объединитель, int8.'),
]

FINAL = """import json, os
from IPython.display import Markdown, display
from google.colab import drive
w = json.load(open('/content/parakeet_cfg.json'))['work']
if GPU:
    display(Markdown(open(f'{w}/eval/summary.md', encoding='utf-8').read()))
    for f in sorted(os.listdir(f'{w}/export')):
        print(f, f'{os.path.getsize(f"{w}/export/{f}") / 2 ** 20:.0f} МБ')
else:
    print('Подготовка готова: звук и шум — в falar-parakeet/shards, исходная модель — в falar-parakeet/base.')
    print('Дальше: Среда выполнения → Сменить среду выполнения → A100 GPU → «Выполнить всё».')
print('Жду, пока всё запишется на Google Диск…')
drive.flush_and_unmount()   # запись на Диск идёт в фоне: без этого закрытая вкладка может оборвать архив
print('Google Диск: всё записано — вкладку можно закрыть.')"""

OUTRO = """## Что дальше — на ПК

1. С Google Диска скачайте папки `falar-parakeet/export` и `falar-parakeet/eval`.
2. Положите их на ПК в `.stand/parakeet-ft/` в папке проекта и скажите Claude. Он прогонит на записях комнаты (21
   запись, португальский и русский) дообученную модель против нынешней, тем же путём, что в приложении, по критериям,
   записанным до замера.
3. На телефон — только если пройдёт."""


def md(text):
    return dict(cell_type='markdown', metadata={}, source=lines(text))


def code(text):
    return dict(cell_type='code', metadata={}, execution_count=None, outputs=[], source=lines(text))


def lines(text):
    xs = text.split('\n')
    return [x + '\n' for x in xs[:-1]] + [xs[-1]]


def build():
    module = (HERE / 'parakeet_ft.py').read_text(encoding='utf-8')
    cells = [md(INTRO), md('## 1. Настройки'), code(SETTINGS),
             md(f'## 2. Окружение\nВидеокарта (без неё — только подготовка), Google Диск, NeMo ({NEMO}) в отдельном '
                f'окружении Python 3.12.'), code(ENV),
             md('## 3. Код конвейера\nТот же файл, что `tools/colab/parakeet_ft.py` в репозитории Falar.'),
             code('%%writefile /content/parakeet_ft.py\n' + module.rstrip('\n'))]
    for name, gpu, text in STAGES:
        # «!» не останавливает «Выполнить всё», если команда упала: тогда следующие этапы шли впустую и ставили
        # зелёные галочки (Colab, 02.11). Проверка кода выхода останавливает прогон на упавшем этапе.
        # вывод — и сюда, и в WORK/logs/<этап>.log на Диске (pipefail: код выхода — у самого этапа, а не у tee)
        run = (f'!mkdir -p "{{WORK}}/logs" && bash -c "set -o pipefail; {VENV}/bin/python -u /content/parakeet_ft.py '
               f'{name} --cfg /content/parakeet_cfg.json 2>&1 | tee -a \'{{WORK}}/logs/{name}.log\'"\n'
               f"assert _exit_code == 0, f'этап {name} упал — ошибка в выводе выше и в {{WORK}}/logs/{name}.log; "
               f"«Выполнить всё» остановлено'")
        if gpu:                                       # подготовка без видеокарты (CPU) — эти этапы пропускает
            run = ('if GPU:\n' + '\n'.join('    ' + x for x in run.split('\n')) +
                   f"\nelse:\n    print('Этап {name} — на видеокарте: сейчас пропущен.')")
        cells += [md(text), code(run)]
    cells += [md('## Итог\nТаблица до/после и файлы выгрузки; затем ячейка ждёт, пока всё запишется на Google Диск.'),
              code(FINAL), md(OUTRO)]
    nb = dict(nbformat=4, nbformat_minor=0, cells=cells,
              metadata=dict(accelerator='GPU', colab=dict(provenance=[], gpuType='A100', machine_shape='hm'),
                            kernelspec=dict(name='python3', display_name='Python 3'), language_info=dict(name='python')))
    out = HERE / 'parakeet_ft.ipynb'
    out.write_text(json.dumps(nb, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    return out


if __name__ == '__main__':
    print(build())
