#!/bin/bash
# Микрофоны телефона: сколько их отдаёт Android, что он о них сообщает и как на два канала записи приходит шум
# своего динамика — шаг 1 проверки второго микрофона (владелец 15.10: «можно ли использовать второй микрофон для
# улучшения качества звука», «микрофон надо как-то автоопределять»).
#
#   bash bench/apk/test_micpair_device.sh [vr_mo_16,vr_st_16,mic_st_48,…]   # INSTALL=1 — сначала поставить сборку
#   SER=192.168.0.175:37419 bash bench/apk/test_micpair_device.sh            # Redmi по беспроводной отладке
#
# По конфигурации за раз (--es mictest, TranslatorService.micStand): источник_каналы_частота[_микрофон] — vr
# VOICE_RECOGNITION (как у слушания), mic MIC, cam CAMCORDER, unp UNPROCESSED; mo/st — один или два канала; 16/48 кГц;
# bottom/back — микрофон с таким адресом. В каждой 0,8 с фона, потом динамик играет шум обоими каналами, левым и
# правым. Записи и сводка — в .stand/mic-<время>/ (в них и звук комнаты — в git не кладутся), на телефоне
# удаляются; разбор — tools/mic_probe.py, он же сверяет свой счёт со счётом телефона (MicPair).
#
# Только на свободный телефон: экран погашен или впереди Falar/рабочий стол, экрана не касались 3 минуты, журнал
# Falar молчит 3 минуты и слушание выключено. Громкость на время прогона — VOL из 15 (по умолчанию 8), прежняя
# возвращается и сверяется. Замок общий с test_all_device.sh.
. "$(dirname "$0")/../stand.sh"   # $STAND, $PRIVATE — состояние стенда и данные телефонов внутри проекта
R=$(cd "$(dirname "$0")/../.." && pwd)
SER=${SER:-${ANDROID_SERIAL:-f6lnlrorgi59xwge}}; ADB="$R/tools/platform-tools/adb -s $SER"
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
CFGS=${1:-vr_mo_16,vr_mo_16_bottom,vr_mo_16_back,vr_st_16,vr_st_48,mic_st_48,cam_st_48,unp_st_48}; [ "$CFGS" = "--restore" ] && CFGS=vr_mo_16
STATE=$STAND; mkdir -p "$STATE"; OUT=$STATE/mic-$(date +%Y%m%d-%H%M%S)
say() { printf '%s\n' "$*"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i l; for i in $(seq "$3"); do l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
focus() { sh "dumpsys window" | grep -m1 mCurrentFocus; }
asleep() { sh "dumpsys power" | grep -qE 'mWakefulness=(Asleep|Dozing)'; }
free3() {
  local a quiet listening
  a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
  quiet=$(( $(sh "date +%s") - $(sh "stat -c %Y $LOG 2>/dev/null || echo 0") ))
  listening=$(sh "grep -E '▶ слушаю|⏹ не слушаю' $LOG | tail -1" | grep -c '▶ слушаю')
  [ "$listening" = 0 ] && [ "$quiet" -ge 180 ] && { asleep || { case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) true;; *) false;; esac && [ "${a:-0}" -ge 180000 ]; }; }
}
wait_free() { local n=0; until free3; do n=$((n+1)); [ $n -eq 1 ] && say "  жду, пока телефон свободен ($(focus | sed 's/.*{//; s/}.*//'))"; sleep 20; done; }

PENDV=$STATE/mic-volume-pending
# Вернуть громкость из $PENDV и сверить; файл убирается, только если громкость на телефоне та самая.
putvol() {
  [ -f "$PENDV" ] || return 0
  [ -s "$PENDV" ] || { say "  ВНИМАНИЕ: файл прежней громкости пуст (обрыв питания?) — громкость не трогаю, проверьте ($PENDV)"; return 0; }
  local v now; v=$(cat "$PENDV")
  sh "cmd media_session volume --stream 3 --set $v" >/dev/null
  now=$(sh "cmd media_session volume --stream 3 --get" | sed -n 's/.*volume is \([0-9]*\).*/\1/p')
  if [ "$now" = "$v" ]; then rm -f "$PENDV"; say "  громкость возвращена: $v"; else say "  ГРОМКОСТЬ НЕ ВОЗВРАЩЕНА (нужно $v, сейчас ${now:-нет связи}) — вернёт следующий запуск или --restore"; fi
}
if [ -z "$FALAR_STAND_LOCK" ]; then
  exec 9>"$STATE/lock"; flock -n 9 || { say "  замок занят — жду, пока освободится"; flock -w ${LOCK_WAIT:-3600} 9 || { say "замок так и не освободился — не начинаю"; exit 1; }; }
