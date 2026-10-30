# Состояние стенда (замки, пульс, прогоны, эталон телефона) и данные с телефонов — внутри проекта (владелец 30.10:
# «все что касается проекта … должно лежать в рамках проекта»): <проект>/.stand и <проект>/private, вне git
# (.git/info/exclude). Общие для всех рабочих деревьев (git worktree): корень — по общему каталогу git.
# Подключается из скриптов bench/*/: . "$(dirname "$0")/../stand.sh". FALAR_STAND, FALAR_PRIVATE — переопределить.
_falar_root=$(cd "$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --path-format=absolute --git-common-dir)/.." && pwd)
STAND=${FALAR_STAND:-$_falar_root/.stand}
PRIVATE=${FALAR_PRIVATE:-$_falar_root/private}
