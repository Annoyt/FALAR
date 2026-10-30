"""Состояние стенда и данные с телефонов — внутри проекта (владелец 30.10): <проект>/.stand и <проект>/private, вне
git, общие для всех рабочих деревьев (корень — по общему каталогу git). Как bench/stand.sh для скриптов оболочки.
FALAR_STAND, FALAR_PRIVATE — переопределить."""
import os
import subprocess


def root():
    here = os.path.dirname(os.path.abspath(__file__))
    g = subprocess.run(['git', '-C', here, 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                       capture_output=True, text=True).stdout.strip()
    return os.path.dirname(g) if g else os.path.dirname(here)


def stand(*p):
    return os.path.join(os.environ.get('FALAR_STAND') or os.path.join(root(), '.stand'), *p)


def private(*p):
    return os.path.join(os.environ.get('FALAR_PRIVATE') or os.path.join(root(), 'private'), *p)
