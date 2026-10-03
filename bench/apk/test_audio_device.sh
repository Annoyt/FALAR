#!/bin/bash
# Живой звук собеседников и метка стенда — на телефоне, в отдельном тестовом разговоре.
#
#   bash bench/apk/test_audio_device.sh [--restore]
#   INSTALL=1 bash bench/apk/test_audio_device.sh     # сначала поставить bench/apk/Falar.apk
#
# Проверяет:
#  A1 «Хранить звук собеседников» выключено — португальская реплика ложится без звука, файла нет;
#  A2 включено — у португальской реплики поле audio, файл .wav появляется за 10 с, и это ровно тот
#     кусок, что слышал распознаватель: 44 байта заголовка + 2 байта на каждый отсчёт записи, ровно;
#  A3 русская реплика — без звука: хранится только португальская речь собеседника;
#  A9 затишье — WAV сжимается в Opus и удаляется (владелец 03.10: «вав файлы сильно большие, их нужно
#     пережимать перед хранением»): за ≤ 20 с вместо .wav лежит .ogg того же имени, 1–6 КБ на секунду фразы,
#     в журнале «🎙 звук сжат в затишье»; на Android 9 — пропуск (WAV остаётся);
#  A4 «Послушать, как сказали» (стенд --es playclip last) — сжатая запись раскрылась на телефоне и звучит той
#     же длины, что фраза (±0,4 с);
#  A5 метка стенда: реплика со стенда — stand: 1, с «--es stand 0» в той же команде — без метки;
#  A6 переключатель переживает перезапуск приложения;
#  A7 скорость (критерий записан до замера): распознавание + перевод на секунду звука — медиана по
#     репликам со звуком не больше медианы без звука на 10 % или на 25 мс/с, что больше. Заходы по 5 своих
#     фраз подряд, без ожидания (запись прежней фразы идёт одновременно с распознаванием следующей, как
#     вживую), порядок ABBA: без · со · со · без · без · со · со · без. Первый прогон 03.10 («без · со» ×2,
#     сравнение сырого «до звука») провалился не из-за записи: телефон грелся по ходу прогона (без звука
#     170 → 302 мс на секунду звука между кругами), а «со звуком» всегда шёл вторым; и фразы в заходах
#     разные — сырое «до звука» мерило их длину;
#  A8 Falar не падал (logcat).
#
# Голоса — живые записи Tatoeba (bench/air/corpus; в сборку не идут). Данные владельца не трогаются:
# тестовый разговор «ТЕСТ звук» — отдельным файлом с самым свежим временем, в конце удаляется вместе
# со своим звуком; выученное и словари — из снимка; «Хранить звук» — как было; молчаливый режим только
# на прогон. Прогон оборвался, даже kill -9, — следующий запуск сначала возвращает телефон (или --restore).
# Замок общий с test_all_device.sh; Falar запускается только поверх себя, рабочего стола или
# погашенного экрана, и только когда экрана не касались минуту.
R=$(cd "$(dirname "$0")/../.." && pwd); A=$R/bench/apk
ONLY_RESTORE=0; [ "${1:-}" = "--restore" ] && ONLY_RESTORE=1
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
STATE=${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand; mkdir -p "$STATE"; PEND=$STATE/audio-pending
FILES="models/learned.json models/phrasebook_user.json word_ru.json known_words.json models/wordlist.json"
PY=$R/.venv/bin/python; [ -x "$PY" ] || PY=python3
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
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
# последняя реплика тестового разговора: python-выражение над x (реплика) и o (разговор)
last() { $ADB pull "$F/chats/$TID.json" "$D/c.json" >/dev/null 2>&1 || { echo "нет файла"; return; }
  "$PY" -c "import json; o=json.load(open('$D/c.json',encoding='utf-8')); t=o.get('turns',[]); x=t[-1] if t else {}; print($1)"; }
nturns() { last "len(t)"; }

# ---- замок -----------------------------------------------------------------------------------------
if [ -z "$FALAR_STAND_LOCK" ]; then
  exec 9>"$STATE/lock"
  flock -n 9 || { say "на телефоне уже идёт проверка (замок $STATE/lock) — не начинаю"; exit 1; }
fi

# ---- возврат: по файлу $PEND, поэтому переживает и kill -9 ------------------------------------------
restore() {
  [ -f "$PEND" ] || return 0
  local snap keep tid wavs clips f
  snap=$(sed -n 's/^snap //p' "$PEND"); keep=$(sed -n 's/^keep //p' "$PEND"); tid=$(sed -n 's/^chat //p' "$PEND"); wavs=$(sed -n 's/^wavs //p' "$PEND")
  say "== возврат"
  if grep -q '^started' "$PEND"; then launch "--es silent 0 --es stand 0 --es keepaudio ${keep:-0}"; sleep 4; fi
  $ADB shell "am force-stop $PKG"; sleep 1
  for f in $FILES; do
    if [ -f "$snap/$(basename $f)" ]; then $ADB push "$snap/$(basename $f)" "$F/$f" >/dev/null 2>&1 && say "  вернул $f"
    elif [ -f "$snap/$(basename $f).none" ]; then $ADB shell "rm -f $F/$f"; fi
  done
  if [ -n "$tid" ]; then
    # звук тестовых реплик — по именам из самого разговора: чужие файлы не трогаем
    clips=$($ADB exec-out "cat $F/chats/$tid.json" 2>/dev/null | "$PY" -c "import json,sys
try: print(' '.join(x['audio'] for x in json.load(sys.stdin).get('turns',[]) if x.get('audio')))
except Exception: pass")
    for f in $clips; do b=${f%.*}; $ADB shell "rm -f $F/audio/$b.wav $F/audio/$b.ogg $F/audio/$b.m4a $F/audio/$b.wav.part $F/audio/$b.ogg.part $F/audio/$b.m4a.part"; done
    $ADB shell "rm -f $F/chats/$tid.json"; say "  тестовый разговор удалён вместе со звуком ($([ -n "$clips" ] && echo "файлов: $(echo $clips | wc -w)" || echo "звука не было"))"
  fi
  for f in $wavs; do $ADB shell "rm -f $F/$f"; done
  rm -f "$PEND"; [ -n "$snap" ] && rm -rf "$snap"
  launch; say "  «Хранить звук собеседников»: ${keep:-0} · текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
}
$ADB wait-for-device
restore
[ "$ONLY_RESTORE" = 1 ] && exit 0

if [ "${INSTALL:-0}" = 1 ]; then
  APK=$A/Falar.apk; [ -f "$APK" ] || { say "нет $APK — сначала build.sh"; exit 1; }
  vnew=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$A/AndroidManifest.xml" | head -1)
  vold=$(sh "dumpsys package $PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  [ -n "$vold" ] && [ "${vnew:-0}" -lt "$vold" ] && { say "сборка $vnew старше той, что на телефоне ($vold): понижение = удаление с данными — не ставлю"; exit 1; }
  # Свободен для установки (как в test_voices_device.sh): экрана не касались 3 минуты, журнал молчит
  # 3 минуты, слушание выключено — установка убила бы живой разговор.
  n=0; while :; do
    a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
    quiet=$(( $(sh "date +%s") - $(sh "stat -c %Y $LOG 2>/dev/null || echo 0") ))
    listening=$(sh "grep -E '▶ слушаю|⏹ не слушаю' $LOG | tail -1" | grep -c '▶ слушаю')
    if [ "$listening" = 0 ] && [ "$quiet" -ge 180 ] && { asleep || { case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) true;; *) false;; esac && [ "${a:-0}" -ge 180000 ]; }; }; then break; fi
    n=$((n+1)); [ $n -eq 1 ] && say "  жду, пока телефон свободен для установки (журнал молчит $quiet с, слушание: $listening)"; sleep 20
  done
  free=$(sh "df /data" | awk 'NR==2{print $4}'); say "  свободно на /data: $free КБ"
  [ "${free:-0}" -ge 1500000 ] || { say "мало места для установки — попросите владельца перезагрузить телефон"; exit 1; }
  before=$(sh "dumpsys package $PKG" | grep -m1 lastUpdateTime)
  out=$($ADB install --no-incremental -r "$APK" 2>&1 | tr -d '\r' | grep -E '^(Success|Failure)'); say "  установка: ${out:-нет ответа}"
  [ "$before" != "$(sh "dumpsys package $PKG" | grep -m1 lastUpdateTime)" ] || { say "сборка не сменилась"; exit 1; }
  case "$out" in Success*) ;; *) exit 1;; esac
