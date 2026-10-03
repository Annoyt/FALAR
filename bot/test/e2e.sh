#!/bin/bash
# Бот целиком в настоящей среде Workers — workerd из wrangler, локальная D1 — против поддельного
# Bot API (test/fake-telegram.mjs). Аккаунт Cloudflare и сеть не нужны.
#   bash bot/test/e2e.sh          # KEEP=1 — оставить журнал wrangler dev и базу во временной папке
# Проверяет то, чего не видят проверки на столе: сборку с псевдонимами модулей, секрет webhook,
# SQL в D1 (именованные параметры → ?N), ответы Bot API через fetch, ошибки Telegram.
set -u
B=$(cd "$(dirname "$0")/.." && pwd); cd "$B"
export WRANGLER_SEND_METRICS=false
TMP=$(mktemp -d /tmp/falar-bot-e2e.XXXX); TG=${TG_PORT:-8790}; WP=${WORKER_PORT:-8789}
CFG=$B/.wrangler-e2e.jsonc
W="npx --no-install wrangler"
pass=0; fail=0
chk() { if eval "$1"; then pass=$((pass+1)); echo "PASS $2"; else fail=$((fail+1)); echo "FAIL $2"; fi; }
cleanup() {
  [ -n "${WPID:-}" ] && kill -- -"$WPID" 2>/dev/null   # вся группа: npx, wrangler, workerd
  [ -n "${TPID:-}" ] && kill "$TPID" 2>/dev/null; wait 2>/dev/null
  rm -f "$CFG"; if [ "${KEEP:-0}" = 1 ]; then echo "оставлено: $TMP"; else rm -rf "$TMP"; fi
  echo; echo "итог: PASS $pass, FAIL $fail"
}
trap cleanup EXIT
for p in $TG $WP; do ss -ltn "sport = :$p" | grep -q LISTEN && { echo "порт $p занят — другой прогон?"; exit 1; }; done

sed 's/__D1_ID__/00000000-0000-0000-0000-000000000000/' wrangler.template.jsonc > "$CFG"
node cf/schema-sql.mjs > "$TMP/schema.sql"
$W d1 execute falar-feedback --local --persist-to "$TMP/state" --config "$CFG" --file "$TMP/schema.sql" --yes > "$TMP/d1.log" 2>&1 \
  || { echo "таблицы в локальную D1 не легли:"; tail -5 "$TMP/d1.log"; exit 1; }
node test/fake-telegram.mjs $TG & TPID=$!
setsid $W dev --local --config "$CFG" --ip 127.0.0.1 --port $WP --persist-to "$TMP/state" --show-interactive-dev-session=false \
  --var BOT_TOKEN:t0k --var WEBHOOK_SECRET:s3cret --var OWNER:1000 --var TG_API:http://127.0.0.1:$TG \
  --var GITHUB_API:http://127.0.0.1:$TG --test-scheduled > "$TMP/dev.log" 2>&1 & WPID=$!
for i in $(seq 90); do curl -s -o /dev/null "http://127.0.0.1:$WP/" && break; sleep 1; done
curl -s -o /dev/null "http://127.0.0.1:$WP/" || { echo "wrangler dev не поднялся:"; tail -20 "$TMP/dev.log"; exit 1; }

post() { curl -s -o /dev/null -w '%{http_code}' -X POST -H 'content-type: application/json' \
  -H "X-Telegram-Bot-Api-Secret-Token: ${2-s3cret}" --data "$1" "http://127.0.0.1:$WP/telegram"; }
reset() { curl -s -X POST "http://127.0.0.1:$TG/reset" > /dev/null; }
failnext() { curl -s -X POST --data "$3" "http://127.0.0.1:$TG/fail/$1/$2" > /dev/null; }
# js 'выражение над c — массивом вызовов' → печать результата
js() { curl -s "http://127.0.0.1:$TG/calls" | node -e "const c = JSON.parse(require('fs').readFileSync(0, 'utf8')); const r = ($1); console.log(typeof r === 'string' ? r : JSON.stringify(r));"; }
sql() { $W d1 execute falar-feedback --local --persist-to "$TMP/state" --config "$CFG" --command "$1" --json 2>/dev/null \
  | node -e "const j = JSON.parse(require('fs').readFileSync(0, 'utf8')); console.log(JSON.stringify(j[0].results));"; }
