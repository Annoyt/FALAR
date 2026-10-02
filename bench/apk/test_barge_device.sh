#!/bin/bash
# Перебивание на телефоне: человек поверх озвучки — озвучка смолкает, а сказанное распознаётся.
#
#   bash bench/apk/test_barge_device.sh            # INSTALL=1 — сначала поставить bench/apk/Falar.apk
#   bash bench/apk/test_barge_device.sh --restore  # только вернуть телефон после оборванного прогона
#   VOL=10 …   # громкость озвучки на время прогона (прежняя возвращается); без VOL — нужна не ниже 5 из 15
#   DUMP=3 …   # только записать совмещение микрофона и сыгранного на концах трёх фраз (barge-dump)
#
# Проверяет (в отдельном тестовом разговоре, «человек» — колонки ПК, живые фразы из bench/air/corpus):
#  B0 короткая фраза (2,8 с) начинает звучать сразу, а не когда придёт следующая (порог старта дорожки);
#  B1 перебивание само сверяет время динамика по эху (поправка меток времени) и учит эхо этой громкости;
#  B2 только озвучка, без человека: ни одной остановки (провалы громкости считаются и печатаются);
#  B3 человек поверх озвучки с перебиванием: озвучка смолкла, его слова распознаны — сколько из сказанных;
#  B4 то же без перебивания (--es barge 0), как было до него: сколько слов человека распознано.
# Главное число — B3 против B4: доля слов человека, сказанных поверх озвучки, в распознанном.
#
# Данные владельца не трогаются: тестовый разговор «ТЕСТ перебивание» с самым свежим временем, в конце
# удаляется; выученное, словари, свои слова — из снимка; модули, «вслух», ожидание паузы и перебивание —
# как были; слушание в конце выключено (оно и было выключено — иначе прогон не начинается). Модуль
# отпечатка на время прогона выключен: в тестовом разговоре голосов нет, слушание переводило бы ничего
# (skip_novoice). Громкость — та, что у владельца. Прогон оборвался, даже kill -9, — следующий запуск
# сначала возвращает телефон (или --restore). Замок общий с остальными стендами.
R=$(cd "$(dirname "$0")/../.." && pwd)
SER=${SER:-f6lnlrorgi59xwge}; ONLY_RESTORE=0
for a in "$@"; do case "$a" in --restore) ONLY_RESTORE=1;; esac; done
ADB="$R/tools/platform-tools/adb -s $SER"
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log; TSV=$F/at.tsv
STATE=${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand; mkdir -p "$STATE"; PEND=$STATE/barge-pending
FILES="models/learned.json models/phrasebook_user.json word_ru.json known_words.json models/wordlist.json"
PY=$R/.venv/bin/python; [ -x "$PY" ] || PY=python3
pass=0; fail=0; skip=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
tmark() { sh "wc -l < $TSV" | awk '{print $1+0}'; }
wl() { local i l; for i in $(seq "$3"); do l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
since() { sh "tail -n +$(($1+1)) $LOG"; }
focus() { sh "dumpsys window" | grep -m1 mCurrentFocus; }
asleep() { sh "dumpsys power" | grep -qE 'mWakefulness=(Asleep|Dozing)'; }
idle() {
  asleep && return 0
  case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) ;; *) return 1;; esac
  local a; a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
  [ "${a:-0}" -ge 60000 ]
}
wait_idle() { local n=0; until idle; do n=$((n+1)); [ $n -eq 1 ] && say "  жду, пока телефон свободен ($(focus))"; sleep 15; done; }
launch() { wait_idle; $ADB shell "am start -n $ACT $*" >/dev/null 2>&1; }
# свободен для прогона: экрана не касались 3 минуты, журнал молчит 3 минуты, слушание выключено
free3() {
  local a quiet listening
  a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
  quiet=$(( $(sh "date +%s") - $(sh "stat -c %Y $LOG 2>/dev/null || echo 0") ))
  listening=$(sh "grep -E '▶ слушаю|⏹ не слушаю' $LOG | tail -1" | grep -c '▶ слушаю')
  [ "$listening" = 0 ] && [ "$quiet" -ge 180 ] && { asleep || { case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) true;; *) false;; esac && [ "${a:-0}" -ge 180000 ]; }; }
}

# ---- замок -----------------------------------------------------------------------------------------
if [ -z "$FALAR_STAND_LOCK" ]; then
  exec 9>"$STATE/lock"
  flock -n 9 || { say "  замок занят — жду, пока освободится"; flock -w ${LOCK_WAIT:-7200} 9 || { say "замок так и не освободился — не начинаю"; exit 1; }; }