fi
D=$(mktemp -d "$STATE/audio-run.XXXX")
trap 'restore; rm -rf "$D"; say; say "итог: PASS $pass, FAIL $fail"' EXIT
T0=$(sh "date '+%m-%d %H:%M:%S.000'")

# ---- записи: живые голоса Tatoeba -----------------------------------------------------------------
"$PY" - "$R" "$D" <<'EOF' || { say "записи не собрались"; exit 1; }
import glob, sys, wave
R, D = sys.argv[1], sys.argv[2]
def take(lang, n):
    out = []
    for p in sorted(glob.glob(f'{R}/bench/air/corpus/{lang}/*.wav')):
        w = wave.open(p)
        if w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2 and 1.5 <= w.getnframes() / 16000 <= 4: out.append(p)
        if len(out) == n: break
    return out
pt, ru = take('pt', 44), take('ru', 1)   # каждой фразе — один раз: повтор в разговоре отбрасывается как «прочли вслух»
with open(f'{D}/clips.tsv', 'w', encoding='utf-8') as f:
    for k, p in enumerate(pt + ru):
        name = f'at_{"pt" if k < len(pt) else "ru"}{k}.wav'
        w = wave.open(p); data = w.readframes(w.getnframes())
        o = wave.open(f'{D}/{name}', 'wb'); o.setnchannels(1); o.setsampwidth(2); o.setframerate(16000); o.writeframes(data); o.close()
        f.write(f'{name}\t{len(data) / 2 / 16000:.2f}\t{len(data) // 2}\n')
