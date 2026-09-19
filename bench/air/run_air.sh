#!/bin/bash
# Замер распознавания через воздух между двумя устройствами.
#
#   bash bench/air/run_air.sh --listener <serial> --player <serial> --lang pt [опции]
#   bash bench/air/run_air.sh --listener <serial> --lang pt --mode file      # та же машина, но по файлу
#
# Слушающий — целевое устройство: держит микрофон в непрерывном режиме и пишет at.tsv.
# Говорящий — любой телефон с этим же APK; моделей не требует, только проигрывает эталоны
# в динамик с выровненной громкостью.
#
# Смысл двух режимов: «по файлу» на том же устройстве даёт базу, «через воздух» — то же самое
# плюс комната, динамик, микрофон и шум. Разница между ними и есть цена воздуха. Сравнивать
# воздух на одном телефоне с файлом на другом нельзя — смешаются два фактора.
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=$R/tools/platform-tools/adb
PKG=dev.agenttranslator
LIS=""; PLY=""; LANG_=""; GAP=4000; ROUNDS=1; VOL=""; LABEL=""; NORM=-20; MODE=air; SEG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --listener) LIS=$2; shift 2;;
    --player)   PLY=$2; shift 2;;
    --lang)     LANG_=$2; shift 2;;
    --gap)      GAP=$2; shift 2;;
    --rounds)   ROUNDS=$2; shift 2;;
    --vol)      VOL=$2; shift 2;;
    --label)    LABEL=$2; shift 2;;
    --norm)     NORM=$2; shift 2;;
    --mode)     MODE=$2; shift 2;;
    --seg)      SEG=$2; shift 2;;      # настройки нарезки: «preroll=400 gate=3 minspeech=150»
    *) echo "не знаю ключ: $1"; exit 2;;
  esac
done
[ -n "$LIS" ] && [ -n "$LANG_" ] || { echo "нужны --listener и --lang"; exit 2; }
[ "$MODE" = file ] || [ -n "$PLY" ] || { echo "для режима air нужен --player"; exit 2; }
[ -n "$LABEL" ] || LABEL=$MODE
case "$LANG_" in pt) DIR=pt2ru;; ru) DIR=ru2pt;; *) echo "--lang: pt или ru"; exit 2;; esac

L="$ADB -s $LIS"
P="$ADB -s $PLY"
REF=$R/bench/air/corpus/$LANG_
EXT=/sdcard/Android/data/$PKG/files
OUT=$R/bench/air/logs/$LABEL-$LANG_
mkdir -p "$OUT"

# Часы двух телефонов и компьютера расходятся; без поправки сопоставление по времени врёт.
offset() {  # печатает (время устройства − время компьютера) в мс
  local a=$1 h d
  h=$(date +%s%3N); d=$($a shell date +%s%3N | tr -d '\r')
  echo $(( d - h ))
}
lines() { $1 shell "test -f $EXT/at.tsv && wc -l < $EXT/at.tsv || echo 0" | tr -d '\r '; }
# Каталог, созданный через adb, на Android 16 остаётся shell:ext_data_rw 0770 — приложение
# внутрь не войдёт. Поэтому каталог просим создать само приложение, а файлы кладём внутрь.
mkdirs() {  # $1 = команда adb, $2 = путь относительно files/
  local t; t=$(mktemp); echo "$2" > "$t"
  $1 push "$t" "$EXT/dirs.txt" >/dev/null
  $1 shell am start -n $PKG/.MainActivity --es mkdirs 1 >/dev/null 2>&1
  sleep 4; rm -f "$t"
}

echo "== подготовка =="
$L wait-for-device
$L shell pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null || true
$L shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
OFF_L=$(offset "$L"); echo "  слушающий $LIS, часы $OFF_L мс к компьютеру"

if [ "$MODE" = file ]; then
  mkdirs "$L" "air/$LANG_"
  $L push "$REF/." "$EXT/air/$LANG_/" >/dev/null
  $L shell "rm -f $EXT/air/$LANG_/*.txt"
fi

if [ "$MODE" = air ]; then
  $P wait-for-device
  OFF_P=$(offset "$P"); echo "  говорящий  $PLY, часы $OFF_P мс к компьютеру"
  mkdirs "$P" "air/$LANG_"
  $P push "$REF/." "$EXT/air/$LANG_/" >/dev/null
  $P shell "rm -f $EXT/air/$LANG_/*.txt"
  # cmd media_session, а не media: на MIUI 14 бинарника media нет вовсе, и под set -e
  # ненулевой код молча убивал весь прогон ещё до первого файла.
  [ -n "$VOL" ] && { $P shell "cmd media_session volume --stream 3 --set $VOL" >/dev/null 2>&1 || true; }
  VOLNOW=$($P shell "cmd media_session volume --stream 3 --get" 2>/dev/null | tr -d '\r' | grep -o 'volume is .*' | tail -1)
  [ -n "$VOLNOW" ] || VOLNOW="неизвестна"
  echo "  громкость говорящего: $VOLNOW"
fi

# Журналы на телефоне только дописываются и никогда не обнуляются, поэтому всё, что мы ищем,
# ищем ТОЛЬКО в хвосте, добавленном этим прогоном. Иначе второй прогон на тех же телефонах
# находит метку конца от первого и обрывается на десятой секунде, напечатав правдоподобное
# и неверное число.
N0_L=$(lines "$L")
N0_LOG=$($L shell "test -f $EXT/at.log && wc -l < $EXT/at.log || echo 0" | tr -d '\r ')
[ "$MODE" = air ] && N0_P=$(lines "$P")
tailgrep() {  # $1 = adb, $2 = файл, $3 = смещение, $4 = что искать
  $1 shell "tail -n +$(( $3 + 1 )) $2 2>/dev/null | grep -c -- '$4' || echo 0" | tr -d '\r ' | tail -1
}

