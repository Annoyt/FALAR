#!/bin/bash
# Память разговора на телефоне — в отдельном тестовом разговоре.
#
#   bash bench/apk/test_memo_device.sh [серийный номер]
#
# Проверяет: «кто говорит» считается по репликам и виден по касанию строки с названием; уточнитель
# получает память в фоне и пишет её в журнал; облачный пересмотр пишет ключевые детали; память,
# вписанная человеком, облаком не перезаписывается.
#
# Разговоры владельца не трогаются: тестовый разговор кладётся файлом с самым свежим временем (без
# «нового разговора», который запускает разбор покидаемого) и в конце удаляется. Выученное, словарь,
# известные слова и пины возвращаются из снимка, снятого прямо перед проверкой. Настройки разбора
# возвращаются в REFINE_EVERY и CLOUD_EVERY (по умолчанию 1 и 10 — как у владельца на 28.09).
# Облачная часть идёт, только если в приложении есть ключ OpenRouter и согласие на отправку: в облако
# уходит только синтетический тестовый разговор ниже.
#
# Экран: касания — только когда он включён и впереди Falar; экран блокировки не трогаем никогда.
R=$(cd "$(dirname "$0")/../.." && pwd)
SER=${1:-f6lnlrorgi59xwge}; ADB="$R/tools/platform-tools/adb -s $SER"
ACT=app.falar/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/app.falar/files; LOG=$F/at.log
REFINE_EVERY=${REFINE_EVERY:-1}; CLOUD_EVERY=${CLOUD_EVERY:-10}
D=$(mktemp -d /tmp/falar-memo.XXXX); SNAP=$D/snap; mkdir -p $SNAP
TID=$(date +%s%3N)
pass=0; fail=0; skip=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sk() { skip=$((skip+1)); say "ПРОПУСК $1"; }
sh() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
# ждать строку журнала после отметки; шаблон — расширенный (grep -E): у toybox «\|» в простом не работает
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
since() { sh "tail -n +$(($1+1)) $LOG"; }
front() { sh "dumpsys power" | grep -q "mWakefulness=Awake" && sh "dumpsys window" | grep -m1 mCurrentFocus | grep -q "app.falar/"; }
chat() { $ADB pull "$F/chats/$TID.json" "$D/chat.json" >/dev/null 2>&1 && python3 -c "import json,sys; o=json.load(open('$D/chat.json',encoding='utf-8')); print(o.get(sys.argv[1],''))" "$1"; }
dump() { sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml"; }
# центр узла с таким resource-id (строка с названием разговора — app.falar:id/hint)
node() { python3 -c "
import re,sys
x=sys.stdin.read()
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0)
    if 'resource-id=\"'+sys.argv[1]+'\"' in n:
        a,b,c,d=map(int,re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', n).groups()); print((a+c)//2,(b+d)//2); break" "$1"; }
alltext() { python3 -c "
import re,sys,html
print('\n'.join(html.unescape(t) for t in re.findall(r'text=\"([^\"]*)\"', sys.stdin.read()) if t))"; }

restore() {
  say "== возврат"
  $ADB shell "am start -n $ACT --es listen off --es refineevery $REFINE_EVERY --es cloudevery $CLOUD_EVERY" >/dev/null 2>&1; sleep 3
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
say "== снимок перед проверкой"
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1; done
say "  $(ls $SNAP | tr '\n' ' ')"

say "== тестовый разговор $TID: хозяйка квартиры (женщина) и съёмщик (мужчина)"
python3 - "$D/test.json" "$TID" <<'EOF'
import json, sys
path, tid = sys.argv[1], int(sys.argv[2])
T = [("pt2ru", "Olá, eu sou a dona do apartamento. Muito obrigada por vir.", "Здравствуйте, я владелец квартиры. Большое спасибо, что пришли."),
     ("ru2pt", "Здравствуйте, я пришёл посмотреть квартиру.", "Olá, vim ver o apartamento."),
     ("pt2ru", "O aluguel é dois mil e quinhentos por mês, com condomínio incluído.", "Аренда — две тысячи пятьсот в месяц, включая кондоминиум."),
     ("ru2pt", "Я понял. А можно с животными? У нас кошка.", "Entendi. Pode com animais? Temos uma gata."),
     ("pt2ru", "Pode sim, eu mesma tenho dois gatos.", "Да, у меня самого две кошки."),
     ("ru2pt", "Отлично. Когда можно заехать?", "Ótimo. Quando posso me mudar?")]
turns = [{"dir": d, "src": s, "dst": t, "at": tid + 1000 * (k + 1)} for k, (d, s, t) in enumerate(T)]
json.dump({"id": tid, "name": "ТЕСТ Falar", "named": True, "saved": tid, "turns": turns}, open(path, "w", encoding="utf-8"), ensure_ascii=False)
EOF
$ADB shell "am force-stop app.falar"; sleep 1
$ADB push "$D/test.json" "$F/chats/$TID.json" >/dev/null 2>&1
m=$(mark); $ADB shell "am start -n $ACT" >/dev/null 2>&1
st=$(wl "$m" 'восстановлено реплик|микрофон выключен' 120)
wl "$m" 'микрофон выключен|🎚' 120 >/dev/null; sleep 2
cur=$(sh "ls -t $F/chats/ | head -1")
[ "$cur" = "$TID.json" ] && res 0 "M0 открыт тестовый разговор" || { res 1 "M0 текущий разговор не тестовый: $cur"; exit 1; }
printf '%s' "$st" | grep -q "восстановлено реплик 6" && res 0 "M0 после запуска рабочая история — из разговора (6 реплик)" || res 1 "M0 история после запуска: ${st:-нет строки}"
$ADB shell "am start -n $ACT --es refineevery 1 --es cloudevery 0" >/dev/null 2>&1; sleep 2

say "== M1: «Память разговора» по касанию строки с названием"
if front; then
  xy=$(dump | node "app.falar:id/hint")
  if [ -n "$xy" ] && front; then
    $ADB shell "input tap $xy"; sleep 2
    txt=$(dump | alltext)
    printf '%s\n' "$txt" | grep -q "Кто говорит: по-португальски — женщина, по-русски — мужчина." \
      && res 0 "M1 «кто говорит» посчитан по репликам и виден" || { res 1 "M1 нет строки «кто говорит»"; printf '%s\n' "$txt" | head -12; }
    printf '%s\n' "$txt" | grep -q "Ключевые детали — пока нет" && res 0 "M1 деталей пока нет — так и сказано" || res 1 "M1 нет строки о деталях"
    $ADB exec-out screencap -p > /tmp/falar-memo-dialog.png 2>/dev/null
    front && $ADB shell "input keyevent 4"; sleep 1
  else sk "M1 строка с названием не найдена на экране"; fi
else sk "M1 экран выключен или впереди не Falar — касаний не делаем"; fi

say "== M2: уточнитель получает память — реплика хозяйки, где род виден только из разговора"
# «Eu vi … fiquei feliz» — португальский рода не показывает, а по-русски нужно «видела», «была рада».
feed() { $ADB shell "am start -n $ACT --es feedtext '$1'" >/dev/null 2>&1; }
m=$(mark); feed 'Eu vi o anúncio ontem e fiquei feliz que alguém ligou tão rápido.'
raw=$(wl "$m" '#[0-9]+ pt2ru' 60); n=$(printf '%s' "$raw" | grep -o '#[0-9]*' | head -1)
say "  сырой: $(printf '%s' "$raw" | cut -c1-220)"
l=$(wl "$m" 'память для уточнителя|разбор отложен' 90)
if printf '%s' "$l" | grep -q "разбор отложен"; then
  # Ядро и уточнитель вместе на этом телефоне не помещаются (results/2026-09-28-memory.md). Для
  # проверки порог снижается до перезапуска; система при подъёме освободит место за счёт фоновых.
  say "  $(printf '%s' "$l" | cut -c1-200)"; say "  порог снижен для проверки: --es llmneed 900"
  $ADB shell "am start -n $ACT --es llmneed 900" >/dev/null 2>&1; sleep 1
  m=$(mark); feed 'Eu fui lá hoje cedo e deixei a chave com o porteiro.'
  raw=$(wl "$m" '#[0-9]+ pt2ru' 60); n=$(printf '%s' "$raw" | grep -o '#[0-9]*' | head -1)
  say "  сырой: $(printf '%s' "$raw" | cut -c1-220)"
  l=$(wl "$m" 'память для уточнителя|разбор отложен' 120)
fi
if printf '%s' "$l" | grep -q "память для уточнителя"; then
  say "  $(printf '%s' "$l" | cut -c1-300)"
  printf '%s' "$l" | grep -q "Кто говорит: по-португальски — женщина" && res 0 "M2 в памяти уточнителя — кто говорит" || res 1 "M2 в памяти нет «кто говорит»"
  r=$(wl "$m" "🔁 $n " 150); say "  $(printf '%s' "$r" | cut -c1-300)"
  if printf '%s' "$r" | grep -qE "по контексту|не изменил"; then res 0 "M2 уточнитель ответил с памятью в фоне"
  else res 1 "M2 уточнитель не ответил: ${r:-нет строки}"; fi
  printf '%s' "$r" | grep -qE "видела|была рада|пошла|ходила|оставила|сходила" && res 0 "M2 в уточнённом переводе женский род" \
    || say "  (женского рода в ответе нет — смотрите строку выше)"
else res 1 "M2 разбора не было: ${l:-нет строки}"; fi

# «Улучшить» и ответ облака. Пока озвучивается правка прошлого пересмотра, облако занято («запрос
# уже в работе») — тогда повторяем через несколько секунд.
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
say "== M3: облачный пересмотр пишет ключевые детали"
if [ -n "$(sh "ls $F/models/openrouter.json 2>/dev/null")" ]; then
  l=$(better); say "  $(printf '%s' "$l" | cut -c1-300)"
  if printf '%s' "$l" | grep -q "память обновлена"; then
    res 0 "M3 память от облака записана"
    say "  память: $(chat memo)"; [ "$(chat memoBy)" = "cloud" ] && res 0 "M3 пометка «облако»" || res 1 "M3 пометка: $(chat memoBy)"
  elif printf '%s' "$l" | grep -q "☁ ушло"; then res 1 "M3 облако ответило без памяти"
  else sk "M3 облако недоступно: $l"; fi

  say "== M4: память, вписанная человеком, облаком не перезаписывается"
  MINE="Хозяйка квартиры (женщина) сдаёт её мне (мужчина), 2500 в месяц, можно с кошкой"
  m=$(mark); $ADB shell "am start -n $ACT --es memo '$MINE'" >/dev/null 2>&1
  wl "$m" 'записана вами' 20 >/dev/null && res 0 "M4 память записана как человеческая" || res 1 "M4 нет записи"
  l=$(better); say "  $(printf '%s' "$l" | cut -c1-300)"
  if printf '%s' "$l" | grep -q "☁ ушло"; then
    [ "$(chat memo)" = "$MINE" ] && [ "$(chat memoBy)" = "user" ] && res 0 "M4 после пересмотра память та же, пометка «человек»" || res 1 "M4 память изменилась: $(chat memoBy) · $(chat memo)"
    printf '%s' "$l" | grep -q "память ваша, не тронута" && say "  журнал: облако прислало память, ваша не тронута" || say "  (облако на этот раз памяти не прислало)"
  else sk "M4 облако не ответило: $l"; fi
  if front; then
    xy=$(dump | node "app.falar:id/hint")
    if [ -n "$xy" ] && front; then
      $ADB shell "input tap $xy"; sleep 2
      dump | alltext | grep -q "Ключевые детали (ваши" && res 0 "M5 экран показывает, что память ваша" || res 1 "M5 на экране не видно, что память ваша"
      $ADB exec-out screencap -p > /tmp/falar-memo-dialog-user.png 2>/dev/null
      front && $ADB shell "input keyevent 4"; sleep 1
    else sk "M5 строка с названием не найдена"; fi
  else sk "M5 экран выключен или впереди не Falar"; fi
  m=$(mark); $ADB shell "am start -n $ACT --es memo off" >/dev/null 2>&1
  wl "$m" 'возвращена автоматике' 20 >/dev/null && [ -z "$(chat memo)" ] && res 0 "M6 «вернуть облаку» стирает память человека" || res 1 "M6 память не стёрта"
else sk "M3–M6 нет ключа OpenRouter в приложении"; fi
