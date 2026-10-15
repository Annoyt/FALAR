#!/bin/bash
# Надписи по-португальски на телефоне (L, UiLang; владелец 04.10). Стенд --es uilang выбирает язык так же,
# как «Настройки» → «Язык» (с Android 13 — через LocaleManager, общий с настройками Android):
#   N1 «pt» — системный «язык приложения» становится pt-BR;
#   N2 снимки всех экранов по-португальски (uishot) и их подписи (<имя>.txt): португальские есть, русских
#      надписей нет; снимки смотрит человек — не обрезано ли, не налезает ли;
#   N3 «как в системе» — снова язык телефона (на русском телефоне — русские надписи), снимки для сравнения;
#   N4 выбор после прогона — тот же, что был до него (и при обрыве: cmd locale set-app-locales, без запуска).
#   bash bench/apk/test_lang_device.sh             # INSTALL=1 — сначала поставить bench/apk/Falar.apk (build.sh)
# Снимки — /tmp/falar-lang-shots/{pt,ru}-<экран>.png (на них разговоры владельца: посмотреть и удалить). Телефон —
# рабочий аппарат владельца: начинаем, когда
# экран погашен или впереди Falar либо рабочий стол и экрана не касались FREE_S секунд (60).
R=$(cd "$(dirname "$0")/../.." && pwd); A=$R/bench/apk
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand"; exec 9>"${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
FREE_S=${FREE_S:-60}; SHOTS=/tmp/falar-lang-shots; mkdir -p $SHOTS
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
focus() { sh "dumpsys window | grep -m1 mCurrentFocus"; }
# Свободен: экран погашен — или впереди Falar либо рабочий стол.
idle() {
  sh "dumpsys power" | grep -qE "mWakefulness=(Asleep|Dozing)" && return 0
  case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) return 0;; esac; return 1
}
launch() {
  local n=0
  until idle; do n=$((n+1)); [ $n -eq 1 ] && say "  (запуск ждёт, пока телефон освободится: $(focus))"; sleep 5; done
  $ADB shell "am start -n $ACT $*" >/dev/null 2>&1
}
free_phone() {
  local n=0 f a
  [ -x "${ADB%% *}" ] || { say "нет adb: ${ADB%% *}"; exit 2; }
  [ -n "$(sh 'echo ok')" ] || { say "телефон не подключён"; exit 2; }
  while :; do
    sh "dumpsys power" | grep -qE "mWakefulness=(Asleep|Dozing)" && return 0
    f=$(focus); a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
    case "$f" in *$PKG*|*com.miui.home*|*launcher*) [ "${a:-0}" -ge $((FREE_S * 1000)) ] && return 0;; esac
    n=$((n+1)); [ $n -eq 1 ] && say "  жду: экран погашен или впереди Falar/рабочий стол, ${FREE_S} с без касаний ($f)"; sleep 10
  done
}
# Выбор языка приложения в системе (Android 13+): «[pt-BR]», «[ru]» или «[]» — как в системе.
applang() { sh "cmd locale get-app-locales $PKG" | sed -n 's/.*are \[\(.*\)\].*/\1/p'; }
setlang() {   # setlang pt|ru|system — ждёт, пока системный выбор станет таким; экран пересоздаётся
  local want i; case "$1" in pt) want=pt-BR;; ru) want=ru;; *) want="";; esac
  launch --es uilang "$1"
  for i in $(seq 15); do [ "$(applang)" = "$want" ] && { sleep 4; return 0; }; sleep 1; done
  return 1
}
shot() {      # shot <префикс> <экран> — снимок и подписи экрана (<имя>.txt), см. MainActivity.uiShot
  local f=$F/lang-$1-$2 n0=-1 n1 i
  sh "rm -f $f.png $f.txt"; launch --es uishot lang-$1-$2 --es uiscreen "$2"
  for i in $(seq 20); do sleep 1; n1=$(sh "stat -c %s $f.txt 2>/dev/null"); [ -n "$n1" ] && [ "$n1" = "$n0" ] && break; n0=${n1:--1}; done
  [ -n "$n1" ] || return 1
  $ADB pull "$f.png" "$SHOTS/$1-$2.png" >/dev/null 2>&1; $ADB pull "$f.txt" "$SHOTS/$1-$2.txt" >/dev/null 2>&1; sh "rm -f $f.png $f.txt"
}
has() { grep -qxF -- "$2" "$SHOTS/$1.txt" 2>/dev/null || grep -qF -- "$2" "$SHOTS/$1.txt" 2>/dev/null; }