fi

# ---- возврат: по файлу $PEND, поэтому переживает и kill -9 ------------------------------------------
restore() {
  [ -f "$PEND" ] || return 0
  local snap mods tid wavs set
  snap=$(sed -n 's/^snap //p' "$PEND"); mods=$(sed -n 's/^mods //p' "$PEND"); tid=$(sed -n 's/^chat //p' "$PEND")
  wavs=$(sed -n 's/^wavs //p' "$PEND"); set=$(sed -n 's/^set //p' "$PEND")
  say "== возврат"
  if grep -q '^started' "$PEND"; then launch "--es listen off $set --es modules '${mods}'"; sleep 4; fi
  $ADB shell "am force-stop $PKG"; sleep 1
  local f; for f in $FILES; do
    if [ -f "$snap/$(basename $f)" ]; then $ADB push "$snap/$(basename $f)" "$F/$f" >/dev/null 2>&1 && say "  вернул $f"
    elif [ -f "$snap/$(basename $f).none" ]; then $ADB shell "rm -f $F/$f"; fi
  done
  [ -n "$tid" ] && $ADB shell "rm -f $F/chats/$tid.json" && say "  тестовый разговор удалён"
  local w; for w in $wavs; do $ADB shell "rm -f $F/$w"; done
  say "  текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  rm -f "$PEND"; [ -n "$snap" ] && rm -rf "$snap"
  launch; say "  модули: [${mods}] · ${set} · слушание выключено"
}
PENDV=$STATE/barge-volume-pending
# Вернуть громкость из $PENDV и сверить; файл убирается, только если громкость на телефоне та самая.
putvol() {
  [ -f "$PENDV" ] || return 0
  local v now; v=$(cat "$PENDV")
  sh "cmd media_session volume --stream 3 --set $v" >/dev/null
  now=$(sh "cmd media_session volume --stream 3 --get" | sed -n 's/.*volume is \([0-9]*\).*/\1/p')
  if [ "$now" = "$v" ]; then rm -f "$PENDV"; say "  громкость возвращена: $v"; else say "  ГРОМКОСТЬ НЕ ВОЗВРАЩЕНА (нужно $v, сейчас ${now:-нет связи}) — вернёт следующий запуск или --restore"; fi
}
$ADB wait-for-device
putvol
restore
[ "$ONLY_RESTORE" = 1 ] && exit 0
n=0; until free3; do n=$((n+1)); [ $n -eq 1 ] && say "  жду, пока телефон свободен ($(focus | sed 's/.*{//; s/}.*//'))"; sleep 20; done

# ---- INSTALL=1 ------------------------------------------------------------------------------------
if [ "${INSTALL:-0}" = 1 ]; then
  APK=$R/bench/apk/Falar.apk; [ -f "$APK" ] || { say "нет $APK — сначала build.sh"; exit 1; }
  vnew=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$R/bench/apk/AndroidManifest.xml" | head -1)
  vold=$(sh "dumpsys package $PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  [ -n "$vold" ] && [ "${vnew:-0}" -lt "$vold" ] && { say "сборка $vnew старше той, что на телефоне ($vold) — не ставлю"; exit 1; }
  free=$(sh "df /data" | awk 'NR==2{print $4}'); [ "${free:-0}" -ge 1500000 ] || { say "мало места для установки ($free КБ) — попросите владельца перезагрузить телефон"; exit 1; }
  before=$(sh "dumpsys package $PKG" | grep -m1 lastUpdateTime)
  out=$($ADB install --no-incremental -r "$APK" 2>&1 | tr -d '\r' | grep -E '^(Success|Failure)'); say "  установка: ${out:-нет ответа}"
  after=$(sh "dumpsys package $PKG" | grep -m1 lastUpdateTime)
  case "$out" in Success*) [ "$before" != "$after" ] || { say "сборка не сменилась"; exit 1; };; *) exit 1;; esac
