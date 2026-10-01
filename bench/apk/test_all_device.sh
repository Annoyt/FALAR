#!/bin/bash
# Все проверки на телефоне одной командой: по очереди, под замком, со страховочным снимком и итогом.
#
#   bash bench/apk/test_all_device.sh                  # экран 0.26.0 — набор SUITES ниже
#   bash bench/apk/test_all_device.sh ui hearing       # только эти: <имя> — test_<имя>_device.sh;
#                                                      # scroll-day и scroll-night — test_scroll с THEME
#   BUILD=1 bash bench/apk/test_all_device.sh          # сначала сборка, тесты на столе и установка
#   bash bench/apk/test_all_device.sh --restore        # только вернуть телефон по страховочному снимку
#
# Без человека:
# - замок: второй прогон не начнётся, пока идёт первый — ни этот, ни отдельный test_*_device.sh
#   (01.10 две копии test_ui девять минут касались телефона одновременно);
# - BUILD=1: build.sh, test.sh (провал на столе — на телефон не идём), установка — только когда
#   телефон свободен; Falar, бывший впереди, после установки снова впереди;
# - страховочный снимок до прогона (выученное, словари, свои слова, пины, ключи облака, разговоры,
#   настройки, модули, состояние «Что нового») в ~/.cache/falar-stand/. После прогона — сверка и возврат
#   того, что отличается. Окно «Что нового», которое ждёт показа (поставили новую версию), на время
#   прогона прячется — иначе оно легло бы поверх экрана под касаниями проверок — и после возвращается;
#   Прогон оборвался, даже kill -9 — снимок остаётся «невозвращённым», и следующий запуск сначала
#   возвращает его (или --restore). Разговоры, появившиеся за прогон, удаляются, только если они
#   тестовые («ТЕСТ …») или пустые: настоящий разговор владельца не трогается;
# - Falar запускается только поверх себя, рабочего стола или погашенного экрана — телефон рабочий,
#   его могут взять в руки посреди прогона;
# - падения Falar за прогон — по logcat; итог — таблица по скриптам, журналы — в
#   ~/.cache/falar-stand/runs/<время>/ (из /tmp их стирала перезагрузка ПК посреди прогона), пять
#   последних; код выхода 0 — только если всё прошло и Falar не падал.
R=$(cd "$(dirname "$0")/../.." && pwd); A=$R/bench/apk
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
STATE=${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand; mkdir -p "$STATE"
SUITES="ui better scroll-day scroll-night busy memo hearing mic modules whatsnew"
FILES="models/learned.json models/phrasebook_user.json word_ru.json known_words.json models/wordlist.json models/openrouter.json"
say() { printf '%s\n' "$*"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }   # не из stdin: внутри «while read» adb съел бы его
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i l; for i in $(seq "$3"); do l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
launch() { $ADB shell "am start -n $ACT $*" >/dev/null 2>&1; }
focus() { sh "dumpsys window" | grep -m1 mCurrentFocus; }
asleep() { sh "dumpsys power" | grep -qE 'mWakefulness=(Asleep|Dozing)'; }
# Телефон свободен для запуска Falar: экран погашен — или впереди Falar либо рабочий стол, и экрана
# не касались минуту.
idle() {
  asleep && return 0
  case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) ;; *) return 1;; esac
  local a; a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
  [ "${a:-0}" -ge 60000 ]
}
wait_idle() { local n=0; until idle; do n=$((n+1)); [ $n -eq 1 ] && say "  жду, пока телефон свободен ($(focus))"; sleep 15; done; }
field() { sed -n "s/.*$1 \\([0-9a-z]*\\).*/\\1/p"; }

# ---- замок ----------------------------------------------------------------------------------------
exec 9>"$STATE/lock"
flock -n 9 || { say "на телефоне уже идёт проверка (замок $STATE/lock) — вторую не начинаю"; exit 1; }
export FALAR_STAND_LOCK=1