free_phone
PREV=$(applang); say "== язык приложения до прогона: [${PREV}]"
# Возврат выбора — и при обрыве прогона: системной командой, без вывода Falar на экран (телефон могут взять в руки).
back() { [ "$(applang)" = "$PREV" ] || sh "cmd locale set-app-locales $PKG --locales '${PREV}'"; }
trap back EXIT
if [ "$INSTALL" = 1 ]; then
  apk=${APK:-$A/Falar.apk}
  want=$(aapt2 dump badging "$apk" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
  have=$(sh "dumpsys package $PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  [ -n "$have" ] && [ -n "$want" ] && [ "$want" -lt "$have" ] && { say "на телефоне сборка $have, эта — $want: понижение стёрло бы данные, не ставлю"; exit 1; }
  say "== ставлю $apk ($want поверх ${have:-нет})"
  r=$($ADB install --no-incremental -r "$apk" 2>&1 | tail -1); say "  $r"
  case "$r" in Success*) ;; *) say "не поставилось — дальше нет смысла"; exit 1;; esac
fi
launch; sleep 6

say "== N1 португальский"
setlang pt && res 0 "N1 системный язык приложения — pt-BR" || res 1 "N1 системный язык приложения — pt-BR ($(applang))"

say "== N2 снимки и подписи по-португальски"
# Разговор — последним: после установки и смены языка модели ещё грузятся, и на месте кнопок реплики идёт ход загрузки.
for s in drawer words settings mods cloud setup talk; do shot pt $s && res 0 "N2 снимок $s" || res 1 "N2 снимок $s"; done
for w in "talk:Ouvir" "talk:Foto, texto" "talk:Melhorar" "drawer:Configurações" "settings:Idioma da interface" "settings:Do sistema" \
         "settings:Tela da conversa" "words:Palavras" "mods:Módulos" "cloud:Chaves"; do
  has "pt-${w%%:*}" "${w#*:}" && res 0 "N2 ${w%%:*}: «${w#*:}»" || res 1 "N2 ${w%%:*}: «${w#*:}»"
done
for w in "talk:Слушать" "talk:Улучшить" "settings:Экран разговора" "drawer:Настройки"; do
  has "pt-${w%%:*}" "${w#*:}" && res 1 "N2 ${w%%:*}: русского «${w#*:}» нет" || res 0 "N2 ${w%%:*}: русского «${w#*:}» нет"
done
# Русские строки, оставшиеся на португальских экранах, — файлом для глаз, не в вывод: среди них разговоры
# владельца (названия, реплики), им не место в журналах прогонов. Надписи там — только нарочно русские.
cat $SHOTS/pt-{talk,drawer,words,settings,mods,cloud,setup}.txt 2>/dev/null | grep -E '[А-Яа-яЁё]' | sort | uniq -c | sort -rn > $SHOTS/pt-cyrillic.txt
say "  русских строк на экранах: $(wc -l < $SHOTS/pt-cyrillic.txt) — $SHOTS/pt-cyrillic.txt (там и разговоры владельца — посмотреть и удалить)"

say "== N3 как в системе ($(sh 'getprop persist.sys.locale'))"
setlang system && res 0 "N3 системный выбор снят" || res 1 "N3 системный выбор снят ($(applang))"
for s in talk settings words; do shot ru $s || res 1 "N3 снимок $s"; done
case "$(sh 'getprop persist.sys.locale')" in
  pt*) has ru-talk "Ouvir" && res 0 "N3 на португальском телефоне — «Ouvir»" || res 1 "N3 на португальском телефоне — «Ouvir»";;
  *) has ru-talk "Слушать" && res 0 "N3 телефон не на португальском — «Слушать»" || res 1 "N3 телефон не на португальском — «Слушать»";;
esac

say "== N4 возврат выбора"
back; sleep 2
[ "$(applang)" = "$PREV" ] && res 0 "N4 язык приложения как до прогона: [$PREV]" || res 1 "N4 язык приложения как до прогона: [$PREV] ≠ [$(applang)]"
say "Итого: $pass PASS, $fail FAIL · снимки: $SHOTS"
[ "$fail" = 0 ]