fi
# Телефон общий: между прогонами другая сессия могла поставить свою сборку. Сверяем установленный APK с этим;
# другая — не начинаем (INSTALL=1 поставит эту, ANYBUILD=1 — всё равно).
inst=$(sh "sha256sum \$(pm path $PKG | head -1 | cut -d: -f2)" | cut -d' ' -f1); mine=$(sha256sum "$R/bench/apk/Falar.apk" 2>/dev/null | cut -d' ' -f1)
[ "${ANYBUILD:-0}" = 1 ] || [ "$inst" = "$mine" ] || { say "на телефоне другая сборка (${inst:0:12}…, эта ${mine:0:12}…) — не начинаю; INSTALL=1 поставит эту"; exit 1; }
D=$(mktemp -d "$STATE/barge-run.XXXX")
trap 'restore; putvol; rm -rf "$D"; say; say "итог: PASS $pass, FAIL $fail"' EXIT
# Громкость: перебиванию нужна слышимая озвучка. VOL — поставить на время прогона (прежняя возвращается и
# сверяется, и после обрыва — следующим запуском); без VOL — какая есть, но не ниже 5 из 15.
VOL0=$(sh "cmd media_session volume --stream 3 --get" | sed -n 's/.*volume is \([0-9]*\).*/\1/p')
if [ -n "$VOL" ]; then
  [ -n "$VOL0" ] || { say "громкость не прочлась — не начинаю"; exit 1; }
  echo "$VOL0" > "$PENDV"; sh "cmd media_session volume --stream 3 --set $VOL" >/dev/null; say "  громкость: $VOL из 15 (была $VOL0)"
elif [ "${VOL0:-0}" -lt 5 ]; then say "громкость озвучки ${VOL0:-?} из 15 — её не слышно, перебиванию нечего ловить; задайте VOL=10"; exit 1
else say "  громкость: $VOL0 из 15"; fi

# ---- B0: короткая фраза звучит сразу -----------------------------------------------------------------
say "== B0: короткая фраза (2,8 с) начинает звучать сразу"
m=$(mark); launch; wl "$m" '🧩 модули:' 150 >/dev/null; sleep 3          # стенд эха ждёт поднятого движка
m=$(mark); launch --es aectest vr_e3,vr_e6
wl "$m" '🔁 эхо-стенд: (готово|ошибка|движок)' 120 >/dev/null
$ADB pull "$F/aec.json" "$D/aec.json" >/dev/null 2>&1; for f in $(sh "ls $F" | grep -E '^aec_.*\.(wav|json)$'); do $ADB shell "rm -f $F/$f" < /dev/null; done
"$PY" - "$D/aec.json" > "$D/b0" <<'EOF'
import json, sys
m = json.load(open(sys.argv[1], encoding='utf-8'))
for cfg in ('vr_e3', 'vr_e6'):
    c = m.get(cfg) or {}
    h0, tt = c.get('track_head0'), c.get('ts_track') or []
    # первая метка, где дорожка уже продвинулась, — через сколько мс после отдачи
    first = next(((n - c['play_nano']) / 1e6 for f, n in tt if f > h0 + 100), None)
    print(cfg, 'нет' if first is None else f'{first:.0f}')
EOF
cat "$D/b0" | sed 's/^/  звучит через, мс: /'
bad=$(awk '$2 == "нет" || $2 > 400' "$D/b0" | wc -l)
[ -s "$D/b0" ] && [ "$bad" = 0 ] && res 0 "B0 фразы по 2,8 с звучат сразу (без порога старта молчали все 3 с)" || res 1 "B0 короткая фраза не зазвучала: $(tr '\n' ' ' < "$D/b0")"