# ---- страховочный снимок и возврат ------------------------------------------------------------------
snap() {
  local d f n m
  d="$STATE/snap-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$d"
  for f in $FILES; do
    n=$(sh "stat -c %s $F/$f 2>/dev/null")
    if [ -n "$n" ]; then
      $ADB pull "$F/$f" "$d/" >/dev/null 2>&1
      [ "$(stat -c %s "$d/$(basename $f)" 2>/dev/null)" = "$n" ] || { say "снимок $f не сошёлся — прогон не начинаю"; rm -rf "$d"; return 1; }
      echo "$f есть" >> "$d/files"
    else echo "$f нет" >> "$d/files"; fi
  done
  sh "ls $F/chats/" > "$d/chats"
  wait_idle
  m=$(mark); launch --es settings show; wl "$m" '🧪 настройки:' 20 > "$d/settings" || { say "у сборки нет стенда --es settings — прогон не начинаю"; rm -rf "$d"; return 1; }
  m=$(mark); launch --es modules show; wl "$m" '🧩 модули сейчас' 30 | grep -oE '\[[a-z,]*\]' | tr -d '[]' > "$d/modules"
  # «Что нового»: снимок состояния, и окно, ждущее показа, — спрятать до возврата (у сборок без стенда пусто).
  m=$(mark); launch --es whatsnew state; wl "$m" '🧪 что нового:' 15 | sed -n 's/.*снимок //p' > "$d/whatsnew"
  if [ -s "$d/whatsnew" ]; then local w; w=$(cat "$d/whatsnew"); launch "--es whatsnew 'put:${w%|*|*}|0|${w##*|}'"; fi
  echo "$d" > "$STATE/pending"
  say "  снимок: $(basename "$d") · $(cut -c10- "$d/settings") · модули [$(cat "$d/modules")]"
}
restore() {
  local d f st b c j now nowmods ref cloud rg qual l lst mods m
  d=$(cat "$STATE/pending" 2>/dev/null); [ -n "$d" ] && [ -d "$d" ] || return 0
  say "== возврат по снимку $(basename "$d")"
  $ADB shell "am force-stop $PKG"; sleep 1
  local lines line; mapfile -t lines 2>/dev/null < "$d/files"
  for line in "${lines[@]}"; do read -r f st <<< "$line"
    b=$(basename $f)
    if [ "$st" = есть ]; then
      [ "$(sh "md5sum $F/$f 2>/dev/null" | cut -d' ' -f1)" = "$(md5sum "$d/$b" | cut -d' ' -f1)" ] || { $ADB push "$d/$b" "$F/$f" >/dev/null 2>&1 && say "  вернул $f"; }
    else sh "ls $F/$f 2>/dev/null" | grep -q . && { $ADB shell "rm -f $F/$f"; say "  убрал $f — до прогона его не было"; }; fi
  done
  for c in $(sh "ls $F/chats/"); do
    grep -qxF "$c" "$d/chats" && continue
    j=$(sh "cat $F/chats/$c" | python3 -c "import json,sys; o=json.load(sys.stdin); print(o.get('name','') or '-', len(o.get('turns',[])))" 2>/dev/null)
    case "$j" in "ТЕСТ "*|*" 0") $ADB shell "rm -f $F/chats/$c"; say "  убран разговор $c ($j)";;
      *) say "  разговор $c появился за прогон и не тестовый — оставлен ($j)";; esac
  done
  $ADB shell "rm -f /data/local/tmp/falar_px.sh /data/local/tmp/falar_sw /sdcard/falar-ui.xml $F/replay.wav $F/modtest.jpg $F/ui-*.png"
  ref=$(field разбор < "$d/settings"); cloud=$(sed -n 's/.*облако \([0-9][0-9]*\).*/\1/p' "$d/settings"); rg=$(sed -n 's/.*чтение вслух \([0-9]\).*/\1/p' "$d/settings")
  qual=$(sed -n 's/.*облако точнее \([01]\).*/\1/p' "$d/settings"); l=$(sed -n 's/.*слушаю \([a-z]*\) ·.*/\1/p' "$d/settings")
  lst=$(case "$l" in ptru) echo both;; pt|ru) echo $l;; *) echo off;; esac); mods=$(cat "$d/modules")
  wait_idle
  m=$(mark); launch --es settings show; now=$(wl "$m" '🧪 настройки:' 20 | cut -c10-)
  m=$(mark); launch --es modules show; nowmods=$(wl "$m" '🧩 модули сейчас' 30 | grep -oE '\[[a-z,]*\]' | tr -d '[]')
  if [ "$now" != "$(cut -c10- "$d/settings")" ] || [ "$nowmods" != "$mods" ]; then
    launch --es listen $lst --es refineevery $ref --es cloudevery $cloud --es readguard $rg --es cloudprefer $([ "$qual" = 1 ] && echo quality || echo fast) \
      $([ "$nowmods" != "$mods" ] && [ -n "$mods" ] && echo "--es modules '$mods'"); sleep 4
    m=$(mark); launch --es settings show; now=$(wl "$m" '🧪 настройки:' 20 | cut -c10-)
    [ "$now" = "$(cut -c10- "$d/settings")" ] && say "  настройки и модули — как были: $now" || say "  ВНИМАНИЕ: настройки после возврата «$now», а было «$(cut -c10- "$d/settings")»"
  fi
  if [ -s "$d/whatsnew" ]; then m=$(mark); launch "--es whatsnew 'put:$(cat "$d/whatsnew")'"; wl "$m" '🧪 что нового:' 15 >/dev/null && say "  «Что нового» — как было: $(cat "$d/whatsnew")"; fi
  $ADB shell "am force-stop $PKG"; sleep 1
  asleep || launch
  rm -f "$STATE/pending"
  ls -d "$STATE"/snap-* 2>/dev/null | sort | head -n -3 | xargs -r rm -rf       # хранить три последних
}

