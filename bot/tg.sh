#!/bin/bash
# Бот обратной связи: команды tgcloud CLI с данными бота из файла вне репозитория.
#   bash bot/tg.sh status | push | migrate | run message '{…}' | webhook …   — как `tgcloud`, но:
#   • токен CLI (TGCLOUD_TOKEN) лежит в ~/.config/falar/bot.env (права 600) и попадает только в
#     окружение этой команды. Нет файла или токена — скрипт спросит его скрытым вводом и сохранит.
#     В .bashrc его не кладём: забытый в окружении токен однажды уже ломал выпуск (GITHUB_TOKEN);
#   • lib/owner.js (в git не попадает) пишется из FALAR_BOT_OWNER того же файла; пока его нет — 0,
#     и бот всем отвечает «настраивается», кроме /id;
#   • tgcloud — только свой, @tgcloud/cli из node_modules по package-lock.json: `npx tgcloud` без
#     установки скачал бы с npm посторонний пакет с именем «tgcloud».
# Другой файл с данными: FALAR_BOT_ENV=/путь bash bot/tg.sh …
set -e
B=$(cd "$(dirname "$0")" && pwd)
ENV=${FALAR_BOT_ENV:-$HOME/.config/falar/bot.env}
cd "$B"
[ -x node_modules/.bin/tgcloud ] || npm ci --no-audit --no-fund
if [ ! -f "$ENV" ]; then
  mkdir -p "$(dirname "$ENV")"
  (umask 077; printf '%s\n' \
    '# Falar, бот обратной связи. Файл вне репозитория: не коммитить, не вставлять в чат.' \
    '# TGCLOUD_TOKEN — @BotFather → бот → Serverless → CLI Access → Access token (app<число>:…).' \
    '# FALAR_BOT_OWNER — ваш Telegram id: бот присылает его на /id.' > "$ENV")
fi
chmod 600 "$ENV"
TOKEN=$(sed -n 's/^TGCLOUD_TOKEN=//p' "$ENV" | tail -1)
if [ -z "$TOKEN" ]; then
  echo "Нужен токен CLI: @BotFather → бот → Serverless → CLI Access → Access token (вида app123:…)."
  read -rsp "Вставьте токен (ввод не виден): " TOKEN; echo
  [[ "$TOKEN" =~ ^app[0-9]+:[A-Za-z0-9_-]+$ ]] || { echo "Это не токен CLI: ожидается app<число>:…" >&2; exit 1; }
  printf 'TGCLOUD_TOKEN=%s\n' "$TOKEN" >> "$ENV"
  echo "Сохранён в $ENV"
fi
OWNER=$(sed -n 's/^FALAR_BOT_OWNER=//p' "$ENV" | tail -1)
[[ "$OWNER" =~ ^[0-9]*$ ]] || { echo "FALAR_BOT_OWNER в $ENV — не число: $OWNER" >&2; exit 1; }
printf "// Пишет bot/tg.sh из bot.env; в git не попадает.\nexport const OWNER = %s;\n" "${OWNER:-0}" > lib/owner.js.new
if cmp -s lib/owner.js.new lib/owner.js; then rm lib/owner.js.new; else mv lib/owner.js.new lib/owner.js; fi
TGCLOUD_TOKEN=$TOKEN exec node_modules/.bin/tgcloud "$@"
