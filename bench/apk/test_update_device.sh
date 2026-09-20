#!/bin/bash
# Обновление приложения на телефоне против локального источника: описание релиза и файл раздаются
# с компьютера через adb reverse, поэтому проверяются и «вышла новая версия», и отказ ставить файл
# с несошедшейся суммой — без настоящего релиза и без ожидания суток.
#
#   bash bench/apk/test_update_device.sh
#
# Установленная сборка выдаётся за более старую: в описании номер на единицу больше, а файл — тот
# же самый APK. До системного окна установки доходит только последний сценарий, и его скрипт не
# подтверждает: нажать «установить» может только человек.
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$R/tools/platform-tools/adb}
A=$R/bench/apk; ACT=app.falar/dev.agenttranslator.MainActivity
LOG=/sdcard/Android/data/app.falar/files/at.log
D=$(mktemp -d); SRV=""
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
stop() { [ -n "$SRV" ] && kill $SRV 2>/dev/null; SRV=""; for p in $(ss -ltnp 2>/dev/null | grep ':8765 ' | grep -o 'pid=[0-9]*' | cut -d= -f2); do kill $p 2>/dev/null; done; sleep 0.3; }
serve() { stop; (cd "$D" && python3 -m http.server 8765 --bind 127.0.0.1 >/dev/null 2>&1 &) ; sleep 0.8; }
trap 'stop; rm -rf $D' EXIT

[ -f "$A/Falar.apk" ] || { say "нет $A/Falar.apk — сначала bench/apk/build.sh"; exit 1; }
$ADB wait-for-device
$ADB reverse tcp:8765 tcp:8765 >/dev/null
cp "$A/Falar.apk" "$D/Falar.apk"
CODE=$(grep -o 'versionCode="[0-9]*"' "$A/AndroidManifest.xml" | grep -o '[0-9]*')
SUM=$(sha256sum "$D/Falar.apk" | cut -d' ' -f1); SIZE=$(stat -c %s "$D/Falar.apk")
mkjson() {   # mkjson <versionCode> <sha256> <minVersionCode>
  printf '{"versionCode":%s,"versionName":"тест-%s","minVersionCode":%s,"notes":"проверка обновления","apk":"Falar.apk","size":%s,"sha256":"%s"}\n' \
    "$1" "$1" "$3" "$SIZE" "$2" > "$D/latest.json"
}
serve
$ADB shell "am start -n $ACT --es updatebase http://127.0.0.1:8765/latest.json" >/dev/null 2>&1; sleep 2

say "== U-A: та же версия — обновления нет"
mkjson "$CODE" "$SUM" 0
m=$(mark); $ADB shell "am start -n $ACT --es update check" >/dev/null 2>&1
l=$(wl "$m" '⬆ обновлений нет' 25); say "  $l"
[ -n "$l" ] && res 0 "U-A на последней версии" || res 1 "U-A"

say "== U-B: версия новее — находится и попадает в уведомление"
mkjson "$((CODE + 1))" "$SUM" 0
m=$(mark); $ADB shell "am start -n $ACT --es update check" >/dev/null 2>&1
l=$(wl "$m" '⬆ Вышла версия' 25); say "  $l"
nt=$(sh "dumpsys notification --noredact | grep -m1 -o 'Вышла версия[^\"]*'"); say "  уведомление: ${nt:-нет}"
ok=1; [ -n "$l" ] && [ -n "$nt" ] && ok=0; res $ok "U-B новая версия найдена"

say "== U-C: сумма не сошлась — файл не ставится"
mkjson "$((CODE + 1))" "$(printf '0%.0s' $(seq 64))" 0
m=$(mark); $ADB shell "am start -n $ACT --es update check" >/dev/null 2>&1; wl "$m" '⬆ Вышла версия' 25 >/dev/null
m=$(mark); $ADB shell "am start -n $ACT --es update install" >/dev/null 2>&1
l=$(wl "$m" 'контрольная сумма' 60); say "  $l"
[ -n "$l" ] && res 0 "U-C подменённый файл отвергнут" || res 1 "U-C"

say "== U-D: описание сломано — это ошибка, а не «обновлений нет»"
echo 'не json вовсе' > "$D/latest.json"
m=$(mark); $ADB shell "am start -n $ACT --es update check" >/dev/null 2>&1
l=$(wl "$m" 'проверка обновления не вышла' 25); say "  $l"
[ -n "$l" ] && res 0 "U-D сломанное описание видно" || res 1 "U-D"

say "== U-E: источника нет — тоже ошибка, а не тишина"
stop
m=$(mark); $ADB shell "am start -n $ACT --es update check" >/dev/null 2>&1
l=$(wl "$m" 'проверка обновления не вышла' 30); say "  $l"
[ -n "$l" ] && res 0 "U-E недоступный источник виден" || res 1 "U-E"
serve

say "== U-F: сумма сошлась — доходит до системного установщика"
mkjson "$((CODE + 1))" "$SUM" 0
m=$(mark); $ADB shell "am start -n $ACT --es update check" >/dev/null 2>&1; wl "$m" '⬆ Вышла версия' 25 >/dev/null
m=$(mark); $ADB shell "am start -n $ACT --es update install" >/dev/null 2>&1
l=$(wl "$m" 'сверено, отдаю установщику' 120); say "  $l"
[ -n "$l" ] && res 0 "U-F скачано, сверено, отдано установщику" || res 1 "U-F"
say "  дальше окно подтверждения показывает система — нажать «установить» может только человек"

$ADB shell "am start -n $ACT --es updatebase off" >/dev/null 2>&1
say ""; say "итог: PASS $pass, FAIL $fail"
[ $fail -eq 0 ]
