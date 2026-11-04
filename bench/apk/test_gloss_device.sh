#!/bin/bash
# Глоссарий прошлых разговоров на телефоне (results/2026-11-01-mt-cloud-memory.md, шаг 2): уточнитель получает
# облачные пары других разговоров, если термин стоит во фразе.
#
#   bash bench/apk/test_gloss_device.sh          # INSTALL=1 — сначала поставить bench/apk/Falar.apk (build.sh)
#
# Два тестовых разговора файлами: «старый» с облачной парой «skate elétrico → электрический скейтборд» и «текущий»
# (самый свежий — его приложение и откроет) со своей парой «bicicleta → велосипед». Фраза подаётся текстом
# (--es feedtext), уточнитель разбирает каждую реплику (--es refineevery 1), облако выключено (--es cloudevery 0).
#   T1 фраза с термином старого разговора — в журнале «📚 #N термины прошлых разговоров» с этой парой;
#   T2 уточнитель ответил на эту реплику («🔁 #N …»);
#   T3 фраза только со своим термином — строки «📚» для неё нет: своя пара важнее, прошлой там нет;
#   T4 Falar не падал.
# Строки «📚» в вывод не печатаются: в них могут быть и термины настоящих разговоров владельца.
# В конце всё как было: тестовые разговоры удалены, выученное, пины, свои и известные слова — из снимка, частоты
# разбора и облака — прежние.
. "$(dirname "$0")/../stand.sh"   # $STAND, $PRIVATE — состояние стенда и данные телефонов внутри проекта
R=$(cd "$(dirname "$0")/../.." && pwd); A=$R/bench/apk
MAIN=$(cd "$(git -C "$R" rev-parse --path-format=absolute --git-common-dir)/.." && pwd)
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "$STAND"; exec 9>"$STAND/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
ADB=${ADB:-$( [ -x "$R/tools/platform-tools/adb" ] && echo "$R/tools/platform-tools/adb" || echo "$MAIN/tools/platform-tools/adb")}; PY=${PY:-python3}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
mkdir -p "$PRIVATE"; D=$(mktemp -d "$PRIVATE/gloss.XXXX"); SNAP=$D/snap; mkdir -p "$SNAP"
TB=$(date +%s%3N); TA=$((TB - 86400000))          # «старый» — на сутки раньше, «текущий» — сейчас
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "test -f $LOG && wc -l < $LOG || echo 0" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
FREE_S=${FREE_S:-60}
focus() { sh "dumpsys window | grep -m1 mCurrentFocus"; }
idle() {
  sh "dumpsys power" | grep -qE "mWakefulness=(Asleep|Dozing)" && return 0
  local a; a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
  case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) [ "${a:-0}" -ge $((FREE_S * 1000)) ] && return 0;; esac; return 1
}
launch() {
  local n=0
  until idle; do n=$((n+1)); [ $n -eq 1 ] && say "  (запуск ждёт, пока телефон освободится: $(focus))"; sleep 5; done
  $ADB shell "am start -n $ACT $*" >/dev/null 2>&1
}
feed() { launch --es feedtext "'$1'"; }
# Частоты разбора и облака, к которым вернуться: из настроек приложения (стенд --es settings show).
orig_settings() {
  local m st; m=$(mark); launch --es settings show
  st=$(wl "$m" '🧪 настройки:' 20)
  REF=$(printf '%s' "$st" | sed -n 's/.*разбор \([0-9][0-9]*\).*/\1/p'); CLOUD=$(printf '%s' "$st" | sed -n 's/.*облако \([0-9][0-9]*\).*/\1/p')
  REF=${REF:-3}; CLOUD=${CLOUD:-0}
}
chat() {   # chat <файл> <id> <имя> <pt-термин> <ru-термин>
  "$PY" - "$@" <<'PYC'
import json, sys
out, tid, name, pt, ru = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5]
t = [{"dir": "pt2ru", "src": "Bom dia, tudo bem?", "dst": "Доброе утро, как дела?", "at": tid + 1000},
     {"dir": "ru2pt", "src": "Всё хорошо, спасибо.", "dst": "Tudo bem, obrigado.", "at": tid + 2000}]
json.dump({"id": tid, "name": name, "named": True, "saved": tid, "turns": t,
           "terms": [{"pt": pt, "ru": ru, "src": "cloud"}]}, open(out, "w", encoding="utf-8"), ensure_ascii=False)
