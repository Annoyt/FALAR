#!/bin/bash
# Прогнать записанную комнату через нарезку с заданными настройками.
#
# УСТАРЕЛ (03.10.2026). Прогон не отделён от разговоров владельца: реплики ложатся в его текущий
# разговор, модуль отпечатка голоса не выключается — при включённых голосах куски отбрасываются
# («в разговоре ещё нет голосов»), и WER выходит 100 % (так было 02.10). До 03.10 скрипт ещё и
# оставлял приложение слушать комнату после выхода (44 минуты 02.10). То же самое правильно делает
# bash bench/air/gain_sweep.sh --rec near-pt --seg "preroll=800 …": отдельный тестовый разговор,
# модули на время выключены и возвращены, ждёт свободный телефон; WER — asr_device.py, как в проверке
# качества перед выпуском. Запуск этого — только с FORCE=1.
#
#   bash bench/air/replay_air.sh --listener <serial> --rec bench/air/rec/near-pt \
#        --seg "preroll=300 tail=300 gate=6 minspeech=250" --label g6
#
# Комната зафиксирована в записи, поэтому два прогона отличаются ровно настройками — в отличие
# от прогонов через живой воздух, где разница между комнатами больше разницы между настройками.
# Стенд --es feedonly 1 держит микрофон глухим и вне подачи: до неё и после «подача закончена» в нарезку
# шли живые фразы комнаты и попадали в listener.tsv (02.10 так 16 минут не кончался gain_sweep). На выходе
# — feedonly 0, иначе приложение осталось бы глухим, пока его не откроют с рабочего стола.
set -e
if [ "${FORCE:-}" != 1 ]; then
  echo "replay_air.sh устарел: пишет в текущий разговор владельца и не выключает отпечаток голоса (WER тогда 100 %)." >&2
  echo "Вместо него: bash bench/air/gain_sweep.sh --rec <запись> --seg \"preroll=… gate=…\". Всё же запустить — FORCE=1." >&2
  exit 2
fi
R=$(cd "$(dirname "$0")/../.." && pwd)
# Один прогон на телефоне за раз — и отдельный скрипт, и вызванный из других (как у test_*_device.sh).
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand"; exec 9>"${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
ADB=$R/tools/platform-tools/adb
PKG=app.falar
LIS=""; REC=""; SEG=""; LABEL=""; SPEED=4

while [ $# -gt 0 ]; do
  case "$1" in
    --listener) LIS=$2; shift 2;; --rec) REC=$2; shift 2;;
    --seg) SEG=$2; shift 2;;      --label) LABEL=$2; shift 2;;
    --speed) SPEED=$2; shift 2;;
    *) echo "не знаю ключ: $1"; exit 2;;
  esac
done
[ -n "$LIS" ] && [ -n "$REC" ] || { echo "нужны --listener и --rec"; exit 2; }
[ -d "$REC" ] || { echo "нет каталога записи: $REC"; exit 2; }
LANG_=$(basename "$REC" | sed 's/.*-//')
case "$LANG_" in pt) DIR=pt2ru;; ru) DIR=ru2pt;; *) echo "по имени каталога не понять язык: $REC"; exit 2;; esac
[ -n "$LABEL" ] || LABEL=$(echo "$SEG" | tr ' =' '--')

L="$ADB -s $LIS"
EXT=/sdcard/Android/data/$PKG/files
OUT=$REC/replay-$LABEL
mkdir -p "$OUT"

offset() { local h d; h=$(date +%s%3N); d=$($L shell date +%s%3N | tr -d '\r'); echo $(( d - h )); }
lines()  { $L shell "test -f $EXT/at.tsv && wc -l < $EXT/at.tsv || echo 0" | tr -d '\r '; }
tailgrep() { $L shell "tail -n +$(( $1 + 1 )) $EXT/at.log 2>/dev/null | grep -c -- '$2' || echo 0" | tr -d '\r ' | tail -1; }

SEGARGS=""; for kv in $SEG; do SEGARGS="$SEGARGS --es ${kv%%=*} ${kv#*=}"; done
echo "== настройки: ${SEG:-по умолчанию} · скорость ×$SPEED =="

OFF_L=$(offset)
# Запись кладём в files/, а не в подкаталог: каталог, созданный через adb, остаётся shell:0770
# и приложение внутрь не входит — подача падает с «Failed to read wave file».
$L push "$REC/room.wav" "$EXT/replay.wav" >/dev/null
N0_L=$(lines); N0_LOG=$($L shell "test -f $EXT/at.log && wc -l < $EXT/at.log || echo 0" | tr -d '\r ')

$L shell am force-stop $PKG >/dev/null 2>&1 || true; sleep 2
trap '$L shell am start -n $PKG/dev.agenttranslator.MainActivity --es vad 0 --es feedonly 0 >/dev/null 2>&1 || true' EXIT
$L shell am start -n $PKG/dev.agenttranslator.MainActivity --es vad 1 --es silent 1 --es feedonly 1 --es fixdir $DIR --es denoise 0 $SEGARGS >/dev/null
READY=0
for _ in $(seq 40); do sleep 2; [ "$(tailgrep "$N0_LOG" 'микрофон:')" != 0 ] && { READY=1; break; }; done
[ "$READY" = 1 ] || { echo "слушающий не поднялся"; exit 1; }
$L shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true

SEC=$(python3 -c "import wave; w=wave.open('$REC/room.wav'); print(int(w.getnframes()/w.getframerate()))")
echo "== подаю $SEC с записи =="
$L shell am start -n $PKG/dev.agenttranslator.MainActivity --es feedwav "$EXT/replay.wav" --es speed "$SPEED" >/dev/null
T=0; WAIT=$(( SEC / SPEED + 180 ))
while [ $T -lt $WAIT ]; do
  sleep 10; T=$(( T + 10 ))
  [ "$(tailgrep "$N0_LOG" 'подача закончена')" != 0 ] && { echo "  подача закончена на $T с"; break; }
  printf '  %s с\n' "$T"
done
sleep 10

$L shell "tail -n +$(( N0_L + 1 )) $EXT/at.tsv" | tr -d '\r' > "$OUT/listener.tsv"
echo "$OFF_L" > "$OUT/listener.offset"
echo "${SEG:-по умолчанию}" > "$OUT/seg.txt"

python3 "$R/bench/air/air_wer.py" "$OUT" --lang "$LANG_" --mode replay --rec "$REC"
