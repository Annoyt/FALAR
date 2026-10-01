#!/bin/bash
# «Улучшить» гаснет, когда последняя фраза уже обработана, — на телефоне, в отдельном тестовом разговоре.
#
#   bash bench/apk/test_better_device.sh [серийный номер]
#
# Владелец 01.10: «надо тушить кнопку улучшить, если данная фраза уже была обработана». Проверяет:
# B1 — в свежем тестовом разговоре кнопка доступна; B2 — после облачного пересмотра гаснет; B3 — новая
# фраза зажигает её снова. Состояние — стендовой командой --es betterstate 1 (строка «🧪 «Улучшить»: …»
# в журнале): так же решает экран, и проверка идёт и при погашенном экране.
#
# Разговоры владельца не трогаются — схема test_memo_device.sh: тестовый разговор кладётся файлом с
# самым свежим временем и в конце удаляется, выученное и словари возвращаются из снимка. В облако
# уходит только синтетический разговор ниже (1–5 бесплатных запросов OpenRouter).
R=$(cd "$(dirname "$0")/../.." && pwd)
SER=${1:-f6lnlrorgi59xwge}; ADB="$R/tools/platform-tools/adb -s $SER"
ACT=app.falar/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/app.falar/files; LOG=$F/at.log
D=$(mktemp -d /tmp/falar-better.XXXX); SNAP=$D/snap; mkdir -p $SNAP
TID=$(date +%s%3N)
pass=0; fail=0; skip=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
state() { local m; m=$(mark); $ADB shell "am start -n $ACT --es betterstate 1" >/dev/null 2>&1; wl "$m" '🧪 «Улучшить»' 15; }
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

say "== B2: после облачного пересмотра — гаснет"
l=$(better); say "  $(printf '%s' "$l" | cut -c1-200)"
if printf '%s' "$l" | grep -q "☁ ушло"; then
  sleep 2; l=$(state); say "  $l"
  printf '%s' "$l" | grep -q "погашена" && res 0 "B2 погашена после пересмотра" || res 1 "B2 после пересмотра всё ещё: $l"
  say "== B3: новая фраза — снова доступна"
  m=$(mark); $ADB shell "am start -n $ACT --es feedtext 'Тогда до встречи в пять'" >/dev/null 2>&1
  wl "$m" 'Тогда до встречи' 60 >/dev/null; sleep 3
  l=$(state); say "  $l"
  printf '%s' "$l" | grep -q "можно" && res 0 "B3 новая фраза — доступна" || res 1 "B3 после новой фразы: $l"
else skip=$((skip+1)); say "ПРОПУСК B2–B3: облако не ответило"; fi