echo "== слушающий в непрерывный режим =="
# Перезапуск начисто обязателен: иначе метка «микрофон:» осталась от прошлого прогона и её
# не будет в новом хвосте, а ждать нечего. Заодно обнуляются VAD и накопленный уровень фона.
$L shell am force-stop $PKG >/dev/null 2>&1 || true
[ "$MODE" = air ] && { $P shell am force-stop $PKG >/dev/null 2>&1 || true; }
sleep 2
# Направление закреплено: профилей голоса на стенде нет, а по умолчанию route() выбрал бы pt2ru.
# Молчаливый режим: иначе собственная озвучка лезет в воздух между эталонами.
# В режиме file микрофон выключаем: иначе окружающий звук даёт лишние сегменты, и они
# приписываются последнему проигранному файлу — ровно так и получилось в первом прогоне.
SEGARGS=""
for kv in $SEG; do SEGARGS="$SEGARGS --es ${kv%%=*} ${kv#*=}"; done
$L shell am start -n $PKG/.MainActivity \
   --es vad $([ "$MODE" = file ] && echo 0 || echo 1) --es silent 1 --es fixdir $DIR --es denoise 0 $SEGARGS >/dev/null
# Ждём настоящей готовности, а не фиксированной паузы: холодная загрузка моделей заняла
# 3 с на POCO и 13 с на Redmi, и на медленном устройстве первые файлы уезжали бы в тишину.
# Метка — строка «микрофон:», её пишет startCapture() сразу после rec.startRecording().
READY=0
for _ in $(seq 40); do
  sleep 2
  [ "$(tailgrep "$L" "$EXT/at.log" "$N0_LOG" 'микрофон:')" != 0 ] && { READY=1; break; }
done
[ "$READY" = 1 ] || { echo "слушающий не поднялся: нет метки «микрофон:» в журнале"; exit 1; }
$L shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true
sleep 5                       # фон копится в тишине, дадим ему устояться

NF=$(ls "$REF"/*.wav | wc -l)
if [ "$MODE" = file ]; then
  echo "== прогон по файлу: $NF файлов =="
  $L shell am start -n $PKG/.MainActivity \
     --es soak "$EXT/air/$LANG_" --es dir $DIR --es gap 1500 --es rounds "$ROUNDS" >/dev/null
  WAIT=$(( NF * ROUNDS * 8 + 60 ))
else
  echo "== прогон через воздух: $NF файлов × $ROUNDS, пауза $GAP мс =="
  $P shell am start -n $PKG/.MainActivity \
     --es vad 0 --es playdir "$EXT/air/$LANG_" --es gap "$GAP" --es rounds "$ROUNDS" --es norm "$NORM" >/dev/null
  sleep 5
  $P shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true
  WAIT=$(( NF * ROUNDS * (GAP / 1000 + 10) + 90 ))
fi

echo "== жду окончания (не дольше $WAIT с) =="
T=0; DONE=0
while [ $T -lt $WAIT ]; do
  sleep 10; T=$(( T + 10 ))
  if [ "$MODE" = air ]; then
    [ "$(tailgrep "$P" "$EXT/at.tsv" "$N0_P" 'play_end')" != 0 ] && { echo "  говорящий закончил на $T с"; DONE=1; break; }
  else
    [ "$(tailgrep "$L" "$EXT/at.log" "$N0_LOG" 'соак завершён')" != 0 ] && { echo "  соак закончил на $T с"; DONE=1; break; }
  fi
  printf '  %s с\n' "$T"
done
[ "$DONE" = 1 ] || echo "  ВНИМАНИЕ: метка конца не дождалась, число считается по неполному прогону"
sleep 15                      # последняя фраза ещё распознаётся

echo "== забираю журналы =="
# Конвейер прячет код возврата adb, и отвалившийся телефон печатался бы как «WER 100%».
$L shell "tail -n +$(( N0_L + 1 )) $EXT/at.tsv" > "$OUT/listener.raw" || { echo "слушающий отвалился, замера нет"; exit 1; }
tr -d '\r' < "$OUT/listener.raw" > "$OUT/listener.tsv"; rm -f "$OUT/listener.raw"
echo "$OFF_L" > "$OUT/listener.offset"
if [ "$MODE" = air ]; then
  $P shell "tail -n +$(( N0_P + 1 )) $EXT/at.tsv" > "$OUT/player.raw" || { echo "говорящий отвалился, замера нет"; exit 1; }
  tr -d '\r' < "$OUT/player.raw" > "$OUT/player.tsv"; rm -f "$OUT/player.raw"
  echo "$OFF_P" > "$OUT/player.offset"
  echo "$VOLNOW" > "$OUT/volume"
fi
$L shell "tail -300 $EXT/at.log" | tr -d '\r' > "$OUT/listener.log"
printf 'режим=%s язык=%s пауза=%s кругов=%s норма=%s громкость=%s нарезка=%s\n' \
  "$MODE" "$LANG_" "$GAP" "$ROUNDS" "$NORM" "${VOLNOW:-—}" "${SEG:-по умолчанию}" > "$OUT/run.txt"

echo "== счёт =="
python3 "$R/bench/air/air_wer.py" "$OUT" --lang "$LANG_" --mode "$MODE" --gap "$GAP"
