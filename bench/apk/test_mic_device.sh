#!/bin/bash
# Круглая кнопка удержания на телефоне — в отдельном тестовом разговоре.
#
#   bash bench/apk/test_mic_device.sh
#
# Проверяется настоящим касанием: кнопка видна в режиме удержания и лежит над списком; касание
# мимо круга (в углу квадрата кнопки) запись не начинает; удержание круга начинает запись, и
# круг в это время окрашен тем, как слышно (в тихой комнате молчат — красный), а микрофон белый;
# после отпускания — снова сливовый, а как слышно эту фразу — словами в журнале. Удержание пишет комнату, поэтому
# разговор тестовый, а выученное и словари возвращаются из снимка. Разговоры владельца не трогаются.
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
D=$(mktemp -d /tmp/falar-mic.XXXX); SNAP=$D/snap; mkdir -p $SNAP; TID=$(date +%s%3N)
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
free_phone() {
  local n=0
  while :; do
    local f; f=$(sh "dumpsys window | grep -m1 mCurrentFocus")
    case "$f" in *$PKG*|*com.miui.home*|*launcher*|*mCurrentFocus=null*) return 0;; esac
    n=$((n+1)); [ $n -eq 1 ] && say "  телефон занят ($f), жду…"; sleep 10
  done
}
front() { sh "dumpsys power" | grep -q "mWakefulness=Awake" && sh "dumpsys window" | grep -m1 mCurrentFocus | grep -q "$PKG/"; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
since() { sh "tail -n +$(($1+1)) $LOG"; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
# Цвет пикселя снимка экрана: «r g b».
px() { python3 -c "from PIL import Image; print(*Image.open('$1').convert('RGB').getpixel(($2,$3)))"; }
mint() { python3 -c "import sys; r,g,b=map(int,'$1'.split()); sys.exit(0 if g>180 and b>150 and r<150 else 1)"; }
white() { python3 -c "import sys; r,g,b=map(int,'$1'.split()); sys.exit(0 if min(r,g,b)>215 else 1)"; }
# Сливовый круг поверх светлого фона: красный и синий заметно выше зелёного, красный ненамного выше синего.
plum() { python3 -c "import sys; r,g,b=map(int,'$1'.split()); sys.exit(0 if r>g+25 and b>g+10 and r-b<55 else 1)"; }
restore() {
  say "== возврат"
  $ADB shell "am force-stop $PKG"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1; done
  $ADB shell "rm -f $F/chats/$TID.json"; say "  тестовый разговор удалён · текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  $ADB shell "am start -n $ACT" >/dev/null 2>&1; rm -rf "$D"
  say; say "итог: PASS $pass, FAIL $fail"
}
trap restore EXIT

$ADB wait-for-device; free_phone
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1; done
python3 - "$D/t.json" "$TID" <<'EOF'
import json, sys
path, tid = sys.argv[1], int(sys.argv[2])
T = [("pt2ru", "Bom dia, tudo bem?", "Доброе утро, как дела?"), ("ru2pt", "Всё хорошо, спасибо.", "Tudo bem, obrigado.")]
turns = [{"dir": d, "src": s, "dst": t, "at": tid + 1000 * (k + 1)} for k, (d, s, t) in enumerate(T)]
json.dump({"id": tid, "name": "ТЕСТ кнопка", "named": True, "saved": tid, "turns": turns}, open(path, "w", encoding="utf-8"), ensure_ascii=False)
EOF
$ADB shell "am force-stop $PKG"; sleep 1; $ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
m=$(mark); $ADB shell "am start -n $ACT" >/dev/null 2>&1; wl "$m" 'микрофон выключен' 120 >/dev/null; sleep 2
front || { say "впереди не Falar или экран погашен — касаться нельзя"; exit 1; }
# Режим удержания на 40 с — стендовый показ отпущенной позиции; касания ниже идут по-настоящему.
$ADB shell "am start -n $ACT --es micanim idle --es micsec 40" >/dev/null 2>&1; sleep 2

say "== M1: кнопка видна в режиме удержания"
b=$(sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" | python3 -c "
import re,sys
x=sys.stdin.read()
m=re.search(r'content-desc=\"Удерживайте и говорите[^\"]*\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)
print(*m.groups() if m else '')")
[ -n "$b" ] && res 0 "M1 кнопка на экране: $b" || { res 1 "M1 кнопки нет в дереве экрана"; exit 1; }
read x0 y0 x1 y1 <<< "$b"; cx=$(( (x0 + x1) / 2 )); cy=$(( (y0 + y1) / 2 )); half=$(( (x1 - x0) / 2 ))
body=$(( cy - half * 14 / 110 ))            # середина тела микрофона: 3,5 клетки над центром сетки, клетка = 0,40·R/11
dx=$(( cx + half * 45 / 100 )); dy=$(( cy - half * 45 / 100 ))   # круг между разделителем и надписями, на 45° — без букв

say "== M2: касание мимо круга запись не начинает"
m=$(mark); front && $ADB shell "input tap $(( cx + half * 92 / 100 )) $(( cy + half * 92 / 100 ))"; sleep 2
since "$m" | grep -q '● запись' && res 1 "M2 касание в углу кнопки начало запись" || res 0 "M2 касание в углу кнопки ушло мимо"

say "== M3: удержание круга — запись, микрофон мятный"
m=$(mark)
front && { $ADB shell "input swipe $cx $cy $cx $cy 2500" & sleep 1.2; $ADB exec-out screencap -p > $D/held.png; wait; }
c=$(px $D/held.png $cx $body); k=$(px $D/held.png $dx $dy)
wl "$m" '● запись' 5 >/dev/null && res 0 "M3 удержание начало запись" || res 1 "M3 удержание запись не начало"
white "$c" && res 0 "M3 в нажатой позиции микрофон белый ($c)" || res 1 "M3 микрофон в нажатой позиции не белый ($c)"
plum "$k" && res 1 "M3 круг остался сливовым ($k) — цвета «как слышно» нет" || res 0 "M3 круг окрашен тем, как слышно ($k)"
cp $D/held.png /tmp/falar-mic-held.png

say "== M4: отпустили — снова отпущенная позиция"
sleep 1.5; $ADB exec-out screencap -p > $D/up.png; c=$(px $D/up.png $cx $body); k=$(px $D/up.png $dx $dy)
white "$c" && res 1 "M4 микрофон остался белым ($c)" || res 0 "M4 микрофон снова не белый ($c)"
plum "$k" && res 0 "M4 круг снова сливовый ($k)" || res 1 "M4 круг не сливовый после отпускания ($k)"
wl "$m" 'тишина / не распознано|→|не похоже' 20 | cut -c1-120 | sed 's/^/  после отпускания: /'
cp $D/up.png /tmp/falar-mic-up.png

say "== M5: как слышно эту фразу — словами в журнале"
h=$(wl "$m" '🎚 (слышно|шумно|тихо|громко|перегруз|речи не)' 10 | cut -c10-)
[ -n "$h" ] && res 0 "M5 в журнале: «$h»" || res 1 "M5 итога фразы в журнале нет"