# ---- фразы и записи ------------------------------------------------------------------------------
"$PY" - "$R" "$D" <<'EOF' || { say "фразы не собрались"; exit 1; }
import os, sys, random
R, D = sys.argv[1], sys.argv[2]
rnd = random.Random(20261002)
# что «говорит» телефон: свои примеры (не данные владельца), по-португальски — переводит и озвучивает по-русски
say = """Amanhã de manhã vamos visitar o museu e depois almoçar perto da praia com os amigos.
O ônibus para o centro passa a cada vinte minutos, mas hoje parece que está bem atrasado.
Eu preciso comprar um carregador novo, porque o meu parou de funcionar ontem à noite.
A reunião foi adiada para quinta-feira, então ainda temos tempo para revisar o contrato.
Se você quiser, posso te mostrar o caminho até a estação, fica a dez minutos daqui.
O restaurante da esquina fecha às onze, mas a cozinha para de servir um pouco antes.
Minha irmã chega no sábado e vai ficar com a gente até o fim do mês que vem.
O médico disse que eu devo descansar mais e beber bastante água durante o dia.
Não esqueça de levar o guarda-chuva, porque a previsão diz que vai chover à tarde.
Nós alugamos um apartamento pequeno, mas tem uma varanda com vista para o mar.
O preço da gasolina subiu de novo, e agora quase todo mundo está indo de bicicleta.
A farmácia de plantão fica na avenida principal, ao lado do supermercado grande.
Quando eu era criança, passava as férias na casa da minha avó, no interior.
O jogo de ontem foi muito disputado, e o empate saiu só nos últimos minutos.
Precisamos trocar o pneu do carro antes da viagem, ele está bem gasto na lateral.
Eles abriram uma padaria nova no bairro, e o pão de queijo de lá é ótimo.
Você sabe se o banco abre no feriado, ou só os caixas eletrônicos funcionam?
O voo atrasou duas horas, então perdemos a conexão e tivemos que dormir no aeroporto.
Vou pedir uma pizza grande para todo mundo, alguém tem alguma preferência de sabor?
O síndico avisou que a água vai ser cortada amanhã entre as nove e o meio-dia.
A professora pediu que as crianças tragam um desenho da família na segunda-feira.
Esse mercado tem frutas mais frescas, mas fica um pouco longe da nossa casa.
Ontem fomos ao cinema e o filme era tão longo que quase dormi no final.
O técnico vem consertar a geladeira hoje à tarde, entre as duas e as cinco horas.""".split('\n')
open(f'{D}/say.txt', 'w', encoding='utf-8').write('\n'.join(say) + '\n')
# «человек» — живые фразы корпуса, 2–4 с, разные дикторы
import wave
cl = []
for lang in ('pt', 'ru'):
    d = f'{R}/bench/air/corpus/{lang}'
    for f in sorted(os.listdir(d)):
        if f.endswith('.wav'):
            with wave.open(f'{d}/{f}') as w:
                s = w.getnframes() / w.getframerate()
            if 2.0 <= s <= 4.0:
                cl.append(f'{d}/{f[:-4]}')
rnd.shuffle(cl)
open(f'{D}/human.txt', 'w', encoding='utf-8').write('\n'.join(cl[:16]) + '\n')
EOF
mapfile -t SAY < "$D/say.txt"; mapfile -t HUM < "$D/human.txt"

# ---- снимок, модули, настройки, тестовый разговор --------------------------------------------------
say "== снимок перед проверкой"
SNAP="$STATE/barge-snap-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$SNAP"
for f in $FILES; do
  n=$(sh "stat -c %s $F/$f 2>/dev/null")
  if [ -n "$n" ]; then $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1
    [ "$(stat -c %s "$SNAP/$(basename $f)" 2>/dev/null)" = "$n" ] || { say "снимок $f не сошёлся — не начинаю"; exit 1; }
  else touch "$SNAP/$(basename $f).none"; fi
done
case "$(sh "grep -E '▶ слушаю|⏹ не слушаю' $LOG | tail -1")" in *"▶ слушаю"*) say "у владельца включено слушание — не начинаю"; exit 1;; esac
m=$(mark); launch --es modules show
MODS=$(wl "$m" '🧩 модули сейчас' 60 | grep -oE '\[[a-z,]*\]' | tr -d '[]')
[ -n "$(wl "$m" '🧩 модули сейчас' 1)" ] || { say "модули не прочлись — не начинаю"; exit 1; }
BARGE=$(sh "grep -E '🗣 перебивание (включено|выключено)' $LOG | tail -1" | grep -q выключено && echo 0 || echo 1)
SET="--es barge $BARGE --es silent 0"
TID=$(date +%s%3N)
{ echo "snap $SNAP"; echo "mods $MODS"; echo "chat $TID"; echo "set $SET"; } > "$PEND"
say "  $(ls "$SNAP" | tr '\n' ' ')· модули [$MODS] · перебивание было: $BARGE"
T0=$(sh "date '+%m-%d %H:%M:%S.000'")
"$PY" -c "import json; json.dump({'id': $TID, 'name': 'ТЕСТ перебивание', 'named': True, 'saved': $TID, 'turns': []}, open('$D/t.json','w',encoding='utf-8'), ensure_ascii=False)"
$ADB shell "am force-stop $PKG"; sleep 1
$ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
echo started >> "$PEND"
MODS_RUN=$(printf '%s' ",$MODS," | tr ',' '\n' | grep -v -e '^$' -e '^speaker$' | paste -sd,)
m=$(mark); launch "--es modules '$MODS_RUN' --es silent 0 --es barge 1"
wl "$m" '🧩 модули:' 150 >/dev/null; sleep 3
cur=$(sh "ls -t $F/chats/ | head -1"); [ "$cur" = "$TID.json" ] || { res 1 "B· текущий разговор не тестовый: $cur"; exit 1; }
launch --es listen both; sleep 2

