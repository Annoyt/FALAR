#!/bin/bash
# Загрузчик моделей на телефоне: сценарии M1–M8 из bench/TESTS.md §3.10 против локального
# источника (tools/models_serve.py через adb reverse) — обрыв, битый файл, 404, «стоп» и
# продолжение, ожидание сети, полная проверка. Печатает PASS/FAIL по сценарию и цифры.
#
#   bash bench/apk/test_models_device.sh          # M1–M8, трогает только необязательные файлы
#   FULL=1 bash bench/apk/test_models_device.sh   # плюс M9: каталог моделей убирается, первый экран,
#                                                 # 1,9 ГБ обязательного с локального источника, потом всё возвращается
#
# Телефон — рабочий телефон человека: перед каждым запуском приложения ждём, пока на экране
# не чужое приложение. Файлы удаляются только те, что скрипт сам потом докачивает.
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; M=$F/models; LOG=$F/at.log
BASE=http://127.0.0.1:8765
SPK=speaker/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx; DN=denoiser/gtcrn_simple.onnx
pass=0; fail=0; SRV=""
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
# Экран: ждём, пока в фокусе не чужое приложение (наше, рабочий стол или экран погашен).
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
# Порт освобождаем от любого слушателя: чужой сервер на 8765 (например, поднятый руками) молча
# перехватывал бы запросы, и сценарии с обрывом и битым файлом проходили бы «Готово».
serve() { stop_serve; python3 $R/tools/models_serve.py "$@" > /tmp/falar-serve.log 2>&1 & SRV=$!; sleep 0.7; grep -q "источник моделей" /tmp/falar-serve.log || { say "  сервер не поднялся:"; cat /tmp/falar-serve.log; exit 2; }; }
stop_serve() { [ -n "$SRV" ] && kill $SRV 2>/dev/null; SRV=""; for p in $(ss -ltnp 2>/dev/null | grep ':8765 ' | grep -o 'pid=[0-9]*' | cut -d= -f2); do kill $p 2>/dev/null; done; sleep 0.3; }
mark() { sh "wc -l < $LOG" 2>/dev/null | awk '{print $1+0}'; }
# wait_line <метка> <шаблон> <сек> — первая строка журнала после метки, подходящая под шаблон
wait_line() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG 2>/dev/null | grep -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
exists() { [ "$(sh "[ -f $M/$1 ] && echo y")" = y ]; }
size() { sh "stat -c %s $M/$1 2>/dev/null" | awk '{print $1+0}'; }
trap 'stop_serve' EXIT

$ADB wait-for-device
$ADB reverse tcp:8765 tcp:8765 >/dev/null
say "== запуск приложения и источник по умолчанию"
start_app; sleep 2
m=$(mark); start_app --es modelsbase $BASE
wait_line "$m" '⬇ стенд: источник' 15 >/dev/null || say "  (строка про стенд не пришла — сервис ещё грузится?)"

if [ -z "$ONLY_M9" ]; then   # ONLY_M9=1 FULL=1 — сразу к первому запуску целиком
# M1: проверка при старте — первый раз хэши, второй раз по кэшу
say "== M1 проверка при старте"
sh "rm -f $M/.verified.json"; $ADB shell am force-stop $PKG; sleep 1
m=$(mark); start_app; l1=$(wait_line "$m" "📦 модели" 400); say "  $l1"
$ADB shell am force-stop $PKG; sleep 1
m=$(mark); start_app; l2=$(wait_line "$m" '📦 модели' 60); say "  $l2"
case "$l1" in *прохэшировано*) a=0;; *) a=1;; esac; case "$l2" in *"по кэшу"*) b=0;; *) b=1;; esac
res $(( a || b )) "M1 первый старт хэширует, второй по кэшу"
wait_line "$m" 'микрофон выключен\|Слушаю\|🎚' 120 >/dev/null; sleep 1
start_app --es modelsbase $BASE; sleep 1

# M2: необязательное с локального источника
say "== M2 необязательное: удалить и докачать"
sh "rm -f $M/$SPK $M/$DN $M/common_words.txt $M/phrasebook_tatoeba.tsv"
serve
m=$(mark); start_app --es models optional
l=$(wait_line "$m" '⬇ модели (optional)' 300); say "  $l"
ok=1; case "$l" in *Готово*) exists $SPK && exists $DN && exists common_words.txt && exists phrasebook_tatoeba.tsv && ok=0;; esac
res $ok "M2 необязательное докачано"
m=$(mark); start_app --es models check; l=$(wait_line "$m" '📦 проверка:' 60); say "  $l"
case "$l" in *"необязательных нет 0"*) res 0 "M2 проверка: необязательных нет 0";; *) res 1 "M2 проверка";; esac

# M3: битый файл
say "== M3 битый файл"
sh "rm -f $M/$DN"; serve --corrupt gtcrn_simple.onnx
m=$(mark); start_app --es models $DN
l=$(wait_line "$m" '⬇ модели (optional)' 60); say "  $l"
ok=1; case "$l" in *"Не скачалось"*"хэш"*) exists $DN || ok=0;; esac
res $ok "M3 хэш не сошёлся, файл выброшен"
serve; m=$(mark); start_app --es models $DN; l=$(wait_line "$m" '⬇ модели (optional)' 60); say "  $l"
case "$l" in *Готово*) res 0 "M3 после починки источника докачался";; *) res 1 "M3 повтор";; esac