fi
$ADB wait-for-device
[ "$(sh 'getprop ro.serialno')" = f6lnlrorgi59xwge ] || [ "${ANYPHONE:-0}" = 1 ] || { say "это не Redmi стенда ($(sh 'getprop ro.product.model')) — не начинаю; ANYPHONE=1 — всё равно"; exit 1; }
putvol
[ "$1" = "--restore" ] && exit 0
wait_free

if [ "${INSTALL:-0}" = 1 ]; then
  APK=$R/bench/apk/Falar.apk
  vnew=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$R/bench/apk/AndroidManifest.xml" | head -1)
  vold=$(sh "dumpsys package $PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  [ -n "$vold" ] && [ "${vnew:-0}" -lt "$vold" ] && { say "сборка $vnew старше той, что на телефоне ($vold) — не ставлю"; exit 1; }
  out=$($ADB install --no-incremental -r "$APK" 2>&1 | tr -d '\r' | grep -E '^(Success|Failure)'); say "  установка: ${out:-нет ответа}"
  case "$out" in Success*) ;; *) exit 1;; esac
fi
inst=$(sh "sha256sum \$(pm path $PKG | head -1 | cut -d: -f2)" | cut -d' ' -f1); mine=$(sha256sum "$R/bench/apk/Falar.apk" 2>/dev/null | cut -d' ' -f1)
[ "${ANYBUILD:-0}" = 1 ] || [ "$inst" = "$mine" ] || { say "на телефоне другая сборка (${inst:0:12}…, эта ${mine:0:12}…) — не начинаю; INSTALL=1 поставит эту"; exit 1; }
mkdir -p "$OUT"
VOL0=$(sh "cmd media_session volume --stream 3 --get" | sed -n 's/.*volume is \([0-9]*\).*/\1/p')
[ -n "$VOL0" ] || { say "громкость не прочлась — не начинаю"; exit 1; }
printf '%s\n' "$VOL0" > "$PENDV.tmp" && sync "$PENDV.tmp" 2>/dev/null; mv -f "$PENDV.tmp" "$PENDV"; sync   # до смены громкости — на диске
trap 'putvol' EXIT
sh "cmd media_session volume --stream 3 --set ${VOL:-8}" >/dev/null; say "  громкость: ${VOL:-8} из 15 (была $VOL0)"
m=$(mark); $ADB shell "am start -n $ACT" >/dev/null 2>&1
wl "$m" '🧩 модули:' 150 >/dev/null; sleep 3
m=$(mark)
$ADB shell "am start -n $ACT --es mictest $CFGS" >/dev/null 2>&1
wl "$m" '🎤 стенд микрофонов: (готово|ошибка)' $(( $(printf '%s\n' "$CFGS" | tr ',' '\n' | grep -c .) * 10 + 60 )) >/dev/null \
  || say "  стенд не ответил «готово» — что есть в журнале:"
sh "tail -n +$((m+1)) $LOG" | grep '🎤' | tee "$OUT/log.txt" | sed 's/^/  /'
for f in mic.json $(sh "ls $F" | grep -E '^mic_.*\.wav$'); do
  $ADB pull "$F/$f" "$OUT/" >/dev/null 2>&1 && $ADB shell "rm -f $F/$f" < /dev/null
done
say "записи: $OUT"
[ -f "$OUT/mic.json" ] && { PY=$R/.venv/bin/python; [ -x "$PY" ] || PY=python3; say; "$PY" "$R/tools/mic_probe.py" "$OUT"; }