# ---- прогон ---------------------------------------------------------------------------------------
$ADB wait-for-device
if [ -s "$STATE/pending" ]; then say "== прошлый прогон не вернул телефон — сначала возврат"; restore; fi
[ "${1:-}" = --restore ] && exit 0

if [ "${BUILD:-}" = 1 ]; then
  say "== сборка и тесты на столе"
  JAVA_TOOL_OPTIONS=-XX:-DoEscapeAnalysis bash "$A/build.sh" 2>&1 | grep -E '^APK|^sha256|error' || exit 1
  rm -f "$R"/hs_err_pid*.log "$R"/replay_pid*.log
  JAVA_TOOL_OPTIONS=-XX:-DoEscapeAnalysis bash "$A/test.sh" 2>&1 | grep -E 'проверок|провал|ПРОВАЛ'; [ "${PIPESTATUS[0]}" = 0 ] || { say "тесты на столе не прошли — на телефон не иду"; exit 1; }
  wait_idle
  was=$(focus); res=$($ADB install --no-incremental -r "$A/Falar.apk" 2>&1 | grep -E '^(Success|Failure)')
  say "  установка: ${res:-нет ответа} · $(sh "dumpsys package $PKG" | grep -m1 lastUpdateTime | tr -s ' ')"
  [ "$res" = Success ] || exit 1
  case "$was" in *$PKG*) launch;; esac
fi

L="$STATE/runs/$(date +%Y%m%d-%H%M%S)"; mkdir -p "$L"
ls -d "$STATE"/runs/* 2>/dev/null | sort | head -n -5 | xargs -r rm -rf        # хранить пять последних
T0=$(sh "date '+%m-%d %H:%M:%S.000'")
say "== снимок перед прогоном"
snap || exit 1
trap 'restore' EXIT
trap 'say "прервано"; exit 130' INT TERM
[ $# -gt 0 ] && SUITES="$*"
: > "$L/summary"
for s in $SUITES; do
  case "$s" in
    scroll-day) env=THEME=day; t=scroll;; scroll-night) env=THEME=night; t=scroll;;
    mtup) env=; t=mt_upgrade;; *) env=; t=$s;;
  esac
  [ -f "$A/test_${t}_device.sh" ] || { say "нет такого: test_${t}_device.sh"; echo "$s - - - нет_скрипта" >> "$L/summary"; continue; }
  t1=$(date +%s); say "== $s ($(date +%T))"
  timeout 2700 env $env bash "$A/test_${t}_device.sh" > "$L/$s.log" 2>&1
  r=$(grep -a 'итог:' "$L/$s.log" | tail -1)
  p=$(printf '%s' "$r" | sed -n 's/.*PASS \([0-9]*\).*/\1/p'); f=$(printf '%s' "$r" | sed -n 's/.*FAIL \([0-9]*\).*/\1/p'); k=$(printf '%s' "$r" | sed -n 's/.*пропущено \([0-9]*\).*/\1/p')
  echo "$s ${p:--} ${f:--} ${k:-0} $(( $(date +%s) - t1 ))" >> "$L/summary"
  say "   ${r:-итога нет — смотрите $L/$s.log}"; grep -a '^FAIL' "$L/$s.log" | sed 's/^/   /' | head -5
done
crash=$($ADB shell "logcat -d -v time -t '$T0'" 2>/dev/null | tr -d '\r' | grep -A3 'FATAL EXCEPTION' | grep -A1 'Process: app.falar' | grep -vE 'Process:|^--' | head -1 | cut -c1-160)
say; say "== итог ($L)"
printf '%-14s %5s %5s %8s %6s\n' скрипт PASS FAIL пропуск сек
awk '{printf "%-14s %5s %5s %8s %6s\n", $1, $2, $3, $4, $5}' "$L/summary"
say "Falar за прогон: ${crash:+упал — $crash}${crash:-не падал}"
bad=$(awk '$3 != 0 || $2 == "-" {print $1}' "$L/summary" | paste -sd' ' -)
[ -z "$bad" ] && [ -z "$crash" ] && say "ВСЁ ПРОШЛО" || say "НЕ ПРОШЛО: ${bad:-—}${crash:+ · падение Falar}"
[ -z "$bad" ] && [ -z "$crash" ]
