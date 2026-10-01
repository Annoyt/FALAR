#!/bin/bash
# Круглая кнопка удержания на телефоне — в отдельном тестовом разговоре.
#
#   bash bench/apk/test_mic_device.sh
#
# Проверяется настоящим касанием: кнопка видна в режиме удержания и лежит над списком; касание
# мимо круга (в углу квадрата кнопки) запись не начинает; удержание круга начинает запись; пока
# речи не было, цвета нет (круг сливовый, микрофон мятный), а молчат полторы секунды — круг
# красный, микрофон белый; после отпускания — снова сливовый, а как слышно эту фразу — словами в
# журнале. M6 — речь с паузами: вместо микрофона телефон играет запись комнаты (--es micfile, три
# фразы near-pt с паузами по 1,5 с), и в паузах круг должен оставаться зелёным, а не мигать
# оранжевым (владелец 29.09). Удержание пишет в разговор, поэтому разговор тестовый, озвучка
# выключена, а выученное и словари возвращаются из снимка. Разговоры владельца не трогаются.
#
# Телефон — рабочий аппарат владельца: Falar впереди ещё не значит «свободен». Ждём, пока журнал
# приложения молчит три минуты (29.09 владелец разговаривал через Falar, а экран был включён).
R=$(cd "$(dirname "$0")/../.." && pwd)
# Один прогон на телефоне за раз — и отдельный скрипт, и test_all_device.sh (01.10 две копии test_ui
# девять минут касались телефона одновременно).
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand"; exec 9>"${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
D=$(mktemp -d /tmp/falar-mic.XXXX); SNAP=$D/snap; mkdir -p $SNAP; TID=$(date +%s%3N)
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }   # не из stdin: внутри «while read» adb съел бы его
# Сколько секунд журнал приложения молчит.
quiet_s() { sh "echo \$(( \$(date +%s) - \$(stat -c %Y $LOG 2>/dev/null || echo 0) ))"; }
free_phone() {
  local n=0
  while :; do
    local f; f=$(sh "dumpsys window | grep -m1 mCurrentFocus")
    case "$f" in *$PKG*|*com.miui.home*|*launcher*|*mCurrentFocus=null*)
      [ "$(quiet_s)" -ge 180 ] && return 0; f="журнал Falar писался $(quiet_s) с назад";; esac
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
# Красный круг «как слышно» (оттенок у нуля) — не сливовый: у сливы синий заметно выше зелёного.
red() { python3 -c "import sys,colorsys; r,g,b=map(int,'$1'.split()); h=colorsys.rgb_to_hsv(r/255,g/255,b/255)[0]*360; sys.exit(0 if (h<20 or h>345) and r>g+40 and abs(g-b)<20 else 1)"; }
restore() {
  say "== возврат"
  $ADB shell "am force-stop $PKG"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1; done
  $ADB shell "rm -rf $F/chats/$TID.json $F/live.wav $F/sil.wav /data/local/tmp/falar_px.sh /data/local/tmp/falar_px.raw"; say "  тестовый разговор удалён · текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
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
# Режим удержания на 150 с — стендовый показ отпущенной позиции на весь прогон M1–M6 (второй показ
# поверх первого не продлевает его: первый по своему сроку вернул бы прежний режим посреди M6);
# касания ниже идут по-настоящему.
$ADB shell "am start -n $ACT --es micanim idle --es micsec 150" >/dev/null 2>&1; sleep 2

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

# Удержание со звуком из файла (--es micfile) и цветом кнопки по ходу: снимки экрана без сжатия
# на самом телефоне, два раза в секунду (png идёт 2,3 с на снимок — в паузу не попадает), с
# каждого — цвет круга и микрофона; время — от первого кадра удержания (micfile_begin в at.tsv).
# hold <wav> <мс> <имя> — печатает «с_от_начала r g b(круг) r g b(микрофон)» в $D/<имя>.px.
cat > $D/px.sh <<'PHONE'
S=$1; X1=$2; Y1=$3; X2=$4; Y2=$5; F=/data/local/tmp/falar_px.raw
end=$(( $(date +%s) + S ))
while [ $(date +%s) -lt $end ]; do
  t=$(date +%s%3N); screencap $F
  w=$(od -An -tu4 -N4 $F | tr -d ' ')
  a=$(dd if=$F bs=4 skip=$(( 4 + Y1 * w + X1 )) count=1 2>/dev/null | od -An -tu1 | cut -c1-12)
  b=$(dd if=$F bs=4 skip=$(( 4 + Y2 * w + X2 )) count=1 2>/dev/null | od -An -tu1 | cut -c1-12)
  echo $t $a $b
done
rm -f $F
PHONE
$ADB push $D/px.sh /data/local/tmp/falar_px.sh >/dev/null 2>&1
hold() {
  local m; m=$(mark); $ADB shell "am start -n $ACT --es micfile $F/$(basename $1) --es silent 1" >/dev/null 2>&1
  wl "$m" 'стенд: вместо микрофона будет' 10 >/dev/null || return 1
  sleep 1; front || return 1
  $ADB shell "sh /data/local/tmp/falar_px.sh $(( $2 / 1000 + 2 )) $dx $dy $cx $body" > $D/$3.raw &
  sleep 0.3; $ADB shell "date +%s%3N > /data/local/tmp/falar_sw; input swipe $cx $cy $cx $cy $2"; wait
  local t0 sw; t0=$(sh "tail -n 400 $F/at.tsv | grep micfile_begin | tail -1" | cut -f1); sw=$(sh "cat /data/local/tmp/falar_sw; rm -f /data/local/tmp/falar_sw")
  LC_ALL=C awk -v t0="$t0" 'NF == 7 { printf "%.2f %s %s %s %s %s %s\n", ($1 - t0) / 1000, $2, $3, $4, $5, $6, $7 }' $D/$3.raw > $D/$3.px
  # Конец удержания в тех же секундах от первого кадра: касание начинается раньше, чем запись взяла
  # первый кадр (на 0,3–0,6 с), и снимки после отпускания в окно удержания попадать не должны.
  LC_ALL=C awk -v t0="$t0" -v sw="$sw" -v ms="$2" 'BEGIN { printf "%.2f\n", (sw + ms - t0) / 1000 }' > $D/$3.end
}
# Круг: сливовый (цвета нет), иначе оттенок: зелёный 70–170°, жёлтый 45–70°, красный < 20° или > 345°.
cls() { python3 -c "
import colorsys, sys
r, g, b = map(int, sys.argv[1:4])
if r > g + 25 and b > g + 10 and r - b < 55: print('нет_цвета'); sys.exit()
h = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)[0] * 360
print('зелёный' if 70 <= h <= 170 else 'жёлтый' if 45 <= h < 70 else 'красный' if h < 20 or h > 345 else 'оранжевый')" $1 $2 $3; }

say "== M3: удержание, в котором молчат (тишина комнаты вместо микрофона): сначала без цвета, через 1,5 с — красный"
python3 $R/bench/air/live_wav.py $R/bench/air/rec/near-pt $D/sil.wav --silence 5 >/dev/null
$ADB push $D/sil.wav $F/sil.wav >/dev/null 2>&1
m=$(mark); hold $D/sil.wav 4000 m3 || { res 1 "M3 удержание не прошло (запись не взялась или впереди не Falar)"; exit 1; }
wl "$m" '● запись' 5 >/dev/null && res 0 "M3 удержание начало запись" || res 1 "M3 удержание запись не начало"
early=0; earlyok=0; late=0; lateok=0
while read x r g b mr mg mb; do
  c=$(cls $r $g $b); say "  $x с: круг $c ($r $g $b), микрофон $mr $mg $mb"
  if python3 -c "import sys; sys.exit(0 if 0.1 <= $x <= 1.2 else 1)"; then early=$((early+1)); [ "$c" = нет_цвета ] && mint "$mr $mg $mb" && earlyok=$((earlyok+1)); fi
  if python3 -c "import sys; sys.exit(0 if 2.0 <= $x <= min(3.7, $(cat $D/m3.end) - 0.2) else 1)"; then late=$((late+1)); [ "$c" = красный ] && white "$mr $mg $mb" && lateok=$((lateok+1)); fi
done < $D/m3.px
[ $early -ge 1 ] && [ $earlyok = $early ] && res 0 "M3 первую секунду цвета нет: круг сливовый, микрофон мятный ($earlyok из $early)" || res 1 "M3 в первую секунду уже цвет или нет снимков: так $earlyok из $early"
[ $late -ge 1 ] && [ $lateok = $late ] && res 0 "M3 молчат дольше 1,5 с — круг красный, микрофон белый ($lateok из $late)" || res 1 "M3 после 1,5 с тишины не красный: так $lateok из $late"
cp $D/m3.px /tmp/falar-mic-m3.px

say "== M4: отпустили — снова отпущенная позиция"
sleep 1; $ADB exec-out screencap -p > $D/up.png; c=$(px $D/up.png $cx $body); k=$(px $D/up.png $dx $dy)
white "$c" && res 1 "M4 микрофон остался белым ($c)" || res 0 "M4 микрофон снова не белый ($c)"
plum "$k" && res 0 "M4 круг снова сливовый ($k)" || res 1 "M4 круг не сливовый после отпускания ($k)"
wl "$m" 'тишина / не распознано|→|не похоже' 20 | cut -c1-120 | sed 's/^/  после отпускания: /'

say "== M5: как слышно эту фразу — словами в журнале"
h=$(wl "$m" '🎚 (слышно|шумно|тихо|громко|перегруз|речи не)' 10 | cut -c10-)
case "$h" in *"речи не слышно"*) res 0 "M5 в журнале: «$h»";; *) res 1 "M5 молчали, а в журнале не «речи не слышно»: «$h»";; esac

