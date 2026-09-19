#!/bin/bash
# Прогнать записанную комнату через нарезку с заданными настройками.
#
#   bash bench/air/replay_air.sh --listener <serial> --rec bench/air/rec/near-pt \
#        --seg "preroll=300 tail=300 gate=6 minspeech=250" --label g6
#
# Комната зафиксирована в записи, поэтому два прогона отличаются ровно настройками — в отличие
# от прогонов через живой воздух, где разница между комнатами больше разницы между настройками.
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
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
$L shell am start -n $PKG/dev.agenttranslator.MainActivity --es vad 1 --es silent 1 --es fixdir $DIR --es denoise 0 $SEGARGS >/dev/null
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
