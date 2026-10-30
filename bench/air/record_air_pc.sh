#!/bin/bash
# Запись комнаты, где фразы играет ПК через колонки, а слушает телефон — сырой поток микрофона (стенд --es rawsec).
# Шаг 9, часть Б results/2026-10-27-asr-confidence.md: второй телефон как проигрыватель не нужен.
#
#   ANDROID_SERIAL=<телефон> bash bench/air/record_air_pc.sh --lang pt --label pc-cafe --list <фразы.txt> [--gain дБ]
#   ANDROID_SERIAL=<телефон> bash bench/air/record_air_pc.sh --lang pt --level 10 [--list <фразы.txt>]
#
# --level N — только уровни: N секунд сырого потока (со списком — пока играют эти фразы), фон и речь в dBFS. Так
# подбираются громкость колонок и источника шума до записи. Запись ложится в bench/air/rec/<label>-<lang>/ (вне git) и
# читается tools/asr_confidence.py и tools/asr_beam_eval.py; привязка фраз — по звуку (tools/room_rec.py finish).
#
# Пока слушает телефон, приложение переводит услышанное — в свой тестовый разговор, без озвучки; в конце всё как было:
# слушание выключено, частота разбора и облака, выученное и словари из снимка, тестовый разговор удалён. Телефон рабочий:
# Falar запускается, только когда экран погашен или впереди Falar или рабочий стол.
R=$(cd "$(dirname "$0")/../.." && pwd)
MAIN=$(cd "$(git -C "$R" rev-parse --path-format=absolute --git-common-dir)/.." && pwd)
STAND=${FALAR_STAND:-$MAIN/.stand}; PRIVATE=${FALAR_PRIVATE:-$MAIN/private}
pick() { [ -e "$R/$1" ] && echo "$R/$1" || echo "$MAIN/$1"; }
ADB=${ADB:-$(pick tools/platform-tools/adb)}; PY=$(pick .venv)/bin/python
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
LANG_=""; LABEL=""; LIST=""; GAIN=0; LEVEL=0; NOTE=${NOTE:-}
while [ $# -gt 0 ]; do
  case "$1" in --lang) LANG_=$2; shift 2;; --label) LABEL=$2; shift 2;; --list) LIST=$2; shift 2;;
    --gain) GAIN=$2; shift 2;; --level) LEVEL=$2; shift 2;; *) echo "не знаю ключ: $1"; exit 2;; esac
