#!/bin/bash
# Сомнительные слова (WordConf; владелец 30.10) на телефоне — в своих тестовых разговорах.
#
#   bash bench/apk/test_wordmarks_device.sh [запись=near-pt]    # INSTALL=1 — сначала поставить bench/apk/Falar.apk
#
# Часть 1 — весь путь «звук → распознавание → пометки»: «Слушать PT», вместо микрофона — запись комнаты (feedwav,
# стенд --es feedonly 1 держит микрофон глухим вне подачи), реплики идут в тестовый разговор.
#   D1 в журнале «🔎 распознаватель отдаёт уверенность в словах»: мост JNI с вероятностями токенов (tools/sherpa-jni)
#      работает на телефоне; с прежним — «без уверенности», провал;
#   D2 есть строки «🔎 распознаватель не уверен», и у реплик тестового разговора есть поле doubt;
#   D3 пометки разговора сходятся с журналом: отрезок doubt лежит внутри исходника, и слово под ним названо в журнале;
#   D4 доля сомнительных слов — как на ПК для той же записи (кэш tools/asr_confidence.py, ветка asr-confidence: near-pt
#      4,4 %, far-pt 10,6 %, noisy-pt 23 %): от трети до тройной. Иначе вероятности телефона не те, на которых выбран
#      порог 0,7.
# Часть 2 — экран, по готовому разговору, без распознавания: последняя реплика собеседника «Bom dia, tudo bem?»,
# перед ней своя «Где здесь аптека?». Снимок главного экрана — стендом uishot (окно рисуется в png без сжатия и за
# погашенным экраном, MainActivity.uiShot) для трёх видов разговора: без поля doubt, с пометкой «dia» только у
# собеседника, с пометкой «аптека» только у своей. Считаются точки цвета пометки (Look.doubtFg днём и ночью).
#   D5 пометка собеседника прибавляет точки цвета (крупный текст); D6 пометка своей — тоже (карточка, строка
#   русского); на снимках — именно этот разговор; D7 за проверку Falar не падал.
# Касаний нет. Falar запускается, только когда экран погашен или впереди Falar либо рабочий стол и экрана не касались
# FREE_S с (60): телефон рабочий, его могут взять в руки.
# В конце всё как было: слушание выключено, частота разбора и облака, выученное и словари из снимка, «Что нового»,
# тестовые разговоры удалены — текущим снова становится прежний разговор.
R=$(cd "$(dirname "$0")/../.." && pwd); A=$R/bench/apk
# Из рабочей копии git worktree: записи комнаты и adb — из основной (их нет в git).
MAIN=$(cd "$(git -C "$R" rev-parse --git-common-dir)/.." && pwd)
pick() { [ -e "$R/$1" ] && echo "$R/$1" || echo "$MAIN/$1"; }
# Один прогон на телефоне за раз — и отдельный скрипт, и test_all_device.sh.
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand"; exec 9>"${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
ADB=${ADB:-$(pick tools/platform-tools/adb)}; PY=${PY:-python3}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
NAME=${1:-near-pt}; REC=$(pick bench/air/rec/$NAME)
case $NAME in near-pt) PC=4.4;; far-pt) PC=10.6;; noisy-pt) PC=23.0;; nearbg40-pt) PC=13.6;; nearbg45-pt) PC=9.0;; *) PC=;; esac
[ -f "$REC/room.wav" ] || { echo "нет записи $REC/room.wav"; exit 2; }
# Выученное, словари и журнал с телефона — только в ~/.cache/falar-private (владелец 27.10), после прогона удаляются.
PRIV=${XDG_CACHE_HOME:-$HOME/.cache}/falar-private; mkdir -p "$PRIV"
D=$(mktemp -d "$PRIV/wordmarks.XXXX"); SNAP=$D/snap; mkdir -p $SNAP
TID=$(date +%s%3N); TID2=$((TID + 1))
pass=0; fail=0; skip=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sk() { skip=$((skip+1)); say "ПРОПУСК $1"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }   # не из stdin: внутри «while read» adb съел бы его
FREE_S=${FREE_S:-60}
focus() { sh "dumpsys window | grep -m1 mCurrentFocus"; }
# Свободен: экран погашен — или впереди Falar либо рабочий стол, и экрана не касались FREE_S с.
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
free_phone() {
  [ -n "$(sh 'echo ok')" ] || { say "телефон не подключён"; exit 2; }
  local n=0; until idle; do n=$((n+1)); [ $n -eq 1 ] && say "  жду: экран погашен или впереди Falar/рабочий стол, ${FREE_S} с без касаний ($(focus))"; sleep 10; done
}
count() { sh "test -f $1 && wc -l < $1 || echo 0" | awk '{print $1+0}'; }
seen() { sh "tail -n +$(($1+1)) $LOG | grep -c -E -- '$2'" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
# Настройки, к которым вернуться (как в test_hearing_device.sh): частота разбора и облака — из окружения
# (REFINE_EVERY, CLOUD_EVERY), иначе у самого приложения (стенд --es settings show).
orig_settings() {
  REF=${REFINE_EVERY:-}; CLOUD=${CLOUD_EVERY:-}
  [ -n "$REF" ] && [ -n "$CLOUD" ] && return
  local m st; m=$(count $LOG); launch --es settings show
  st=$(wl "$m" '🧪 настройки:' 15)
  [ -z "$REF" ] && REF=$(printf '%s' "$st" | sed -n 's/.*разбор \([0-9][0-9]*\).*/\1/p')
  [ -z "$CLOUD" ] && CLOUD=$(printf '%s' "$st" | sed -n 's/.*облако \([0-9][0-9]*\).*/\1/p')
  REF=${REF:-3}; CLOUD=${CLOUD:-0}
}
AUTO0=$(sh "grep -E '🎚 (чувствительность: авто, сейчас|авто-чувствительность:)' $LOG | tail -1" | sed -n 's/.* \([+-][0-9.]*\) дБ.*/\1/p'); AUTO0=${AUTO0:-0}
# Точки цвета пометки на снимке (или в прямоугольнике): Look.doubtFg днём (0x8A6420) и ночью (GOLD 0xE0B878).
cat > $D/px.py <<'PY'
import sys
from PIL import Image
img = Image.open(sys.argv[1]).convert('RGB'); px = img.load()
x0, y0, x1, y1 = (int(v) for v in sys.argv[2:6]) if len(sys.argv) > 5 else (0, 0, img.width, img.height)
cols = [(0x8A, 0x64, 0x20), (0xE0, 0xB8, 0x78)]
n = 0
for y in range(max(0, y0), min(img.height, y1)):
    for x in range(max(0, x0), min(img.width, x1)):
        p = px[x, y]
        if min(sum((a - b) ** 2 for a, b in zip(p, c)) for c in cols) < 40 * 40: n += 1
print(n)
PY
# Разговор части 2: «Где здесь аптека?» (своя, ru2pt) и «Bom dia, tudo bem?» (собеседника, pt2ru); пометки — ни у
# какой (none), только у собеседника (big) или только у своей (card).
chat2() {   # chat2 <файл> <none|big|card>
  "$PY" - "$1" "$2" "$TID2" <<'PY'
import json, sys
out, marks, tid = sys.argv[1], sys.argv[2], int(sys.argv[3])
t = [{"dir": "ru2pt", "src": "Где здесь аптека?", "dst": "Onde fica a farmácia?", "at": tid - 60000},
     {"dir": "pt2ru", "src": "Bom dia, tudo bem?", "dst": "Добрый день, как дела?", "at": tid - 30000}]
assert t[0]["src"][10:16] == "аптека" and t[1]["src"][4:7] == "dia"
if marks == "card": t[0]["doubt"] = "10-16"
if marks == "big": t[1]["doubt"] = "4-7"
json.dump({"id": tid, "name": "ТЕСТ сомнительные слова, экран", "named": True, "saved": tid, "turns": t},
          open(out, "w", encoding="utf-8"), ensure_ascii=False)
PY
}
# Открыть разговор части 2 и снять главный экран стендом uishot; печатает число точек цвета пометки на снимке или
# «-»: снимка нет либо на нём не этот разговор.
shot2() {   # shot2 <none|big|card>
  local f=$F/wordmarks-$1 m i n0=-1 n1
  chat2 $D/c2.json "$1"
  $ADB shell "am force-stop $PKG"; sleep 1; $ADB push $D/c2.json "$F/chats/$TID2.json" >/dev/null 2>&1
  m=$(count $LOG); launch --es listen off; wl "$m" '🧩 модули:' 60 >/dev/null; sleep 2
  sh "rm -f $f.png $f.txt"; launch --es uishot wordmarks-$1 --es uiscreen main
  for i in $(seq 20); do sleep 1; n1=$(sh "stat -c %s $f.txt 2>/dev/null"); [ -n "$n1" ] && [ "$n1" = "$n0" ] && break; n0=${n1:--1}; done
  $ADB pull "$f.png" $D/$1.png >/dev/null 2>&1; $ADB pull "$f.txt" $D/$1.txt >/dev/null 2>&1; sh "rm -f $f.png $f.txt"
  [ -s $D/$1.png ] && [ -s $D/$1.txt ] || { echo "-"; return; }
  { grep -qxF 'Bom dia, tudo bem?' $D/$1.txt || grep -qxF 'dia,' $D/$1.txt; } && grep -qxF 'Где здесь аптека?' $D/$1.txt || { echo "-"; return; }
  "$PY" $D/px.py $D/$1.png
}
restore() {
  say "== возврат"
  $ADB shell "am force-stop $PKG"; sleep 2      # подача записи могла ещё идти
  launch --es listen off --es silent 0 --es feedonly 0 ${REF:+--es refineevery $REF} ${CLOUD:+--es cloudevery $CLOUD} --es micautodb $AUTO0; sleep 3
  if [ -s $D/whatsnew ]; then local m; m=$(count $LOG); launch "--es whatsnew 'put:$(cat $D/whatsnew)'"; wl "$m" '🧪 что нового:' 15 >/dev/null; fi
  $ADB shell "am force-stop $PKG"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1; done
  $ADB shell "rm -f $F/chats/$TID.json $F/chats/$TID2.json $F/replay.wav $F/wordmarks-*.png $F/wordmarks-*.txt"
  say "  тестовые разговоры удалены · текущим снова станет: $(sh "ls -t $F/chats/ | head -1") · разбор $REF, облако $CLOUD"
  sh "dumpsys power" | grep -qE "mWakefulness=(Asleep|Dozing)" || launch
  [ -n "$KEEP" ] && say "  снимки и разговор: $D (там же снимок выученного — удалить после)" || rm -rf "$D"
  say; say "итог: PASS $pass, FAIL $fail, ПРОПУСК $skip"
}
# Падения Falar за проверку — по logcat с начала прогона.
T0=$($ADB shell "date '+%m-%d %H:%M:%S.000'" 2>/dev/null | tr -d '\r')
crashed() { $ADB shell "logcat -d -v time -t '$T0'" 2>/dev/null | tr -d '\r' | grep -A3 'FATAL EXCEPTION' | grep -A1 'Process: app.falar' | grep -vE 'Process:|^--' | head -1 | cut -c1-160; }

$ADB wait-for-device; free_phone
trap restore EXIT
if [ "$INSTALL" = 1 ]; then
  apk=${APK:-$A/Falar.apk}
  want=$(aapt2 dump badging "$apk" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
  have=$(sh "dumpsys package $PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  [ -n "$have" ] && [ -n "$want" ] && [ "$want" -lt "$have" ] && { say "на телефоне сборка $have, эта — $want: понижение стёрло бы данные, не ставлю"; exit 1; }
  r=$($ADB install --no-incremental -r "$apk" 2>&1 | tail -1); say "== ставлю $apk ($want поверх ${have:-нет}): $r"
  case "$r" in Success*) ;; *) exit 1;; esac
  free_phone
fi
orig_settings
# «Что нового»: окно, ждущее показа (поставили новую сборку), легло бы поверх экрана — прячется до возврата.
m=$(count $LOG); launch --es whatsnew state
wl "$m" '🧪 что нового:' 15 | sed -n 's/.*снимок //p' > $D/whatsnew
if [ -s $D/whatsnew ]; then w=$(cat $D/whatsnew); launch "--es whatsnew 'put:${w%|*|*}|0|${w##*|}'"; sleep 2; fi
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1; done

say "== Часть 1: запись $NAME — распознавание и пометки"
"$PY" -c "import json; json.dump({'id': $TID, 'name': 'ТЕСТ сомнительные слова', 'named': True, 'saved': $TID, 'turns': []}, open('$D/t.json','w',encoding='utf-8'), ensure_ascii=False)"
# Перезапуск: строка «отдаёт ли уверенность» пишется при первом распознавании после запуска службы.
$ADB shell "am force-stop $PKG"; sleep 1; $ADB push $D/t.json "$F/chats/$TID.json" >/dev/null 2>&1
$ADB push "$REC/room.wav" "$F/replay.wav" >/dev/null 2>&1 || { say "запись не легла на телефон"; exit 1; }
m0=$(count $LOG)
launch --es listen pt --es silent 1 --es feedonly 1 --es refineevery 0 --es cloudevery 0
for _ in $(seq 60); do sleep 2; [ "$(seen $m0 '🧩 модули:')" != 0 ] && [ "$(seen $m0 'микрофон:|слушаю')" != 0 ] && break; done
m1=$(count $LOG)
launch --es feedwav $F/replay.wav --es speed 2
wl "$m1" '▷ подаю' 30 >/dev/null || { res 1 "D0 подача записи не началась"; exit 1; }
wl "$m1" '▷ подача закончена|▷ ошибка подачи' 900 >/dev/null || say "  (конца подачи за 15 мин не дождался)"
# Перевод отстаёт от подачи: ждём, пока реплики перестанут прибавляться 20 с.
last=-1; for _ in $(seq 60); do n=$(seen $m1 '^.{0,12}#[0-9]+ pt2ru|^.{0,12}#[0-9]+ ru2pt'); [ "$n" = "$last" ] && break; last=$n; sleep 20; done
sh "tail -n +$((m0+1)) $LOG" > $D/log.txt
$ADB pull "$F/chats/$TID.json" $D/chat.json >/dev/null 2>&1 || { res 1 "D2 тестового разговора нет"; exit 1; }

if grep -q '🔎 распознаватель отдаёт уверенность в словах' $D/log.txt; then res 0 "D1 распознаватель отдаёт уверенность в словах"
elif grep -q '🔎 библиотека распознавателя без уверенности' $D/log.txt; then res 1 "D1 библиотека JNI без вероятностей токенов (tools/sherpa-jni)"
else res 1 "D1 строки об уверенности распознавателя нет (сборка без пометок?)"; fi
"$PY" - $D/log.txt $D/chat.json "$PC" > $D/d.txt <<'PY'
import json, re, sys
log, chat, pc = open(sys.argv[1], encoding='utf-8').read(), json.load(open(sys.argv[2], encoding='utf-8')), sys.argv[3]
lines = [l for l in log.splitlines() if '🔎 распознаватель не уверен' in l]
named = [w.lower() for l in lines for w in re.findall(r'«([^»]+)» [0-9.]+', l)]
turns = chat.get('turns', [])
words = sum(len(re.findall(r'\w+', t.get('src', ''))) for t in turns)
marked, bad, spans = sum(1 for t in turns if t.get('doubt')), [], 0
for t in turns:
    src = t.get('src', '')
    for part in filter(None, t.get('doubt', '').split(',')):
        a, b = (int(v) for v in part.split('-')); spans += 1
        if not (0 <= a < b <= len(src)) or src[a:b].lower() not in named: bad.append(f'{part} в «{src}»')
rate = 100.0 * len(named) / max(1, words)
print(f'реплик {len(turns)}, с пометками {marked}; слов {words}, сомнительных в журнале {len(named)} ({rate:.1f} %)'
      + (f', на ПК {pc} %' if pc else '') + f'; отрезков {spans}, не сошлось {len(bad)}')
print('D2', 0 if lines and marked else 1)
print('D3', 0 if spans and not bad else 1, '; '.join(bad[:3]))
print('D4', '-' if not pc or words < 100 else 0 if float(pc) / 3 <= rate <= float(pc) * 3 else 1)
PY
say "  $(head -1 $D/d.txt)"
v=$(sed -n 's/^D2 //p' $D/d.txt); res "$v" "D2 строки «распознаватель не уверен» в журнале и поле doubt у реплик"
v=$(sed -n 's/^D3 \([01]\).*/\1/p' $D/d.txt); bad=$(sed -n 's/^D3 [01] *//p' $D/d.txt)
res "$v" "D3 пометки разговора — на словах, названных в журнале${bad:+ (не сошлось: $bad)}"
v=$(sed -n 's/^D4 //p' $D/d.txt)
case "$v" in -) sk "D4 доля сомнительных: нет числа ПК для $NAME или меньше 100 слов";; *) res "$v" "D4 доля сомнительных слов — как на ПК (от трети до тройной)";; esac