# M4: 404
say "== M4 404"
sh "rm -f $M/common_words.txt"; serve --missing common_words.txt
m=$(mark); start_app --es models common_words.txt
l=$(wait_line "$m" '⬇ модели (optional)' 60); say "  $l"
case "$l" in *"HTTP 404"*) res 0 "M4 404 — ошибка без повторов";; *) res 1 "M4 404";; esac
serve; m=$(mark); start_app --es models common_words.txt; wait_line "$m" 'Готово' 60 >/dev/null && res 0 "M4 докачан" || res 1 "M4 докачан"

# M5: обрыв и докачка с Range
say "== M5 обрыв на 9 МБ, докачка с Range"
sh "rm -f $M/$SPK"; serve --drop 3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx:9000000
m=$(mark); start_app --es models $SPK
l=$(wait_line "$m" '⬇ модели (optional)' 120); say "  $l"; sleep 0.5
grep 3dspeaker /tmp/falar-serve.log | sed 's/^/  сервер: /'
n=$(grep -c "3dspeaker" /tmp/falar-serve.log); rg=$(grep "3dspeaker" /tmp/falar-serve.log | grep -c "bytes=")
ok=1; case "$l" in *Готово*) [ "$n" -ge 2 ] && [ "$rg" -ge 1 ] && ok=0;; esac
res $ok "M5 два запроса, второй с Range, файл верный"

# M6: стоп посередине и продолжение с того же места
say "== M6 стоп и продолжение"
sh "rm -f $M/$SPK"; serve --slow 25
m=$(mark); start_app --es models $SPK; sleep 5
start_app --es models stop
l=$(wait_line "$m" 'Остановлено' 30); say "  $l"
p=$(size $SPK.part); say "  .part: $p байт"
ok=1; [ "$p" -gt 0 ] && ok=0; res $ok "M6 после «стоп» остался .part"
serve; m=$(mark); start_app --es models $SPK
l=$(wait_line "$m" '⬇ модели (optional)' 120); say "  $l"
grep 3dspeaker /tmp/falar-serve.log | sed 's/^/  сервер: /'
ok=1; case "$l" in *Готово*) grep -q "bytes=$p-" /tmp/falar-serve.log && ok=0;; esac
res $ok "M6 продолжение ровно с длины .part"

# M7: полная проверка
say "== M7 проверить файлы"
m=$(mark); start_app --es models verify; l=$(wait_line "$m" '📦 проверка файлов' 300); say "  $l"
case "$l" in *"не сошлось или нет 0"*) res 0 "M7 все файлы сошлись";; *) res 1 "M7 проверка";; esac

# M8: нет сети — ждём, сеть вернулась — докачалось
say "== M8 без сети"
sh "rm -f $M/$DN"; serve
sh "cmd connectivity airplane-mode enable" >/dev/null; sleep 3
m=$(mark); start_app --es models $DN; sleep 4
nt=$(sh "dumpsys notification --noredact 2>/dev/null | grep -m1 -o 'Ждём сеть[^\"]*'"); say "  уведомление: ${nt:-нет}"
sh "cmd connectivity airplane-mode disable" >/dev/null
l=$(wait_line "$m" '⬇ модели (optional)' 120); say "  $l"
ok=1; case "$l" in *Готово*) [ -n "$nt" ] && ok=0;; esac
res $ok "M8 ждал сеть, после возврата докачал"
fi

# M9 (FULL=1): первый экран и обязательное целиком
if [ -n "$FULL" ]; then
  say "== M9 первый запуск: каталог моделей убран"
  $ADB shell am force-stop $PKG; sleep 1
  sh "mv $M $M.keep"; serve
  m=$(mark); start_app
  sleep 4; sh "uiautomator dump /sdcard/ui.xml >/dev/null; grep -o 'text=\"1. Микрофон\"' /sdcard/ui.xml | head -1" | grep -q Микрофон && res 0 "M9 первый экран показан" || res 1 "M9 первый экран (при погашенном экране снимок дерева невозможен)"
  l=$(wait_line "$m" '📦 модели' 30); say "  $l"
  start_app --es modelsbase $BASE; sleep 1
  t0=$(date +%s); start_app --es models core
  l=$(wait_line "$m" '⬇ модели (core)' 1800); say "  $l  ($(( $(date +%s) - t0 )) с)"
  l2=$(wait_line "$m" 'Готово. ASR\|ASR+VAD загружены' 300); say "  $l2"
  ok=1; case "$l" in *Готово*) [ -n "$l2" ] && ok=0;; esac
  res $ok "M9 обязательное скачано, движки поднялись"
  $ADB shell am force-stop $PKG; sleep 1
  sh "rm -rf $M; mv $M.keep $M"
  start_app; wait_line "$(mark)" '📦 модели' 120 >/dev/null
fi

stop_serve
say ""; say "итог: PASS $pass, FAIL $fail"
[ $fail -eq 0 ]
