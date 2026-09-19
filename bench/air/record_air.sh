#!/bin/bash
# Записать один проход корпуса через комнату — сырым потоком микрофона.
#
#   bash bench/air/record_air.sh --listener <serial> --player <serial> --lang pt --label near
#
# Зачем отдельно от run_air.sh: подбирать настройки нарезки последовательными прогонами через
# живую комнату нельзя — SNR между прогонами гулял 14→21 дБ, и разница между комнатами больше
# разницы между настройками (results/2026-09-12-air.md). Поэтому комнату записываем один раз,
# а дальше крутим запись (replay_air.sh).
#
# Кладётся в bench/air/rec/<label>-<lang>/: room.wav — сырой микрофон, player.tsv — что и когда
# играл второй телефон, listener.tsv — как нарезал живой тракт в этот же проход (это база).
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=$R/tools/platform-tools/adb
PKG=app.falar
LIS=""; PLY=""; LANG_=""; LABEL=""; GAP=3000; VOL=15; NORM=-14; SEC=0

while [ $# -gt 0 ]; do
  case "$1" in
    --listener) LIS=$2; shift 2;; --player) PLY=$2; shift 2;;
    --lang) LANG_=$2; shift 2;;   --label) LABEL=$2; shift 2;;
    --gap) GAP=$2; shift 2;;      --vol) VOL=$2; shift 2;;
    --norm) NORM=$2; shift 2;;    --sec) SEC=$2; shift 2;;
    *) echo "не знаю ключ: $1"; exit 2;;
  esac
done
[ -n "$LIS" ] && [ -n "$PLY" ] && [ -n "$LANG_" ] && [ -n "$LABEL" ] || { echo "нужны --listener --player --lang --label"; exit 2; }
case "$LANG_" in pt) DIR=pt2ru;; ru) DIR=ru2pt;; *) echo "--lang: pt или ru"; exit 2;; esac

L="$ADB -s $LIS"; P="$ADB -s $PLY"
REF=$R/bench/air/corpus/$LANG_
EXT=/sdcard/Android/data/$PKG/files
OUT=$R/bench/air/rec/$LABEL-$LANG_
mkdir -p "$OUT"

offset() { local a=$1 h d; h=$(date +%s%3N); d=$($a shell date +%s%3N | tr -d '\r'); echo $(( d - h )); }
lines()  { $1 shell "test -f $EXT/at.tsv && wc -l < $EXT/at.tsv || echo 0" | tr -d '\r '; }
tailgrep() { $1 shell "tail -n +$(( $3 + 1 )) $2 2>/dev/null | grep -c -- '$4' || echo 0" | tr -d '\r ' | tail -1; }
mkdirs() { local t; t=$(mktemp); echo "$2" > "$t"; $1 push "$t" "$EXT/dirs.txt" >/dev/null
           $1 shell am start -n $PKG/dev.agenttranslator.MainActivity --es mkdirs 1 >/dev/null 2>&1; sleep 4; rm -f "$t"; }

NF=$(ls "$REF"/*.wav | wc -l)
# Длительность записи с запасом: файлы + паузы + разгон. Мало — хвост корпуса не попадёт.
if [ "$SEC" = 0 ]; then
  AUD=$(python3 -c "
import wave,glob
print(int(sum(wave.open(f).getnframes()/wave.open(f).getframerate() for f in glob.glob('$REF/*.wav'))))")
  SEC=$(( AUD + NF * GAP / 1000 + 60 ))
fi

echo "== подготовка: $NF файлов, запись $SEC с =="
$L wait-for-device; $P wait-for-device
OFF_L=$(offset "$L"); OFF_P=$(offset "$P")
mkdirs "$P" "air/$LANG_"
$P push "$REF/." "$EXT/air/$LANG_/" >/dev/null
$P shell "rm -f $EXT/air/$LANG_/*.txt"
$P shell "cmd media_session volume --stream 3 --set $VOL" >/dev/null 2>&1 || true

N0_L=$(lines "$L"); N0_P=$(lines "$P")
N0_LOG=$($L shell "test -f $EXT/at.log && wc -l < $EXT/at.log || echo 0" | tr -d '\r ')

$L shell am force-stop $PKG >/dev/null 2>&1 || true
$P shell am force-stop $PKG >/dev/null 2>&1 || true
sleep 2
$L shell am start -n $PKG/dev.agenttranslator.MainActivity --es vad 1 --es silent 1 --es fixdir $DIR --es denoise 0 >/dev/null
READY=0
for _ in $(seq 40); do sleep 2; [ "$(tailgrep "$L" "$EXT/at.log" "$N0_LOG" 'микрофон:')" != 0 ] && { READY=1; break; }; done
[ "$READY" = 1 ] || { echo "слушающий не поднялся"; exit 1; }
$L shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true
sleep 5

echo "== пишу комнату и играю корпус =="
$L shell am start -n $PKG/dev.agenttranslator.MainActivity --es rawsec "$SEC" >/dev/null
sleep 2
$P shell am start -n $PKG/dev.agenttranslator.MainActivity --es vad 0 --es playdir "$EXT/air/$LANG_" --es gap "$GAP" --es norm "$NORM" >/dev/null
sleep 5; $P shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true

T=0; WAIT=$(( SEC + 120 ))
while [ $T -lt $WAIT ]; do
  sleep 15; T=$(( T + 15 ))
  [ "$(tailgrep "$L" "$EXT/at.log" "$N0_LOG" 'сырой поток сохранён')" != 0 ] && { echo "  запись закрыта на $T с"; break; }
  printf '  %s с из %s\n' "$T" "$SEC"
done

echo "== забираю =="
$L pull "$EXT/raw.wav" "$OUT/room.wav" >/dev/null
$L shell "tail -n +$(( N0_L + 1 )) $EXT/at.tsv" | tr -d '\r' > "$OUT/listener.tsv"
$P shell "tail -n +$(( N0_P + 1 )) $EXT/at.tsv" | tr -d '\r' > "$OUT/player.tsv"
echo "$OFF_L" > "$OUT/listener.offset"; echo "$OFF_P" > "$OUT/player.offset"
printf 'язык=%s пауза=%s норма=%s громкость=%s длительность=%s\n' "$LANG_" "$GAP" "$NORM" "$VOL" "$SEC" > "$OUT/rec.txt"
python3 -c "
import wave; w=wave.open('$OUT/room.wav'); print('  room.wav: %.0f с, %d Гц' % (w.getnframes()/w.getframerate(), w.getframerate()))"

echo "== как нарезал живой тракт в этот же проход =="
python3 "$R/bench/air/air_wer.py" "$OUT" --lang "$LANG_" --mode air --gap "$GAP" | tail -5
