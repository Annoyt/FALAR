#!/bin/bash
# Переход перевода на два файла на направление (0.23.0) на телефоне, против локального источника.
#
#   bash bench/apk/test_mt_upgrade_device.sh
#
# Телефон приводится к состоянию «обновились с 0.22»: прежние encoder_model.onnx и decoder_model.onnx
# на месте (кладутся из зеркала models/), нового encoder_kv_model.onnx нет. Проверяется: приложение
# работает прежним путём и экран первого запуска не показывает; облегчение предлагается; скачивание
# с tools/models_serve.py через adb reverse; после сверки прежние удалены; после перезапуска —
# две сессии. В конце на телефоне остаётся новое состояние — то, к которому и идём.
#
# Телефон — рабочий телефон человека: запуск приложения только когда на экране не чужое приложение.
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; M=$F/models; LOG=$F/at.log
BASE=http://127.0.0.1:8765; SRV=""
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
free_phone() {
  local n=0
  while :; do
    local f; f=$(sh "dumpsys window | grep -m1 mCurrentFocus"); local w; w=$(sh "dumpsys power | grep -m1 mWakefulness")
    case "$f" in *$PKG*|*com.miui.home*|*launcher*|*mCurrentFocus=null*) return 0;; esac
    case "$w" in *Asleep*|*Dozing*) return 0;; esac
    n=$((n+1)); [ $n -eq 1 ] && say "  телефон занят ($f), жду…"; sleep 10
  done
}
start_app() { free_phone; $ADB shell "am start -n $ACT $*" >/dev/null 2>&1; }
mark() { sh "wc -l < $LOG" 2>/dev/null | awk '{print $1+0}'; }
# ожидание строки журнала после метки; шаблон расширенный (grep телефона не понимает «\|» в простом)
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
since() { sh "tail -n +$(($1+1)) $LOG"; }
serve() { stop_serve; python3 $R/tools/models_serve.py > /tmp/falar-serve.log 2>&1 & SRV=$!; sleep 0.7; grep -q "источник моделей" /tmp/falar-serve.log || { say "  сервер не поднялся:"; cat /tmp/falar-serve.log; exit 2; }; }
stop_serve() { [ -n "$SRV" ] && kill $SRV 2>/dev/null; SRV=""; for p in $(ss -ltnp 2>/dev/null | grep ':8765 ' | grep -o 'pid=[0-9]*' | cut -d= -f2); do kill $p 2>/dev/null; done; sleep 0.3; }
trap 'stop_serve' EXIT
size() { sh "stat -c %s $M/$1 2>/dev/null || echo 0" | awk "{print \$1+0}"; }   # нет файла — 0

$ADB wait-for-device
say "== телефон в состоянии «обновились с 0.22»: прежние файлы перевода на месте, нового нет"
free_phone; $ADB shell "am force-stop $PKG"; sleep 1
for d in pt2ru ru2pt; do
  for f in encoder_model.onnx decoder_model.onnx; do
    want=$(stat -c %s $R/models/mt/$d/$f)
    [ "$(size mt/$d/$f)" = "$want" ] || { $ADB push $R/models/mt/$d/$f $M/mt/$d/$f >/dev/null 2>&1 && say "  положен mt/$d/$f"; }
  done
  $ADB shell "rm -f $M/mt/$d/encoder_kv_model.onnx"
done

say "== U1–U2: прежним путём работает, облегчение предлагается"
m=$(mark); start_app
l=$(wl "$m" '📦 модели' 400); say "  $l"
printf '%s' "$l" | grep -qE "обязательных на месте ([0-9]+)/\1" && res 0 "U1 обязательное на месте — прежние файлы заменяют новый" || res 1 "U1 обязательного не хватает"
u=$(wl "$m" 'можно облегчить перевод' 30); say "  $u"
printf "%s" "$u" | grep -qE "30[45]\.[0-9] МБ" && res 0 "U2 предложено облегчение: около 305 МБ" || res 1 "U2 облегчение не предложено"
mt=$(wl "$m" 'MT загружен' 120); say "  $mt"
printf '%s' "$mt" | grep -q "три сессии" && res 0 "U1 перевод идёт прежним путём" || res 1 "U1 перевод: ${mt:-нет строки}"

say "== U3–U4: скачивание замены с локального источника"
$ADB reverse tcp:8765 tcp:8765 >/dev/null; serve
m=$(mark); start_app --es modelsbase $BASE; sleep 1
t0=$(date +%s); start_app --es models upgrade
l=$(wl "$m" '⬇ модели \(upgrade\)' 600); say "  $l  ($(( $(date +%s) - t0 )) с)"
printf '%s' "$l" | grep -q "Готово: 2 из 2" && res 0 "U3 оба новых файла скачаны и сверены" || res 1 "U3 загрузка: ${l:-нет строки}"
c=$(since "$m" | grep -m1 'удалены заменённые файлы'); say "  $c"
printf '%s' "$c" | grep -q "файлы: 4" && res 0 "U4 прежние четыре файла удалены" || res 1 "U4 прежние не удалены"
gone=1; for d in pt2ru ru2pt; do for f in encoder_model.onnx decoder_model.onnx; do [ "$(size mt/$d/$f)" = 0 ] || gone=0; done; done
new=1; for d in pt2ru ru2pt; do [ "$(size mt/$d/encoder_kv_model.onnx)" = "$(stat -c %s $R/models/mt/$d/encoder_kv_model.onnx)" ] || new=0; done
[ $gone = 1 ] && [ $new = 1 ] && res 0 "U4 на телефоне только новые файлы" || res 1 "U4 файлы: прежние убраны=$gone, новые на месте=$new"
since "$m" | grep -q "станет легче со следующего запуска" && res 0 "U4 сказано, когда облегчение вступит" || res 1 "U4 нет строки о перезапуске"
stop_serve

say "== U5: после перезапуска — две сессии, предлагать нечего"
free_phone; $ADB shell "am force-stop $PKG"; sleep 2
m=$(mark); start_app
l=$(wl "$m" '📦 модели' 400); say "  $l"
mt=$(wl "$m" 'MT загружен' 120); say "  $mt"
printf '%s' "$mt" | grep -q "две сессии" && res 0 "U5 перевод двумя сессиями" || res 1 "U5 перевод: ${mt:-нет строки}"
sleep 3; since "$m" | grep -q "можно облегчить" && res 1 "U5 облегчение всё ещё предлагается" || res 0 "U5 облегчать больше нечего"

say; say "итог: PASS $pass, FAIL $fail"