echo 1 > "$TMP/n"
# Номер сообщения — в файле: msg зовётся внутри $(…), и счётчик в переменной там бы не рос.
msg() {   # msg <from_id> <имя> <chat_id> <chat_type> <текст> [доп. поля JSON без скобок]
  local N; N=$(( $(cat "$TMP/n") + 1 )); echo $N > "$TMP/n"
  printf '{"update_id":%d,"message":{"message_id":%d,"date":0,"from":{"id":%s,"is_bot":false,"first_name":"%s"},"chat":{"id":%s,"type":"%s"%s},"text":%s%s}}' \
    $N $N "$1" "$2" "$3" "$4" "$([ "$4" = supergroup ] && echo ',"is_forum":true')" "$(node -e 'console.log(JSON.stringify(process.argv[1]))' "$5")" "${6:+,$6}"
}

echo "== доступ"
chk '[ "$(curl -s -o /dev/null -w %{http_code} http://127.0.0.1:$WP/)" = 404 ]' "W1 корень — 404, бот живёт только на /telegram"
chk '[ "$(post "$(msg 2001 Анна 2001 private "#ошибка x")" wrong)" = 403 ]' "W2 чужой секрет — 403"
chk '[ "$(post "$(msg 2001 Анна 2001 private "#ошибка x")" "")" = 403 ]' "W2 без секрета — 403"
chk '[ "$(js "c.length")" = 0 ]' "W2 и Bot API не тронут"
chk '[ "$(post "{not json")" = 400 ]' "W3 мусор вместо JSON — 400"
chk '[ "$(post "{\"update_id\":1,\"edited_message\":{}}")" = 200 ] && [ "$(js "c.length")" = 0 ]' "W4 не сообщение — 200 и тишина"

echo "== человек → разработчику в личку"
post "$(msg 2001 Анна 2001 private /start)" > /dev/null
chk '[ "$(js "c.map(x => [x.token, x.method, x.params.chat_id])")" = "[[\"t0k\",\"sendMessage\",2001]]" ]' "W5 /start — приветствие, токен из секрета"
reset
r=$(post "$(msg 2001 Анна 2001 private "#перевод · португальский → русский
Исходник: Bom dia")")
chk '[ "$r" = 200 ] && [ "$(js "c[0].params.chat_id + \" \" + c[0].params.text.split(\"\\n\")[0]")" = "1000 👤 Анна" ]' "W6 отчёт — разработчику, с подписью"
chk '[ "$(sql "SELECT chat, person FROM relays")" = "[{\"chat\":1000,\"person\":2001}]" ]' "W6 связь в D1 (именованные параметры → ?N)"
copy=$(sql "SELECT msg FROM relays" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8'))[0].msg)")
reset
post "$(msg 1000 Я 1000 private "Спасибо!" "\"reply_to_message\":{\"message_id\":$copy,\"date\":0,\"chat\":{\"id\":1000,\"type\":\"private\"},\"from\":{\"id\":9,\"is_bot\":true,\"first_name\":\"Falar\"}}")" > /dev/null
chk '[ "$(js "c.filter(x => x.method === \"copyMessage\").map(x => [x.params.chat_id, x.params.from_chat_id])")" = "[[2001,1000]]" ]' "W7 ответ разработчика — человеку"

