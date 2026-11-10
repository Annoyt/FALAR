#!/bin/bash
# Озвучка — синтез речи телефона (SysTts, с 0.38.0 вместо Piper) на телефоне.
#
#   bash bench/apk/test_voice_device.sh [серийный номер]
#
# V1 при запуске синтез поднят, у русского и португальского — голос без сети (в журнале «TTS: синтез телефона … ·
#    ru <голос> · pt <голос>»); голосов Piper (models/tts_ru, tts_pt) на телефоне нет.
# V2 реплика pt → ru звучит: в строке реплики нет «без озвучки», первый звук — не позже 400 мс после перевода
#    («tts→звук»; у первой после запуска — 1,5 с: она открывает дорожку вывода); то же для ru → pt — другой язык
#    сразу следом (у каждого языка свой синтез: смена голоса не ждёт загрузки).
# V3 выбор голоса (тот же путь, что «Настройки» → «Голоса», --es ttsvoice): голос сменился и звучит; в конце —
#    прежний.
# V4 за проверку Falar не падал (logcat). В конце — прежний голос и «Что озвучивать», молчаливый режим стенда.
# Разговоры владельца не трогаются: реплики — в отдельном тестовом разговоре, он удаляется. Телефон — рабочий:
# запуск только когда впереди Falar, рабочий стол или экран погашен.
. "$(dirname "$0")/../stand.sh"
R=$(cd "$(dirname "$0")/../.." && pwd)
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "$STAND"; exec 9>"$STAND/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
SER=${1:-}; ADB="$R/tools/platform-tools/adb${SER:+ -s $SER}"; [ -x "$R/tools/platform-tools/adb" ] || ADB="$(cd "$R/../.." && pwd)/tools/platform-tools/adb${SER:+ -s $SER}"
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity; F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
pass=0; fail=0; skip=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sk() { skip=$((skip+1)); say "SKIP $*"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
since() { sh "tail -n +$(($1+1)) $LOG"; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(since "$1" | grep -E -m1 -- "$2"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
free_phone() {
  local n=0
  while :; do
    local f; f=$(sh "dumpsys window | grep -m1 mCurrentFocus")
    sh "dumpsys power" | grep -q "mWakefulness=Awake" || return 0
    case "$f" in *$PKG*|*com.miui.home*|*launcher*|*Launcher*) return 0;; esac
    n=$((n+1)); [ $n -eq 1 ] && say "  нужен Falar, рабочий стол или погашенный экран впереди ($f), жду…"; sleep 10
  done
}
start() { free_phone; sh "am start -n $ACT $*" >/dev/null; }
TID=$(date +%s%3N); D=$(mktemp -d /tmp/falar-voice.XXXX); WAS=""
# Падения Falar за проверку — по logcat с начала прогона
T0=$(sh "date '+%m-%d %H:%M:%S.000'")
crashed() { sh "logcat -d -v time -t '$T0'" | grep -A3 'FATAL EXCEPTION' | grep -A1 'Process: app.falar' | grep -vE 'Process:|^--' | head -1 | cut -c1-160; }
restore() {
  say "== возврат"
  [ -n "$WAS" ] && { m=$(mark); start --es ttsvoice "ru:$WAS"; wl "$m" '🔊 голос ru:' 20 >/dev/null; say "  голос ru возвращён: $WAS"; }
  m=$(mark); start --es voicewhat "${VW:-auto}" --es silent 1; wl "$m" '🔈' 10 >/dev/null
  free_phone; sh "am force-stop $PKG"; sleep 2
  sh "rm -f $F/chats/$TID.json"; say "  тестовый разговор удалён"
  start; rm -rf "$D"
  c=$(crashed); [ -z "$c" ] && res 0 "V4 за проверку Falar не падал" || res 1 "V4 Falar упал: $c"
  say "== итог: прошло $pass, не прошло $fail, пропущено $skip"
}
trap restore EXIT
# Отдельный тестовый разговор — он становится текущим (последний сохранённый), реплики проверки идут в него
python3 - "$D/t.json" "$TID" <<'EOF'
import json, sys
path, tid = sys.argv[1], int(sys.argv[2])
json.dump({"id": tid, "name": "ТЕСТ голос", "named": True, "saved": tid, "turns": []}, open(path, "w", encoding="utf-8"), ensure_ascii=False)
EOF

say "== V1: синтез телефона и голоса без сети"
free_phone; sh "am force-stop $PKG"; sleep 1; $ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
# «Что озвучивать» человека — по последней строке выбора в журнале (вернётся в конце)
case "$(sh "grep -E '🔈 озвучиваю|🔈 озвучка — авто' $LOG | tail -1")" in
  *"только русский"*) VW=ru;; *"только португальский"*) VW=pt;; *"оба языка"*) VW=both;; *) VW=auto;; esac
