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
# возвращаются к тем, что были (REFINE_EVERY и CLOUD_EVERY задают их явно).
# Облачная часть идёт, только если в приложении есть ключ OpenRouter и согласие на отправку: в облако
# уходит только синтетический тестовый разговор ниже.
#
# Экран: касания — только когда он включён и впереди Falar; экран блокировки не трогаем никогда.
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
REF=; CLOUD=
D=$(mktemp -d /tmp/falar-memo.XXXX); SNAP=$D/snap; mkdir -p $SNAP
TID=$(date +%s%3N)
pass=0; fail=0; skip=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sk() { skip=$((skip+1)); say "ПРОПУСК $1"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }   # не из stdin: внутри «while read» adb съел бы его
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
# ждать строку журнала после отметки; шаблон — расширенный (grep -E): у toybox «\|» в простом не работает
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
since() { sh "tail -n +$(($1+1)) $LOG"; }
# Настройки, к которым вернуться: частота разбора и облака — из окружения (REFINE_EVERY, CLOUD_EVERY),
# иначе у самого приложения (стенд --es settings show читает сохранённые настройки), у прежних
# сборок — из журнала; без них — как в приложении по умолчанию. Зовётся после free_phone: запускает Falar.
every() { sh "grep -E '$1: (каждые|только)' $LOG | tail -1" | sed -n 's/.*каждые \([0-9]*\).*/\1/p;s/.*только по кнопке.*/0/p'; }
orig_settings() {
  REF=${REFINE_EVERY:-}; CLOUD=${CLOUD_EVERY:-}
  [ -n "$REF" ] && [ -n "$CLOUD" ] && return
  local m st; m=$(mark); $ADB shell "am start -n $ACT --es settings show" >/dev/null 2>&1
  st=$(wl "$m" '🧪 настройки:' 15)
  [ -z "$REF" ] && REF=$(printf '%s' "$st" | sed -n 's/.*разбор \([0-9][0-9]*\).*/\1/p')
  [ -z "$CLOUD" ] && CLOUD=$(printf '%s' "$st" | sed -n 's/.*облако \([0-9][0-9]*\).*/\1/p')
  [ -z "$REF" ] && REF=$(every 'разбор контекста'); [ -z "$CLOUD" ] && CLOUD=$(every 'пересмотр разговора в облаке')
  REF=${REF:-3}; CLOUD=${CLOUD:-0}
}
free_phone() {
  local n=0
  while :; do
    local f; f=$(sh "dumpsys window | grep -m1 mCurrentFocus")
    case "$f" in *app.falar*|*com.miui.home*|*launcher*|*mCurrentFocus=null*) return 0;; esac
    n=$((n+1)); [ $n -eq 1 ] && say "  телефон занят ($f), жду…"; sleep 10
  done
}
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
  $ADB shell "am start -n $ACT --es listen off ${REF:+--es refineevery $REF} ${CLOUD:+--es cloudevery $CLOUD}" >/dev/null 2>&1; sleep 3
  say "  разбор: $REF, облако: $CLOUD"
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

$ADB wait-for-device; free_phone; orig_settings
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
    lab=$(printf '%s\n' "$txt" | grep -oE 'от облака|от модели|облачный пересмотр|уточнител|глоссари|obrigada' | sort -u | tr '\n' ' ')
    [ -z "$lab" ] && res 0 "M1 без пояснений для разработчика (владелец 01.10)" || res 1 "M1 в окне служебное: $lab"
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
  # Окно памяти — без пометок, откуда она (владелец 01.10: «зачем простому пользователю информация,
  # что от облака»): вписанное вами видно своим текстом, а вернуть его автоматике — в «ещё…».
  ui6=""
  if front; then
    xy=$(dump | node "app.falar:id/hint")
    if [ -n "$xy" ] && front; then
      $ADB shell "input tap $xy"; sleep 2
      dump > $D/m5.xml; txt=$(alltext < $D/m5.xml)
      printf '%s\n' "$txt" | grep -qF "$MINE" && res 0 "M5 в окне — память, вписанная вами" || { res 1 "M5 в окне нет вписанной памяти"; printf '%s\n' "$txt" | head -12; }
      lab=$(printf '%s\n' "$txt" | grep -oE '\(ваши|от облака|от модели|автоматика их не меняет|уточнител|глоссари' | sort -u | tr '\n' ' ')
      [ -z "$lab" ] && res 0 "M5 без пометок, откуда память" || res 1 "M5 в окне пометки: $lab"
      $ADB exec-out screencap -p > /tmp/falar-memo-dialog-user.png 2>/dev/null
      xy=$(python3 $R/bench/apk/ui.py find $D/m5.xml --text "ещё…" | cut -d' ' -f5,6)
      if [ -n "$xy" ] && front; then
        $ADB shell "input tap $xy"; sleep 1.5
        xy=$(dump > $D/m6.xml; python3 $R/bench/apk/ui.py find $D/m6.xml --text "вернуть память автоматике" | cut -d' ' -f5,6)
        if [ -n "$xy" ] && front; then
          m=$(mark); $ADB shell "input tap $xy"; ui6=1
          wl "$m" 'возвращена автоматике' 20 >/dev/null && [ -z "$(chat memo)" ] && res 0 "M6 «ещё…» → «вернуть память автоматике» стирает память человека" || res 1 "M6 память не стёрта из меню"
        else res 1 "M6 в «ещё…» нет «вернуть память автоматике»"; front && $ADB shell "input keyevent 4"; fi
      else res 1 "M5 у окна вашей памяти нет кнопки «ещё…»"; front && $ADB shell "input keyevent 4"; fi
      sleep 1
    else sk "M5 строка с названием не найдена"; fi
  else sk "M5 экран выключен или впереди не Falar"; fi
  if [ -z "$ui6" ]; then
    m=$(mark); $ADB shell "am start -n $ACT --es memo off" >/dev/null 2>&1
    wl "$m" 'возвращена автоматике' 20 >/dev/null && [ -z "$(chat memo)" ] && res 0 "M6 «вернуть облаку» стирает память человека" || res 1 "M6 память не стёрта"
  fi
else sk "M3–M6 нет ключа OpenRouter в приложении"; fi
