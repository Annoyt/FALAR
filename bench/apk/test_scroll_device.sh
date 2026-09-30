#!/bin/bash
# Крупный текст не наплывает на соседние строки — на телефоне, в отдельном тестовом разговоре.
#
#   bash bench/apk/test_scroll_device.sh
#
# Последняя реплика длинная — крупный текст прокручивается. Прокручиваем его до середины и по
# снимку экрана считаем точки крупного шрифта там, где их быть не должно: выше прокрутки (между
# шапкой с названием разговора и текстом — строка «собеседник») и ниже неё (разделитель «ранее»
# над прежними репликами). Днём крупный шрифт почти чёрный, ночью почти белый — тема по фону. В 0.24.0 экран разговора перестал резать детей по краю — ради облака кнопки
# удержания, — и прокрутка без собственных отступов рисовала текст за своими границами: он ложился
# на соседние строки, а список под ним было не прокрутить. Перед снимком окно перерисовывается
# целиком (--es redraw 1): перерисовка обычно частичная, и вылезший текст виден не на каждом кадре —
# без этого проверка один раз прошла на сборке с ошибкой. Разговоры владельца не трогаются.
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
D=$(mktemp -d /tmp/falar-scroll.XXXX); TID=$(date +%s%3N)
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
front() { sh "dumpsys power" | grep -q "mWakefulness=Awake" && sh "dumpsys window" | grep -m1 mCurrentFocus | grep -q "$PKG/"; }
free_phone() {
  local n=0
  while :; do
    local f; f=$(sh "dumpsys window | grep -m1 mCurrentFocus")
    sh "dumpsys power" | grep -q "mWakefulness=Awake" && case "$f" in *$PKG*|*com.miui.home*|*launcher*) return 0;; esac
    n=$((n+1)); [ $n -eq 1 ] && say "  нужен включённый экран и Falar или лаунчер впереди ($f), жду…"; sleep 10
  done
}
count() { sh "test -f $1 && wc -l < $1 || echo 0" | awk '{print $1+0}'; }
restore() {
  say "== возврат"
  $ADB shell "am force-stop $PKG"; sleep 2
  $ADB shell "rm -f $F/chats/$TID.json"
  say "  тестовый разговор удалён · текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  $ADB shell "am start -n $ACT" >/dev/null 2>&1; rm -rf "$D"
  say; say "итог: PASS $pass, FAIL $fail"
}
trap restore EXIT

$ADB wait-for-device; free_phone
python3 - "$D/t.json" "$TID" <<'EOF'
import json, sys
path, tid = sys.argv[1], int(sys.argv[2])
long_pt = ("E durante o trabalho de parto a sua equipe vai estar atenta à monitorização da frequência cardíaca do bebê. "
           "Então vai garantir a segurança do bebê mesmo na presença da circular do cordão. ") * 4
long_ru = ("И во время родов ваша команда будет внимательно следить за сердцебиением ребёнка, "
           "чтобы обеспечить его безопасность даже при наличии петли пуповины. ") * 4
T = [("pt2ru", "Bom dia, tudo bem?", "Доброе утро, как дела?"), ("ru2pt", "Всё хорошо, спасибо.", "Tudo bem, obrigado."),
     ("pt2ru", "O cordão é muito resistente.", "Пуповина очень прочная."), ("pt2ru", long_pt.strip(), long_ru.strip())]
turns = [{"dir": d, "src": s, "dst": t, "at": tid + 1000 * (k + 1)} for k, (d, s, t) in enumerate(T)]
json.dump({"id": tid, "name": "ТЕСТ прокрутка", "named": True, "saved": tid, "turns": turns}, open(path, "w", encoding="utf-8"), ensure_ascii=False)
EOF
$ADB shell "am force-stop $PKG"; sleep 1; $ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
m=$(count $LOG); $ADB shell "am start -n $ACT" >/dev/null 2>&1
for _ in $(seq 60); do sleep 2; sh "tail -n +$((m+1)) $LOG" | grep -qE '🎚 (микрофон выключен|чувствительность)' && break; done; sleep 3
front || { say "впереди не Falar или экран погашен — проверять нечем"; exit 1; }

# Границы: название в шапке (id hint), прокрутка крупного текста, разделитель «ранее» над прежними репликами.
b=$(sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" | python3 -c "
import re,sys
x=sys.stdin.read()
def bounds(rx):
    m=re.search(rx+r'[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x); return list(map(int,m.groups())) if m else None
h=bounds(r'resource-id=\"app.falar:id/hint\"'); n=bounds(r'text=\"ранее\"')
sc=[list(map(int,g)) for g in re.findall(r'class=\"android.widget.ScrollView\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)]
s=[q for q in sc if h and n and q[1]>=h[3]-2 and q[3]<=n[1]+2]
print(*(h+s[0]+n) if h and n and s else '')")
[ -n "$b" ] || { res 1 "S0 на экране нет названия, прокрутки или «ранее»"; exit 1; }
read hx0 hy0 hx1 hy1 sx0 sy0 sx1 sy1 nx0 ny0 nx1 ny1 <<< "$b"
say "  название y $hy0–$hy1 · крупный текст y $sy0–$sy1 · «ранее» y $ny0–$ny1"

say "== S1: прокрутили крупный текст до середины — он остаётся в своих границах"
front && $ADB shell "input swipe 540 $((sy1 - 60)) 540 $((sy0 + 60 + (sy1 - sy0) / 3)) 600"; sleep 1.5
$ADB shell "am start -n $ACT --es redraw 1" >/dev/null 2>&1; sleep 1
$ADB exec-out screencap -p > $D/s.png; cp $D/s.png /tmp/falar-scroll.png
# Точки цвета крупного шрифта: днём он почти чёрный на белом (яркость < 50), ночью — почти белый на
# тёмном (> 200). Тема — по фону под шапкой. Остальное на этих полосах серое: «собеседник», «ранее»,
# черты разделителя. Выше прокрутки считаем от низа шапки: сама шапка — сливовая, днём тёмная.
dark() { python3 -c "
from PIL import Image
im=Image.open('$D/s.png').convert('L'); w,h=im.size
night=im.getpixel((6,($hy1+$sy0)//2))<100
print(sum(1 for y in range($1,$2) for x in range(30,w-30,2) if (im.getpixel((x,y))>200 if night else im.getpixel((x,y))<50)))"; }
up=$(dark $hy1 $sy0); down=$(dark $ny0 $ny1)
say "  точек крупного шрифта выше прокрутки: $up, в строке «ранее»: $down"
[ "$up" -lt 40 ] && res 0 "S1 выше прокрутки крупного текста нет ($up)" || res 1 "S1 крупный текст залез на строку названия ($up)"
[ "$down" -lt 40 ] && res 0 "S1 ниже прокрутки крупного текста нет ($down)" || res 1 "S1 крупный текст залез на подсказку под собой ($down)"
say "  снимок экрана: /tmp/falar-scroll.png"