say "== M6: речь с паузами (три фразы near-pt, паузы по 1,5 с) — в паузах цвет держится"
python3 $R/bench/air/live_wav.py $R/bench/air/rec/near-pt $D/live.wav 10 > $D/live.json
$ADB push $D/live.wav $F/live.wav >/dev/null 2>&1
L=$(python3 -c "import json; print(int(json.load(open('$D/live.json'))['len'] * 1000) + 400)")
m=$(mark); hold $D/live.wav $L m6 || { res 1 "M6 удержание не прошло (запись не взялась или впереди не Falar)"; exit 1; }
wl "$m" 'стенд: удержание слышит запись' 3 >/dev/null && res 0 "M6 удержание слушало запись, а не комнату" || res 1 "M6 удержание не взяло запись"
python3 - "$D" > $D/m6.txt <<'PY'
import colorsys, json, sys
d = sys.argv[1]; lab = json.load(open(d + '/live.json')); sp, pa = lab['speech'], lab['pauses']
def cls(r, g, b):
    if r > g + 25 and b > g + 10 and r - b < 55: return 'нет цвета', None
    h = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)[0] * 360
    return ('зелёный' if 70 <= h <= 170 else 'жёлтый' if 45 <= h < 70 else 'красный' if h < 20 or h > 345 else 'оранжевый'), h
