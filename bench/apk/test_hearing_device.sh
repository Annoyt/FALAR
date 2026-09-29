#!/bin/bash
# Строка «как слышно» под кнопками слушания — на телефоне, в отдельном тестовом разговоре.
#
#   bash bench/apk/test_hearing_device.sh [запись=near-pt]
#
# Включается «Слушать PT», вместо микрофона подаётся запись комнаты (feedwav), и по дереву экрана
# (uiautomator) видно, как строка под кнопками меняется с «слушаю…» на итог по каждой фразе.
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
# Строка «как слышно» — content-desc вида HearingView под кнопками слушания.
line() { sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" | python3 -c "
import re,sys
x=sys.stdin.read()
m=re.findall(r'content-desc=\"((?:слушаю|слышно|шумно|тихо|громко|перегруз|речи не)[^\"]*)\"', x)
print(m[0] if m else '')"; }
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

say "== L1: слушаем — строка живая, а не прежняя подсказка"
# До подачи записи микрофон открыт на комнату: строка может успеть показать итог живой фразы.
l=$(line); say "  строка: «$l»"
case "$l" in слушаю*|слышно*|шумно*|тихо*|громко*|перегруз*|"речи не"*) res 0 "L1 строка слушания на месте";; *) res 1 "L1 строки слушания нет: «$l»";; esac

say "== L2: после фраз — итог «как слышно» с цифрой"
m1=$(count $LOG); $ADB shell "am start -n $ACT --es feedwav $F/replay.wav --es speed 2" >/dev/null 2>&1
got=""; shot=0
for i in $(seq 40); do
  sleep 3; front || continue
  l=$(line)
  case "$l" in слышно*|шумно*|тихо*|громко*|перегруз*|"речи не"*) got="$got|$l"
    [ $shot = 0 ] && { $ADB exec-out screencap -p > /tmp/falar-hearing.png; shot=1; };;
  esac
  [ $(echo "$got" | tr '|' '\n' | grep -c .) -ge 5 ] && break
done
n=$(echo "$got" | tr '|' '\n' | grep -c .)
say "  замечено строк: $n"; echo "$got" | tr '|' '\n' | grep . | sort | uniq -c | sort -rn | head -5 | sed 's/^/   /'
[ "$n" -ge 3 ] && res 0 "L2 строка меняется по фразам" || res 1 "L2 итогов по фразам не видно"
echo "$got" | grep -qE '[0-9]+ дБ' && res 0 "L2 в итоге есть цифра" || res 1 "L2 в итоге нет цифры"
echo "$got" | grep -q 'слышно хорошо' && res 0 "L3 тихая комната near-pt — «слышно хорошо»" || res 1 "L3 для near-pt ни разу не «слышно хорошо»"
say "  снимок экрана: /tmp/falar-hearing.png"