EOF
WAVS=$(cut -f1 "$D/clips.tsv" | tr '\n' ' ')
PT=($(grep '^at_pt' "$D/clips.tsv" | cut -f1)); RU=$(grep '^at_ru' "$D/clips.tsv" | cut -f1 | head -1)
dur() { awk -F'\t' -v n="$1" '$1 == n {print $2}' "$D/clips.tsv"; }
smp() { awk -F'\t' -v n="$1" '$1 == n {print $3}' "$D/clips.tsv"; }

# ---- снимок, тестовый разговор ---------------------------------------------------------------------
say "== снимок перед проверкой"
case "$(sh "grep -E '▶ слушаю|⏹ не слушаю' $LOG | tail -1")" in *"▶ слушаю"*) say "у владельца включено слушание — не начинаю"; exit 1;; esac
SNAP="$STATE/audio-snap-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$SNAP"
for f in $FILES; do
  n=$(sh "stat -c %s $F/$f 2>/dev/null")
  if [ -n "$n" ]; then $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1
    [ "$(stat -c %s "$SNAP/$(basename $f)" 2>/dev/null)" = "$n" ] || { say "снимок $f не сошёлся — не начинаю"; exit 1; }
  else touch "$SNAP/$(basename $f).none"; fi
done
m=$(mark); launch --es keepaudio show
KEEP=$(wl "$m" '🎙 хранить звук собеседников:' 90 | grep -q ': да' && echo 1 || echo 0)
[ -n "$(wl "$m" '🎙 хранить звук собеседников:' 1)" ] || { say "стенд не ответил, хранит ли он звук (сборка без этой функции?) — не начинаю"; exit 1; }
TID=$(date +%s%3N)
{ echo "snap $SNAP"; echo "keep $KEEP"; echo "chat $TID"; echo "wavs $WAVS"; } > "$PEND"
say "  $(ls "$SNAP" | tr '\n' ' ')· хранить звук: $KEEP"
for w in $WAVS; do $ADB push "$D/$w" "$F/$w" >/dev/null 2>&1 || { say "запись $w не легла на телефон"; exit 1; }; done
"$PY" -c "import json; json.dump({'id': $TID, 'name': 'ТЕСТ звук', 'named': True, 'saved': $TID, 'turns': []}, open('$D/t.json','w',encoding='utf-8'), ensure_ascii=False)"
$ADB shell "am force-stop $PKG"; sleep 1
$ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
echo started >> "$PEND"
m=$(mark); launch "--es silent 1 --es keepaudio 0"
wl "$m" '🧩 модули:' 150 >/dev/null || { res 1 "A· приложение не поднялось"; exit 1; }
sleep 2
[ "$(sh "ls -t $F/chats/ | head -1")" = "$TID.json" ] || { res 1 "A· текущий разговор не тестовый"; exit 1; }

