#!/bin/bash
# «Улучшить» гаснет, когда последняя фраза уже обработана, — на телефоне, в отдельном тестовом разговоре.
#
#   bash bench/apk/test_better_device.sh [серийный номер]
#
# Владелец 01.10: «надо тушить кнопку улучшить, если данная фраза уже была обработана». Проверяет:
# B1 — в свежем тестовом разговоре кнопка доступна; B2 — после облачного пересмотра гаснет; B3 — новая
# фраза зажигает её снова. Состояние — стендовой командой --es betterstate 1 (строка «🧪 «Улучшить»: …»
# в журнале): так же решает экран, и проверка идёт и при погашенном экране. Если экран включён и
# впереди Falar, проверяется и сам экран (0.26.0): кнопка горит и гаснет вместе со службой; пересмотр
# запускается касанием по «Улучшить», его ход — в реплике («Улучшаю в облаке · ещё ≈ N с», кнопок
# реплики в это время нет), итог — словами в шапке («улучшено · исправлено фраз: N» или «…
# исправлять нечего»; владелец 01.10: модель, знаки и пары — в журнал).
#
# Разговоры владельца не трогаются — схема test_memo_device.sh: тестовый разговор кладётся файлом с
# самым свежим временем и в конце удаляется, выученное и словари возвращаются из снимка. В облако
# уходит только синтетический разговор ниже (1–5 бесплатных запросов OpenRouter).
R=$(cd "$(dirname "$0")/../.." && pwd)
# Один прогон на телефоне за раз — и отдельный скрипт, и test_all_device.sh (01.10 две копии test_ui
# девять минут касались телефона одновременно).
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand"; exec 9>"${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
SER=${1:-f6lnlrorgi59xwge}; ADB="$R/tools/platform-tools/adb -s $SER"
ACT=app.falar/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/app.falar/files; LOG=$F/at.log
D=$(mktemp -d /tmp/falar-better.XXXX); SNAP=$D/snap; mkdir -p $SNAP
TID=$(date +%s%3N)
pass=0; fail=0; skip=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }   # не из stdin: внутри «while read» adb съел бы его
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
state() { local m; m=$(mark); $ADB shell "am start -n $ACT --es betterstate 1" >/dev/null 2>&1; wl "$m" '🧪 «Улучшить»' 15; }
front() { sh "dumpsys power" | grep -q "mWakefulness=Awake" && sh "dumpsys window" | grep -m1 mCurrentFocus | grep -q "app.falar/"; }
dump() { sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" > $D/ui.xml; }
ui() { local c=$1; shift; python3 $R/bench/apk/ui.py "$c" $D/ui.xml "$@"; }
# Что видно на экране — стендом --es uitexts (подписи видимых видов строкой в журнал, у выключенного —
# «[выкл]»): он не ждёт, пока экран затихнет, как uiautomator, и ход облака успевает попасть в снимок.
uitexts() { local m; m=$(mark); $ADB shell "am start -n $ACT --es uitexts 1" >/dev/null 2>&1; wl "$m" '🧪 на экране:' 5; }
chip() { printf '%s' "$1" | grep -oE '(^|\| )Улучшить( \[выкл\])?( \||$)' | head -1 | grep -q 'выкл' && echo false || { printf '%s' "$1" | grep -qE '(^|\| )Улучшить( \||$)' && echo true; }; }
# Кнопка на экране против решения службы. Сначала ждём кнопки реплики: после новой фразы на их месте
# идёт ход уточнителя. Решение службы спрашиваем сразу после снимка экрана — оба об одном и том же.
screen_chip() {
  front || { say "  (экран выключен или впереди не Falar — кнопку на экране не проверить)"; return; }
  local t k e l w
  for k in $(seq 45); do t=$(uitexts); printf '%s' "$t" | grep -q 'Запомнить' && break; sleep 2; done
  l=$(state); e=$(chip "$t"); w=$(printf '%s' "$l" | grep -q ': можно' && echo true || echo false)
  [ "$e" = "$w" ] && res 0 "$1 на экране кнопка $([ $w = true ] && echo горит || echo погашена)" || res 1 "$1 на экране кнопка enabled=${e:-нет}, а служба: $l"
}
# «Улучшить» касанием: ждём, пока кнопка горит (облако бывает занято своим пересмотром), жмём и
# снимаем экран, пока не придёт ответ. Пишет строку ответа; ход — в $D/cloud.
better_tap() {
  local k m c t x y
  for k in $(seq 24); do t=$(uitexts); [ "$(chip "$t")" = true ] && break; sleep 5; done
  front || return 1
  dump; read -r x y <<< "$(ui find --text Улучшить | awk '{print $5, $6}')"
  m=$(mark); : > $D/cloud; $ADB shell "input tap $x $y"; sleep 0.3
  dump; ui find --text "Отправить разговор в облако?" >/dev/null && { $ADB shell "input keyevent 4"; echo "нет согласия (окно согласия на экране — решение владельца, не нажимаю)"; return 0; }
  for k in $(seq 150); do
    t=$(uitexts); c=$(printf '%s' "$t" | grep -oE 'Улучшаю в облаке · [^|]*[^ |]')
    [ -n "$c" ] && printf '%s\t%s\n' "$c" "$(printf '%s' "$t" | grep -q 'Запомнить' && echo видны || echo скрыты)" >> $D/cloud
    since "$m" | grep -qE '☁ ушло|☁ не вышло|нет согласия|улучшить нельзя' && break
  done
  wl "$m" '☁ ушло|☁ не вышло|нет согласия|улучшить нельзя' 5
}
since() { sh "tail -n +$(($1+1)) $LOG"; }
# Нажать «Улучшить»; облако бывает занято своим пересмотром — тогда ждём и жмём снова (как test_memo_device.sh).
better() {
  local k l m
  for k in 1 2 3 4 5 6; do
    m=$(mark); $ADB shell "am start -n $ACT --es better 1" >/dev/null 2>&1
    l=$(wl "$m" '☁ ушло|☁ не вышло|нет согласия|улучшить нельзя|запрос уже в работе' 180)
    printf '%s' "$l" | grep -q "запрос уже в работе" || { printf '%s\n' "$l"; return 0; }
    sleep 5
  done
  return 1
}

restore() {
  say "== возврат"
  $ADB shell "am force-stop app.falar"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1 && say "  вернул $f"
  done
  $ADB shell "rm -f $F/chats/$TID.json" && say "  тестовый разговор удалён"
  say "  текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  $ADB shell "am start -n $ACT" >/dev/null 2>&1
  rm -rf "$D"
  say; say "итог: PASS $pass, FAIL $fail, пропущено $skip"
}
trap restore EXIT

$ADB wait-for-device
[ -n "$(sh "ls $F/models/openrouter.json 2>/dev/null")" ] || { say "нет ключа OpenRouter в приложении — облачной части не будет"; exit 0; }
say "== снимок перед проверкой"
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1; done
python3 - "$D/test.json" "$TID" <<'PY'
import json, sys
path, tid = sys.argv[1], int(sys.argv[2])
T = [("pt2ru", "Bom dia! O apartamento ainda está disponível.", "Доброе утро! Квартира ещё свободна."),
     ("ru2pt", "Я хотел бы посмотреть квартиру сегодня.", "Eu gostaria de ver o apartamento hoje."),
     ("pt2ru", "Pode ser às cinco da tarde?", "Можно в пять вечера?"),
     ("ru2pt", "Да, в пять мне удобно.", "Sim, às cinco está bom para mim.")]
turns = [{"dir": d, "src": s, "dst": t, "at": tid + 1000 * (k + 1)} for k, (d, s, t) in enumerate(T)]
json.dump({"id": tid, "name": "ТЕСТ Улучшить", "named": True, "saved": tid, "turns": turns}, open(path, "w", encoding="utf-8"), ensure_ascii=False)
PY
$ADB shell "am force-stop app.falar"; sleep 1
$ADB push "$D/test.json" "$F/chats/$TID.json" >/dev/null 2>&1
m=$(mark); $ADB shell "am start -n $ACT" >/dev/null 2>&1
wl "$m" 'микрофон выключен|🎚' 120 >/dev/null; sleep 2
cur=$(sh "ls -t $F/chats/ | head -1")
[ "$cur" = "$TID.json" ] || { res 1 "B0 текущий разговор не тестовый: $cur"; exit 1; }
$ADB shell "am start -n $ACT --es silent 1" >/dev/null 2>&1; sleep 1     # переводы не звучат вслух; обычный запуск в возврате это снимает

say "== B1: в свежем разговоре «Улучшить» доступна"
l=$(state); say "  $l"
case "$l" in *"способ: cloud"*) ;; *) say "  облако сейчас не способ улучшения — проверять нечего"; skip=$((skip+1)); exit 0;; esac
printf '%s' "$l" | grep -q "можно" && res 0 "B1 доступна" || res 1 "B1 погашена в свежем разговоре"
screen_chip B1 "$l"