echo "== группа с топиками"
reset
post "$(msg 1000 Я -100500 supergroup /setup)" > /dev/null
chk '[ "$(js "c.filter(x => x.method === \"createForumTopic\").map(x => x.params.name).join(\",\")")" = "Переводы,Ошибки,Идеи,Задачи,Аналитика" ]' "W8 /setup — пять топиков"
reset
post "$(msg 2002 Борис 2002 private "#идея Тёмная тема")" > /dev/null
t=$(sql "SELECT value FROM settings WHERE key = 'topic:-100500:idea'" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8'))[0].value)")
chk '[ "$(js "[c[0].params.chat_id, c[0].params.message_thread_id]")" = "[-100500,$t]" ]' "W9 #идея — в топик «Идеи» ($t)"
reset
failnext sendMessage 400 "Bad Request: message thread not found"
post "$(msg 2002 Борис 2002 private "#идея ещё")" > /dev/null
chk '[ "$(js "c.filter(x => x.params.chat_id === -100500).map(x => x.params.message_thread_id ?? null)")" = "[$t,null]" ]' "W10 ошибка Telegram (400, топик удалён) — в общий топик"
left=$(sql "SELECT count(*) AS n FROM settings WHERE key = 'topic:-100500:idea'")
chk '[ "$left" = "[{\"n\":0}]" ]' "W10 удалённый топик забыт"
echo "== кнопки темы"
reset
post "$(msg 2004 Глеб 2004 private "Хочу тёмную тему")" > /dev/null
pm=$(cat "$TMP/n")
chk '[ "$(js "c.find(x => x.params.chat_id === 2004).params.reply_markup.inline_keyboard[0].map(b => b.callback_data).join()")" = "c:bug:$pm,c:idea:$pm,c:translation:$pm" ]' "W12 без темы — человеку «о чём это?» с кнопками"
gen=$(sql "SELECT msg FROM relays WHERE person = 2004" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8'))[0].msg)")
bug=$(sql "SELECT value FROM settings WHERE key = 'topic:-100500:bug'" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8'))[0].value)")
reset
N=$(( $(cat "$TMP/n") + 1 )); echo $N > "$TMP/n"
r=$(post "{\"update_id\":$N,\"callback_query\":{\"id\":\"q1\",\"chat_instance\":\"x\",\"data\":\"c:bug:$pm\",\"from\":{\"id\":2004,\"is_bot\":false,\"first_name\":\"Глеб\"},\"message\":{\"message_id\":900,\"date\":0,\"chat\":{\"id\":2004,\"type\":\"private\"}}}}")
chk '[ "$r" = 200 ] && [ "$(js "c.map(x => x.method).join()")" = "copyMessage,deleteMessage,answerCallbackQuery,editMessageText" ]' "W13 нажатие — копия в топик, старая удалена, ответ, кнопки сняты"
chk '[ "$(js "[c[0].params.message_thread_id, c[0].params.message_id, c[1].params.message_id]")" = "[$bug,$gen,$gen]" ]' "W13 из общего топика ($gen) — в «Ошибки» ($bug)"
now=$(sql "SELECT msg FROM relays WHERE person = 2004" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8'))[0].msg)")
chk '[ "$now" != "$gen" ]' "W13 связь в D1 переехала на новую копию ($now)"
echo "== сводка скачиваний (по расписанию)"
curl -s -X POST --data '[{"tag_name":"v0.29.0","published_at":"2026-10-03T08:00:00Z","draft":false,"prerelease":false,"assets":[{"name":"Falar.apk","download_count":2},{"name":"Falar-slim.apk","download_count":0},{"name":"latest.json","download_count":1}]},{"tag_name":"v0.28.0","published_at":"2026-10-02T07:00:00Z","draft":false,"prerelease":false,"assets":[{"name":"Falar.apk","download_count":3},{"name":"latest.json","download_count":5}]}]' "http://127.0.0.1:$TG/gh/releases" > /dev/null
reset
sched() { curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$WP/__scheduled?cron=5+*+*+*+*"; }
r=$(sched); for i in $(seq 20); do [ "$(js "c.length")" != 0 ] && break; sleep 0.3; done
an=$(sql "SELECT value FROM settings WHERE key = 'topic:-100500:analytics'" | node -e "console.log(JSON.parse(require('fs').readFileSync(0,'utf8'))[0].value)")
chk '[ "$r" = 200 ] && [ "$(curl -s http://127.0.0.1:$TG/gh/calls | node -e "const c = JSON.parse(require(\"fs\").readFileSync(0, \"utf8\")); console.log(c.map(x => x.url + \" \" + x.ua + \" \" + x.auth).join())")" = "/repos/Annoyt/FALAR/releases?per_page=100&page=1 falar-feedback-bot null" ]' "W14 запуск по расписанию — один запрос к GitHub: адрес, user-agent, без токена"
chk '[ "$(js "c.map(x => [x.method, x.params.chat_id, x.params.message_thread_id, x.params.text.split(\"\\n\")[0].replace(/ · .*/, \"\")])")" = "[[\"sendMessage\",-100500,$an,\"📊 GitHub\"]]" ]' "W15 сводка — в топик «Аналитика» ($an)"
chk '[ "$(js "c[0].params.text.split(\"\\n\")[1]")" = "Всего с 0.28.0: APK 5, облегчённый 0, проверок обновлений 6" ]' "W15 счёт по файлам выпусков"
chk '[ "$(sql "SELECT count(*) AS n FROM stats")" = "[{\"n\":1}]" ]' "W16 снимок дня — в D1"
reset
r=$(sched); sleep 1
chk '[ "$r" = 200 ] && [ "$(js "c.length")" = 0 ] && [ "$(curl -s http://127.0.0.1:$TG/gh/calls | node -e "console.log(JSON.parse(require(\"fs\").readFileSync(0, \"utf8\")).length)")" = 1 ]' "W17 второй запуск в тот же день — ни GitHub, ни сообщений"
chk '! grep -qE "Uncaught|TypeError|ReferenceError" "$TMP/dev.log"' "W11 в журнале Worker нет необработанных ошибок"
