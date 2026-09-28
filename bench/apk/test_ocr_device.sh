#!/bin/bash
# Офлайн-чтение снимков на телефоне: докачка моделей, точность на наборе вывесок, время, наложение.
#
#   bash bench/apk/test_ocr_device.sh [серийный номер]
#
# O1 модели чтения докачиваются сами (ярус auto) — как у обновившегося с 0.23: models/ocr на
#    телефоне удаляется, приложение запускается с локальным источником (tools/models_serve.py через
#    adb reverse — USB-стенд считается Wi-Fi телефона), ждём «чтение снимков готово».
# O2 набор bench/ocr (32 снимка вывесок) читается на телефоне (--es ocrbench): вывод сверяется с
#    расшифровкой (tools/ocr_eval.py --got) и с эталоном на столе — строка в строку; время — из журнала.
# O3 весь путь снимка (--es photofile) в отдельном тестовом разговоре: реплика «📷» со снимком и
#    абзацами, без озвучки, в выученное не копится; на экране — перевод поверх снимка (снимок экрана).
#
# Разговоры владельца не трогаются: тестовый разговор — отдельным файлом с самым свежим временем,
# в конце удаляется вместе со своими снимками; выученное и словари возвращаются из снимка до проверки.
# Телефон — рабочий телефон человека: запуск приложения только когда впереди Falar, рабочий стол
# или экран погашен; экран блокировки не трогаем никогда.
R=$(cd "$(dirname "$0")/../.." && pwd)
SER=${1:-}; ADB="$R/tools/platform-tools/adb${SER:+ -s $SER}"
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log; TSV=$F/at.tsv
BASE=http://127.0.0.1:8765; SRV=""; PY=$R/.venv/bin/python
D=$(mktemp -d /tmp/falar-ocr.XXXX); SNAP=$D/snap; mkdir -p $SNAP $D/got; TID=$(date +%s%3N)
pass=0; fail=0; skip=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sk() { skip=$((skip+1)); say "ПРОПУСК $1"; }
sh() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
free_phone() {
  local n=0
  while :; do
    local f; f=$(sh "dumpsys window | grep -m1 mCurrentFocus"); local w; w=$(sh "dumpsys power | grep -m1 mWakefulness")
    case "$f" in *$PKG*|*com.miui.home*|*launcher*|*mCurrentFocus=null*) return 0;; esac
    case "$w" in *Asleep*|*Dozing*) return 0;; esac
    n=$((n+1)); [ $n -eq 1 ] && say "  телефон занят ($f), жду…"; sleep 10
  done
}
start_app() { free_phone; $ADB shell "am start -n $ACT $*" >/dev/null 2>&1; }
front() { sh "dumpsys power" | grep -q "mWakefulness=Awake" && sh "dumpsys window" | grep -m1 mCurrentFocus | grep -q "$PKG/"; }
mark() { sh "wc -l < $1" | awk '{print $1+0}'; }
# ожидание строки журнала после метки; шаблон расширенный (grep телефона не понимает «\|» в простом)
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
serve() { stop_serve; python3 $R/tools/models_serve.py > $D/serve.log 2>&1 & SRV=$!; sleep 0.7; grep -q "источник моделей" $D/serve.log || { say "  сервер не поднялся:"; cat $D/serve.log; exit 2; }; }
stop_serve() { [ -n "$SRV" ] && kill $SRV 2>/dev/null; SRV=""; for p in $(ss -ltnp 2>/dev/null | grep ':8765 ' | grep -o 'pid=[0-9]*' | cut -d= -f2); do kill $p 2>/dev/null; done; sleep 0.3; }
size() { sh "stat -c %s $F/models/$1 2>/dev/null || echo 0" | awk '{print $1+0}'; }

