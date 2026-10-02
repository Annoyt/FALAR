#!/bin/bash
# Бот обратной связи на Cloudflare Workers — всё, что требует аккаунта и секретов:
#   bash bot/cf.sh login        вход в Cloudflare через браузер, один раз (вход хранит wrangler)
#   bash bot/cf.sh token        токен бота из @BotFather скрытым вводом → ~/.config/falar/bot.env
#   bash bot/cf.sh deploy       база D1 и таблицы, код, секреты, webhook; повторять после правок кода
#   bash bot/cf.sh owner <id>   ваш Telegram id (бот присылает его на /id) — сразу в секреты Worker
#   bash bot/cf.sh status       бот и webhook глазами Telegram: адрес, очередь, последняя ошибка
#   bash bot/cf.sh tail         живой журнал Worker
# Секреты — в ~/.config/falar/bot.env (права 600) и в секретах Worker. В репозиторий, в командные строки
# процессов и в чат не попадают: wrangler получает их через stdin, Bot API — из файла (cf/tg.mjs).
# wrangler — только свой, из node_modules (версия закреплена в package-lock.json).
set -e
B=$(cd "$(dirname "$0")" && pwd); cd "$B"
ENV=${FALAR_BOT_ENV:-$HOME/.config/falar/bot.env}; export FALAR_BOT_ENV=$ENV
export WRANGLER_SEND_METRICS=false
W="npx --no-install wrangler"
[ -x node_modules/.bin/wrangler ] || npm ci --no-audit --no-fund
get() { [ -f "$ENV" ] && sed -n "s/^$1=//p" "$ENV" | tail -1; }
setv() {   # заменить или добавить KEY=VALUE, права 600
  mkdir -p "$(dirname "$ENV")"; touch "$ENV"; chmod 600 "$ENV"
  local t; t=$(mktemp "$ENV.XXXX"); grep -v "^$1=" "$ENV" > "$t" || true
  printf '%s=%s\n' "$1" "$2" >> "$t"; chmod 600 "$t"; mv "$t" "$ENV"
}
username() { node cf/tg.mjs getMe 2>/dev/null | sed -n 's/.*"username": "\(.*\)".*/\1/p'; }

case "${1:-}" in
login)
  $W login ;;
token)
  echo "Токен бота: @BotFather → /mybots → бот → API Token (вида 123456789:AA…)."
  read -rsp "Вставьте токен (ввод не виден): " T; echo
  [[ "$T" =~ ^[0-9]{5,15}:[A-Za-z0-9_-]{30,}$ ]] || { echo "Это не токен бота: ожидается <число>:<35 знаков>" >&2; exit 1; }
  setv BOT_TOKEN "$T"; unset T
  [ -n "$(get WEBHOOK_SECRET)" ] || setv WEBHOOK_SECRET "$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 40)"
  u=$(username)
  [ -n "$u" ] || { echo "Telegram не принял токен (getMe) — проверьте и повторите." >&2; exit 1; }
  echo "Сохранён в $ENV. Бот: @$u"
  [ "$u" = falar_feedback_bot ] || echo "Имя бота не falar_feedback_bot — скажите Claude: ссылки в приложении и на сайте надо поменять."
  ;;
deploy)
  [ -n "$(get BOT_TOKEN)" ] || { echo "Сначала: bash bot/cf.sh token" >&2; exit 1; }
  [ -n "$(get WEBHOOK_SECRET)" ] || setv WEBHOOK_SECRET "$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 40)"
  $W whoami 2>&1 | grep -qiE 'logged in' || { echo "Сначала: bash bot/cf.sh login" >&2; exit 1; }
  id=$(get CF_D1_ID)
  if [ -z "$id" ]; then
    out=$($W d1 create falar-feedback 2>&1) || { printf '%s\n' "$out" >&2; exit 1; }
    id=$(printf '%s' "$out" | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1)
    [ -n "$id" ] || { echo "Не нашёл номер базы в ответе wrangler:" >&2; printf '%s\n' "$out" >&2; exit 1; }
    setv CF_D1_ID "$id"; echo "База D1 создана: falar-feedback"
  fi
  sed "s/__D1_ID__/$id/" wrangler.template.jsonc > wrangler.jsonc
  node cf/schema-sql.mjs > .cf-schema.sql
  $W d1 execute falar-feedback --remote --file .cf-schema.sql --yes > /dev/null
  echo "Таблицы на месте"
  $W deploy 2>&1 | tee .cf-deploy.log | grep -vE '^\s*$' | tail -6
  url=$(grep -oE 'https://[a-z0-9.-]+\.workers\.dev' .cf-deploy.log | head -1); rm -f .cf-deploy.log
  if [ -z "$url" ]; then
    echo "Нет адреса *.workers.dev. Если wrangler просит подадрес: откройте dash.cloudflare.com → Workers & Pages," >&2
    echo "согласитесь на адрес *.workers.dev и повторите bash bot/cf.sh deploy." >&2; exit 1
  fi
  setv BOT_URL "$url"
  node cf/secrets.mjs | $W secret bulk > /dev/null && echo "Секреты Worker обновлены"
  node cf/tg.mjs setWebhook "{\"url\":\"$url/telegram\",\"allowed_updates\":[\"message\"],\"max_connections\":1}" > /dev/null \
    && echo "Webhook: $url/telegram"
  echo "Бот: @$(username)"
  [ -n "$(get FALAR_BOT_OWNER)" ] || echo "Дальше: напишите боту /id и выполните bash bot/cf.sh owner <число>"
  ;;
owner)
  [[ "${2:-}" =~ ^[0-9]+$ ]] || { echo "Нужно число: bash bot/cf.sh owner 123456789" >&2; exit 1; }
  setv FALAR_BOT_OWNER "$2"
  node cf/secrets.mjs | $W secret bulk > /dev/null && echo "Разработчик: $2 — бот теперь пересылает сообщения вам"
  ;;
status)
  echo "Бот: @$(username)"
  node cf/tg.mjs getWebhookInfo | grep -E '"(url|pending_update_count|last_error_date|last_error_message|max_connections)"' ;;
tail)
  $W tail ;;
*)
  sed -n '2,10p' "$0"; exit 1 ;;
esac
