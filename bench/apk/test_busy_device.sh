#!/bin/bash
# Ход перевода реплики на экране разговора — в отдельном тестовом разговоре.
#
#   bash bench/apk/test_busy_device.sh
#
# Подаётся реплика собеседницы; проверяется: пока уточнитель поднимается и уточняет, ход виден в
# самой реплике (0.26.0) — подпись этапа, отрезки «распознаю · перевожу · уточняю» по этапу, а кнопок
# реплики на это время нет; по машинному журналу (at.tsv, строки busy) — порядок стадий и сколько
# длится каждая; после конца хода на его месте снова кнопки. Если уточнителю не хватило памяти и
# разбор отложен, порог снижается до перезапуска (--es llmneed 900) и подаётся вторая фраза. Разговоры владельца не трогаются: тестовый разговор — отдельным файлом, выученное
# и словари возвращаются из снимка, настройки разбора — к тем, что были (REFINE_EVERY/CLOUD_EVERY — явно).
R=$(cd "$(dirname "$0")/../.." && pwd); A=$R/bench/apk
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log; TSV=$F/at.tsv
REF=; CLOUD=
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
# Настройки, к которым вернуться: частота разбора и облака — из окружения (REFINE_EVERY, CLOUD_EVERY),
# иначе у самого приложения (стенд --es settings show читает сохранённые настройки), у прежних
# сборок — из журнала; без них — как в приложении по умолчанию. Зовётся после free_phone: запускает Falar.
every() { sh "grep -E '$1: (каждые|только)' $LOG | tail -1" | sed -n 's/.*каждые \([0-9]*\).*/\1/p;s/.*только по кнопке.*/0/p'; }
orig_settings() {
  REF=${REFINE_EVERY:-}; CLOUD=${CLOUD_EVERY:-}
  [ -n "$REF" ] && [ -n "$CLOUD" ] && return
  local m st; m=$(mark $LOG); $ADB shell "am start -n $ACT --es settings show" >/dev/null 2>&1
  st=$(wl "$m" '🧪 настройки:' 15)
  [ -z "$REF" ] && REF=$(printf '%s' "$st" | sed -n 's/.*разбор \([0-9][0-9]*\).*/\1/p')
  [ -z "$CLOUD" ] && CLOUD=$(printf '%s' "$st" | sed -n 's/.*облако \([0-9][0-9]*\).*/\1/p')
  [ -z "$REF" ] && REF=$(every 'разбор контекста'); [ -z "$CLOUD" ] && CLOUD=$(every 'пересмотр разговора в облаке')
  REF=${REF:-3}; CLOUD=${CLOUD:-0}
}
restore() {
  say "== возврат"
  $ADB shell "am start -n $ACT ${REF:+--es refineevery $REF} ${CLOUD:+--es cloudevery $CLOUD} --es silent 0" >/dev/null 2>&1; sleep 2
  say "  разбор: $REF, облако: $CLOUD"
  $ADB shell "am force-stop $PKG"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1; done
  $ADB shell "rm -f $F/chats/$TID.json /data/local/tmp/falar_px.sh"; say "  тестовый разговор удалён · текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  $ADB shell "am start -n $ACT" >/dev/null 2>&1; rm -rf "$D"
  say; say "итог: PASS $pass, FAIL $fail"
}
trap restore EXIT

$ADB wait-for-device; free_phone; orig_settings
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

