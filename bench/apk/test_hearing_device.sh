#!/bin/bash
# «Как слышно» при прослушивании — на телефоне, в отдельном тестовом разговоре.
#
#   bash bench/apk/test_hearing_device.sh [запись=near-pt]
#
# Включается «Слушать PT», вместо микрофона подаётся запись комнаты (feedwav). Итог по каждой
# фразе — словами в журнале («🎚 слышно хорошо: …»), а на экране слов нет: под кнопками остаётся
# обычная подсказка (её content-desc читает uiautomator), как слышно — только цветом полоски.
# Нужны включённый экран и Falar впереди: экран не разблокируется, чужое приложение — ждём.
# В конце всё как было: слушание выключено, частота разбора и облака, выученное и словари из
# снимка, тестовый разговор удалён.
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
REC=$R/bench/air/rec/${1:-near-pt}
D=$(mktemp -d /tmp/falar-hear.XXXX); SNAP=$D/snap; mkdir -p $SNAP; TID=$(date +%s%3N)
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
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
every() { sh "grep -E '$1: (каждые|только)' $LOG | tail -1" | sed -n 's/.*каждые \([0-9]*\).*/\1/p;s/.*только по кнопке.*/0/p'; }
REF=$(every 'разбор контекста'); REF=${REF:-3}; CLOUD=$(every 'пересмотр разговора в облаке'); CLOUD=${CLOUD:-0}
AUTO0=$(sh "grep -E '🎚 (чувствительность: авто, сейчас|авто-чувствительность:)' $LOG | tail -1" | sed -n 's/.* \([+-][0-9.]*\) дБ.*/\1/p'); AUTO0=${AUTO0:-0}
# Строка под кнопками слушания — content-desc вида HearingView: подсказка или (была бы) оценка.
line() { sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" | python3 -c "
import re,sys
x=sys.stdin.read()
m=re.findall(r'content-desc=\"((?:Обе вместе|слушаю|слышно|шумно|тихо|громко|перегруз|речи не)[^\"]*)\"', x)
print(m[0] if m else '')"; }
verdicts() { sh "tail -n +$(($1+1)) $LOG | grep -E '🎚 (слышно|шумно|тихо|громко|перегруз|речи не)'"; }
restore() {
  say "== возврат"
  # Сначала остановить: подача записи ещё идёт и после сброса снова подстроила бы авто.
  $ADB shell "am force-stop $PKG"; sleep 2
  $ADB shell "am start -n $ACT --es listen off --es silent 0 --es refineevery $REF --es cloudevery $CLOUD --es micautodb $AUTO0" >/dev/null 2>&1; sleep 3
  $ADB shell "am force-stop $PKG"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1; done
  $ADB shell "rm -f $F/chats/$TID.json $F/replay.wav"
  say "  тестовый разговор удалён · текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  $ADB shell "am start -n $ACT" >/dev/null 2>&1; rm -rf "$D"
  say; say "итог: PASS $pass, FAIL $fail"
}
trap restore EXIT

$ADB wait-for-device; free_phone
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1; done
printf '{"id": %s, "name": "ТЕСТ как слышно", "named": true, "saved": %s, "turns": []}\n' $TID $TID > $D/t.json
$ADB shell "am force-stop $PKG"; sleep 1; $ADB push $D/t.json "$F/chats/$TID.json" >/dev/null 2>&1
$ADB push "$REC/room.wav" "$F/replay.wav" >/dev/null 2>&1
m0=$(count $LOG)
$ADB shell "am start -n $ACT --es listen pt --es silent 1 --es refineevery 0 --es cloudevery 0" >/dev/null 2>&1
for _ in $(seq 60); do sleep 2; [ "$(seen $m0 '🧩 модули:')" != 0 ] && [ "$(seen $m0 'микрофон:')" != 0 ] && break; done
sleep 3; front || { say "впереди не Falar или экран погашен — строку не проверить"; exit 1; }

say "== L1: слушаем — под кнопками обычная подсказка"
l=$(line); say "  строка: «$l»"
case "$l" in "Обе вместе"*) res 0 "L1 подсказка на месте";; *) res 1 "L1 под кнопками не подсказка: «$l»";; esac

say "== L2: после фраз — итог «как слышно» словами в журнале, на экране — нет"
m1=$(count $LOG); $ADB shell "am start -n $ACT --es feedwav $F/replay.wav --es speed 2" >/dev/null 2>&1
shown=""; shot=0
for i in $(seq 40); do
  sleep 3; front || continue
  l=$(line); case "$l" in "Обе вместе"*) ;; *) shown="$shown|$l";; esac
  [ $shot = 0 ] && [ $i -ge 3 ] && { $ADB exec-out screencap -p > /tmp/falar-hearing.png; shot=1; }
  [ $(verdicts $m1 | grep -c .) -ge 5 ] && break
done
v=$(verdicts $m1); n=$(echo "$v" | grep -c .)
say "  итогов в журнале: $n"; echo "$v" | cut -c10- | sed 's/ · .*//' | sort | uniq -c | sort -rn | head -5 | sed 's/^/   /'
[ "$n" -ge 3 ] && res 0 "L2 итог по каждой фразе — в журнале" || res 1 "L2 итогов в журнале нет"
echo "$v" | grep -qE 'речь -?[0-9]+, фон -?[0-9]+ dBFS' && res 0 "L2 в журнале — речь и фон цифрами" || res 1 "L2 в журнале нет цифр"
echo "$v" | grep -q 'слышно хорошо' && res 0 "L3 тихая комната near-pt — «слышно хорошо»" || res 1 "L3 для near-pt ни разу не «слышно хорошо»"
[ -z "$shown" ] && res 0 "L4 на экране слов нет — подсказка не сменилась ни разу" || res 1 "L4 на экране появлялось: «${shown#|}»"
say "  снимок экрана: /tmp/falar-hearing.png"