# реплика из записи: testwav как сегмент слушания; ждём строку реплики и возвращаем её
turn() { local m l; m=$(mark); launch --es testwav "$F/$1" --es dir "$2" $3; l=$(wl "$m" '^[0-9:.]+ #[0-9]+ ' 60); printf '%s' "$l"; }

say "== A1: выключено — без звука"
n0=$(nturns); l=$(turn "${PT[0]}" pt2ru); say "  ${l:0:120}"
[ "$(nturns)" -gt "$n0" ] && [ "$(last "x.get('audio','')")" = "" ] && res 0 "A1 реплика без поля audio" || res 1 "A1 реплика: $(last "x")"
[ -z "$(sh "ls $F/audio/ 2>/dev/null | grep '^${TID}_'")" ] && res 0 "A1 файлов звука тестового разговора нет" || res 1 "A1 файл появился"

say "== A2: включено — звук рядом с репликой"
m=$(mark); launch --es keepaudio 1; wl "$m" '🎙 звук собеседников хранится' 30 >/dev/null || say "  (строки о включении нет)"
l=$(turn "${PT[1]}" pt2ru); say "  ${l:0:120}"
clip=$(last "x.get('audio','')")
case "$clip" in ${TID}_*.wav) res 0 "A2 у реплики поле audio: $clip";; *) res 1 "A2 поле audio: '${clip}'";; esac
sz=""; for i in $(seq 10); do sz=$(sh "stat -c %s $F/audio/$clip 2>/dev/null"); [ -n "$sz" ] && break; sleep 1; done
d1=$(dur "${PT[1]}"); n1=$(smp "${PT[1]}")
"$PY" -c "import sys; s=int('${sz:-0}'); sys.exit(0 if s == 44 + 2 * int('$n1') else 1)" && res 0 "A2 файл за ≤10 с, $sz байт на $d1 с — весь кусок распознавателя" || res 1 "A2 файл: '${sz:-нет}' байт на $d1 с"

say "== A9: затишье — WAV сжимается в Opus"
sdk=$(sh "getprop ro.build.version.sdk"); base=${clip%.*}
if [ "${sdk:-0}" -lt 29 ]; then say "ПРОПУСК A9: Android 9 — звук остаётся WAV"; else
  m9=$(mark); z=""; for i in $(seq 20); do z=$(sh "stat -c %s $F/audio/$base.ogg 2>/dev/null"); w=$(sh "ls $F/audio/$base.wav 2>/dev/null"); [ -n "$z" ] && [ -z "$w" ] && break; sleep 1; done
  "$PY" -c "import sys; z=int('${z:-0}'); d=float('$d1'); sys.exit(0 if 1000 * d <= z <= 6000 * d and '$w' == '' else 1)" && res 0 "A9 за ≤20 с вместо WAV — Opus, $z байт на $d1 с (WAV был $sz)" || res 1 "A9 ogg '${z:-нет}' байт, wav '${w:-нет}'"
  l=$(wl "$m9" '🎙 звук сжат в затишье' 5); say "  ${l:-нет строки о сжатии}"
fi

say "== A4: «Послушать, как сказали»"
m=$(mark); launch --es playclip last; l=$(wl "$m" '🎙 звук реплики' 30); say "  ${l:-нет строки}"
s=$(printf '%s' "$l" | sed -n 's/.*звук реплики: \([0-9.]*\) с.*/\1/p')
"$PY" -c "import sys; sys.exit(0 if abs(float('${s:-0}') - float('$d1')) <= 0.4 else 1)" && res 0 "A4 запись раскрылась и звучит $s с (фраза $d1 с)" || res 1 "A4 длина '${s:-нет}' против $d1"