m=$(mark); start --es listen off --es silent 0
l=$(wl "$m" 'TTS: синтез телефона за|TTS: синтеза речи нет|синтез речи телефона не поднялся' 180); say "  ${l:-журнал молчит}"
printf '%s' "$l" | grep -q -E ' ru [a-z]' && printf '%s' "$l" | grep -q -E ' pt [a-z]' && res 0 "V1 синтез поднят, голоса ru и pt без сети" || res 1 "V1 голосов ru и pt нет"
ms=$(printf '%s' "$l" | sed -n 's/.*за \([0-9]*\) мс.*/\1/p'); say "  загрузка синтеза: ${ms:-?} мс"
[ "$(sh "ls -d $F/models/tts_ru $F/models/tts_pt 2>/dev/null | wc -l")" = 0 ] && res 0 "V1 голосов Piper на телефоне нет" || res 1 "V1 голоса Piper остались"
wl "$m" '🧩 модули:' 120 >/dev/null; sleep 2

turn() {   # $1 — подача (--es feedtext / --es typed), $2 — начало строки реплики (образец), $3 — метка, $4 — предел, мс
  local lim=${4:-400}
  local m; m=$(mark); start "$1"; local l; l=$(wl "$m" "$2" 40); sleep 1
  local t; t=$(since "$m" | grep -E -m1 -A2 -- "$2" | tr '\n' ' ')
  local d; d=$(printf '%s' "$t" | sed -n 's/.*tts→звук \([0-9-]*\).*/\1/p')
  say "  $3: $(printf '%s' "$t" | cut -c1-170)"
  if [ -z "$t" ]; then res 1 "$3 реплики нет в журнале"; return; fi
  printf '%s' "$t" | grep -q 'без озвучки\|озвучка выключена' && { res 1 "$3 не звучит"; return; }
  [ -n "$d" ] && [ "$d" -le $lim ] && res 0 "$3 звучит, первый звук через $d мс после перевода" || res 1 "$3 первый звук через ${d:-?} мс (> $lim)"
}
say "== V2: реплики звучат, языки вперемешку"
m=$(mark); start --es voicewhat both; wl "$m" '🔈' 10 >/dev/null
# Первая реплика после запуска ещё и открывает дорожку вывода («выход: …», на Redmi ~0,7 с — так было и с Piper,
# у которого первая реплика ждала звука 1,4–3 с): предел у неё 1,5 с, у следующих — 0,4 с.
turn "--es feedtext 'Bom dia, tudo certo por aqui?'" 'pt2ru . Bom dia' "V2 pt → ru (первая после запуска)" 1500
turn "--es typed 'Спасибо, всё хорошо.'" 'ru2pt . Спасибо' "V2 ru → pt сразу следом"
turn "--es feedtext 'Onde fica a farmácia mais próxima?'" 'pt2ru . Onde fica' "V2 снова pt → ru"

say "== V3: выбор голоса"
m=$(mark); start --es ttsvoice show; l=$(wl "$m" '🔊 голоса:' 10); say "  $l"
WAS=$(printf '%s' "$l" | sed -n 's/.* ru \([^ ]*\) .*/\1/p')
[ "$WAS" = "ru-ru-x-ruf-local" ] && alt=ru-ru-x-rue-local || alt=ru-ru-x-ruf-local
m=$(mark); start --es ttsvoice "ru:$alt"; l=$(wl "$m" '🔊 голос ru:' 20); say "  $l"
if printf '%s' "$l" | grep -q "$alt"; then
  res 0 "V3 голос сменился на $alt"
  # Выбранный голос сразу произносит пробную фразу (~2 с, speakOut под тем же замком озвучки): реплика, поданная
  # раньше её конца, ждала бы её — 10.11 так вышло 656 мс «до звука». Ждём, пока пробная фраза отзвучит.
  sleep 4
  turn "--es feedtext 'Muito obrigado pela ajuda.'" 'pt2ru . Muito obrigado' "V3 новым голосом"
else sk "V3 голоса $alt на телефоне нет ($l)"; fi
