#!/bin/bash
# Модули на телефоне: выключенный модуль прячется и отдаёт память, файлы остаются; удаление —
# только отдельным действием; включённый докачивается сам.
#
#   bash bench/apk/test_modules_device.sh [серийный номер]
#
# D1 набор модулей виден в журнале (--es modules show).
# D2 «Озвучка» выключена: голоса отданы, реплика переводится «· озвучка выключена»; включена — голоса снова.
# D3 «Чтение снимков» и «Облако» выключены: снимок не читается (--es photofile), файлы моделей остались.
# D4 «удалить неиспользуемые»: файлы выключенного модуля снимков удалены; включили — докачались
#    сами с локального источника (tools/models_serve.py через adb reverse). Если экран включён и
#    впереди Falar, докачка идёт медленно (--slow, около 1 МБ/с) при открытом экране «Модули и файлы»
#    (0.26.0): строка над модулями — ход докачки, у «Чтения снимков» — метка «качается» и своя полоса
#    «N из M МБ · P %», после — «установлено» и полосы нет.
# D6 «Шумоподавление» (модель в APK): выключено — нарезка по исходному звуку и строка «модуль выключен»;
#    включено — снова «только при шуме»; в «Модули и файлы» — «0,5 МБ · при шуме» или «выключен».
# В конце набор модулей возвращается к тому, что был. Удаление (D4) идёт, только если у других
# выключенных модулей нет файлов на телефоне: чужие скачанные модели тест не удаляет.
# Разговоры владельца не трогаются: реплика — в отдельном тестовом разговоре, выученное и словари
# возвращаются из снимка. Телефон — рабочий телефон человека: запуск только когда впереди Falar,
# рабочий стол или экран погашен.
R=$(cd "$(dirname "$0")/../.." && pwd)
# Один прогон на телефоне за раз — и отдельный скрипт, и test_all_device.sh (01.10 две копии test_ui
# девять минут касались телефона одновременно).
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand"; exec 9>"${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
SER=${1:-}; ADB="$R/tools/platform-tools/adb${SER:+ -s $SER}"
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
BASE=http://127.0.0.1:8765; SRV=""
D=$(mktemp -d /tmp/falar-mod.XXXX); SNAP=$D/snap; mkdir -p $SNAP; TID=$(date +%s%3N)
pass=0; fail=0; skip=0; ORIG=""
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sk() { skip=$((skip+1)); say "ПРОПУСК $1"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }   # не из stdin: внутри «while read» adb съел бы его
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
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
since() { sh "tail -n +$(($1+1)) $LOG"; }
mods() { local m; m=$(mark); start_app --es modules show; wl "$m" '🧩 модули сейчас' 30 | grep -oE '\[[a-z,]*\]' | tr -d '[]'; }
setmods() { local m; m=$(mark); start_app --es modules "'$1'"; sleep 4; }
serve() { stop_serve; python3 $R/tools/models_serve.py "$@" > $D/serve.log 2>&1 & SRV=$!; sleep 0.7; grep -q "источник моделей" $D/serve.log || { say "  сервер не поднялся"; exit 2; }; }
stop_serve() { [ -n "$SRV" ] && kill $SRV 2>/dev/null; SRV=""; for p in $(ss -ltnp 2>/dev/null | grep ':8765 ' | grep -o 'pid=[0-9]*' | cut -d= -f2); do kill $p 2>/dev/null; done; sleep 0.3; }
size() { sh "stat -c %s $F/models/$1 2>/dev/null || echo 0" | awk '{print $1+0}'; }
# Экран «Модули и файлы» касаниями (bench/apk/ui.py): ☰ → «Настройки» → листать до пункта → он.
front() { sh "dumpsys power" | grep -q "mWakefulness=Awake" && sh "dumpsys window" | grep -m1 mCurrentFocus | grep -q "$PKG/"; }
dump() { sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" > $D/ui.xml; }
ui() { local c=$1; shift; python3 $R/bench/apk/ui.py "$c" $D/ui.xml "$@"; }
tapon() { local p; dump; p=$(ui find "$@" | head -1 | awk '{print $5, $6}'); [ -n "$p" ] && front && $ADB shell "input tap $p" && sleep 1.5; }
mods_screen() {
  local i
  tapon --desc "Разговоры, слова, настройки" && tapon --text Настройки || return 1
  for i in 1 2 3 4 5; do dump; ui find --text "Модули и файлы" >/dev/null && break; front && $ADB shell "input swipe 540 1600 540 700 350"; sleep 1.2; done
  tapon --text "Модули и файлы" && dump && [ "$(ui sub --rid hint | sed -n 1p)" = "Модули и файлы" ]
}

restore() {
  say "== возврат"
  stop_serve
  [ -n "$ORIG" ] || ORIG="__keep__"
  [ "$ORIG" != "__keep__" ] && { setmods "$ORIG"; say "  модули возвращены: [$ORIG]"; }
  free_phone; $ADB shell "am force-stop $PKG"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1
  done
  $ADB shell "rm -f $F/chats/$TID.json $F/modtest.jpg"
  say "  тестовый разговор удалён · текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  start_app; rm -rf "$D"
  c=$(crashed); [ -z "$c" ] && res 0 "D5 за проверку Falar не падал" || res 1 "D5 Falar упал: $c"
  say; say "итог: PASS $pass, FAIL $fail, пропущено $skip"
}
# Падения Falar за проверку — по logcat с начала прогона (падения самого uiautomator не в счёт).
T0=$($ADB shell "date '+%m-%d %H:%M:%S.000'" 2>/dev/null | tr -d '\r')
crashed() { $ADB shell "logcat -d -v time -t '$T0'" 2>/dev/null | tr -d '\r' | grep -A3 'FATAL EXCEPTION' | grep -A1 'Process: app.falar' | grep -vE 'Process:|^--' | head -1 | cut -c1-160; }
trap restore EXIT

$ADB wait-for-device; free_phone
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1; done
python3 - "$D/t.json" "$TID" <<'EOF'
import json, sys
path, tid = sys.argv[1], int(sys.argv[2])
json.dump({"id": tid, "name": "ТЕСТ модули", "named": True, "saved": tid,
           "turns": [{"dir": "pt2ru", "src": "Olá, tudo bem?", "dst": "Привет, как дела?", "at": tid + 1000}]},
          open(path, "w", encoding="utf-8"), ensure_ascii=False)
EOF
$ADB shell "am force-stop $PKG"; sleep 1; $ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
m=$(mark); start_app; wl "$m" '🧩 модули:' 150 >/dev/null; sleep 3

say "== D1: набор модулей"
ORIG=$(mods); say "  сейчас: [$ORIG]"
[ -n "$ORIG" ] || [ "$(since 0 | grep -c '🧩 модули сейчас: \[\]')" -gt 0 ] && res 0 "D1 набор модулей читается" || res 1 "D1 набора модулей нет в журнале"
without() { printf '%s' "$ORIG" | tr ',' '\n' | grep -vxE "$1" | paste -sd, -; }

say "== D2: озвучка выключена — перевод только на экране"
m=$(mark); setmods "$(without tts)"
l=$(wl "$m" '🔇 озвучка выключена|модули \(выключен' 20); say "  $l"
m=$(mark); start_app --es feedtext "'Eu fui lá hoje cedo.'"
t=$(wl "$m" '#[0-9]+ pt2ru' 60); say "  $(printf '%s' "$t" | cut -c1-160)"
printf '%s' "$(since $m)" | grep -q "озвучка выключена" && res 0 "D2 реплика переведена без голоса" || res 1 "D2 нет пометки «озвучка выключена»"
if printf '%s' "$ORIG" | grep -q tts; then
  m=$(mark); setmods "$ORIG"; l=$(wl "$m" '🔊 озвучка подключена|TTS загружен' 60); say "  $l"
  [ -n "$l" ] && res 0 "D2 включили обратно — голоса подняты без перезапуска" || res 1 "D2 голоса не поднялись"
fi

say "== D3: снимки и облако выключены — снимок не читается, файлы на месте"
det0=$(size ocr/det.onnx)
m=$(mark); setmods "$(without 'ocr|cloud')"
$ADB push $R/bench/ocr/photos/p03.jpg $F/modtest.jpg >/dev/null 2>&1
m=$(mark); start_app --es photofile $F/modtest.jpg; sleep 8
since "$m" | grep -q "📷 OCR за" && res 1 "D3 снимок прочитан при выключенном модуле" || res 0 "D3 снимок не читается"
[ "$(size ocr/det.onnx)" = "$det0" ] && res 0 "D3 файлы модуля остались ($det0 байт)" || res 1 "D3 файлы модуля пропали"

say "== D4: удалить неиспользуемые — и включить обратно"
m=$(mark); start_app --es modules show; u=$(wl "$m" '🧩 модули сейчас' 30 | grep -oE 'файлы [0-9.]+ МБ' | grep -oE '[0-9.]+')
ocrmb=$(awk -v a="$(stat -c %s $R/models/ocr/det.onnx)" -v b="$(stat -c %s $R/models/ocr/rec.onnx)" 'BEGIN{printf "%.1f", (a+b)/1e6}')
say "  неиспользуемые файлы: ${u:-?} МБ (из них снимки — $ocrmb)"
if [ "$det0" = 0 ]; then sk "D4 моделей снимков на телефоне нет — удалять нечего";
elif awk -v u="${u:-0}" -v o="$ocrmb" 'BEGIN{exit !(u - o > 0.2)}'; then sk "D4 у других выключенных модулей есть файлы (${u} МБ) — тест их не удаляет";
else
  m=$(mark); start_app --es modules unused-delete
  l=$(wl "$m" 'удалены модели выключенных' 30); say "  $l"
  [ "$(size ocr/det.onnx)" = 0 ] && [ "$(size ocr/rec.onnx)" = 0 ] && res 0 "D4 файлы выключенного модуля удалены" || res 1 "D4 файлы на месте"
  $ADB reverse tcp:8765 tcp:8765 >/dev/null
  ui4=""; front && ui4=1
  if [ -n "$ui4" ]; then serve --slow 60; else serve; fi
  m=$(mark); start_app --es modelsbase $BASE; sleep 2
  [ -n "$ui4" ] && { mods_screen || { ui4=""; say "  (экран «Модули и файлы» не открылся — ход на экране не проверить)"; }; }
  m2=$(mark); setmods "$(without cloud),ocr"
  if [ -n "$ui4" ]; then
    # Ход на экране — стендом --es uitexts (подписи видимого экрана строкой в журнал): uiautomator
    # снимает дерево, только когда экран затих, а во время докачки он обновляется, и снимок приходил
    # уже после конца хода. Ловим от «📦 докачиваю» до «модули докачаны» после него: строка «докачаны»
    # бывает и раньше — после удаления файлов служба сверяет включённые модули.
    : > $D/dl
    for i in $(seq 90); do
      mm=$(mark); start_app --es uitexts 1; wl "$mm" '🧪 на экране:' 5 >> $D/dl
      since "$m2" | grep -q 'докачиваю' && since "$m2" | grep -q 'модули докачаны' && break
      sleep 0.5
    done
    say "  снимков экрана за докачку: $(grep -c 'на экране' $D/dl)"
    grep -oE 'Качаю · [0-9.]+ из [0-9.]+ МБ|Чтение снимков \| [^|]+ \| [^|]+ \| [^|]+( \| [0-9]+ из [0-9]+ МБ · [0-9]+ %)?' $D/dl | sed 's/Чтение снимков | [^|]* | /Чтение снимков | … | /' | sort -u | head -6 | sed 's/^/    /'
    grep -qE 'Качаю · [0-9.]+ из [0-9.]+ МБ' $D/dl && res 0 "D4 над модулями — ход докачки «Качаю · … из … МБ»" || res 1 "D4 хода докачки над модулями не видно"
    grep -qE 'Чтение снимков \| [^|]+ \| [0-9,]+ [МГ]Б \| (качается|в очереди) \| [0-9]+ из [0-9]+ МБ · [0-9]+ %' $D/dl \
      && res 0 "D4 у «Чтения снимков» — «качается» и своя полоса «N из M МБ · P %»" || res 1 "D4 у модуля не видно метки и полосы загрузки"
  fi
  l=$(wl "$m" 'модули докачаны|докачиваю' 120); say "  $l"
  d=$(wl "$m" 'модули докачаны' 240); say "  $d"
  [ "$(size ocr/det.onnx)" = "$(stat -c %s $R/models/ocr/det.onnx)" ] && res 0 "D4 включили — модели снимков докачались сами" || res 1 "D4 не докачались"
  if [ -n "$ui4" ]; then
    sleep 2; dump; r=$(ui up --text 'Чтение снимков' --levels 1 | paste -sd'|' -)
    printf '%s' "$r" | grep -q '|установлено' && ! printf '%s' "$r" | grep -qE '[0-9]+ из [0-9]+ МБ · ' \
      && res 0 "D4 после докачки — «установлено», полосы нет" || res 1 "D4 после докачки у модуля: $r"
    front && { $ADB shell "input keyevent 4"; sleep 1; $ADB shell "input keyevent 4"; sleep 1; }
  fi
  stop_serve
fi

say "== D6: шумоподавление — модуль, модель в APK"
if ! printf ',%s,' "$ORIG" | grep -q ',denoise,'; then
  sk "D6 модуль «Шумоподавление» выключен у владельца — включать его тест не будет"
else
  m=$(mark); setmods "$(without denoise)"
  l=$(wl "$m" 'модуль «Шумоподавление» выключен' 20); say "  $l"
  [ -n "$l" ] && res 0 "D6 выключили — нарезка по исходному звуку" || res 1 "D6 нет строки «модуль «Шумоподавление» выключен»"
  m=$(mark); setmods "$ORIG"
  l=$(wl "$m" 'только при шуме: фон не ниже' 20); say "  $l"
  [ -n "$l" ] && res 0 "D6 включили — снова «только при шуме»" || res 1 "D6 нет строки «только при шуме»"
  if front && mods_screen; then
    # Модулей восемь — строка шумоподавления ниже края экрана: листаем, пока не покажется.
    for i in 1 2 3 4 5; do dump; ui find --text 'Шумоподавление' >/dev/null && break; front && $ADB shell "input swipe 540 1500 540 900 350"; sleep 1.2; done
    r=$(ui up --text 'Шумоподавление' --levels 1 | paste -sd'|' -); say "  строка модуля: $r"
    printf '%s' "$r" | grep -q '0,5 МБ|при шуме' && res 0 "D6 в «Модули и файлы» — «0,5 МБ · при шуме»" || res 1 "D6 строка модуля: $r"
    front && { $ADB shell "input keyevent 4"; sleep 1; $ADB shell "input keyevent 4"; sleep 1; }
  else sk "D6 экран «Модули и файлы» — впереди не Falar"; fi
fi
