#!/bin/bash
# «Как слышно» при прослушивании — на телефоне, в отдельном тестовом разговоре.
#
#   bash bench/apk/test_hearing_device.sh [запись=near-pt]
#
# Включается «Слушать PT», вместо микрофона подаётся запись комнаты (feedwav). Итог по каждой
# фразе — словами в журнале («🎚 слышно хорошо: …»), а на экране слов нет (владелец 29.09): как
# слышно — только цветом кольца вокруг «Слушать» в доке (0.26.0). Кольцо и половинки кнопки
# проверяются по снимкам экрана без сжатия на самом телефоне (px_phone.sh): включённая половинка PT
# мятная, RU — нет; пока идёт речь, кольцо хоть раз зелёное (near-pt слышно хорошо), в паузах —
# серое; после «не слушать» кольца нет. До 0.26.0 здесь читалась подпись полоски под кнопками
# (content-desc HearingView). У кнопки дока подпись постоянная, и та проверка проходила бы всегда.
# Нужны включённый экран и Falar впереди: экран не разблокируется, чужое приложение — ждём.
# В конце всё как было: слушание выключено, частота разбора и облака, выученное и словари из
# снимка, тестовый разговор удалён. Частоту можно задать: REFINE_EVERY=1 CLOUD_EVERY=5 bash …
R=$(cd "$(dirname "$0")/../.." && pwd); A=$R/bench/apk
# Один прогон на телефоне за раз — и отдельный скрипт, и test_all_device.sh (01.10 две копии test_ui
# девять минут касались телефона одновременно).
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand"; exec 9>"${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
REC=$R/bench/air/rec/${1:-near-pt}
D=$(mktemp -d /tmp/falar-hear.XXXX); SNAP=$D/snap; mkdir -p $SNAP; TID=$(date +%s%3N)
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }   # не из stdin: внутри «while read» adb съел бы его
front() { sh "dumpsys power" | grep -q "mWakefulness=Awake" && sh "dumpsys window" | grep -m1 mCurrentFocus | grep -q "$PKG/"; }
free_phone() {
  local n=0
  while :; do
    local f; f=$(sh "dumpsys window | grep -m1 mCurrentFocus")
    sh "dumpsys power" | grep -q "mWakefulness=Awake" && case "$f" in *$PKG*|*com.miui.home*|*launcher*) return 0;; esac
    n=$((n+1)); [ $n -eq 1 ] && say "  нужен включённый экран и Falar или лаунчер впереди ($f), жду…"; sleep 10
  done
}
count() { sh "test -f $1 && wc -l < $1 || echo 0" | awk '{print $1+0}'; }
seen() { sh "tail -n +$(($1+1)) $LOG | grep -c -E -- '$2'" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
# Настройки, к которым вернуться: частота разбора и облака — из окружения (REFINE_EVERY, CLOUD_EVERY),
# иначе у самого приложения (стенд --es settings show читает сохранённые настройки), у прежних
# сборок — из журнала; без них — как в приложении по умолчанию. Зовётся после free_phone: запускает Falar.
every() { sh "grep -E '$1: (каждые|только)' $LOG | tail -1" | sed -n 's/.*каждые \([0-9]*\).*/\1/p;s/.*только по кнопке.*/0/p'; }
orig_settings() {
  REF=${REFINE_EVERY:-}; CLOUD=${CLOUD_EVERY:-}
  [ -n "$REF" ] && [ -n "$CLOUD" ] && return
  local m st; m=$(count $LOG); $ADB shell "am start -n $ACT --es settings show" >/dev/null 2>&1
  st=$(wl "$m" '🧪 настройки:' 15)
  [ -z "$REF" ] && REF=$(printf '%s' "$st" | sed -n 's/.*разбор \([0-9][0-9]*\).*/\1/p')
  [ -z "$CLOUD" ] && CLOUD=$(printf '%s' "$st" | sed -n 's/.*облако \([0-9][0-9]*\).*/\1/p')
  [ -z "$REF" ] && REF=$(every 'разбор контекста'); [ -z "$CLOUD" ] && CLOUD=$(every 'пересмотр разговора в облаке')
  REF=${REF:-3}; CLOUD=${CLOUD:-0}
}
AUTO0=$(sh "grep -E '🎚 (чувствительность: авто, сейчас|авто-чувствительность:)' $LOG | tail -1" | sed -n 's/.* \([+-][0-9.]*\) дБ.*/\1/p'); AUTO0=${AUTO0:-0}
dump() { sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" > $D/ui.xml; }
# Слова «как слышно» на экране — в тексте или подписи любого вида: итоги и децибелы живут в журнале.
words() { dump; python3 $A/ui.py texts $D/ui.xml | grep -E '(слышно хорошо|шумно|тихо|громко|перегруз): |речи не слышно|дБ|dBFS' | head -3; }
verdicts() { sh "tail -n +$(($1+1)) $LOG | grep -E '🎚 (слышно|шумно|тихо|громко|перегруз|речи не)'"; }
# Цвета точек кольца, PT, RU против фона дока рядом с кнопкой (последняя точка в строке): мята —
# включённая половинка, «фон» — ничего не нарисовано, серое кольцо — пауза, иначе оттенок «как слышно».
cat > $D/classify.py <<'PY'
import colorsys, sys
idx = list(map(int, sys.argv[1:]))
def c(p, bg):
    d = lambda a, b: sum((x - y) ** 2 for x, y in zip(a, b)) ** 0.5
    if d(p, (111, 224, 188)) < 40: return 'мята'
    if d(p, bg) < 30: return 'фон'
    h, s, v = colorsys.rgb_to_hsv(*(x / 255 for x in p))
    if s < 0.25: return 'серое'
    h *= 360
    return 'зелёный' if 70 <= h <= 170 else 'жёлтый' if 45 <= h < 70 else 'красный' if h < 20 or h > 345 else 'оранжевый'
for line in sys.stdin:
    v = list(map(int, line.split()[1:]))
    if len(v) < 12: continue
    pts = [tuple(v[i * 3:i * 3 + 3]) for i in range(len(v) // 3)]
    print(*(c(pts[i], pts[-1]) for i in idx))
PY
classify() { python3 $D/classify.py "$@"; }
restore() {
  say "== возврат"
  # Сначала остановить: подача записи ещё идёт и после сброса снова подстроила бы авто.
  $ADB shell "am force-stop $PKG"; sleep 2
  $ADB shell "am start -n $ACT --es listen off --es silent 0 ${REF:+--es refineevery $REF} ${CLOUD:+--es cloudevery $CLOUD} --es micautodb $AUTO0" >/dev/null 2>&1; sleep 3
  $ADB shell "am force-stop $PKG"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1; done
  $ADB shell "rm -f $F/chats/$TID.json $F/replay.wav /data/local/tmp/falar_px.sh"
  say "  тестовый разговор удалён · текущим снова станет: $(sh "ls -t $F/chats/ | head -1") · разбор $REF, облако $CLOUD"
  $ADB shell "am start -n $ACT" >/dev/null 2>&1; rm -rf "$D"
  say; say "итог: PASS $pass, FAIL $fail"
}
# Падения Falar за проверку — по logcat с начала прогона (падения самого uiautomator не в счёт).
T0=$($ADB shell "date '+%m-%d %H:%M:%S.000'" 2>/dev/null | tr -d '\r')
crashed() { $ADB shell "logcat -d -v time -t '$T0'" 2>/dev/null | tr -d '\r' | grep -A3 'FATAL EXCEPTION' | grep -A1 'Process: app.falar' | grep -vE 'Process:|^--' | head -1 | cut -c1-160; }
trap restore EXIT

$ADB wait-for-device; free_phone; orig_settings
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1; done
printf '{"id": %s, "name": "ТЕСТ как слышно", "named": true, "saved": %s, "turns": []}\n' $TID $TID > $D/t.json
$ADB shell "am force-stop $PKG"; sleep 1; $ADB push $D/t.json "$F/chats/$TID.json" >/dev/null 2>&1
$ADB push "$REC/room.wav" "$F/replay.wav" >/dev/null 2>&1
$ADB push $A/px_phone.sh /data/local/tmp/falar_px.sh >/dev/null 2>&1
m0=$(count $LOG)
$ADB shell "am start -n $ACT --es listen pt --es silent 1 --es refineevery 0 --es cloudevery 0" >/dev/null 2>&1
for _ in $(seq 60); do sleep 2; [ "$(seen $m0 '🧩 модули:')" != 0 ] && [ "$(seen $m0 'микрофон:|слушаю')" != 0 ] && break; done
sleep 3; front || { say "впереди не Falar или экран погашен — экран не проверить"; exit 1; }

# Кнопка «Слушать» дока: 108 × 54 dp, пилюля с полями 7 dp, кольцо — на 3 dp снаружи пилюли
# (ListenButton). Точки: кольцо на прямом верхнем крае, середины половинок PT и RU левее и правее букв,
# фон дока в 6 dp левее кнопки — с ним сравнивается «ничего не нарисовано» в обеих темах.
dump; b=$(python3 $A/ui.py find $D/ui.xml --desc 'Обе вместе' --prefix)
[ -n "$b" ] || { res 1 "L0 кнопки «Слушать» нет в дереве экрана"; exit 1; }
read x0 y0 x1 y1 _ <<< "$b"
P=$(python3 -c "
d=($x1-$x0)/108; X=lambda v: round($x0+v*d); Y=lambda v: round($y0+v*d)
print(X(40), Y(3), X(17), Y(27), X(91), Y(27), X(-6), Y(27))")
say "  кнопка «Слушать»: $x0,$y0–$x1,$y1 · точки (кольцо, PT, RU, фон): $P"

say "== L1: «Слушать PT» — половинка PT мятная, RU — нет"
px=$(sh "sh /data/local/tmp/falar_px.sh 0 $P"); read c1 c2 c3 <<< "$(printf '%s\n' "$px" | classify 0 1 2)"
say "  снимок: $px · PT $c2 · RU $c3"
[ "$c2" = мята ] && [ "$c3" != мята ] && res 0 "L1 включённая половинка — PT" || res 1 "L1 половинки не по состоянию: PT $c2, RU $c3"

say "== L2: после фраз — итог «как слышно» словами в журнале; на экране — только цвет"
m1=$(count $LOG)
$ADB shell "sh /data/local/tmp/falar_px.sh 50 $P" > $D/ring.raw 2>/dev/null &
$ADB shell "am start -n $ACT --es feedwav $F/replay.wav --es speed 2" >/dev/null 2>&1
shown=""; shot=0; away=0
for i in $(seq 40); do
  sleep 3; front || { away=1; continue; }
  w=$(words); [ -n "$w" ] && shown="$shown|$w"
  [ $shot = 0 ] && [ $i -ge 3 ] && { $ADB exec-out screencap -p > /tmp/falar-hearing.png; shot=1; }
  [ $(verdicts $m1 | grep -c .) -ge 5 ] && break
done
wait
v=$(verdicts $m1); n=$(echo "$v" | grep -c .)
say "  итогов в журнале: $n"; echo "$v" | cut -c10- | sed 's/ · .*//' | sort | uniq -c | sort -rn | head -5 | sed 's/^/   /'
[ "$n" -ge 3 ] && res 0 "L2 итог по каждой фразе — в журнале" || res 1 "L2 итогов в журнале нет"
echo "$v" | grep -qE 'речь -?[0-9]+, фон -?[0-9]+ dBFS' && res 0 "L2 в журнале — речь и фон цифрами" || res 1 "L2 в журнале нет цифр"
echo "$v" | grep -q 'слышно хорошо' && res 0 "L3 тихая комната near-pt — «слышно хорошо»" || res 1 "L3 для near-pt ни разу не «слышно хорошо»"
[ -z "$shown" ] && res 0 "L4 на экране слов «как слышно» нет" || res 1 "L4 на экране появлялось: «${shown#|}»"

say "== L5: кольцо вокруг «Слушать» — цвет «как слышно»"
classify 0 < $D/ring.raw > $D/ring.cls
sort $D/ring.cls | uniq -c | sort -rn | sed 's/^/   /'
tot=$(grep -c . $D/ring.cls); grn=$(grep -c '^зелёный' $D/ring.cls); gry=$(grep -c '^серое' $D/ring.cls); none=$(grep -c '^фон' $D/ring.cls)
if [ $away = 1 ]; then say "  (телефон брали в руки или экран гас — кольцо не оценивается)"
else
  [ "$grn" -ge 1 ] && res 0 "L5 пока звучит речь, кольцо зелёное ($grn снимков из $tot)" || res 1 "L5 кольцо ни разу не зелёное (снимков $tot)"
  [ "$gry" -ge 1 ] && res 0 "L5 в паузах кольцо серое ($gry снимков)" || res 1 "L5 серого кольца в паузах не было"
  [ "$tot" -ge 10 ] && [ $((none * 10)) -le "$tot" ] && res 0 "L5 кольцо видно всё время слушания (без него $none из $tot)" || res 1 "L5 кольца нет на $none снимках из $tot"
fi
say "  снимок экрана: /tmp/falar-hearing.png"

say "== L6: «не слушать» — кольца нет, обе половинки погасли"
$ADB shell "am start -n $ACT --es listen off" >/dev/null 2>&1; sleep 3
if front; then
  px=$(sh "sh /data/local/tmp/falar_px.sh 0 $P"); read c1 c2 c3 <<< "$(printf '%s\n' "$px" | classify 0 1 2)"
  say "  снимок: $px · кольцо $c1 · PT $c2 · RU $c3"
  [ "$c1" = фон ] && res 0 "L6 кольца нет" || res 1 "L6 кольцо осталось: $c1"
  [ "$c2" != мята ] && [ "$c3" != мята ] && res 0 "L6 обе половинки погасли" || res 1 "L6 половинка горит: PT $c2, RU $c3"
else say "  (впереди не Falar — не проверить: $(sh "dumpsys window | grep -m1 mCurrentFocus"))"; fi
c=$(crashed); [ -z "$c" ] && res 0 "L7 за проверку Falar не падал" || res 1 "L7 Falar упал: $c"