# одна фраза телефона: подать текст, дождаться начала звука (строка say_begin в at.tsv); вернуть отметку времени
k=0
speak() {
  local t0 i l; t0=$(tmark)
  launch "--es feedasr '${SAY[$k]}'"; k=$((k+1))
  for i in $(seq 60); do l=$(sh "tail -n +$((t0+1)) $TSV | grep -m1 -F say_begin"); [ -n "$l" ] && { printf '%s' "$l" | cut -f1; return 0; }; sleep 0.25; done
  return 1
}
# дождаться тишины: ни озвучки, ни нарезки (журнал молчит 3 с)
calm() { local i a b; for i in $(seq 40); do a=$(sh "stat -c %Y $LOG"); sleep 3; b=$(sh "stat -c %Y $LOG"); [ "$a" = "$b" ] && return 0; done; }

# DUMP=N — только записать совмещение на концах N фраз (--es bargedump) и забрать на ПК, без B1–B4.
if [ -n "$DUMP" ]; then
  say "== совмещение: $DUMP фраз(ы) в $STATE/barge-dump"
  rm -rf "$STATE/barge-dump"; mkdir -p "$STATE/barge-dump"
  m=$(mark); launch --es bargedump "$DUMP"; sleep 1
  for i in $(seq "$DUMP"); do speak >/dev/null || say "  фраза $i не зазвучала"; calm; done
  since "$m" | grep -E '🗣' | sed 's/^/  /'
  for f in $(sh "ls $F" | grep -E '^barge_[0-9]+'); do $ADB pull "$F/$f" "$STATE/barge-dump/" >/dev/null 2>&1 && $ADB shell "rm -f $F/$f" < /dev/null; done
  ls "$STATE/barge-dump" | tr '\n' ' '; say; exit 0
fi
say "== B1: перебивание сверяет время динамика и учит эхо"
m1=$(mark); launch --es barge forget; sleep 1          # с нуля: выученное прошлых прогонов не в счёт
for i in 1 2 3 4 5 6; do speak >/dev/null || say "  фраза $i не зазвучала"; calm;
  since "$m1" | grep -q '🗣 перебивание готово' && break; done
since "$m1" | grep -E '🗣 (перебивание учится|сверка времени)' | sed 's/^/  /'
l=$(since "$m1" | grep -m1 'время динамика сверено'); say "  ${l:-нет строки о сверке}"
off=$(printf '%s' "$l" | sed -n 's/.*поправка \([0-9-]*\) мс.*/\1/p')
[ -n "$off" ] && [ "$off" -ge 40 ] && [ "$off" -le 140 ] && res 0 "B1 поправка времени найдена по эху: $off мс" || res 1 "B1 поправка: ${off:-нет}"
l=$(since "$m1" | grep -m1 '🗣 перебивание готово'); say "  ${l:-нет строки о готовности}"
[ -n "$l" ] && res 0 "B1 эхо этой громкости выучено (фраз: $k)" || res 1 "B1 перебивание не готово после $k фраз"

say "== B2: только озвучка — ни одной остановки"
m2=$(mark)
for i in 1 2 3 4 5 6; do speak >/dev/null; calm; done
dips=$(since "$m2" | grep -c '🗣 похоже, перебивают'); stops=$(since "$m2" | grep -c '🗣 перебили')
[ "$stops" = 0 ] && res 0 "B2 6 фраз без человека: остановок 0 (пауз в озвучке: $dips)" || res 1 "B2 ложных остановок: $stops (пауз $dips)"

# фраза человека поверх озвучки: колонки ПК через 0,6–1,6 с после начала звука
human() {
  local clip=$1 at dly
  at=$(speak) || return 1
  dly=$(LC_ALL=C awk -v s="$RANDOM" 'BEGIN { srand(s); printf "%.1f", 0.6 + rand() }')
  sleep "$dly"; printf '%s\t%s\t%s\n' "$clip" "$at" "$(date +%s%3N)" >> "$D/played.tsv"
  pw-play "$clip.wav" 2>/dev/null; calm
}
trial() {   # $1 — метка серии, дальше клипы
  local tag=$1; shift; local mm c; mm=$(mark)
  $ADB pull "$F/chats/$TID.json" "$D/chat0_$tag.json" >/dev/null 2>&1
  : > "$D/played.tsv"
  for c in "$@"; do human "$c"; done
  $ADB pull "$F/chats/$TID.json" "$D/chat_$tag.json" >/dev/null 2>&1
  cp "$D/played.tsv" "$D/played_$tag.tsv"
  since "$mm" > "$D/log_$tag.txt"
}
# Перебивание не выучилось — B3 и B4 ничего бы не показали: дальше не идём, строки «учится» — в журнале прогона.
if ! since "$m1" | grep -q '🗣 перебивание готово'; then
  since "$m1" | grep -E '🗣' | sed 's/^/  /' | tail -12
  say "B3/B4 пропущены: перебивание не готово"; exit 1