say "== Часть 2: экран — цвет пометки в крупном тексте и в карточке"
# Текущий разговор — самый свежий файл (Chats.list): разговор части 1 убрать, когда Falar уже не может его записать.
$ADB shell "am force-stop $PKG"; sleep 1; $ADB shell "rm -f $F/chats/$TID.json"
n0=$(shot2 none); nb=$(shot2 big); nc=$(shot2 card)
say "  точек цвета пометки на снимке: без пометок $n0 · пометка собеседника $nb · своя $nc"
if [ "$n0" = - ]; then res 1 "D5 снимка разговора без пометок нет (или на нём не тот разговор)"
else
  [ "$nb" != - ] && [ $((nb - n0)) -ge 40 ] && res 0 "D5 сомнительное слово собеседника — цветом (+$((nb - n0)) точек)" || res 1 "D5 пометка собеседника не видна: $n0 → $nb точек"
  [ "$nc" != - ] && [ $((nc - n0)) -ge 15 ] && res 0 "D6 своё сомнительное слово в карточке — цветом (+$((nc - n0)) точек)" || res 1 "D6 своя пометка не видна: $n0 → $nc точек"
fi
c=$(crashed); [ -z "$c" ] && res 0 "D7 за проверку Falar не падал" || res 1 "D7 Falar упал: $c"
[ $fail = 0 ]
