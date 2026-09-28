#!/bin/bash
# Полоса «что приложение делает сейчас» на экране разговора — в отдельном тестовом разговоре.
#
#   bash bench/apk/test_busy_device.sh
#
# Подаётся реплика собеседницы; проверяется: полоса видна, пока уточнитель поднимается и уточняет;
# по машинному журналу (at.tsv, строки busy) — порядок стадий и сколько длится каждая; после конца
# полоса пропадает. Разговоры владельца не трогаются: тестовый разговор — отдельным файлом, выученное
# и словари возвращаются из снимка, настройки разбора — в REFINE_EVERY/CLOUD_EVERY.
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log; TSV=$F/at.tsv
REFINE_EVERY=${REFINE_EVERY:-1}; CLOUD_EVERY=${CLOUD_EVERY:-10}
D=$(mktemp -d /tmp/falar-busy.XXXX); SNAP=$D/snap; mkdir -p $SNAP; TID=$(date +%s%3N)
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
awake_front() { sh "dumpsys power" | grep -q "mWakefulness=Awake" && sh "dumpsys window" | grep -m1 mCurrentFocus | grep -q "$PKG/"; }
mark() { sh "wc -l < $1" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
restore() {
  say "== возврат"
  $ADB shell "am start -n $ACT --es refineevery $REFINE_EVERY --es cloudevery $CLOUD_EVERY --es silent 0" >/dev/null 2>&1; sleep 2
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
T = [("pt2ru", "Olá, eu sou a dona do apartamento. Muito obrigada por vir.", "Здравствуйте, я владелец квартиры. Большое спасибо, что пришли."),
     ("ru2pt", "Здравствуйте, я пришёл посмотреть квартиру.", "Olá, vim ver o apartamento.")]
turns = [{"dir": d, "src": s, "dst": t, "at": tid + 1000 * (k + 1)} for k, (d, s, t) in enumerate(T)]
json.dump({"id": tid, "name": "ТЕСТ полоса", "named": True, "saved": tid, "turns": turns}, open(path, "w", encoding="utf-8"), ensure_ascii=False)
EOF
$ADB shell "am force-stop $PKG"; sleep 1; $ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
m=$(mark $LOG); $ADB shell "am start -n $ACT" >/dev/null 2>&1; wl "$m" 'микрофон выключен' 120 >/dev/null; sleep 2
$ADB shell "am start -n $ACT --es refineevery 1 --es cloudevery 0 --es silent 1" >/dev/null 2>&1; sleep 2

say "== B1–B3: реплика → перевод → уточнитель, полоса на экране"
mt=$(mark $TSV); m=$(mark $LOG)
$ADB shell "am start -n $ACT --es feedtext 'Eu fui lá hoje cedo e deixei a chave com o porteiro.'" >/dev/null 2>&1
seen=""
for i in $(seq 40); do
  if awake_front; then
    b=$(sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" | python3 -c "
import re,sys
x=sys.stdin.read()
m=re.search(r'resource-id=\"app.falar:id/busy\".*?text=\"([^\"]+)\"', x, re.S)
print(m.group(1) if m else '')")
    [ -n "$b" ] && { seen="$b"; $ADB exec-out screencap -p > /tmp/falar-busy.png 2>/dev/null; break; }
  fi
  sh "tail -n +$((m+1)) $LOG" | grep -qE "разбор контекста: реплик|разбор отложен" && break
  sleep 1
done
say "  на экране: ${seen:-ничего не поймано}"
[ -n "$seen" ] && res 0 "B1 полоса видна, пока приложение работает: «$seen»" || { awake_front && res 1 "B1 полоса не видна" || say "  (экран выключен или впереди не Falar — экран не проверен)"; }
wl "$m" 'разбор контекста: реплик|разбор отложен' 120 | cut -c1-160 | sed 's/^/  /'
sleep 3
ev=$(sh "tail -n +$((mt+1)) $TSV" | awk -F'\t' '$2=="busy"{print $1"\t"$3"\t"$4"\t"$5"\t"$6}')
say "  стадии (от подачи, с):"
printf '%s\n' "$ev" | awk -F'\t' 'NR==1{t0=$1} {printf "    %6.1f  %-7s %s%s\n", ($1-t0)/1000, $2, $3, ($5>0? " · "$4+1" из "$5 : "")}'
printf '%s\n' "$ev" | grep -q "live.перевожу" && printf '%s\n' "$ev" | grep -qP "live\t-" && res 0 "B2 «перевожу…» появилась и ушла" || res 1 "B2 нет стадии перевода"
if printf '%s\n' "$ev" | grep -q "refine"; then
  printf '%s\n' "$ev" | grep -q "уточняю перевод" && res 0 "B3 «уточняю перевод» с номером реплики" || res 1 "B3 нет стадии уточнения"
  last=$(printf '%s\n' "$ev" | grep "refine" | tail -1 | cut -f3)
  [ "$last" = "-" ] && res 0 "B3 после разбора полоса ушла" || res 1 "B3 полоса осталась: $last"
else say "  (уточнитель не поднялся — смотрите строку «разбор отложен» выше)"; fi