restore() {
  say "== возврат"
  stop_serve
  free_phone; $ADB shell "am force-stop $PKG"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1
  done
  # снимки тестового разговора — по его файлу, чужие не трогаем
  if $ADB pull "$F/chats/$TID.json" "$D/last.json" >/dev/null 2>&1; then
    for p in $(python3 -c "import json; print(' '.join(t['photo']['file'] for t in json.load(open('$D/last.json', encoding='utf-8'))['turns'] if 'photo' in t))"); do
      $ADB shell "rm -f $F/photos/$p"; done
  fi
  $ADB shell "rm -f $F/chats/$TID.json; rm -rf $F/ocrbench $F/ocrtest.jpg"
  say "  тестовый разговор и его снимки удалены · текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  start_app; rm -rf "$D"
  say; say "итог: PASS $pass, FAIL $fail, пропущено $skip"
}
trap restore EXIT

$ADB wait-for-device; free_phone
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1; done
LEARN0=$(sha256sum "$SNAP/learned.json" 2>/dev/null | cut -c1-16)

say "== O1: модели чтения докачиваются сами — как у обновившегося"
$ADB shell "am force-stop $PKG"; sleep 1
$ADB shell "rm -rf $F/models/ocr"
$ADB reverse tcp:8765 tcp:8765 >/dev/null; serve
m=$(mark $LOG); t0=$(date +%s); start_app --es modelsbase $BASE
l=$(wl "$m" '📦 модели' 400); say "  $l"
printf '%s' "$l" | grep -qE "обязательных на месте ([0-9]+)/\1" && res 0 "O1 без моделей чтения обязательное на месте — экрана первой загрузки нет" || res 1 "O1 обязательного не хватает"
a=$(wl "$m" 'докачиваю чтение снимков' 200); say "  $a"
[ -n "$a" ] && res 0 "O1 докачка началась сама" || res 1 "O1 докачка не началась"
d=$(wl "$m" 'чтение снимков готово' 300); say "  $d  ($(( $(date +%s) - t0 )) с от запуска)"
[ -n "$d" ] && res 0 "O1 модели на месте, сказано об этом" || res 1 "O1 докачка не закончилась"
ok=1; for f in det.onnx rec.onnx; do [ "$(size ocr/$f)" = "$(stat -c %s $R/models/ocr/$f)" ] || ok=0; done
[ $ok = 1 ] && res 0 "O1 оба файла совпали по размеру (хэш сверило приложение)" || res 1 "O1 файлы не те"
stop_serve