say "== A3: русская реплика — без звука"
l=$(turn "$RU" ru2pt); say "  ${l:0:120}"
[ "$(last "x.get('dir','')")" = "ru2pt" ] && [ "$(last "x.get('audio','')")" = "" ] && res 0 "A3 русская реплика без звука" || res 1 "A3 реплика: $(last "x")"
l=$(turn "${PT[2]}" pt2ru)

say "== A5: метка стенда"
[ "$(last "x.get('stand',0)")" = "1" ] && res 0 "A5 реплика со стенда помечена" || res 1 "A5 метки нет: $(last "x")"
l=$(turn "${PT[3]}" pt2ru "--es stand 0")
[ "$(last "x.get('stand',0)")" = "0" ] && res 0 "A5 с «stand 0» — без метки" || res 1 "A5 метка осталась: $(last "x")"

say "== A6: переключатель переживает перезапуск"
$ADB shell "am force-stop $PKG"; sleep 1
m=$(mark); launch "--es silent 1 --es keepaudio show"; l=$(wl "$m" '🎙 хранить звук собеседников:' 150); say "  ${l:-нет строки}"
printf '%s' "$l" | grep -q ': да' && res 0 "A6 после перезапуска — включено" || res 1 "A6 ${l:-нет строки}"
wl "$m" '🧩 модули:' 150 >/dev/null; sleep 2

say "== A7: скорость со звуком и без"
burst() {   # пять своих фраз (с $1) подряд, без ожидания; печатает по строке «до_звука мс_на_с_звука»
  local m k; m=$(mark)
  for k in 0 1 2 3 4; do launch --es testwav "$F/${PT[$(( $1 + k ))]}" --es dir pt2ru; done
  for k in $(seq 90); do [ "$(since "$m" | grep -cE '^[0-9:.]+ #[0-9]+ ')" -ge 5 ] && break; sleep 1; done
  since "$m" | "$PY" -c "
import re, sys
for l in sys.stdin:
    m = re.search(r'asr (\d+) · mt (\d+) · tts→звук -?\d+ · до звука (\d+) мс \(аудио ([\d.]+) с\)', l)
    if m: print(m.group(3), round((int(m.group(1)) + int(m.group(2))) / float(m.group(4))))" | head -5
}
OFF=""; ON=""; k0=4
for mode in 0 1 1 0 0 1 1 0; do   # ABBA: нагрев телефона по ходу прогона ложится на оба режима поровну
  m=$(mark); launch --es keepaudio $mode; wl "$m" '🎙 звук собеседников' 20 >/dev/null
  r=$(burst $k0 | tr '\n' ';'); k0=$((k0 + 5))
  if [ $mode = 1 ]; then ON="$ON$r"; else OFF="$OFF$r"; fi
done
say "  без звука (до звука мс, мс на с звука): $OFF"; say "  со звуком: $ON"
v=$("$PY" -c "
import statistics as s, sys
def rows(t): return [tuple(int(v) for v in x.split()) for x in t.split(';') if x.strip()]
a, b = rows('$OFF'), rows('$ON')
if len(a) < 16 or len(b) < 16: print('мало реплик', len(a), len(b)); sys.exit(1)
na, nb = s.median(x[1] for x in a), s.median(x[1] for x in b); lim = max(na * 1.10, na + 25)
print('на секунду звука: без %d мс, со звуком %d мс, предел %d · «до звука» без %d, со звуком %d' % (na, nb, lim, s.median(x[0] for x in a), s.median(x[0] for x in b)))
sys.exit(0 if nb <= lim else 1)"); r=$?
res $r "A7 $v"

say "== A8: Falar не падал"
crash=$($ADB shell "logcat -d -v time -t '$T0'" 2>/dev/null | tr -d '\r' | grep -A3 'FATAL EXCEPTION' | grep -A1 'Process: app.falar' | grep -vE 'Process:|^--' | head -1 | cut -c1-160)
[ -z "$crash" ] && res 0 "A8 не падал" || res 1 "A8 упал: $crash"