say "== B1–B3: реплика → перевод → уточнитель, ход — в самой реплике"
# Ход реплики (0.26.0) — на месте её кнопок: отрезки «распознаю · перевожу · уточняю» и подпись
# (id busy); «Улучшить» и «Запомнить» в это время не видно. Отрезки — по цвету правого края каждого
# на снимке без сжатия: пройденный — слива (ночью золото), текущий — мята, следующий — цвет рамки.
$ADB push $A/px_phone.sh /data/local/tmp/falar_px.sh >/dev/null 2>&1
dump() { sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" > $D/ui.xml; }
segs() {   # отрезки хода по снимку: «пройден пройден текущий»
  local bar pts
  bar=$(python3 $A/ui.py kids $D/ui.xml --rid busy | awk '$5 == "android.view.View" {print $1, $2, $3, $4; exit}')
  [ -n "$bar" ] || return
  pts=$(python3 -c "
x0,y0,x1,y1=map(int,'$bar'.split()); d=(y1-y0)/8; n=3; g=4*d; w=(x1-x0-g*(n-1))/n
print(*[v for i in range(n) for v in (round(x0+i*(w+g)+w-2*d), (y0+y1)//2)])")
  sh "sh /data/local/tmp/falar_px.sh 0 $pts" | python3 -c "
import sys
v=list(map(int,sys.stdin.read().split()[1:])); C={'пройден':[(224,184,120),(128,34,68)],'текущий':[(111,224,188)],'впереди':[(53,43,61),(230,223,230)]}
d=lambda a,b: sum((x-y)**2 for x,y in zip(a,b))
print(*[min(C, key=lambda k: min(d(v[i*3:i*3+3], c) for c in C[k])) for i in range(len(v)//3)])"
}
# Кнопки реплики: «Улучшить» прячется без облака и уточнителя, поэтому смотрим и «Запомнить».
chips() { { python3 $A/ui.py find $D/ui.xml --text 'Улучшить' || python3 $A/ui.py find $D/ui.xml --text 'Запомнить'; } >/dev/null && echo видны || echo скрыты; }
states=$D/states; : > $states
watch() {   # подать фразу и ловить ход на экране, пока не кончится разбор (или его не отложат)
  local i m b
  m=$(mark $LOG); $ADB shell "am start -n $ACT --es feedtext '$1'" >/dev/null 2>&1
  for i in $(seq 60); do
    if awake_front; then
      dump; b=$(python3 $A/ui.py sub $D/ui.xml --rid busy | head -1)
      if [ -n "$b" ]; then
        printf '%s\t%s\t%s\n' "$b" "$(segs)" "$(chips)" >> $states
        [ -s /tmp/falar-busy.png ] || $ADB exec-out screencap -p > /tmp/falar-busy.png 2>/dev/null
      fi
    fi
    sh "tail -n +$((m+1)) $LOG" | grep -qE "разбор контекста: реплик|разбор отложен" && break
    sleep 0.5
  done
}
rm -f /tmp/falar-busy.png
mt=$(mark $TSV); m=$(mark $LOG)
watch 'Eu fui lá hoje cedo e deixei a chave com o porteiro.'
if sh "tail -n +$((m+1)) $LOG" | grep -q "разбор отложен"; then
  # Ядро и уточнитель вместе на этом телефоне помещаются не всегда (results/2026-09-28-memory.md):
  # тогда ход уточнения длится 0 с и ловить нечего. Порог снижается до перезапуска, как в test_memo.
  say "  $(sh "tail -n +$((m+1)) $LOG" | grep -m1 "разбор отложен" | cut -c1-160)"
  say "  порог снижен для проверки: --es llmneed 900 (до перезапуска)"
  $ADB shell "am start -n $ACT --es llmneed 900" >/dev/null 2>&1; sleep 1
  m=$(mark $LOG); watch 'A cozinha é pequena, mas a sala tem muita luz.'
fi
say "  на экране (подпись · отрезки · кнопки реплики):"; sed 's/^/    /' $states | sort -u | head -8
if [ -s $states ]; then
  res 0 "B1 ход виден в реплике, пока приложение работает: «$(head -1 $states | cut -f1)»"
  grep -q $'\tвидны$' $states && res 1 "B1 во время хода кнопки реплики видны" || res 0 "B1 во время хода кнопок реплики нет — ход на их месте"
  bad=$(python3 - $states <<'PY'
import sys
bad = []
for line in open(sys.argv[1], encoding='utf-8'):
    cap, seg, _ = line.rstrip('\n').split('\t')
    if not seg: continue
    k = 2 if ('уточн' in cap or 'уточнитель' in cap) else 1 if cap.startswith('перевожу') else 0
    want = ' '.join(['пройден'] * k + ['текущий'] + ['впереди'] * (2 - k))
    if seg != want: bad.append(cap + ': ' + seg + ' вместо ' + want)
print('; '.join(bad))
PY
)
  [ -z "$(cut -f2 $states | tr -d '\n ')" ] && say "  (полосы этапов в дереве экрана не нашлось — отрезки не проверены)" \
    || { [ -z "$bad" ] && res 0 "B1 отрезки этапов по подписи: пройденные, текущий, следующие" || res 1 "B1 отрезки не по этапу: $bad"; }
else awake_front && res 1 "B1 ход не виден" || say "  (экран выключен или впереди не Falar — экран не проверен)"; fi
wl "$m" 'разбор контекста: реплик|разбор отложен' 120 | cut -c1-160 | sed 's/^/  /'
sleep 3
ev=$(sh "tail -n +$((mt+1)) $TSV" | awk -F'\t' '$2=="busy"{print $1"\t"$3"\t"$4"\t"$5"\t"$6}')
say "  стадии (от подачи, с):"
printf '%s\n' "$ev" | awk -F'\t' 'NR==1{t0=$1} {printf "    %6.1f  %-7s %s%s\n", ($1-t0)/1000, $2, $3, ($5>0? " · "$4+1" из "$5 : "")}'
printf '%s\n' "$ev" | grep -q "live.перевожу" && printf '%s\n' "$ev" | grep -qP "live\t-" && res 0 "B2 «перевожу…» появилась и ушла" || res 1 "B2 нет стадии перевода"
if printf '%s\n' "$ev" | grep -q "уточняю перевод"; then
  res 0 "B3 «уточняю перевод» с номером реплики"
  last=$(printf '%s\n' "$ev" | grep "refine" | tail -1 | cut -f3)
  [ "$last" = "-" ] && res 0 "B3 после разбора ход ушёл" || res 1 "B3 ход остался: $last"
else res 1 "B3 нет стадии уточнения (уточнитель не поднялся — строка выше)"; fi
if awake_front; then
  dump
  python3 $A/ui.py find $D/ui.xml --rid busy >/dev/null && res 1 "B4 после разбора ход всё ещё на экране" \
    || [ "$(chips)" = видны ] && res 0 "B4 после разбора на месте хода — снова кнопки реплики" || res 1 "B4 после разбора нет ни хода, ни кнопок реплики"
fi
[ -s /tmp/falar-busy.png ] && say "  снимок экрана: /tmp/falar-busy.png"