fi
say "== B3: человек поверх озвучки — с перебиванием"
trial on "${HUM[@]:0:8}"
say "== B4: то же без перебивания — как было"
launch --es barge 0; sleep 1
trial off "${HUM[@]:8:8}"
launch --es barge 1; sleep 1
"$PY" - "$D" <<'EOF' > "$D/b34"
import json, re, sys, os, difflib
D = sys.argv[1]
words = lambda t: [w for w in re.sub(r"[^\w' -]", ' ', t.lower()).split() if len(w) > 1]
norm = lambda t: ' '.join(words(t))
said = [norm(l) for l in open(f'{D}/say.txt', encoding='utf-8') if l.strip()]
def score(tag):
    played = [l.rstrip('\n').split('\t') for l in open(f'{D}/played_{tag}.tsv', encoding='utf-8') if l.strip()]
    n0 = len(json.load(open(f'{D}/chat0_{tag}.json', encoding='utf-8')).get('turns', []))
    turns = json.load(open(f'{D}/chat_{tag}.json', encoding='utf-8')).get('turns', [])[n0:]
    # реплики человека — это не фразы, поданные телефону (их исходник — текст из say.txt)
    hum = [t.get('src', '') for t in turns if max((difflib.SequenceMatcher(None, norm(t.get('src', '')), s).ratio() for s in said), default=0) < 0.8]
    rec = []
    for clip, _, _ in played:
        ref = words(open(clip + '.txt', encoding='utf-8').read())
        per = [sum(w in set(words(h)) for w in ref) / max(1, len(ref)) for h in hum]
        # фраза человека могла лечь одной репликой или двумя (нарезка разрезала): объединение тех, где она видна
        got = set(w for h, r in zip(hum, per) if r >= 0.2 for w in words(h))
        rec.append(max([sum(w in got for w in ref) / max(1, len(ref))] + per))
    log = open(f'{D}/log_{tag}.txt', encoding='utf-8').read()
    return rec, log.count('🗣 перебили'), log.count('🗣 похоже, перебивают'), log.count('🗣 не подтвердилось')
for tag in ('on', 'off'):
    rec, st, du, un = score(tag)
    print(tag, f'{sum(rec) / max(1, len(rec)) * 100:.0f}', len(rec), st, du, un, ' '.join(f'{r * 100:.0f}' for r in rec))
EOF
cat "$D/b34" | sed 's/^/  /'
read _ rec_on n_on stops_on dips_on undo_on _ < <(grep '^on ' "$D/b34")
read _ rec_off n_off stops_off _ < <(grep '^off ' "$D/b34")
[ "${stops_on:-0}" -ge $(( ${n_on:-8} * 6 / 10 )) ] && res 0 "B3 озвучка смолкла под человеком: $stops_on из $n_on" || res 1 "B3 озвучка смолкла только $stops_on из $n_on"
[ "${rec_on:-0}" -gt "${rec_off:-0}" ] && res 0 "B3/B4 слов человека распознано: с перебиванием $rec_on %, без — $rec_off %" || res 1 "B3/B4 слов человека: с перебиванием ${rec_on:-?} %, без — ${rec_off:-?} %"
[ "${stops_off:-0}" = 0 ] && res 0 "B4 выключенное перебивание не срабатывает" || res 1 "B4 остановок при выключенном: $stops_off"
cp "$D"/b34 "$D"/log_*.txt "$D"/played_*.tsv "$STATE/" 2>/dev/null
mkdir -p "$STATE/barge-last" && cp "$D"/b0 "$D"/b34 "$D"/log_*.txt "$D"/played_*.tsv "$STATE/barge-last/" 2>/dev/null
# Падения за прогон
cr=$(sh "logcat -d -b crash -T '$T0'" | grep -c "Process: $PKG")
[ "$cr" = 0 ] && res 0 "B· Falar не падал" || res 1 "B· падений Falar в logcat: $cr"
