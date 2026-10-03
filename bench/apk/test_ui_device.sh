#!/bin/bash
# Новый интерфейс 0.26.0 — настоящими касаниями, в отдельном тестовом разговоре.
#
#   bash bench/apk/test_ui_device.sh          # KEEP=1 — оставить снимки экранов в /tmp/falar-ui-shots
#
# До этого скрипта новый экран видели только глазами на снимках uishot. Здесь касания идут по дереву
# экрана (uiautomator, bench/apk/ui.py), цвета — по снимкам экрана:
#   U0 загрузка движков — ход в реплике: «Загружаю модели · … · ещё ≈ N с», этапы по порядку, оценка
#      не растёт, отрезки по этапу; после загрузки на месте хода — кнопки реплики;
#   U1 экран разговора: шапка, кто сказал, крупно и мелко, «ранее», карточки собеседника слева и ваши
#      справа, кнопки реплики, док;
#   U2 панель ☰ поверх экрана: разговоры, «Слова», «Настройки»; закрывают «назад» и касание мимо;
#   U3 «Слова»: сегменты «Слова · N / Фразы · K / Знаю · M», порог −/+ (у фраз свой), карточка
#      «Повторение» — «Начать» и «Закрыть», «⋯», своё слово;
#   U4 «Настройки»: группы; сегменты (и «Перевод фраз: быстрее / точнее») показывают применённое и меняют
#      службу; «Журнал»; «Модули и файлы»; «Облако» — ключи, «Добавить ключ», убрать запасной ключ,
#      строка «Облако» в настройках;
#   U5 «Снимок, текст»: меню, набрать фразу и перевести; долгое касание — снимок в облако; галерея;
#   U6 «Слушать» половинками PT | RU — одна, обе, ни одной: служба, строка в шапке, мятные половинки;
#   U7 кнопки реплики: «Запомнить»; «⋯» — меню по роли реплики и удаление; карточка — меню; «Улучшить»
#      горит так, как решает служба;
#   U8 «＋» — новый разговор; возврат к прежнему из панели;
#   U9 дневная и ночная тема (стенд --es uitheme): шапка, фон, текст — и снимки всех экранов uishot;
#      после пересоздания экрана строка состояния в настройках — от службы, а не «Запуск сервиса…».
#   U10 обратная связь: «⋯» реплики → «Сообщить о переводе» и «Настройки» → «Написать разработчику» —
#      кнопки Telegram и GitHub, готовые ссылки (шаблон GitHub, метка топика для бота). Ссылки не
#      открываются: стенд --es linkdry 1 пишет их в журнал — Telegram на телефоне владельца.
#   U11 «Что озвучивать»: сегмент «Авто · RU · PT · Оба» в настройках показывает выбор службы и меняет
#      его; при «PT» русский перевод не звучит (строка реплики «без озвучки»), при «RU» — звучит.
#   U12 системные полосы (targetSdk 35: окно лежит под ними, Bars.java) — в обеих темах: шапка ниже
#      строки состояния, а под строкой — слива шапки; кнопки дока, низ панели ☰ и поле «Своё слово»
#      выше полосы навигации, поле и «+» — над клавиатурой; под навигацией — фон экрана; служба с
#      микрофоном видимая (startForeground); окно снимка — между полосами; у экрана первого запуска
#      (снимок uishot setup) под строкой состояния — её цвет.
#
# Разговоры владельца не трогаются: тестовый разговор — файлом с самым свежим временем; выученное,
# словари, свои слова, пины и ключи облака возвращаются из снимка; разговоры, появившиеся за проверку,
# удаляются; настройки (частота разбора и облака, чтение вслух, слушание) — как были. Ключ облака в
# поле не вводится: «Сохранить» отправил бы его OpenRouter на проверку. Запасной ключ для «убрать» —
# заведомо поддельный, кладётся файлом и пропадает вместе с возвратом файла. Пересмотр в облаке на
# время проверки выключен — наружу ничего не уходит. Камера не открывается; «Начать» в «Повторении»
# включает на слух одно слово или кусочек живой фразы, ответа скрипт не даёт: сроки повторения — данные
# владельца. Нужна сборка со стендами --es settings и --es uitheme.
# Телефон — рабочий аппарат владельца: начинаем, когда экран включён, впереди Falar или рабочий стол,
# экрана не касались FREE_S секунд (60) и журнал Falar столько же молчит; касаемся, только пока
# впереди Falar.
R=$(cd "$(dirname "$0")/../.." && pwd); A=$R/bench/apk
# Один прогон на телефоне за раз — и отдельный скрипт, и test_all_device.sh (01.10 две копии test_ui
# девять минут касались телефона одновременно).
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand"; exec 9>"${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
FREE_S=${FREE_S:-60}; SHOTS=/tmp/falar-ui-shots
D=$(mktemp -d /tmp/falar-ui.XXXX); SNAP=$D/snap; mkdir -p $SNAP; TID=$(date +%s%3N)
FAKE="sk-or-v1-test$(printf '%056d' 0)abcd"; FAKEID="sk-or-v1-test…abcd"   # собирается здесь: строку вида ключа в исходнике не пропускает защита секретов GitHub
pass=0; fail=0; skip=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
chk() { if eval "$1"; then res 0 "$2"; else res 1 "$2"; fi; }      # chk 'условие' "что проверяем"
sk() { skip=$((skip+1)); say "ПРОПУСК $1"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }   # не из stdin: внутри «while read» adb съел бы его
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
since() { sh "tail -n +$(($1+1)) $LOG"; }
# Каждый запуск — на свободном телефоне (idle). После force-stop впереди бывает задача, лежавшая под Falar:
# её вывел не человек, если экрана не касались с проверки free_phone (UA0), — тогда Falar возвращается на место.
launch() {
  local n=0
  until idle || { [ -n "$UA0" ] && [ "$(ua)" = "$UA0" ]; }; do
    n=$((n+1)); [ $n -eq 1 ] && say "  (запуск ждёт, пока телефон освободится: $(focus))"; sleep 5
  done
  $ADB shell "am start -n $ACT $*" >/dev/null 2>&1
}
ua() { sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=\([0-9]*\) .*/\1/p' | head -1; }   # касание, мс от загрузки
# Посреди проверки — только поверх самого Falar: телефон могут взять в руки, и запуск вытащил бы
# Falar поверх чужого приложения.
start() { front || { say "  (впереди не Falar — запуск пропущен: $(focus))"; return 1; }; launch "$@"; }
# Телефон свободен для запуска Falar: экран погашен — или впереди Falar либо рабочий стол.
idle() {
  sh "dumpsys power" | grep -qE "mWakefulness=(Asleep|Dozing)" && return 0
  case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) return 0;; esac; return 1
}
# Впереди Falar — его экран или его всплывающее меню («⋯» шапки — отдельное окно PopupWindow).
front() {
  sh "dumpsys power" | grep -q "mWakefulness=Awake" || return 1
  local w; w=$(sh "dumpsys window" | grep -m2 -E 'mCurrentFocus|mFocusedApp')
  printf '%s\n' "$w" | grep mCurrentFocus | grep -q "$PKG/" && return 0
  printf '%s\n' "$w" | grep mCurrentFocus | grep -q PopupWindow && printf '%s\n' "$w" | grep mFocusedApp | grep -q "$PKG/"
}
focus() { sh "dumpsys window | grep -m1 mCurrentFocus"; }
free_phone() {
  local n=0 f a q
  [ -x "${ADB%% *}" ] || { say "нет adb: ${ADB%% *}"; exit 2; }
  while :; do
    f=$(focus); a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
    local now mt; now=$(sh "date +%s"); mt=$(sh "stat -c %Y $LOG"); q=$(( ${now:-0} - ${mt:-0} ))
    if sh "dumpsys power" | grep -q "mWakefulness=Awake"; then
      case "$f" in *$PKG*|*com.miui.home*|*launcher*) [ "${a:-0}" -ge $((FREE_S * 1000)) ] && [ "$q" -ge "$FREE_S" ] && { UA0=$(ua); return 0; };; esac
    fi
    n=$((n+1)); [ $n -eq 1 ] && say "  жду: экран включён, впереди Falar или рабочий стол, ${FREE_S} с без касаний и записей в журнал ($f)"; sleep 10
  done
}
# Дерево экрана и касания по нему.
dump() { sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" > $D/ui.xml; }
ui() { local c=$1; shift; python3 $A/ui.py "$c" $D/ui.xml "$@"; }
at() { ui find "$@" | head -1; }                 # «x0 y0 x1 y1 cx cy enabled selected»
has() { ui find "$@" >/dev/null; }
cnt() { ui find "$@" --all | grep -c .; }
xy() { at "$@" | awk '{print $5, $6}'; }
tapxy() { front || { say "  (впереди не Falar — касание пропущено: $(focus))"; return 1; }; $ADB shell "input tap $1 $2"; sleep "${3:-1.2}"; }
tapon() { local p; dump; p=$(xy "$@"); [ -n "$p" ] || { say "  (на экране нет: $*)"; return 1; }; tapxy $p; }
# «Назад» — только если открыто что-то поверх разговора: на самом экране разговора оно закрыло бы Falar.
press() {
  dump
  if has --desc "Разговоры, слова, настройки" && ! has --text Настройки \
     && ! grep -qE 'resource-id="android:id/(alertTitle|button[123]|select_dialog_listview|title)"' $D/ui.xml; then
    say "  (открыт сам разговор — «назад» не нажимаю)"; return 0
  fi
  front && $ADB shell "input keyevent 4"; sleep 1
}
title() { ui sub --rid hint | sed -n 1p; }
line2() { ui sub --rid hint | sed -n 2p; }
swipe_up() { front && $ADB shell "input swipe $((W / 2)) $((H * 7 / 10)) $((W / 2)) $((H * 3 / 10)) 350"; sleep 1.2; }
swipe_down() { front && $ADB shell "input swipe $((W / 2)) $((H * 3 / 10)) $((W / 2)) $((H * 7 / 10)) 350"; sleep 1.2; }
# Листать, пока не появится: сначала вниз, потом вверх до самого начала.
seek() { local i; dump; has "$@" && return 0
  for i in 1 2 3 4 5; do swipe_up; dump; has "$@" && return 0; done
  for i in 1 2 3 4 5 6 7 8 9 10; do swipe_down; dump; has "$@" && return 0; done; return 1; }
ime() { sh "dumpsys input_method" | grep -q 'mInputShown=true'; }
# Системные полосы по dumpsys window, px экрана: «низ строки состояния, верх полосы навигации, верх
# клавиатуры» (0 — нет или не видна). Android 13 пишет ITYPE_STATUS_BAR, 14+ — statusBars.
cat > $D/bars.py <<'PY'
import re, sys
s = sys.stdin.read()
def src(names, vis=False):
    for m in re.finditer(r'InsetsSource [^\n]*?type=(\w+) frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\][^\n]*?visible=(true|false)', s):
        if m.group(1) in names and (not vis or m.group(6) == 'true'): return list(map(int, m.groups()[1:5]))
sb, nb, k = src(('ITYPE_STATUS_BAR', 'statusBars')), src(('ITYPE_NAVIGATION_BAR', 'navigationBars')), src(('ITYPE_IME', 'ime'), True)
print(sb[3] if sb else 0, nb[1] if nb else 0, k[1] if k and k[3] > k[1] else 0)
PY
sysbars() { sh "dumpsys window" | python3 $D/bars.py; }
low() { at "$@" | cut -d' ' -f4; }               # нижний край вида, px экрана
# Отрезки хода реплики (ProgressLine — первый ребёнок вида busy) по цвету правого края каждого:
# пройден — слива (ночью золото), текущий — мята, впереди — цвет рамки.
cat > $D/segs.py <<'PY'
import sys
v = list(map(int, sys.stdin.read().split()[1:]))
C = {'пройден': [(224, 184, 120), (128, 34, 68)], 'текущий': [(111, 224, 188)], 'впереди': [(53, 43, 61), (230, 223, 230)]}
d = lambda a, b: sum((x - y) ** 2 for x, y in zip(a, b))
print(*[min(C, key=lambda k: min(d(v[i * 3:i * 3 + 3], c) for c in C[k])) for i in range(len(v) // 3)])
PY
segsat() {   # segsat "x0 y0 x1 y1" n — отрезки полосы с этими границами
  local pts; pts=$(python3 -c "
x0,y0,x1,y1=map(int,'$1'.split()); d=(y1-y0)/8; n=$2; g=4*d; w=(x1-x0-g*(n-1))/n
print(*[v for i in range(n) for v in (round(x0+i*(w+g)+w-2*d), (y0+y1)//2)])")
  sh "sh /data/local/tmp/falar_px.sh 0 $pts" | python3 $D/segs.py
}
want() { python3 -c "k,n=$1,$2; print(' '.join(['пройден']*k+['текущий']+['впереди']*(n-k-1)))"; }
# Половинки «Слушать» и фон дока рядом (ListenButton 108 × 54 dp): мята — включена.
cat > $D/halves.py <<'PY'
import sys
v = list(map(int, sys.stdin.read().split()[1:]))
mint = lambda p: sum((x - y) ** 2 for x, y in zip(p, (111, 224, 188))) ** 0.5 < 40
print('PT' if mint(v[0:3]) else '-', 'RU' if mint(v[3:6]) else '-')
PY
halves() { sh "sh /data/local/tmp/falar_px.sh 0 $HP" | python3 $D/halves.py; }

restore() {
  say "== возврат"
  $ADB shell "am force-stop $PKG"; sleep 2              # тема стенда, слушание, молчаливый режим — вместе с процессом
  local lines line; mapfile -t lines 2>/dev/null < "$D/files"
  for line in "${lines[@]}"; do read -r f st <<< "$line"
    b=$(basename $f)
    if [ "$st" = есть ]; then [ -s "$SNAP/$b" ] && $ADB push "$SNAP/$b" "$F/$f" >/dev/null 2>&1 && say "  вернул $f"
    else sh "ls $F/$f" | grep -q . && $ADB shell "rm -f $F/$f" && say "  убрал $f — до проверки его не было"; fi
  done
  if [ -s $D/chats.before ]; then
    for c in $(sh "ls $F/chats/"); do grep -qxF "$c" $D/chats.before || { $ADB shell "rm -f $F/chats/$c"; say "  убран разговор $c"; }; done
  fi
  $ADB shell "rm -f /data/local/tmp/falar_px.sh /sdcard/falar-ui.xml"
  local n=0
  until idle; do n=$((n+1)); [ $n -eq 1 ] && say "  телефон занят ($(focus)) — настройки верну, когда освободится"; sleep 15; done
  if [ -n "$REF" ]; then
    launch --es listen ${LST:-off} --es refineevery $REF --es cloudevery $CLOUD --es readguard $RG --es voicewhat ${VW:-auto} \
      $([ -n "$MTQ" ] && echo "--es mtprefer $([ "$MTQ" = 1 ] && echo quality || echo speed)"); sleep 3
    $ADB shell "am force-stop $PKG"; sleep 1
    say "  настройки: разбор $REF, облако $CLOUD, чтение вслух $RG, слушание ${LST:-off}, озвучка ${VW:-auto}${MTQ:+, перевод точнее $MTQ}"
  fi
  say "  текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  idle && launch; rm -rf "$D"
  say; say "итог: PASS $pass, FAIL $fail, пропущено $skip"
}
# Падения Falar за проверку — по logcat с начала прогона (падения самого uiautomator не в счёт).
T0=$($ADB shell "date '+%m-%d %H:%M:%S.000'" 2>/dev/null | tr -d '\r')
crashed() { $ADB shell "logcat -d -v time -t '$T0'" 2>/dev/null | tr -d '\r' | grep -A3 'FATAL EXCEPTION' | grep -A1 'Process: app.falar' | grep -vE 'Process:|^--' | head -1 | cut -c1-160; }
trap restore EXIT

$ADB wait-for-device
read W H <<< "$(sh 'wm size' | tail -1 | grep -oE '[0-9]+x[0-9]+' | tr x ' ')"
DP=$(sh 'wm density' | tail -1 | grep -oE '[0-9]+$' | awk '{print $1 / 160}')
dp() { awk -v v="$1" -v d="$DP" 'BEGIN { printf "%d", v * d + 0.5 }'; }
free_phone
say "== снимок перед проверкой (экран ${W}×${H}, dp = $DP)"
sh "ls $F/chats/" > $D/chats.before
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json models/wordlist.json models/openrouter.json; do
  n=$(sh "stat -c %s $F/$f 2>/dev/null")
  if [ -n "$n" ]; then $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1
    [ "$(stat -c %s "$SNAP/$(basename $f)" 2>/dev/null)" = "$n" ] || { say "  $f не снялся целиком — проверку не начинаю"; : > $D/files; exit 1; }
    echo "$f есть" >> $D/files
  else echo "$f нет" >> $D/files; fi
done
m=$(mark); launch --es settings show
st=$(wl "$m" '🧪 настройки:' 20)
[ -n "$st" ] || { say "у сборки нет стенда --es settings — настройки потом не вернуть, проверку не начинаю"; exit 1; }
REF=$(printf '%s' "$st" | sed -n 's/.*разбор \([0-9][0-9]*\).*/\1/p'); CLOUD=$(printf '%s' "$st" | sed -n 's/.*облако \([0-9][0-9]*\).*/\1/p')
RG=$(printf '%s' "$st" | sed -n 's/.*чтение вслух \([0-9]\).*/\1/p'); QUAL=$(printf '%s' "$st" | sed -n 's/.*облако точнее \([01]\).*/\1/p')
MTQ=$(printf '%s' "$st" | sed -n 's/.*перевод точнее \([01]\).*/\1/p')   # пусто — у сборки нет «Перевод фраз: быстрее / точнее»
VW=$(printf '%s' "$st" | sed -n 's/.*озвучка \([a-z]*\).*/\1/p')
L=$(printf '%s' "$st" | sed -n 's/.*слушаю \([a-z]*\) ·.*/\1/p'); LST=$(case "$L" in ptru) echo both;; pt|ru) echo $L;; *) echo off;; esac)
m=$(mark); launch --es modules show
MODS=$(wl "$m" '🧩 модули сейчас' 30 | grep -oE '\[[a-z,]*\]' | tr -d '[]')
mod() { printf ',%s,' "$MODS" | grep -q ",$1,"; }
NLOAD=$(mod tts && echo 4 || echo 3)
say "  настройки: разбор $REF, облако $CLOUD, чтение вслух $RG, облако точнее $QUAL, перевод точнее ${MTQ:-—}, слушание $LST · модули: [$MODS]"
NKEYS=0
if [ -s "$SNAP/openrouter.json" ]; then
  # Запасной ключ — поддельный, последним: запросы идут с первого, до него очередь не дойдёт.
  NKEYS=$(python3 - "$SNAP/openrouter.json" "$D/or.json" "$FAKE" <<'PY'
import json, sys
src, dst, fake = sys.argv[1:4]
j = json.load(open(src, encoding='utf-8'))
keys = [k for k in ([j.get('key', '')] + list(j.get('keys', []))) if k]
keys = list(dict.fromkeys(keys)) + [fake]
j['keys'] = keys
json.dump(j, open(dst, 'w', encoding='utf-8'), ensure_ascii=False)
print(len(keys))
PY
)
fi
python3 - "$D/t.json" "$TID" <<'PY'
import json, sys
path, tid = sys.argv[1], int(sys.argv[2])
T = [("pt2ru", "Bom dia, tudo bem?", "Доброе утро, как дела?"),
     ("ru2pt", "Всё хорошо, спасибо.", "Tudo bem, obrigado."),
     ("pt2ru", "Quanto custa o quarto por noite?", "Сколько стоит номер за ночь?"),
     ("ru2pt", "Мне нужен номер на две ночи.", "Preciso de um quarto para duas noites."),
     ("pt2ru", "São trezentos reais por noite.", "Триста реалов за ночь.")]
turns = [{"dir": d, "src": s, "dst": t, "at": tid + 1000 * (k + 1)} for k, (d, s, t) in enumerate(T)]
json.dump({"id": tid, "name": "ТЕСТ интерфейс", "named": True, "saved": tid, "turns": turns}, open(path, "w", encoding="utf-8"), ensure_ascii=False)
PY
$ADB push $A/px_phone.sh /data/local/tmp/falar_px.sh >/dev/null 2>&1

say "== U0: загрузка движков — ход в реплике"
$ADB shell "am force-stop $PKG"; sleep 1
$ADB push $D/t.json $F/chats/$TID.json >/dev/null 2>&1
[ -s $D/or.json ] && $ADB push $D/or.json $F/models/openrouter.json >/dev/null 2>&1
# Подписи хода — стендом --es uitexts (строка видимого экрана в журнал): uiautomator снимает дерево,
# только когда экран затих, а загрузка обновляет его дважды в секунду. Полосу этапов берём из первого
# же дерева, где она есть, и снимаем её цвета до и после подписи: совпали — этап за это время не
# сменился, и отрезки сверяются с подписью.
m=$(mark); launch; : > $D/load; last=""; bar=""
for i in $(seq 80); do
  front || { sleep 1; continue; }
  [ -n "$bar" ] || { dump; bar=$(ui kids --rid busy | awk '$5 == "android.view.View" {print $1, $2, $3, $4; exit}'); }
  s1=""; [ -n "$bar" ] && s1=$(segsat "$bar" $NLOAD)
  mm=$(mark); start --es uitexts 1; t=$(wl "$mm" '🧪 на экране:' 5)
  s2=""; [ -n "$bar" ] && s2=$(segsat "$bar" $NLOAD)
  c=$(printf '%s' "$t" | grep -oE 'Загружаю модели · [^|]*[^ |]')
  if [ -n "$c" ] && [ "$c" != "$last" ]; then printf '%s\t%s\n' "$c" "$([ "$s1" = "$s2" ] && printf '%s' "$s1")" >> $D/load; last=$c; fi
  since $m | grep -q '🧩 модули:' && [ -z "$c" ] && break
done
wl "$m" 'микрофон выключен|▶ слушаю' 60 >/dev/null; sleep 1
sed 's/^/    /' $D/load
python3 - $D/load $NLOAD > $D/load.res <<'PY'
import re, sys
rows = [l.rstrip('\n').split('\t') for l in open(sys.argv[1], encoding='utf-8') if l.strip()]
# Этапы — в порядке службы (TranslatorService.init): озвучка последней с 03.10 — сказанное во время загрузки
# переводится, не дожидаясь её (Screen.loadExpect, ScreenTest L1).
n = int(sys.argv[2]); names = ['распознавание', 'перевод', 'словарь и разговоры'] + (['озвучка'] if n == 4 else [])
bad_cap, stages, etas, bad_seg = [], [], [], []
for cap, seg in rows:
    m = re.fullmatch(r'Загружаю модели · (.+) · ещё ≈ (\d+) с', cap)
    if not m or m.group(1) not in names: bad_cap.append(cap); continue
    k = names.index(m.group(1)); stages.append(k); etas.append(int(m.group(2)))
    want = ' '.join(['пройден'] * k + ['текущий'] + ['впереди'] * (n - k - 1))   # цвета — только устоявшиеся
    if seg and seg != want: bad_seg.append('%s: %s вместо %s' % (m.group(1), seg, want))
print(len(rows))
print('; '.join(bad_cap))
print(int(all(a <= b for a, b in zip(stages, stages[1:]))), len(set(stages)))
print(int(all(b <= a for a, b in zip(etas, etas[1:]))), ' '.join(map(str, etas)))
print(int(any(s for _, s in rows)), '; '.join(bad_seg))
PY
{ read nl; read badcap; read ord nst; read mono etas; read anyseg badseg; } < $D/load.res
if [ "${nl:-0}" -ge 1 ]; then
  res 0 "U0 ход загрузки виден в реплике ($nl снимков, этапов: $nst)"
  chk '[ -z "$badcap" ]' "U0 подпись «Загружаю модели · этап · ещё ≈ N с»${badcap:+ — не так: $badcap}"
  chk '[ "$ord" = 1 ]' "U0 этапы идут по порядку"
  chk '[ "$mono" = 1 ]' "U0 «ещё ≈ N с» не растёт: $etas"
  if [ "$anyseg" = 1 ]; then chk '[ -z "$badseg" ]' "U0 отрезки по этапу${badseg:+ — $badseg}"; else sk "U0 устоявшихся цветов полосы не поймано — отрезки не проверены"; fi
else res 1 "U0 ход загрузки не пойман (впереди: $(focus))"; fi
dump
chk '! has --rid busy && has --text Запомнить' "U0 после загрузки хода нет — на его месте кнопки реплики"
# targetSdk 34+: служба с микрофоном стала видимой (TranslatorService.toForeground) — Android 14+ иначе её не пустит.
fg=$(sh "dumpsys activity services $PKG" | grep -m1 -oE 'isForeground=(true|false)')
chk '[ "$fg" = isForeground=true ]' "U12 служба с микрофоном — видимая: ${fg:-службы нет}"

say "== U1: экран разговора"
start --es silent 1 --es refineevery 0 --es cloudevery 0; sleep 2
dump
chk '[ "$(title)" = "ТЕСТ интерфейс" ]' "U1 в шапке — название разговора («$(title)»)"
chk 'has --desc "Разговоры, слова, настройки" && has --desc "Новый разговор"' "U1 в шапке ☰ и «＋»"
chk 'has --text собеседник' "U1 кто сказал — «собеседник»"
chk 'has --text "São trezentos reais por noite." || has --text "São"' "U1 крупно — португальская реплика"
chk 'has --text "Триста реалов за ночь."' "U1 мелко — русская"
chk 'has --text ранее' "U1 разделитель «ранее»"
o=$(at --text "Quanto custa o quarto por noite?"); me=$(at --text "Preciso de um quarto para duas noites.")
if [ -n "$o" ] && [ -n "$me" ]; then
  read ox0 _ ox1 _ <<< "$o"; read mx0 _ mx1 _ <<< "$me"
  chk "[ $ox0 -le $(dp 32) ] && [ $mx1 -ge $((W - $(dp 32))) ] && [ $mx0 -gt $ox0 ]" "U1 карточки: собеседник слева (x $ox0), вы справа (x $mx0–$mx1 из $W)"
else res 1 "U1 карточек прежних реплик не видно"; fi
chk 'has --text Запомнить && has --desc "Меню реплики"' "U1 кнопки реплики: «Запомнить», «⋯»"
if mod cloud || mod llm; then chk 'has --text Улучшить' "U1 «Улучшить» есть: есть чем улучшать"; else chk '! has --text Улучшить' "U1 «Улучшить» нет: облако и уточнитель выключены"; fi
chk 'has --desc "Снимок или набрать фразу" && has --desc "Удерживайте и говорите" --prefix && has --desc "Обе вместе" --prefix && has --text Слушать' "U1 док: «Снимок, текст», кнопка удержания, «Слушать»"

say "== U2: панель ☰"
tapon --desc "Разговоры, слова, настройки"; dump
chk 'has --text "Новый разговор" --contains && has --text Разговоры && has --text Слова && has --text Настройки' "U2 в панели: «＋ Новый разговор», разговоры, «Слова», «Настройки»"
chk 'has --text "ТЕСТ интерфейс" --all && [ $(cnt --text "ТЕСТ интерфейс") -ge 2 ]' "U2 тестовый разговор в списке"
read SB NB _ <<< "$(sysbars)"; ft=$(at --text Falar | cut -d' ' -f2); nl=$(low --text Настройки)
chk '[ "${SB:-0}" -gt 0 ] && [ "${ft:-0}" -ge "$SB" ] && [ "${nl:-99999}" -le "$NB" ]' "U12 панель ☰ между полосами: «Falar» с ${ft:-—} (строка состояния до $SB), «Настройки» до ${nl:-—} (навигация с $NB)"
press; dump; chk '! has --text Настройки' "U2 «назад» закрывает панель"
tapon --desc "Разговоры, слова, настройки"; tapxy $((W - $(dp 20))) $((H / 2)); dump
chk '! has --text Настройки' "U2 касание мимо панели закрывает её"

say "== U3: «Слова»"
# Карточки «Слов» считаются в затишье (TranslatorService.refreshCards, правило владельца 03.10: отбор — фоном),
# до разбора подсказка — «Разбираю разговоры…» и счёта нет: ждём строку разбора в журнале.
tapon --desc "Разговоры, слова, настройки"; m=$(mark); tapon --text Слова
wl "$m" '📗 «Слова»:' 40 >/dev/null || say "  (разбора «Слов» за 40 с не было — затишья не дождались)"; sleep 1.5; dump
chk '[ "$(title)" = Слова ] && [ "$(line2)" = "из ваших разговоров" ]' "U3 шапка «Слова · из ваших разговоров»"
seg() { ui texts | grep -m1 -E "^$1 · [0-9]+$"; }                                         # «Фразы · 5»
thr() { ui up --text "встречалось не меньше" --levels 1 | grep -E '^[0-9]+$' | head -1; }   # порог −/+
chk '[ -n "$(seg Слова)" ] && [ -n "$(seg Фразы)" ] && [ -n "$(seg Знаю)" ]' "U3 сегменты со счётчиками: «$(seg Слова)», «$(seg Фразы)», «$(seg Знаю)»"
chk '[ "$(at --text "Слова ·" --prefix | cut -d" " -f8)" = true ]' "U3 выбраны «Слова»"
n0=$(thr)
plus=$(ui find --text "+" --all | sort -n -k2 | head -1 | awk '{print $5, $6}')
if [ -n "$n0" ] && [ -n "$plus" ]; then
  tapxy $plus 2; dump; n1=$(thr)
  e=$([ "$n0" -lt 10 ] && echo $((n0 + 1)) || echo 10)
  h=$(ui texts | grep -m1 -E '^(Слова и связки от|Пока нечего показать|Разбираю разговоры)')
  chk '[ "$n1" = "$e" ] && printf "%s" "$h" | grep -qE "(от|не меньше) $e "' "U3 «+»: порог $n0 → $n1, подсказка «$h»"
  tapon --text "−"; sleep 2; dump; n2=$(thr)
  chk '[ "$n2" = "$n0" ]' "U3 «−»: порог обратно $n2"
else res 1 "U3 нет порога «встречалось не меньше N раз»"; fi
tapon --text "Фразы ·" --prefix; sleep 2; dump
nf=$(thr); h=$(ui texts | grep -m1 -E '^(Фразы, сказанные не меньше|Повторяющихся фраз пока нет|Разбираю разговоры)')
chk '[ "$(at --text "Фразы ·" --prefix | cut -d" " -f8)" = true ] && [ -n "$nf" ] && printf "%s" "$h" | grep -qF "не меньше $nf "' "U3 «Фразы»: выбраны, свой порог ${nf:-—}, «$h»"
tapon --text "Знаю ·" --prefix; sleep 2; dump
h=$(ui texts | grep -m1 -E '^(Знаю:|Известного пока нет|Разбираю разговоры)')
chk '[ "$(at --text "Знаю ·" --prefix | cut -d" " -f8)" = true ] && ! has --text "встречалось не меньше" && printf "%s" "$h" | grep -qE "^(Знаю:|Известного пока нет)"' "U3 «Знаю»: выбран, порога нет, «$h»"
tapon --text "Слова ·" --prefix; sleep 2; dump
# «Повторение» — всегда, и без озвучки: на слух звучит живой голос, вслух говорите вы. Что в раскрытой
# карточке, говорит её строка (Screen.reviewLine): «Пора повторить: N» — слово с ответами, иначе — что
# повторять нечего.
r=$(ui up --text Повторение --levels 1 | sed -n 2p)
chk 'has --text Повторение && [ -n "$r" ]' "U3 карточка «Повторение»: «$r»"
tapon --text "Начать" --contains; sleep 2; dump
if printf '%s' "$r" | grep -q '^Пора повторить'; then
  chk 'has --text Закрыть && has --text "На слух" && has --text Вслух && { has --text Показать && has --text Понял && has --text "Не понял" || has --text "В этом режиме — всё"; }' "U3 «Начать» раскрывает повторение: «На слух · Вслух», «Ещё раз · Показать», «Понял · Не понял»"
else
  chk 'has --text Закрыть && has --text "На слух" && has --text Вслух && { has --text "нечего повторять" || has --text "На сегодня всё"; }' "U3 «Начать» раскрывает повторение: «На слух · Вслух» и «$(ui texts | grep -m1 -xE 'нечего повторять|На сегодня всё')»"
fi
tapon --text Закрыть; dump; chk 'has --text Начать --contains' "U3 «Закрыть» сворачивает карточку"
tapon --desc Ещё; dump
chk 'has --text "Очистить выученное…"' "U3 «⋯» в шапке — «Очистить выученное…»"
press
dump; e=$(xy --class android.widget.EditText)
if [ -n "$e" ]; then
  read SB NB _ <<< "$(sysbars)"; fl=$(low --class android.widget.EditText)
  chk '[ "${NB:-0}" -gt 0 ] && [ "${fl:-99999}" -le "$NB" ]' "U12 поле «Своё слово» выше полосы навигации: низ ${fl:-—}, навигация с $NB"
  tapxy $e 1.5; $ADB shell "input text Falarteste"; sleep 1; dump
  read _ _ KB <<< "$(sysbars)"; fl=$(low --class android.widget.EditText); pl=$(low --desc "Добавить своё слово")
  if ime && [ "${KB:-0}" -gt 0 ]; then
    chk '[ "${fl:-99999}" -le "$KB" ] && [ "${pl:-99999}" -le "$KB" ]' "U12 поле и «+» над клавиатурой: низ ${fl:-—} и ${pl:-—}, клавиатура с $KB"
  else sk "U12 клавиатура не открылась — поле над ней не проверить"; fi
  m=$(mark); tapon --desc "Добавить своё слово"
  l=$(wl "$m" '📝 добавлено: Falarteste' 10); chk '[ -n "$l" ]' "U3 своё слово полем и «+»: ${l:-строки в журнале нет}"
  ime && press
else res 1 "U3 нет поля «Своё слово»"; fi
press; dump; chk '[ "$(title)" = "ТЕСТ интерфейс" ]' "U3 «назад» — снова разговор"

say "== U4: «Настройки»"
tapon --desc "Разговоры, слова, настройки"; tapon --text Настройки; sleep 1.5; dump
chk '[ "$(title)" = Настройки ]' "U4 шапка «Настройки»"
G=$(ui texts); for i in 1 2 3; do swipe_up; dump; G="$G
$(ui texts)"; done
gr=""; for g in "Экран разговора" "Микрофон и звук" "Приложение"; do printf '%s\n' "$G" | grep -qxF "$g" || gr="$gr «$g»"; done
# «Перевод» — всегда, если у сборки есть «Перевод фраз: быстрее / точнее», иначе — только с облаком или уточнителем
if [ -n "$MTQ" ] || mod cloud || mod llm; then printf '%s\n' "$G" | grep -qxF "Перевод" || gr="$gr «Перевод»"; fi
chk '[ -z "$gr" ]' "U4 группы на месте${gr:+ — нет:$gr}"
seek --text "Пока держу"
names=("Слушать" "Пока держу" "Пока видно"); cur=""
for i in 0 1 2; do [ "$(at --text "${names[$i]}" | cut -d' ' -f8)" = true ] && cur=$i; done
chk '[ "$cur" = "$RG" ]' "U4 «Пока вы читаете вслух» показывает применённое: «${names[${cur:-0}]}»"
alt=$([ "$RG" = 0 ] && echo 1 || echo 0)
m=$(mark); tapon --text "${names[$alt]}"; l=$(wl "$m" '🔇 пока читаю вслух' 10); dump
chk '[ -n "$l" ] && [ "$(at --text "${names[$alt]}" | cut -d" " -f8)" = true ]' "U4 сегмент меняет службу: ${l:-строки нет}"
m=$(mark); tapon --text "${names[$RG]}"; wl "$m" '🔇 пока читаю вслух' 10 >/dev/null
# «Перевод фраз: Быстрее / Точнее» — на этом экране других «Быстрее» и «Точнее» нет (облачные — на экране «Облако»)
if [ -n "$MTQ" ]; then
  mq=$([ "$MTQ" = 1 ] && echo Точнее || echo Быстрее); mo=$([ "$MTQ" = 1 ] && echo Быстрее || echo Точнее)
  seek --text "Перевод фраз"
  chk 'seek --text $mq && [ "$(at --text $mq | cut -d" " -f8)" = true ]' "U4 «Перевод фраз»: «$mq», как в настройках"
  m=$(mark); tapon --text $mo; l=$(wl "$m" '🔤 перевод:' 10); dump
  chk '[ -n "$l" ] && [ "$(at --text $mo | cut -d" " -f8)" = true ]' "U4 «$mo» меняет перевод в службе: ${l:-строки нет}"
  m=$(mark); tapon --text $mq; wl "$m" '🔤 перевод:' 10 >/dev/null
else sk "U4 «Перевод фраз»: у сборки нет настройки «быстрее / точнее»"; fi
if mod llm; then
  if seek --text "По кнопке"; then
    pk=$(at --text "По кнопке"); y=$(echo $pk | cut -d' ' -f6)
    chk '[ "$(echo $pk | cut -d" " -f8)" = true ]' "U4 «Разбор контекста»: «По кнопке», как задано"
    three=$(ui find --text 3 --all | awk -v y=$y '($6 - y) ^ 2 < 400 {print $5, $6; exit}')
    m=$(mark); tapxy $three; l=$(wl "$m" '🧠 разбор контекста: каждые 3' 10); dump
    chk '[ -n "$l" ] && [ "$(ui find --text 3 --all | awk -v y=$y '"'"'($6 - y) ^ 2 < 400 {print $8; exit}'"'"')" = true ]' "U4 «3» — служба разбирает каждые 3: ${l:-строки нет}"
    m=$(mark); tapon --text "По кнопке"; l=$(wl "$m" '🧠 разбор контекста: только по кнопке' 10)
    chk '[ -n "$l" ]' "U4 «По кнопке» — обратно"
  else res 1 "U4 нет «Разбор контекста» при включённом уточнителе"; fi
fi
if mod cloud; then
  seek --text Облако
  want=$(python3 -c "
n=$NKEYS; a=n%100; b=a%10
w='ключей' if 11<=a<=14 else 'ключ' if b==1 else 'ключа' if 2<=b<=4 else 'ключей'
print(('ключа нет' if n==0 else '%d %s' % (n, w)) + ' · пересмотр по кнопке')")
  sub=$(ui up --text Облако --levels 1 | sed -n 2p)
  chk '[ "$sub" = "$want" ]' "U4 строка «Облако»: «$sub»"
fi
seek --text Журнал; tapon --text Журнал; sleep 1.5; dump
chk '[ "$(title)" = Журнал ] && ui texts | grep -q "молчаливый режим"' "U4 «Журнал» — отдельным экраном, в нём свежие строки"
press; dump; chk '[ "$(title)" = Настройки ]' "U4 «назад» из «Журнала» — в настройки"
seek --text "Модули и файлы"; tapon --text "Модули и файлы"; sleep 1.5; dump
s=$(ui texts | grep -m1 -E '^(Все модели установлены · [0-9]+ из [0-9]+|Качаю · |Жду Wi-Fi|Обязательных нет|Модулям не хватает|Проверяю файлы)')
chk '[ "$(title)" = "Модули и файлы" ] && [ -n "$s" ]' "U4 «Модули и файлы»: «$s»"
pills=$(ui texts | grep -cE '^(установлено|выключен|выключен · файлы на месте|качается|ждёт Wi-Fi|в очереди|не скачано|нужен ключ|ключ есть|ключей: [0-9]+)$')
chk '[ "$pills" -ge 2 ]' "U4 у модулей метки состояния ($pills на экране)"
if mod cloud; then
  wp=$([ $NKEYS = 0 ] && echo "нужен ключ" || { [ $NKEYS = 1 ] && echo "ключ есть" || echo "ключей: $NKEYS"; })
  chk 'seek --text "$wp"' "U4 у облака — «$wp»"
fi
chk 'seek --text "Проверить файлы"' "U4 «Проверить файлы» на месте"
press; dump
if mod cloud && [ $NKEYS -ge 2 ]; then
  seek --text Облако; tapon --text Облако; sleep 1.5; dump
  chk '[ "$(title)" = Облако ]' "U4 «Облако» — отдельным экраном"
  keys() { ui texts | grep -c '^Ключ '; }        # строки ключей; ответ «ключ … убран» под ними — со строчной
  chk '[ $(keys) = $NKEYS ] && [ $(cnt --desc "Убрать ключ") = $NKEYS ]' "U4 ключей $NKEYS, у каждого своя корзина"
  chk 'ui up --text "Ключ $FAKEID" --levels 1 | grep -qx запасной' "U4 поддельный ключ — последним, «запасной»"
  tapon --text "Добавить ключ"; dump; chk 'has --text Сохранить' "U4 «Добавить ключ» — поле и «Сохранить»"
  tapon --text "Добавить ключ"; dump; chk '! has --text Сохранить' "U4 повторное касание прячет поле"
  y=$(at --text "Ключ $FAKEID" | cut -d' ' -f6)
  tr=$(ui find --desc "Убрать ключ" --all | awk -v y=$y '{d=($6-y)^2; if (b=="" || d<b) {b=d; p=$5" "$6}} END {print p}')
  tapxy $tr; dump
  if has --text "Убрать ключ $FAKEID?"; then
    m=$(mark); tapon --text убрать; l=$(wl "$m" "☁ ключ $FAKEID убран" 15); sleep 1; dump
    chk '[ -n "$l" ] && ! has --text "Ключ $FAKEID" && [ $(keys) = $((NKEYS - 1)) ]' "U4 корзина и «убрать» — ключа нет, остальные на месте (ключей $(keys))"
  else res 1 "U4 корзина не спросила «Убрать ключ …?»"; press; fi
  chk 'seek --text "По кнопке" && [ "$(at --text "По кнопке" | cut -d" " -f8)" = true ]' "U4 «Пересмотр разговора»: «По кнопке», как задано"
  q=$([ "$QUAL" = 1 ] && echo Точнее || echo Быстрее)
  chk 'seek --text $q && [ "$(at --text $q | cut -d" " -f8)" = true ]' "U4 «Облако выбирает»: «$q», как в настройках"
  press; seek --text Облако
  want=$(python3 -c "
n=$NKEYS-1; a=n%100; b=a%10
w='ключей' if 11<=a<=14 else 'ключ' if b==1 else 'ключа' if 2<=b<=4 else 'ключей'
print(('ключа нет' if n==0 else '%d %s' % (n, w)) + ' · пересмотр по кнопке')")
  chk '[ "$(ui up --text Облако --levels 1 | sed -n 2p)" = "$want" ]' "U4 после «убрать» строка «Облако» — «$want»"
else sk "U4 облако выключено или ключей нет — экран «Облако» не проверен"; fi
press; dump; chk '[ "$(title)" = "ТЕСТ интерфейс" ]' "U4 «назад» из настроек — разговор"

say "== U5: «Снимок, текст»"
dump; tapon --desc "Снимок или набрать фразу"; dump
photo=0; mod ocr || mod cloud && photo=1
if [ $photo = 1 ]; then
  chk 'has --text "Снять камерой" && has --text "Из галереи" && has --text "Набрать фразу"' "U5 меню: камера, галерея, набрать"
  tapon --text "Набрать фразу"; dump
fi
e=$(xy --class android.widget.EditText)
if has --text "Набрать фразу" && [ -n "$e" ]; then
  tapxy $e 1.5; $ADB shell "input text Obrigado%spela%sajuda"; sleep 1; dump
  m=$(mark); tapon --text перевести; l=$(wl "$m" 'Obrigado pela ajuda' 30); sleep 1.5; dump
  chk '[ -n "$l" ] && { has --text "Obrigado pela ajuda" || has --text Obrigado; }' "U5 набранная фраза переведена и на экране: $(printf '%s' "$l" | cut -c1-90)"
else res 1 "U5 нет окна «Набрать фразу» с полем"; press; fi
if mod cloud; then
  p=$(xy --desc "Снимок или набрать фразу"); front && $ADB shell "input swipe $p $p 900"; sleep 1.5; dump
  chk 'has --text "Снимок → в облако"' "U5 долгое касание — снимок в облако, минуя офлайн-чтение"
  press
fi
if [ $photo = 1 ]; then
  tapon --desc "Снимок или набрать фразу"; tapon --text "Из галереи"; sleep 3
  f=$(focus); case "$f" in *$PKG/*) res 1 "U5 «Из галереи» не открыл выбор снимка";; *)
    res 0 "U5 «Из галереи» открывает выбор снимка ($(printf '%s' "$f" | grep -oE '[a-z0-9.]+/[A-Za-z0-9.$]+' | head -1))"
    $ADB shell "input keyevent 4"; sleep 2; chk 'front' "U5 «назад» из выбора — снова Falar";; esac
fi

say "== U6: «Слушать» половинками PT | RU"
dump; b=$(at --desc "Обе вместе" --prefix)
if [ -n "$b" ]; then
  read x0 y0 x1 y1 cx cy _ <<< "$b"
  HP=$(python3 -c "
d=($x1-$x0)/108; print(round($x0+17*d), round($y0+27*d), round($x0+91*d), round($y0+27*d))")
  LX=$((x0 + (x1 - x0) * 3 / 10)); RX=$((x0 + (x1 - x0) * 7 / 10))
  step() {   # касание, строка журнала, строка в шапке, половинки, подпись
    local m l wline=$3 whalves=$4; m=$(mark); tapxy $1 $cy 0.3; l=$(wl "$m" "$2" 10); sleep 1; dump
    if since "$m" | grep -qE '#[0-9]+ (pt2ru|ru2pt) \|'; then
      # Пока слушаем, в комнате могли заговорить: реплика ставит в шапку «вы» или «собеседник».
      chk '[ -n "$l" ] && [ "$(halves)" = "$whalves" ]' "U6 $5: служба «$(printf '%s' "$l" | cut -c10-)», половинки $(halves) (строку в шапке сменила реплика из комнаты)"
    elif since "$m" | grep -qF '🎤 в разговоре ещё нет голосов'; then
      # С голосами разговора (модуль «Отпечаток голоса») речь комнаты в разговоре без голосов не переводится,
      # а в шапку встаёт, как их завести (TranslatorService.skipVoice), — и эта строка верна.
      chk '[ -n "$l" ] && { line2 | grep -qF "$wline" || line2 | grep -qF "скажите фразу кнопкой FALAR — голос запомнится"; } && [ "$(halves)" = "$whalves" ]' "U6 $5: служба «$(printf '%s' "$l" | cut -c10-)», в шапке «$(line2)», половинки $(halves) (в комнате говорили, а голосов в разговоре нет)"
    else
      chk '[ -n "$l" ] && line2 | grep -qF "$wline" && [ "$(halves)" = "$whalves" ]' "U6 $5: служба «$(printf '%s' "$l" | cut -c10-)», в шапке «$(line2)», половинки $(halves)"
    fi
  }
  step $LX '▶ слушаю только португальский' 'слушаю португальский' 'PT -' 'PT'
  step $RX '▶ слушаю оба языка' 'слушаю оба языка' 'PT RU' 'PT + RU'
  step $LX '▶ слушаю только русский' 'слушаю русский' '- RU' 'RU'
  step $RX '⏹ не слушаю' 'Микрофон выключен' '- -' 'ни одной'
else res 1 "U6 нет кнопки «Слушать»"; fi

say "== U7: кнопки реплики"
dump; m=$(mark); tapon --text Запомнить; l=$(wl "$m" '📌 запомнено:' 10)
chk '[ -n "$l" ]' "U7 «Запомнить» — пин: $(printf '%s' "$l" | cut -c10-100)"
# Последняя и предпоследняя реплики — из самого разговора: пока слушали (U6), в комнате могли заговорить.
turns() { sh "cat $F/chats/$TID.json" | python3 -c "
import json,sys; t=json.load(sys.stdin)['turns']; pt=lambda x: x['src'] if x['dir'].startswith('pt') else x['dst']
print(len(t), t[-1]['dir'], (pt(t[-2]) if len(t) > 1 else '').split()[0] if len(t) > 1 else '-')"; }
read n0 d0 w0 <<< "$(turns)"
tapon --desc "Меню реплики"; dump
if [ "${d0#ru}" != "$d0" ]; then
  chk 'has --text "Удалить реплику" && has --text "Исправить текст" && has --text "Исправить перевод"' "U7 «⋯» у вашей реплики — с правкой текста и перевода"
else
  chk 'has --text "Удалить реплику" && has --text "Сообщить о переводе" && has --text "Перенести в другой разговор" && ! has --text "Исправить текст"' "U7 «⋯» у реплики собеседника — без «Исправить текст»"
fi
m=$(mark); tapon --text "Удалить реплику"; l=$(wl "$m" '✂ реплика' 10); sleep 1.5; dump
read n1 _ _ <<< "$(turns)"
chk '[ -n "$l" ] && [ "$n1" = $((n0 - 1)) ] && ui texts | grep -qF -- "$w0"' "U7 «Удалить реплику»: реплик $n0 → $n1, наверху снова прежняя («$w0…»)"
tapon --text "Preciso de um quarto para duas noites."; dump
chk 'has --text "Исправить текст" && has --text "Исправить перевод"' "U7 касание вашей карточки — меню с правкой"
press
m=$(mark); start --es betterstate 1; l=$(wl "$m" '🧪 «Улучшить»' 15); dump
if has --text Улучшить; then
  en=$(at --text Улучшить | cut -d' ' -f7)
  w=$(printf '%s' "$l" | grep -q ': можно' && echo true || echo false)
  chk '[ "$en" = "$w" ]' "U7 «Улучшить» горит так, как решает служба: «$(printf '%s' "$l" | cut -c10-)» → enabled=$en"
else sk "U7 «Улучшить» не показана — нечем улучшать"; fi

say "== U10: обратная связь"
fld() { python3 -c 'import sys, urllib.parse as u; print(u.parse_qs(u.urlsplit(sys.argv[1]).query, keep_blank_values=True).get(sys.argv[2], [""])[0])' "$1" "$2"; }
url() { printf '%s' "$1" | sed 's/.*🔗 //'; }
m=$(mark); start --es linkdry 1; l=$(wl "$m" '🧪 ссылки: только в журнал' 10)
chk '[ -n "$l" ]' "U10 стенд: ссылки — в журнал, Telegram и браузер не открываются"
tapon --desc "Меню реплики"; tapon --text "Сообщить о переводе"; sleep 1; dump
chk 'has --text Telegram && has --text GitHub && has --text отмена && has --text "видно всем" --contains' "U10 «Сообщить о переводе»: Telegram, GitHub и что уйдёт"
m=$(mark); tapon --text GitHub; u=$(url "$(wl "$m" '🔗 https://github.com/Annoyt/FALAR/issues/new\?template=translation.yml&' 10)")
chk '[ -n "$u" ] && ! printf "%s" "$u" | grep -q "labels=" && [ -n "$(fld "$u" source)" ] && fld "$u" device | grep -q "^Falar " && [ ${#u} -le 6000 ]' "U10 → GitHub: шаблон перевода, поля из реплики, без labels=, ссылка ${#u} знаков"
tapon --desc "Меню реплики"; tapon --text "Сообщить о переводе"; sleep 1
m=$(mark); tapon --text Telegram; u=$(url "$(wl "$m" '🔗 https://t.me/falar_tbot\?text=' 10)")
chk '[ -n "$u" ] && fld "$u" text | head -1 | grep -qE "^#перевод · (португальский → русский|русский → португальский)$"' "U10 → Telegram: «$(fld "$u" text | head -1)»"
tapon --desc "Разговоры, слова, настройки"; tapon --text Настройки; sleep 1.5
seek --text "Написать разработчику"; tapon --text "Написать разработчику"; sleep 1; dump
chk 'has --text "Что-то не работает" && has --text "Идея или пожелание" && has --text Telegram && has --text GitHub' "U10 «Написать разработчику»: ошибка или идея, Telegram или GitHub"
tapon --text "Идея или пожелание"
m=$(mark); tapon --text GitHub; u=$(url "$(wl "$m" '🔗 https://github.com/Annoyt/FALAR/issues/new\?template=idea.yml&' 10)")
chk '[ -n "$u" ] && fld "$u" device | grep -q "^Falar "' "U10 идея → GitHub: шаблон идеи, «$(fld "$u" device)»"
seek --text "Написать разработчику"; tapon --text "Написать разработчику"; sleep 1
m=$(mark); tapon --text Telegram; u=$(url "$(wl "$m" '🔗 https://t.me/falar_tbot\?text=' 10)")
chk '[ -n "$u" ] && fld "$u" text | head -1 | grep -q "^#ошибка · Falar "' "U10 ошибка → Telegram: «$(fld "$u" text | head -1)»"
m=$(mark); start --es linkdry 0; l=$(wl "$m" '🧪 ссылки: открываются' 10)
chk '[ -n "$l" ]' "U10 стенд выключен — ссылки снова открываются"
press; dump; chk '[ "$(title)" = "ТЕСТ интерфейс" ]' "U10 «назад» из настроек — разговор"

say "== U11: «Что озвучивать»"
if mod tts; then
  tapon --desc "Разговоры, слова, настройки"; tapon --text Настройки; sleep 1.5
  seek --text "Что озвучивать"; seek --text Оба; dump
  keys=(auto ru pt both); cur=-1
  for i in 0 3; do [ "$(at --text "$([ $i = 0 ] && echo Авто || echo Оба)" | cut -d' ' -f8)" = true ] && cur=$i; done
  want=-1; for i in 0 1 2 3; do [ "${keys[$i]}" = "${VW:-auto}" ] && want=$i; done
  chk 'has --text "Что озвучивать" && has --text Авто && has --text Оба && { [ "$cur" = "$want" ] || [ "$want" = 1 ] || [ "$want" = 2 ]; }' "U11 сегмент на месте и показывает выбор службы («${VW:-auto}»)"
  # Касание по уже выбранному сегменту ничего не меняет — сначала тот из двух, что не выбран.
  a=Оба; pa='🔈 озвучиваю оба языка'; b=Авто; pb='🔈 озвучка — авто'
  [ "$cur" = 3 ] && { a=Авто; pa='🔈 озвучка — авто'; b=Оба; pb='🔈 озвучиваю оба языка'; }
  m=$(mark); tapon --text $a; l=$(wl "$m" "$pa" 10)
  chk '[ -n "$l" ]' "U11 «$a» — служба: $(printf '%s' "$l" | cut -c10-)"
  m=$(mark); tapon --text $b; l=$(wl "$m" "$pb" 10)
  chk '[ -n "$l" ]' "U11 «$b» — служба: $(printf '%s' "$l" | cut -c10-)"
  press; dump
  # Прогон идёт в молчаливом режиме, а он глушит озвучку раньше выбора языка: на две реплики он
  # выключается («RU» скажет вслух короткое «спасибо за терпение») и после включается снова.
  # Текст — в кавычках внутри строки am start: иначе оболочка телефона режет его по пробелам.
  m=$(mark); start --es voicewhat pt --es silent 0; wl "$m" '🔈 озвучиваю только португальский' 10 >/dev/null
  m=$(mark); start "--es feedtext 'Bom dia, tudo certo?'"; l=$(wl "$m" 'pt2ru . Bom dia' 30); sleep 1; l=$(since "$m" | grep -E -m1 -A2 'pt2ru . Bom dia' | tr '\n' ' ')
  chk 'printf "%s" "$l" | grep -q "без озвучки: выбрано озвучивать только португальский"' "U11 «PT»: русский перевод не звучит — «без озвучки»"
  m=$(mark); start --es voicewhat ru; wl "$m" '🔈 озвучиваю только русский' 10 >/dev/null
  m=$(mark); start "--es feedtext 'Obrigado pela paciência'"; l=$(wl "$m" 'pt2ru . Obrigado pela' 30); sleep 1; l=$(since "$m" | grep -E -m1 -A2 'pt2ru . Obrigado pela' | tr '\n' ' ')
  chk '[ -n "$l" ] && ! printf "%s" "$l" | grep -q "без озвучки"' "U11 «RU»: русский перевод звучит"
  m=$(mark); start --es voicewhat ${VW:-auto} --es silent 1; wl "$m" '🔈 молчаливый режим' 10 >/dev/null
else sk "U11 модуль «Озвучка» выключен — выбор не показывается"; fi


say "== U8: «＋» — новый разговор и возврат из панели"
m=$(mark); tapon --desc "Новый разговор"; l=$(wl "$m" '＋ новый разговор' 10); sleep 1; dump
chk '[ -n "$l" ] && [ "$(title)" = "Новый разговор" ] && ! has --text собеседник && has --text "—"' "U8 «＋»: пустой «Новый разговор»"
tapon --desc "Разговоры, слова, настройки"; dump
chk 'has --text "пока пусто" --contains' "U8 в панели — новый разговор «пока пусто»"
m=$(mark); tapon --text "ТЕСТ интерфейс"; l=$(wl "$m" "↩ разговор $TID продолжен" 15); sleep 1.5; dump
chk '[ -n "$l" ] && [ "$(title)" = "ТЕСТ интерфейс" ] && ! has --text Настройки' "U8 разговор из панели открыт, панель закрыта"

say "== U9: дневная и ночная тема"
theme() {
  local m; m=$(mark); start --es uitheme $1; wl "$m" '🧪 тема:' 10 >/dev/null || return 1
  sleep 3; start --es silent 1; sleep 1.5
}
# Шапка — сливой; фон под ней — белый днём, тёмный ночью; крупный текст — тёмный днём, светлый ночью.
look() {
  dump; $ADB exec-out screencap -p > $D/$1.png 2>/dev/null
  python3 - $D/$1.png $1 "$(at --rid hint)" "$(at --text ранее)" "$(at --text 'São trezentos reais por noite.' || at --text São)" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB'); day = sys.argv[2] == 'day'
hb, sep, big = [list(map(int, a.split()[:4])) if a.strip() else None for a in sys.argv[3:6]]
out = []
if hb:
    r, g, b = im.getpixel((8, (hb[1] + hb[3]) // 2)); out.append('шапка %d,%d,%d %s' % (r, g, b, 'ok' if r > g + 30 and r >= b else 'НЕ слива'))
if sep:
    p = im.getpixel((4, (sep[1] + sep[3]) // 2)); ok = min(p) >= 235 if day else max(p) <= 45
    out.append('фон %d,%d,%d %s' % (p + ('ok' if ok else 'НЕ ' + ('белый' if day else 'тёмный'),)))
if big:
    px = [im.getpixel((x, y)) for x in range(big[0], big[2], 2) for y in range(big[1], big[3], 2)]
    L = [0.299 * r + 0.587 * g + 0.114 * b for r, g, b in px]
    ink = sum(1 for v in L if (v < 90 if day else v > 170)) / max(1, len(L))
    out.append('текст %.0f%% %s [%d,%d–%d,%d]' % (ink * 100, 'ok' if 0.02 <= ink <= 0.6 else 'НЕ ' + ('тёмный' if day else 'светлый'), *big))
print(' · '.join(out))
PY
}
# Полосы на экране разговора — по дереву и снимку из look(): шапка (подпись названия) ниже строки
# состояния, кнопки дока выше полосы навигации; под строкой состояния — слива (градиент шапки), под
# навигацией — фон экрана. Точки строки — средняя треть (по краям часы и значки), навигации — у краёв
# полосы по бокам от «пилюли» жестов.
barsck() {
  python3 - $D/$1.png $1 "$(sysbars)" "$(at --rid hint)" "$(at --desc 'Удерживайте и говорите' --prefix)" \
    "$(at --desc 'Снимок или набрать фразу')" "$(at --desc 'Обе вместе' --prefix)" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB'); day = sys.argv[2] == 'day'; w, h = im.size
sb, nb, _ = map(int, sys.argv[3].split())
hb, mic, inp, lis = [list(map(int, a.split()[:4])) if a.strip() else None for a in sys.argv[4:8]]
if not sb or not nb: print('полосы в dumpsys window НЕ нашлись'); sys.exit()
out = ['строка до %d, навигация с %d' % (sb, nb)]
out.append('шапка с %s %s' % (hb[1] if hb else '—', 'ok' if hb and hb[1] >= sb else 'НЕ ниже строки'))
for name, b in (('кнопка', mic), ('снимок', inp), ('слушать', lis)):
    out.append('%s до %s %s' % (name, b[3] if b else '—', 'ok' if b and b[3] <= nb else 'НЕ выше навигации'))
px = [im.getpixel((x, sb // 2)) for x in range(w // 3, 2 * w // 3, 5)]
plum = sum(1 for r, g, b in px if r >= g + 10 and r >= b and 0.299 * r + 0.587 * g + 0.114 * b < 130) / len(px)
out.append('под строкой слива %.0f%% %s' % (plum * 100, 'ok' if plum >= 0.6 else 'НЕ слива'))
px = [im.getpixel((x, y)) for y in (nb + 2, h - 3) for x in list(range(8, w // 3, 5)) + list(range(2 * w // 3, w - 8, 5))]
bg = sum(1 for p in px if (min(p) >= 235 if day else max(p) <= 45)) / len(px)
out.append('под навигацией фон %.0f%% %s' % (bg * 100, 'ok' if bg >= 0.9 else 'НЕ ' + ('белый' if day else 'тёмный')))
print(' · '.join(out))
PY
}
shots() {   # снимки экранов самим приложением: доля светлых или тёмных точек посередине
  local s m out="" sb; read sb _ _ <<< "$(sysbars)"
  for s in talk drawer words settings mods cloud log setup; do
    $ADB shell "rm -f $F/ui-$1-$s.png"; start --es uishot ui-$1-$s --es uiscreen $s
    local n0=-1 n1 i                          # строку «снимок экрана» uishot пишет на экран, не в журнал — ждём файл
    for i in $(seq 20); do sleep 1; n1=$(sh "stat -c %s $F/ui-$1-$s.png 2>/dev/null"); [ -n "$n1" ] && [ "$n1" = "$n0" ] && break; n0=${n1:--1}; done
    [ -n "$n1" ] || { out="$out $s:нет"; continue; }
    $ADB pull $F/ui-$1-$s.png $D/ >/dev/null 2>&1; $ADB shell "rm -f $F/ui-$1-$s.png"
    out="$out $(python3 - $D/ui-$1-$s.png $1 $s ${sb:-0} <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('L'); day = sys.argv[2] == 'day'; w, h = im.size
x1 = int(w * 0.75) if sys.argv[3] == 'drawer' else w      # у панели — её сторона, справа затемнение
v = sorted(im.getpixel((x, y)) for x in range(0, x1, 6) for y in range(int(h * 0.2), int(h * 0.8), 6))
med = v[len(v) // 2]; ok = med > 170 if day else med < 70
# Экран первого запуска — без шапки: под строкой состояния её цвет (U12), а не начало экрана.
if sys.argv[3] == 'setup' and int(sys.argv[4]) > 4:
    rgb = Image.open(sys.argv[1]).convert('RGB'); sb = int(sys.argv[4])
    px = [rgb.getpixel((x, y)) for x in range(w // 3, 2 * w // 3, 6) for y in range(2, sb - 2, 3)]
    ok = ok and sum(1 for r, g, b in px if r >= g + 10 and r >= b and 0.299 * r + 0.587 * g + 0.114 * b < 130) / len(px) >= 0.9
print('%s:%d%s' % (sys.argv[3], med, '' if ok else '!'))
PY
)"
    [ "$KEEP" = 1 ] && { mkdir -p $SHOTS; cp $D/ui-$1-$s.png $SHOTS/; }
  done
  printf '%s' "$out"
}
for t in day night; do
  if theme $t; then
    v=$(look $t); say "  $t: $v"
    printf '%s' "$v" | grep -q "НЕ " && { mkdir -p /tmp/falar-ui-fail; cp $D/$t.png /tmp/falar-ui-fail/$t.png; cp $D/ui.xml /tmp/falar-ui-fail/$t.xml
      say "  снимок и дерево экрана при провале: /tmp/falar-ui-fail/$t.* (разговор тестовый)"; }
    chk '! printf "%s" "$v" | grep -q "НЕ "' "U9 тема «$t» на экране: шапка, фон, крупный текст"
    v=$(barsck $t); say "  $t, полосы: $v"
    chk '! printf "%s" "$v" | grep -q "НЕ "' "U12 тема «$t»: шапка ниже строки состояния, под строкой — слива; док выше навигации, под ней — фон"
    if [ $t = day ]; then   # смена темы пересоздала экран: состояние службы должно доехать и до нового
      tapon --desc "Разговоры, слова, настройки"; tapon --text Настройки; sleep 1.5; dump
      st=$(ui texts | sed -n '/^Проверить$\|^Обновить$/{n;p;q}')
      chk '[ "$(title)" = Настройки ] && [ -n "$st" ] && [ "$st" != "Запуск сервиса…" ]' "U9 после пересоздания экрана строка состояния — от службы: «$st»"
      press
    fi
    sv=$(shots $t); say "  снимки (медиана яркости посередине):$sv"
    chk '! printf "%s" "$sv" | grep -qE "!|нет"' "U9 тема «$t» на всех экранах — $([ $t = day ] && echo светлые || echo тёмные)"
  else res 1 "U9 стенд --es uitheme не ответил"; fi
done
theme system
# Окно снимка во весь экран — тоже под полосами (Bars): «✕» ниже выреза камеры (на стенде он совпадает со
# строкой состояния, которую окно прячет), подпись выше полосы навигации. Снимок свой — надпись на белом;
# реплика ложится в тестовый разговор последней, после U9, чтобы не сбить проверки выше.
if mod ocr; then
  python3 - $D/ui_photo.jpg <<'PY'
import sys
from PIL import Image, ImageDraw, ImageFont
im = Image.new("RGB", (1200, 700), "white"); d = ImageDraw.Draw(im)
f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 96)
d.text((80, 160), "SAÍDA DE", fill="black", font=f); d.text((80, 320), "EMERGÊNCIA", fill="black", font=f)
im.save(sys.argv[1], quality=92)
PY
  was=$(sh "ls $F/photos/ 2>/dev/null"); $ADB push $D/ui_photo.jpg $F/ui_photo.jpg >/dev/null 2>&1
  m=$(mark); start --es photopick $F/ui_photo.jpg; l=$(wl "$m" '📷 OCR за' 60); sleep 2.5; dump
  read SB NB _ <<< "$(sysbars)"; x=$(at --text ✕ | cut -d' ' -f2); tl=$(low --text "касание абзаца" --prefix)
  if [ -n "$l" ] && [ -n "$x" ]; then
    chk '[ "$x" -ge "$SB" ] && [ "${tl:-99999}" -le "$NB" ]' "U12 окно снимка между полосами: «✕» с $x (вырез до $SB), подпись до ${tl:-—} (навигация с $NB)"
    press
  else res 1 "U12 окно снимка не открылось (${l:-снимок не прочитан})"; fi
  $ADB shell "rm -f $F/ui_photo.jpg"
  for f in $(sh "ls $F/photos/ 2>/dev/null"); do printf '%s\n' "$was" | grep -qxF "$f" || $ADB shell "rm -f $F/photos/$f"; done
else sk "U12 окно снимка: модуль «Чтение снимков» выключен"; fi
c=$(crashed); chk '[ -z "$c" ]' "U10 за проверку Falar не падал${c:+: $c}"
[ "$KEEP" = 1 ] && say "  снимки экранов: $SHOTS (в них слова и журнал владельца — не в репозиторий)"
[ $fail -eq 0 ]   # код выхода — по итогу: без этой строки он был кодом предыдущей (1, когда KEEP не задан)
