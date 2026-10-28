#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Досчитать COMET для оценки из Colab на ПК и напечатать таблицу «до и после» — как summary.md ноутбука.
Папка — falar-hymt2/eval с Google Диска (base_*.json, ft_*.json: открытые наборы и отложенный тест с синтетическим
шумом). COMET — окружение ПК с видеокартой (FALAR_COMET_PY, по умолчанию ~/tools/venv-comet-gpu) и модель
models/comet/wmt22-comet-da.

    ~/tools/venv-colab-test/bin/python tools/colab/eval_local.py ~/.cache/falar-stand/hymt2-ft/eval
"""
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
R = HERE.parent.parent
sys.path.insert(0, str(HERE))
import hymt2_ft as H  # noqa: E402


def models_dir():
    own = R / 'models'
    if (own / 'comet').exists():
        return own
    common = subprocess.run(['git', '-C', str(R), 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                            capture_output=True, text=True).stdout.strip()
    return Path(common).parent / 'models'


def main():
    ev = Path(sys.argv[1])
    py = os.environ.get('FALAR_COMET_PY', str(Path.home() / 'tools/venv-comet-gpu/bin/python'))
    ckpt = models_dir() / 'comet' / 'wmt22-comet-da' / 'checkpoints' / 'model.ckpt'
    todo = [str(p) for p in sorted(ev.glob('*_*.json')) if 'comet' not in json.load(open(p, encoding='utf-8'))[0]]
    if todo:
        with tempfile.NamedTemporaryFile('w', suffix='.py', delete=False) as f:
            f.write(H.COMET_SCRIPT)
        subprocess.run([py, f.name, str(ckpt), os.environ.get('COMET_BATCH', '8'), *todo], check=True)
        os.unlink(f.name)
    names = sorted({p.stem.split('_', 1)[1] for p in ev.glob('base_*.json')})
    lines = H.summary_table(ev, names)
    (ev / 'summary.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