PYC
}
restore() {
  say "== возврат"
  launch --es listen off --es refineevery "$REF" --es cloudevery "$CLOUD"; sleep 3
  say "  разбор: $REF, облако: $CLOUD"
  $ADB shell "am force-stop $PKG"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1 && say "  вернул $f"
  done
  $ADB shell "rm -f $F/chats/$TA.json $F/chats/$TB.json"
  say "  тестовые разговоры удалены · текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  sh "dumpsys power" | grep -qE "mWakefulness=(Asleep|Dozing)" || launch
  rm -rf "$D"
  say; say "итог: PASS $pass, FAIL $fail"
}
T0=$($ADB shell "date '+%m-%d %H:%M:%S.000'" 2>/dev/null | tr -d '\r')
crashed() { $ADB shell "logcat -d -v time -t '$T0'" 2>/dev/null | tr -d '\r' | grep -A3 'FATAL EXCEPTION' | grep -A1 'Process: app.falar' | grep -vE 'Process:|^--' | head -1 | cut -c1-160; }

[ -n "$(sh 'echo ok')" ] || { say "телефон не подключён"; exit 2; }
if [ "$INSTALL" = 1 ]; then
  r=$($ADB install --no-incremental -r "${APK:-$A/Falar.apk}" 2>&1 | tail -1); say "== ставлю: $r"; case "$r" in Success*) ;; *) exit 1;; esac
fi
orig_settings
trap restore EXIT
say "== снимок перед проверкой"
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1; done
chat "$D/a.json" "$TA" "ТЕСТ глоссарий, старый" "skate elétrico" "электрический скейтборд"
chat "$D/b.json" "$TB" "ТЕСТ глоссарий" "bicicleta" "велосипед"
$ADB shell "am force-stop $PKG"; sleep 1
$ADB push "$D/a.json" "$F/chats/$TA.json" >/dev/null 2>&1; $ADB push "$D/b.json" "$F/chats/$TB.json" >/dev/null 2>&1
# adb push оставляет время файла с точностью до секунды: у двух разговоров оно совпадёт, и приложение (последний
# по времени изменения) может открыть «старый». Текущий — заметно новее.
sleep 2; $ADB shell "touch $F/chats/$TB.json"
m=$(mark); launch --es listen off; wl "$m" 'восстановлено реплик|микрофон выключен' 120 >/dev/null; sleep 2
cur=$(sh "ls -t $F/chats/ | head -1")
[ "$cur" = "$TB.json" ] || { res 1 "T0 текущий разговор не тестовый: сверху $cur, тестовые: $(sh "ls $F/chats/ | grep -cE '^($TA|$TB).json'") из 2"; sh "ls -lt --time-style=+%s $F/chats/ | head -4" | awk '{print "   ", $6, $7}'; exit 1; }
launch --es refineevery 1 --es cloudevery 0; sleep 2

say "== T1–T2: фраза с термином старого разговора"
m=$(mark); feed 'Meu filho quer um skate elétrico de aniversário.'
raw=$(wl "$m" '#[0-9]+ pt2ru' 60); n=$(printf '%s' "$raw" | grep -o '#[0-9]*' | head -1)
l=$(wl "$m" "📚 $n |разбор отложен" 120)
if printf '%s' "$l" | grep -q "разбор отложен"; then
  # Ядро и уточнитель вместе на этом телефоне помещаются не всегда (results/2026-09-28-memory.md); 01.11 на Redmi
  # было свободно 0,9–1,1 ГБ, и запаса 900 МБ не хватало. Порог — до перезапуска; система освободит место за счёт фоновых.
  say "  уточнитель отложен нехваткой памяти — порог снижен для проверки (--es llmneed ${LLMNEED:-500})"
  launch --es llmneed "${LLMNEED:-500}"; sleep 1
  m=$(mark); feed 'Meu filho ainda quer um skate elétrico de aniversário.'
  raw=$(wl "$m" '#[0-9]+ pt2ru' 60); n=$(printf '%s' "$raw" | grep -o '#[0-9]*' | head -1)
  l=$(wl "$m" "📚 $n " 150)
fi
printf '%s' "$l" | grep -q "skate elétrico → электрический скейтборд" \
  && res 0 "T1 уточнитель получил пару старого разговора" || res 1 "T1 строки «📚 $n» с парой старого разговора нет"
r=$(wl "$m" "🔁 $n " 150)
printf '%s' "$r" | grep -qE "по контексту|не изменил|ответ отброшен" && res 0 "T2 уточнитель ответил на реплику" || res 1 "T2 ответа уточнителя нет"

say "== T3: фраза только со своим термином"
m=$(mark); feed 'Amanhã vou de bicicleta para a escola.'
raw=$(wl "$m" '#[0-9]+ pt2ru' 60); n=$(printf '%s' "$raw" | grep -o '#[0-9]*' | head -1)
r=$(wl "$m" "🔁 $n " 150)
if [ -n "$r" ]; then
  sh "tail -n +$(($m+1)) $LOG" | grep -q "📚 $n .*bicicleta" && res 1 "T3 своя пара пришла как прошлая" || res 0 "T3 своя пара — не из прошлых разговоров"
else res 1 "T3 ответа уточнителя нет"; fi
c=$(crashed); [ -z "$c" ] && res 0 "T4 Falar не падал" || res 1 "T4 Falar упал: $c"
[ $fail = 0 ]