say "== B2: после облачного пересмотра — гаснет"
ui_b2=""
if front; then ui_b2=1; l=$(better_tap); else l=$(better); fi
say "  $(printf '%s' "$l" | cut -c1-200)"
if printf '%s' "$l" | grep -q "☁ ушло"; then
  if [ -n "$ui_b2" ]; then
    sort -u $D/cloud | sed 's/^/    /' | head -6
    if [ -s $D/cloud ]; then
      bad=$(cut -f1 $D/cloud | grep -vE '^Улучшаю в облаке · (ещё ≈ [0-9]+ с|дольше обычного · [0-9]+ с)$' | head -1)
      [ -z "$bad" ] && res 0 "B2 ход облака — в реплике: «$(head -1 $D/cloud | cut -f1)»" || res 1 "B2 подпись хода не та: «$bad»"
      grep -q $'\tвидны$' $D/cloud && res 1 "B2 пока идёт облако, кнопки реплики видны" || res 0 "B2 пока идёт облако, кнопок реплики нет — ход на их месте"
    else say "  (облако ответило раньше, чем экран успели снять, — ход не пойман)"; skip=$((skip+1)); fi
    sleep 1; dump; h=$(ui sub --rid hint | sed -n 2p)
    printf '%s' "$h" | grep -qE '^улучшено · (исправлено фраз: [0-9]+|исправлять нечего)' && res 0 "B2 итог в шапке словами: «$h»" || res 1 "B2 в шапке не итог улучшения: «$h»"
    printf '%s' "$h" | grep -qE ':free|знак|пар ' && res 1 "B2 в шапке служебное (модель, знаки, пары)" || res 0 "B2 в шапке без модели, знаков и пар"
  fi
  sleep 2; l=$(state); say "  $l"
  printf '%s' "$l" | grep -q "погашена" && res 0 "B2 погашена после пересмотра" || res 1 "B2 после пересмотра всё ещё: $l"
  screen_chip B2 "$l"
  say "== B3: новая фраза — снова доступна"
  m=$(mark); $ADB shell "am start -n $ACT --es feedtext 'Тогда до встречи в пять'" >/dev/null 2>&1
  wl "$m" 'Тогда до встречи' 60 >/dev/null; sleep 3
  l=$(state); say "  $l"
  printf '%s' "$l" | grep -q "можно" && res 0 "B3 новая фраза — доступна" || res 1 "B3 после новой фразы: $l"
  screen_chip B3 "$l"
else
  skip=$((skip+1)); say "ПРОПУСК B2–B3: облако не ответило"
  # Неудача тоже видна словами в шапке, без модели и кода ошибки: «улучшить не вышло — нет связи».
  if [ -n "$ui_b2" ] && printf '%s' "$l" | grep -q "☁ не вышло" && front; then
    sleep 1; dump; h=$(ui sub --rid hint | sed -n 2p)
    printf '%s' "$h" | grep -qE '^улучшить не вышло' && ! printf '%s' "$h" | grep -qE ':free|Exception|мс' \
      && res 0 "B2 неудача облака — в шапке словами: «$h»" || res 1 "B2 после неудачи облака в шапке: «$h»"
  fi
fi