done
case "$LANG_" in pt) DIR=pt2ru;; ru) DIR=ru2pt;; *) echo "--lang: pt или ru"; exit 2;; esac
if [ "$LEVEL" = 0 ] && { [ -z "$LABEL" ] || [ -z "$LIST" ]; }; then echo "нужны --label и --list (или --level N)"; exit 2; fi
[ -n "$ANDROID_SERIAL" ] || { echo "задайте ANDROID_SERIAL: при двух подключениях adb телефона не выберет"; exit 2; }
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "$STAND"; exec 9>"$STAND/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
mkdir -p "$PRIVATE"; D=$(mktemp -d "$PRIVATE/roomrec.XXXX"); SNAP=$D/snap; mkdir -p $SNAP
TID=$(date +%s%3N)
say() { printf '%s\n' "$*"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
FREE_S=${FREE_S:-60}
focus() { sh "dumpsys window | grep -m1 mCurrentFocus"; }
idle() {
  sh "dumpsys power" | grep -qE "mWakefulness=(Asleep|Dozing)" && return 0
  local a; a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
  case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) [ "${a:-0}" -ge $((FREE_S * 1000)) ] && return 0;; esac; return 1
}
launch() {
  local n=0
  until idle; do n=$((n+1)); [ $n -eq 1 ] && say "  (запуск ждёт, пока телефон освободится: $(focus))"; sleep 5; done
  $ADB shell "am start -n $ACT $*" >/dev/null 2>&1
}
count() { sh "test -f $1 && wc -l < $1 || echo 0" | awk '{print $1+0}'; }
seen() { sh "tail -n +$(($1+1)) $LOG | grep -c -E -- '$2'" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
REF=; CLOUD=
orig_settings() {
  local m st; m=$(count $LOG); launch --es settings show
  st=$(wl "$m" '🧪 настройки:' 15)
  REF=$(printf '%s' "$st" | sed -n 's/.*разбор \([0-9][0-9]*\).*/\1/p'); CLOUD=$(printf '%s' "$st" | sed -n 's/.*облако \([0-9][0-9]*\).*/\1/p')
  REF=${REF:-3}; CLOUD=${CLOUD:-0}
}
restore() {
  say "== возврат"
  $ADB shell "am force-stop $PKG"; sleep 2
  launch --es listen off --es silent 0 --es feedonly 0 --es refineevery $REF --es cloudevery $CLOUD; sleep 3
  $ADB shell "am force-stop $PKG"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1; done
  $ADB shell "rm -f $F/chats/$TID.json $F/raw.wav"
  say "  тестовый разговор удалён · текущим снова станет: $(sh "ls -t $F/chats/ | head -1") · разбор $REF, облако $CLOUD"
  rm -rf "$D"
}
# Сырой поток N секунд, пока (если задан) играет плейлист с ПК: файл — в $D/raw.wav.
record() {   # record <секунд> [плейлист.wav]
  local m; m=$(count $LOG); sh "rm -f $F/raw.wav"
  launch --es rawsec "$1"
  wl "$m" '💾 пишу сырой поток' 20 >/dev/null || { say "запись не началась"; return 1; }
  [ -n "$2" ] && { sleep 1; paplay "$2" || pw-play "$2"; }
  wl "$m" '💾 сырой поток сохранён' $(( $1 + 60 )) >/dev/null || { say "запись не закрылась"; return 1; }
  $ADB pull "$F/raw.wav" "$D/raw.wav" >/dev/null 2>&1
}

[ -n "$(sh 'echo ok')" ] || { say "телефон не подключён ($ANDROID_SERIAL)"; exit 2; }
orig_settings
trap restore EXIT
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1; done
"$PY" -c "import json; json.dump({'id': $TID, 'name': 'ТЕСТ запись комнаты', 'named': True, 'saved': $TID, 'turns': []}, open('$D/t.json','w',encoding='utf-8'), ensure_ascii=False)"
$ADB shell "am force-stop $PKG"; sleep 1; $ADB push $D/t.json "$F/chats/$TID.json" >/dev/null 2>&1
m0=$(count $LOG)
launch --es listen $LANG_ --es silent 1 --es feedonly 0 --es refineevery 0 --es cloudevery 0 --es fixdir $DIR
for _ in $(seq 60); do sleep 2; [ "$(seen $m0 '🧩 модули:')" != 0 ] && [ "$(seen $m0 'микрофон:|слушаю')" != 0 ] && break; done
sleep 3

if [ "$LEVEL" != 0 ]; then
  PL=; [ -n "$LIST" ] && { "$PY" "$R/tools/room_rec.py" build "$LIST" $LANG_ $D/pl.wav --gain $GAIN >/dev/null; PL=$D/pl.wav; }
  say "== уровни: $LEVEL с${PL:+, играют фразы (усиление $GAIN дБ)}"
  record "$LEVEL" "$PL" && "$PY" "$R/tools/room_rec.py" level $D/raw.wav
  exit 0
fi
"$PY" "$R/tools/room_rec.py" build "$LIST" $LANG_ $D/pl.wav --gain $GAIN
SEC=$("$PY" -c "import wave; w=wave.open('$D/pl.wav'); print(int(w.getnframes() / w.getframerate()) + 8)")
say "== пишу комнату $SEC с: ПК играет фразы, телефон слушает"
record "$SEC" $D/pl.wav || exit 1
OUT=$MAIN/bench/air/rec/$LABEL-$LANG_
"$PY" "$R/tools/room_rec.py" finish $D/raw.wav $D/pl.wav "$OUT" $LANG_ --note "усиление $GAIN дБ; $NOTE"
