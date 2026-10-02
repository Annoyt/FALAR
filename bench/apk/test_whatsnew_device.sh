#!/bin/bash
# «Что нового» после обновления — на телефоне.
#
#   bash bench/apk/test_whatsnew_device.sh
#
# Обновление изображается стендом: --es whatsnew ran:<код> записывает, будто прошлый запуск был той
# версией, и пересоздаёт экран — дальше работает настоящая проверка при запуске (checkNews):
#   N1 обновление с 0.24.0 (28): окно «Что нового в Falar <версия>», «Вы обновились с версии 0.24.0.»,
#      сначала «Новое», потом «Исправлено», пункты всех версий новее 28 — текст целиком, как его
#      собирает whatsnew.txt из рабочей копии;
#   N2 та же сводка — одной записью в журнале;
#   N3 пересозданный экран (стенд --es uitheme system) показывает окно снова, в журнал второй раз не пишет;
#   N4 «Понятно» закрывает окно, и пересозданный экран его больше сам не показывает;
#   N5 «Настройки» → «Что нового в <версия> ›» открывает то же окно;
#   N6 обновление с предыдущей версии: пункты одной версии; «назад» — тоже «видели»;
#   N7 записи нет (как у всех до 0.26.1 включительно): на сборке 32 окна нет, на более новой — её пункты
#      без строки «Вы обновились с версии…»;
#   N8 Falar не падал (logcat).
# Состояние «Что нового» до проверки возвращается стендом --es whatsnew put:<снимок>. Разговоры и
# настройки не трогаются, наружу ничего не уходит. Телефон — рабочий аппарат владельца: начинаем,
# когда экран включён, впереди Falar или рабочий стол и экрана не касались FREE_S секунд (60);
# касаемся, только пока впереди Falar.
R=$(cd "$(dirname "$0")/../.." && pwd); A=$R/bench/apk
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand"; exec 9>"${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
FREE_S=${FREE_S:-60}; NEWS=$A/whatsnew.txt
D=$(mktemp -d /tmp/falar-news.XXXX)
pass=0; fail=0; SNAPTOK=
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
chk() { if eval "$1"; then res 0 "$2"; else res 1 "$2"; fi; }      # chk 'условие' "что проверяем"
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }   # не из stdin: внутри «while read» adb съел бы его
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
since() { sh "tail -n +$(($1+1)) $LOG"; }
launch() { $ADB shell "am start -n $ACT $*" >/dev/null 2>&1; }
focus() { sh "dumpsys window | grep -m1 mCurrentFocus"; }
front() { sh "dumpsys power" | grep -q "mWakefulness=Awake" && focus | grep -q "$PKG/"; }
start() { front || { say "  (впереди не Falar — запуск пропущен: $(focus))"; return 1; }; launch "$@"; }
free_phone() {
  local n=0 f a q now mt
  [ -x "${ADB%% *}" ] || { say "нет adb: ${ADB%% *}"; exit 2; }
  while :; do
    f=$(focus); a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
    now=$(sh "date +%s"); mt=$(sh "stat -c %Y $LOG"); q=$(( ${now:-0} - ${mt:-0} ))
    if sh "dumpsys power" | grep -q "mWakefulness=Awake"; then
      case "$f" in *$PKG*|*com.miui.home*|*launcher*) [ "${a:-0}" -ge $((FREE_S * 1000)) ] && [ "$q" -ge "$FREE_S" ] && return 0;; esac
    fi
    n=$((n+1)); [ $n -eq 1 ] && say "  жду: экран включён, впереди Falar или рабочий стол, ${FREE_S} с без касаний и записей в журнал ($f)"; sleep 10
  done
}
dump() { sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" > $D/ui.xml; }
ui() { local c=$1; shift; python3 $A/ui.py "$c" $D/ui.xml "$@"; }
has() { ui find "$@" >/dev/null; }
xy() { ui find "$@" | head -1 | awk '{print $5, $6}'; }
tapxy() { front || { say "  (впереди не Falar — касание пропущено: $(focus))"; return 1; }; $ADB shell "input tap $1 $2"; sleep "${3:-1.2}"; }
tapon() { local p; dump; p=$(xy "$@"); [ -n "$p" ] || { say "  (на экране нет: $*)"; return 1; }; tapxy $p; }
back() { front && $ADB shell "input keyevent 4"; sleep 1.2; }
# Строка состояния стенда; сразу после пересоздания экрана служба может быть ещё не подключена, и
# строка уходит мимо at.log — тогда ещё раз.
state() { local m i; for i in 1 2 3; do m=$(mark); start --es whatsnew state || return 1; wl "$m" '🧪 что нового:' 8 && return 0; done; return 1; }
recreate() { local m; m=$(mark); start --es uitheme system || return 1; wl "$m" '🧪 тема:' 10 >/dev/null; sleep 3; }
# Окно на экране: первая строка — заголовок, дальше — текст окна (переводы строк — пробелами: так их
# отдаёт и uiautomator, и журнал).
cat > $D/dlg.py <<'PY'
import sys, xml.etree.ElementTree as ET
try: ns = list(ET.parse(sys.argv[1]).getroot().iter('node'))
except Exception: ns = []
title = next((n.get('text', '') for n in ns if n.get('resource-id') == 'android:id/alertTitle'), '')
body = max((n.get('text', '') for n in ns if n.get('class') == 'android.widget.TextView'
            and any(h in n.get('text', '') for h in ('Новое', 'Исправлено'))), key=len, default='')
print(title); print(' '.join(body.split()))
PY
# Что должно быть по whatsnew.txt — тем же правилом, что WhatsNew.notes: версии from < code ≤ to и
# «следующая», сначала новое, потом исправления; номеров версий у пунктов нет.
cat > $D/exp.py <<'PY'
import re, sys
path, frm, to, to_name, from_name, mode = sys.argv[1:7]
frm, to, NEXT = int(frm), int(to), 10 ** 9
E = []
for raw in open(path, encoding='utf-8'):
    s = raw.strip()
    if not s or s.startswith('#'): continue
    if s.startswith('=='):
        m = re.fullmatch(r'== (\d+\.\d+\.\d+) \((\d+)\)', s)
        E.append(['', NEXT, [], []] if s == '== следующая' else [m.group(1), int(m.group(2)), [], []]); continue
    (E[-1][2] if s[0] == '+' else E[-1][3]).append(s[2:].strip())
if mode == 'prev':      # версия перед to — для «обновились с предыдущей»
    c = max((e for e in E if e[1] < to), key=lambda e: e[1]); print(c[1], c[0]); sys.exit()
add, fix = [], []
for name, code, a, f in (E if frm < to else []):
    if code != NEXT and (code <= frm or code > to): continue
    add += a; fix += f
if mode == 'text':
    out = ('Вы обновились с версии ' + from_name + '.\n') if from_name else ''
    for head, items in (('Новое', add), ('Исправлено', fix)):
        if not items: continue
        out += ('\n' if out else '') + head + '\n' + ''.join(t + '\n' for t in items)
    print(' '.join(out.split()))
else:                   # plain — запись журнала
    b = '🆕 Обновлено ' + (from_name + ' → ' + to_name if from_name else 'до ' + to_name)
    for head, items in (('Новое', add), ('Исправлено', fix)):
        if items: b += '\n' + head + ':' + ''.join('\n• ' + t for t in items)
    print(' '.join(b.split()))
PY
exp() { python3 $D/exp.py "$NEWS" "$@"; }
# Сверить окно с ожидаемым; при расхождении — где именно.
dlg_is() {   # dlg_is <from> <имя from или ""> "что проверяем"
  local got title body want
  dump; got=$(python3 $D/dlg.py $D/ui.xml); title=$(printf '%s\n' "$got" | sed -n 1p); body=$(printf '%s\n' "$got" | sed -n 2p)
  want=$(exp "$1" "$CUR" "$NAME" "$2" text)
  chk '[ "$title" = "Что нового в Falar $NAME" ]' "$3: заголовок «$title»"
  if [ "$body" = "$want" ]; then res 0 "$3: текст окна — по whatsnew.txt, ${#want} знаков"
  else res 1 "$3: текст окна"; say "    ждал: ${want:0:300}"; say "    было: ${body:0:300}"; fi
}
no_dlg() { dump; ! has --rid android:id/alertTitle; }

restore() {
  say "== возврат"
  if [ -n "$SNAPTOK" ]; then
    local m; m=$(mark)
    if front || sh "dumpsys power" | grep -qE "mWakefulness=(Asleep|Dozing)"; then launch "--es whatsnew 'put:$SNAPTOK'"
      say "  состояние «Что нового» — как было: $(wl "$m" '🧪 что нового:' 15 | sed 's/.*снимок //')"
    else say "  ВНИМАНИЕ: впереди не Falar — состояние не вернул; вернуть: am start -n $ACT --es whatsnew 'put:$SNAPTOK'"; fi
  fi
  rm -rf "$D"; say; say "итог: PASS $pass, FAIL $fail"
}
trap restore EXIT

$ADB wait-for-device; free_phone
T0=$(sh "date '+%m-%d %H:%M:%S.000'")
CUR=$(sh "dumpsys package $PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
NAME=$(sh "dumpsys package $PKG" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)
LOCAL=$(grep -o 'versionCode="[0-9]*"' $A/AndroidManifest.xml | cut -d'"' -f2)
[ "$CUR" = "$LOCAL" ] || { say "на телефоне сборка $CUR, в рабочей копии $LOCAL — окно сверять не с чем"; exit 1; }
front || { launch; sleep 4; }
front || { say "впереди не Falar — проверять нечем ($(focus))"; exit 1; }
ST=$(state) || { say "у сборки нет стенда --es whatsnew — нужна сборка с «Что нового»"; exit 1; }
SNAPTOK=$(printf '%s' "$ST" | sed 's/.*снимок //'); say "  было: ${ST#*что нового: }"
no_dlg || { tapon --text Понятно; }     # окно владельца, если висело: снимок его вернёт

say "== N1 обновление с 0.24.0"
m=$(mark); start --es whatsnew ran:28; wl "$m" '🆕 Обновлено' 20 > $D/j1; sleep 1
dlg_is 28 0.24.0 "N1 обновились с 0.24.0"
say "== N2 журнал"
chk '[ "$(cut -c10- $D/j1 | tr -s " ")" = "$(exp 28 $CUR $NAME 0.24.0 plain)" ]' "N2 та же сводка — одной записью в журнале"
say "== N3 пересозданный экран"
recreate; dlg_is 28 0.24.0 "N3 окно после пересоздания экрана"
chk '[ "$(since $m | grep -c "🆕 Обновлено")" = 1 ]' "N3 в журнал — один раз"
say "== N4 «Понятно»"
tapon --text Понятно; chk no_dlg "N4 «Понятно» закрывает окно"
chk 'state | grep -q "ждёт 0"' "N4 больше не ждёт показа"
recreate; chk no_dlg "N4 пересозданный экран окна сам не показывает"
say "== N5 из «Настроек»"
tapon --desc "Разговоры, слова, настройки" && tapon --text Настройки && tapon --text "Что нового в $NAME ›"
dlg_is 28 0.24.0 "N5 «Что нового в $NAME ›» в «Настройках»"
tapon --text Понятно; back; dump; chk 'has --desc "Разговоры, слова, настройки"' "N5 назад — на экране разговора"
say "== N6 с предыдущей версии"
read -r PREV PNAME <<< "$(exp 0 $CUR $NAME '' prev)"
m=$(mark); start --es whatsnew ran:$PREV; wl "$m" '🆕 Обновлено' 20 >/dev/null; sleep 1
dlg_is "$PREV" "$PNAME" "N6 обновились с $PNAME — одна версия"
back; chk no_dlg "N6 «назад» закрывает окно"; chk 'state | grep -q "ждёт 0"' "N6 «назад» — тоже «видели»"
say "== N7 записи нет"
m=$(mark); start --es whatsnew forget; sleep 5
if [ "$CUR" -le 32 ]; then
  chk no_dlg "N7 записи нет, сборка $CUR — не новее 0.26.1: окна нет"
  chk 'state | grep -q "с 32 · окно 0 · ждёт 0"' "N7 «с 32»: пришли с версии без окна"
else
  dlg_is 32 "" "N7 записи нет — пункты новее 0.26.1, без строки «Вы обновились…»"; tapon --text Понятно
fi
say "== N8 падения"
crash=$($ADB shell "logcat -d -v time -t '$T0'" 2>/dev/null | tr -d '\r' | grep -A3 'FATAL EXCEPTION' | grep -A1 "Process: $PKG" | grep -vE 'Process:|^--' | head -1)
chk '[ -z "$crash" ]' "N8 Falar не падал${crash:+: $crash}"
