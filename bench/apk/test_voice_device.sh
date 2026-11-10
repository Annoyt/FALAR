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
# V4 за проверку Falar не падал (logcat). В конце — прежний голос, скорость и «Что озвучивать», молчаливый режим стенда.
# V5 скорость речи (тот же путь, что ползунки «Скорость речи», --es ttsspeed): пробная фраза на 2× заметно короче,
#    чем на 1×, на 0,5× — длиннее (длина звука — из журнала «🔊 скорость ru: …· пробная фраза N с звука»).
# V6 уточнение до озвучки (RefineVoice, «Точнее» + PT): русская фраза — уточнителю, голос ждёт его ответ (в журнале
#    «🔁 #N до озвучки», в строке реплики — итог), но не дольше 3 с; португальская (pt → ru) — сразу; «Быстрее» — сразу.
# V7 голос рядом с фоновым разбором («Быстрее», разбор каждые 3 реплики): реплика, поданная, пока уточнитель
#    разбирает прежние, звучит через ≤ 400 мс — уточнитель уступает синтезу («🧠 уточнитель уступил голосу N мс»).
#    Без уточнителя (модуль выключен, файла нет) V6 и V7 пропускаются.
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
TID=$(date +%s%3N); D=$(mktemp -d /tmp/falar-voice.XXXX); WAS=""; WAS_SP=""
# Падения Falar за проверку — по logcat с начала прогона
T0=$(sh "date '+%m-%d %H:%M:%S.000'")
crashed() { sh "logcat -d -v time -t '$T0'" | grep -A3 'FATAL EXCEPTION' | grep -A1 'Process: app.falar' | grep -vE 'Process:|^--' | head -1 | cut -c1-160; }
restore() {
  say "== возврат"
  [ -n "$WAS" ] && { m=$(mark); start --es ttsvoice "ru:$WAS"; wl "$m" '🔊 голос ru:' 20 >/dev/null; say "  голос ru возвращён: $WAS"; }
  [ -n "$WAS_SP" ] && { m=$(mark); start --es ttsspeed "$WAS_SP"; wl "$m" '🔊 скорость pt:' 20 >/dev/null; say "  скорость возвращена: $WAS_SP"; }
  m=$(mark); start --es voicewhat "${VW:-auto}" --es silent 1 ${REF:+--es refineevery $REF} ${CLOUD:+--es cloudevery $CLOUD} \
    $([ -n "$MTQ" ] && echo "--es mtprefer $([ "$MTQ" = 1 ] && echo quality || echo speed)") ${RVK:+--es refinevoice $RVK}; wl "$m" '🔈' 10 >/dev/null
  [ -n "$REF$CLOUD" ] && say "  разбор контекста и пересмотр в облаке — как были: ${REF:-?} и ${CLOUD:-?}"
  free_phone; sh "am force-stop $PKG"; sleep 2
  sh "rm -f $F/chats/$TID.json"; say "  тестовый разговор удалён"
  start; rm -rf "$D"
  c=$(crashed); [ -z "$c" ] && res 0 "V4 за проверку Falar не падал" || res 1 "V4 Falar упал: $c"
  say; say "итог: PASS $pass, FAIL $fail, пропущено $skip"
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
# Разбор контекста (уточнитель, LLM на процессоре) и облачный пересмотр — на время проверки по кнопке: после каждой
# третьей реплики уточнитель 2–3 с занимает процессор, и синтез Google рядом с ним отдавал первый звук через 0,9–1,1 с
# (V3 10.11: «🔁 #2 по контексту (2580 мс)» ровно под репликой #4). Замеряется озвучка, а не соседство с уточнителем.
m=$(mark); start --es settings show; st=$(wl "$m" '🧪 настройки:' 20)
REF=$(printf '%s' "$st" | sed -n 's/.*разбор \([0-9][0-9]*\).*/\1/p'); CLOUD=$(printf '%s' "$st" | sed -n 's/.*облако \([0-9][0-9]*\).*/\1/p')
MTQ=$(printf '%s' "$st" | sed -n 's/.*перевод точнее \([01]\).*/\1/p'); RVK=$(printf '%s' "$st" | sed -n 's/.*до озвучки \([a-z]*\).*/\1/p')
m=$(mark); start --es listen off --es silent 0 --es refineevery 0 --es cloudevery 0
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
  [ -n "$d" ] && [ "$d" -le $lim ] && { res 0 "$3 звучит, первый звук через $d мс после перевода"; return; }
  res 1 "$3 первый звук через ${d:-?} мс (> $lim)"
  # почему ждал: новая дорожка вывода («выход:»), пауза перебивания (🗣) или сам синтез (🔊) — строки журнала с подачи
  since "$m" | grep -E -- 'выход:|🗣|🔊' | head -8 | cut -c1-200 | sed 's/^/    журнал: /'
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

say "== V5: скорость речи"
m=$(mark); start --es ttsspeed show; l=$(wl "$m" '🔊 скорость: ' 10); say "  ${l:-журнал молчит}"
# «ru 1,0× · pt 1,2×» → «ru:1.0,pt:1.2» — вернуть в конце (числа с точкой: запятая в --es делит языки)
sp=$(printf '%s' "$l" | sed -n 's/.*ru \([0-9]\),\([0-9]\)× · pt \([0-9]\),\([0-9]\)×.*/ru:\1.\2,pt:\3.\4/p')
if [ -z "$sp" ]; then res 1 "V5 у сборки нет --es ttsspeed или не тот ответ: ${l:-—}"
else
  WAS_SP=$sp
  # длина пробной фразы на скорости $1 (секунды звука; пусто — не прозвучала)
  len() { local m l; m=$(mark); start --es ttsspeed "ru:$1"; l=$(wl "$m" '🔊 скорость ru: ' 25); say "  $(printf '%s' "$l" | cut -c1-120)" >&2
    printf '%s' "$l" | sed -n 's/.*пробная фраза \([0-9.]*\) с.*/\1/p'; }
  d1=$(len 1.0 | tail -1); d2=$(len 2.0 | tail -1); d05=$(len 0.5 | tail -1)
  ok2=$(awk -v a="$d2" -v b="$d1" 'BEGIN { print (a > 0 && b > 0 && a / b < 0.7) ? 1 : 0 }')
  ok05=$(awk -v a="$d05" -v b="$d1" 'BEGIN { print (a > 0 && b > 0 && a / b > 1.5) ? 1 : 0 }')
  [ "$ok2" = 1 ] && res 0 "V5 на 2× пробная фраза короче: ${d2} с против ${d1} с на 1×" || res 1 "V5 на 2× не короче: ${d2:-?} с против ${d1:-?} с на 1×"
  [ "$ok05" = 1 ] && res 0 "V5 на 0,5× длиннее: ${d05} с против ${d1} с на 1×" || res 1 "V5 на 0,5× не длиннее: ${d05:-?} с против ${d1:-?} с"
fi

# ---- уточнитель: до озвучки (V6) и рядом с голосом (V7)
m=$(mark); start --es modules show; MODS=$(wl "$m" '🧩 модули сейчас' 30 | grep -oE '\[[a-z,]*\]' | tr -d '[]')
if ! printf ',%s,' "$MODS" | grep -q ',llm,'; then sk "V6 V7 модуль «Уточнитель» выключен ($MODS)"
else
  say "== V6: уточнение до озвучки («Точнее» + PT)"
  m=$(mark); start --es mtprefer quality --es refinevoice pt --es llmprobe 1; l=$(wl "$m" '🧠 проба уточнителя:' 120); say "  ${l:-журнал молчит}"
  if ! printf '%s' "$l" | grep -q ': поднят'; then sk "V6 V7 уточнитель не поднялся (${l:-—})"
  else
    # ru → pt: голос ждёт уточнитель. Строка реплики пишется после озвучки — в ней итог ожидания.
    m=$(mark); start "--es typed 'Eu queria saber se ainda tem mesa livre para hoje à noite.'" >/dev/null 2>&1   # прогрев направления — не в счёт
    wl "$m" 'pt2ru|ru2pt' 40 >/dev/null; sleep 4
    m=$(mark); start "--es typed 'Я хотел бы забронировать столик на двоих на восемь вечера.'"; l=$(wl "$m" 'ru2pt . Я хотел бы' 40); sleep 1
    t=$(since "$m" | grep -E -m1 -A2 -- 'ru2pt . Я хотел бы' | tr '\n' ' '); r6=$(since "$m" | grep -E -m1 -- '🔁 #[0-9]+ до озвучки')
    say "  $(printf '%s' "$r6" | cut -c1-160)"; say "  V6 ru → pt: $(printf '%s' "$t" | cut -c1-220)"
    d=$(printf '%s' "$t" | sed -n 's/.*tts→звук \([0-9-]*\).*/\1/p')
    if [ -z "$r6" ]; then res 1 "V6 ru → pt: уточнитель до озвучки не спрашивали"
    elif printf '%s' "$t" | grep -q 'уточнено до озвучки\|уточнитель оставил перевод'; then
      [ -n "$d" ] && [ "$d" -le 3600 ] && res 0 "V6 ru → pt ждёт уточнитель и звучит через $d мс (предел 3 с + синтез)" || res 1 "V6 ru → pt: первый звук через ${d:-?} мс (> 3,6 с)"
    elif printf '%s' "$t" | grep -q 'не успел'; then
      [ -n "$d" ] && [ "$d" -le 3600 ] && res 0 "V6 ru → pt: уточнитель не успел за 3 с — звучит перевод фразы через $d мс" || res 1 "V6 ru → pt: ждал дольше предела — ${d:-?} мс"
    else res 1 "V6 ru → pt: в строке реплики нет итога ожидания"; fi
    turn "--es feedtext 'Pode trazer a conta, por favor?'" 'pt2ru . Pode trazer' "V6 pt → ru при «PT» — без ожидания"
    m=$(mark); start --es mtprefer speed; wl "$m" '🔤 перевод: быстрее' 10 >/dev/null
    turn "--es typed 'Спасибо, было очень вкусно.'" 'ru2pt . Спасибо, было' "V6 «Быстрее»: ru → pt сразу"

    say "== V7: голос рядом с фоновым разбором («Быстрее», разбор каждые 3)"
    m=$(mark); start --es refineevery 3; wl "$m" '🧠 разбор контекста: каждые' 10 >/dev/null
    for ph in 'Bom dia, a senhora fala inglês?' 'Onde fica a estação de metrô?' 'Quanto custa o bilhete de ida e volta?'; do
      m=$(mark); start "--es feedtext '$ph'"; wl "$m" "pt2ru . ${ph%% *}" 40 >/dev/null
    done
    # третья реплика запустила разбор: ждём его первый вопрос и подаём четвёртую посреди него
    m7=$m; wl "$m7" '🔁 #[0-9]+ (по контексту|контекст не изменил|ответ отброшен)' 40 >/dev/null; sleep 0.5
    turn "--es typed 'Да, конечно, сейчас покажу.'" 'ru2pt . Да, конечно' "V7 реплика во время разбора"
    y=$(since "$m7" | grep -E -m1 -- '🧠 уточнитель уступил голосу [0-9]+ мс'); p7=$(since "$m7" | grep -E -c -- '🔁 #[0-9]+ ')
    if [ "$p7" = 0 ]; then sk "V7 разбор не начался — контекст выключен? (уступать было некому)"
    else [ -n "$y" ] && res 0 "V7 уточнитель уступил синтезу: $(printf '%s' "$y" | cut -c10-)" || res 1 "V7 уточнитель не уступил синтезу (разбор шёл: $p7 строк)"; fi
    wl "$m7" '🧠 разбор контекста: реплик' 40 >/dev/null
  fi
fi