rows, colored, bad, in_pause, early_bad, n = [], False, 0, 0, 0, 0
for line in open(d + '/m6.px'):
    x, r, g, b, mr, mg, mb = line.split(); x = float(x); r, g, b = int(r), int(g), int(b)
    if x < 0.05 or x > lab['len']: continue            # до нажатия и после отпускания — не удержание
    n += 1
    # Звук фразы в записи начинается на ~0,3 с позже разметки (динамик); в паузе — запас 0,4 с.
    where = 'до речи' if x < sp[0][0] + 0.3 else 'пауза' if any(a + 0.4 <= x <= e for a, e in pa) else 'речь'
    c, h = cls(r, g, b)
    if c != 'нет цвета': colored = True
    if where == 'до речи' and c in ('красный', 'оранжевый'): early_bad += 1
    if colored and where != 'до речи':
        if c != 'зелёный': bad += 1
        if where == 'пауза': in_pause += 1
    rows.append('%6.2f с  %-7s  %-9s %s  микрофон %s %s %s' % (x, where, c, '' if h is None else '(оттенок %3.0f°)' % h, mr, mg, mb))
print('\n'.join(rows))
print('ИТОГ %d %d %d %d' % (n, bad, in_pause, early_bad))
PY
sed -n '/^ИТОГ/!s/^/  /p' $D/m6.txt
read _ shots bad inpause early <<< "$(grep '^ИТОГ' $D/m6.txt)"
[ "${early:-1}" = 0 ] && res 0 "M6 до речи — без красного и оранжевого" || res 1 "M6 до речи кнопка уже красная или оранжевая ($early снимков)"
[ "${inpause:-0}" -ge 2 ] && [ "${bad:-1}" = 0 ] && res 0 "M6 после появления цвета — только зелёный, в том числе на $inpause снимках в паузах (всего снимков $shots)" \
  || res 1 "M6 после появления цвета не зелёных снимков: $bad, в паузах снимков: $inpause (нужно ≥ 2), всего $shots"
h=$(wl "$m" '🎚 (слышно|шумно|тихо|громко|перегруз|речи не)' 30 | cut -c10-)
case "$h" in *"слышно хорошо"*) res 0 "M6 итог фразы: «$h»";; *) res 1 "M6 итог фразы не «слышно хорошо»: «$h»";; esac
wl "$m" '→' 30 | cut -c1-140 | sed 's/^/  распознано: /'
cp $D/m6.txt /tmp/falar-mic-live.txt; cp $D/m6.px /tmp/falar-mic-m6.px