say "== O2: набор вывесок (bench/ocr) — точность и время на телефоне"
if [ ! -d $R/bench/ocr/photos ] || [ ! -d $R/bench/ocr/runs/ref ]; then sk "O2 нет снимков или эталона на столе (bench/ocr/photos, bench/ocr/runs/ref)";
else
  $ADB shell "rm -rf $F/ocrbench; mkdir -p $F/ocrbench"
  $ADB push $R/bench/ocr/photos/*.jpg $F/ocrbench/ >/dev/null 2>&1
  m=$(mark $LOG); mt=$(mark $TSV); start_app --es ocrbench $F/ocrbench
  l=$(wl "$m" '🧪 снимки:' 900); say "  $l"
  for f in $(sh "ls $F/ocrbench | grep -E '\.(txt|para)$'"); do $ADB pull "$F/ocrbench/$f" "$D/got/" >/dev/null 2>&1; done
  n=$(ls $D/got/*.txt 2>/dev/null | wc -l)
  [ "$n" = 32 ] && res 0 "O2 прочитаны все 32 снимка" || res 1 "O2 прочитано $n из 32"
  e=$($PY $R/tools/ocr_eval.py --got $D/got --ref $R/bench/ocr/runs/ref 2>&1); say "  $(printf '%s' "$e" | sed 's/^/  /')"
  w=$(printf '%s' "$e" | grep -oE 'слова [0-9.]+%' | head -1 | grep -oE '[0-9.]+')
  awk -v w="$w" 'BEGIN{exit !(w+0 >= 90)}' && res 0 "O2 слов прочитано $w % (на столе 93,3 %)" || res 1 "O2 слов прочитано ${w:-?} %"
  same=$(printf '%s' "$e" | grep -oE 'строка в строку [0-9]+' | grep -oE '[0-9]+')
  [ "${same:-0}" -ge 28 ] && res 0 "O2 с эталоном стола строка в строку совпали $same из 32" || res 1 "O2 с эталоном совпали ${same:-0} из 32"
  sh "tail -n +$((mt+1)) $TSV" | awk -F'\t' '$2=="ocrbench"{print $3"\t"$4"x"$5"\tрамок "$6"\tмодели "$7"\tдетектор "$8"\tраспознаватель "$9"\tвсего "$11" мс"}' > $D/times.tsv
  say "  время по снимкам (худшие пять):"; sort -t$'\t' -k7 -V $D/times.tsv | tail -5 | sed 's/^/    /'
fi

say "== O3: весь путь снимка в тестовом разговоре — реплика «📷», без звука, наложение"
python3 - "$D/t.json" "$TID" <<'EOF'
import json, sys
path, tid = sys.argv[1], int(sys.argv[2])
json.dump({"id": tid, "name": "ТЕСТ снимок", "named": True, "saved": tid,
           "turns": [{"dir": "pt2ru", "src": "Olá, tudo bem?", "dst": "Привет, как дела?", "at": tid + 1000}]},
          open(path, "w", encoding="utf-8"), ensure_ascii=False)
EOF
free_phone; $ADB shell "am force-stop $PKG"; sleep 1; $ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
$ADB push $R/bench/ocr/photos/p03.jpg $F/ocrtest.jpg >/dev/null 2>&1
m=$(mark $LOG); start_app; wl "$m" 'движки загружены|микрофон выключен|📦 модели' 120 >/dev/null; sleep 3
m=$(mark $LOG); start_app --es photofile $F/ocrtest.jpg
l=$(wl "$m" '📷 OCR за|📷 не вышло|текста не нашлось' 120); say "  $l"
printf '%s' "$l" | grep -q "📷 OCR за" && res 0 "O3 снимок прочитан и переведён" || res 1 "O3 снимок не прочитан"
sleep 2
if $ADB pull "$F/chats/$TID.json" "$D/after.json" >/dev/null 2>&1; then
  j=$(python3 - "$D/after.json" <<'EOF'
import json, sys
o = json.load(open(sys.argv[1], encoding='utf-8')); ph = [t for t in o['turns'] if 'photo' in t]
if not ph: print('none'); sys.exit()
t = ph[-1]; b = t['photo']['blocks']
print(t['photo']['file'], len(b), sum(1 for x in b if x.get('dst')), '|', t['dst'].replace('\n', ' / '))
EOF
); say "  $j"
  printf '%s' "$j" | grep -qE '^p[0-9]+\.jpg [1-9]' && res 0 "O3 в тестовом разговоре — реплика «📷» со снимком и абзацами" || res 1 "O3 реплики со снимком нет"
  pf=$(printf '%s' "$j" | awk '{print $1}')
  [ "$(sh "ls $F/photos/$pf 2>/dev/null")" != "" ] && res 0 "O3 снимок лежит в каталоге снимков приложения" || res 1 "O3 файла снимка нет"
  printf '%s' "$j" | grep -qi "дорог" && res 0 "O3 перевод по смыслу: «$(printf '%s' "$j" | cut -d'|' -f2 | cut -c1-80)»" || res 1 "O3 перевод: $j"
fi
since=$(sh "tail -n +$((m+1)) $LOG")
printf '%s' "$since" | grep -qE "🔊|озвуч" && res 1 "O3 снимок озвучивался" || res 0 "O3 без озвучки"
LEARN1=$(sh "sha256sum $F/models/learned.json" | cut -c1-16)
[ "$LEARN0" = "$LEARN1" ] && res 0 "O3 выученное не тронуто" || res 1 "O3 выученное изменилось"
if front; then
  x=$(sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml")
  printf '%s' "$x" | grep -q "касание абзаца" && res 0 "O3 на экране перевод поверх снимка" || res 1 "O3 наложение не открылось"
  $ADB exec-out screencap -p > /tmp/falar-ocr-overlay.png 2>/dev/null && say "  снимок экрана: /tmp/falar-ocr-overlay.png"
  $ADB shell input keyevent KEYCODE_BACK; sleep 1
else sk "O3 экран не проверен: погашен или впереди не Falar"; fi
