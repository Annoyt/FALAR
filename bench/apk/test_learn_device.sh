#!/bin/bash
# «Слова», шаг 2 — на телефоне: «Скажите сами», разрез реплики на предложения, экран «Слов» и повторение.
#
#   bash bench/apk/test_learn_device.sh [--restore]
#   INSTALL=1 bash bench/apk/test_learn_device.sh     # сначала поставить bench/apk/Falar.apk
#
# Критерии — записаны до первого прогона (03.10):
#  L1 «Скажите сами» на живой записи своей фразы (6 записей Tatoeba, цель — текст записи): распознано
#     ≥ 80 % слов фразы в ≥ 5 из 6 — носитель, сказавший фразу, видит её зелёной;
#  L2 чужая фраза (звучит одна запись, а сказать надо другую): слово карточки — значимое слово той фразы,
#     которого в сказанном нет, — не распознано ни в одной из 6 (в первом прогоне у одной чужой фразы такого
#     слова не нашлось, и стенд засчитал это провалом; теперь чужая фраза берётся та, где оно есть);
#  L3 разрез: 6 сегментов из двух записей подряд с паузой 0,6 с шума −70 dBFS (как в замере
#     results/2026-10-03-asr-timestamps.md) — пары, которые на компьютере делятся на два предложения; на телефоне
#     текст делится так же в ≥ 5 из 6, и у каждой поделившейся ровно один разрез, в паузе ±50 мс — как in_gap
#     в tools/asr_timestamps.py (края речи там и здесь — по энергии с запасом 2–3 кадра, поэтому запас — наружу).
#     Первый прогон 03.10 брал 6 пар корпуса подряд и ждал деления в ≥ 4 из 6 (на компьютере в среднем 95 %):
#     поделилось 3, но и компьютер те же 6 сегментов делит 3 из 6 — на стыке запятая; разрез в паузе — 3 из 3.
#     Проверять надо телефон против компьютера, а долю деления мерил компьютер. Второй прогон: поделилось 6 из 6,
#     но стенд брал запас 50 мс внутрь паузы, а не наружу, и засчитал провалом разрез 2150 мс при паузе
#     1540–2140 мс — за краем шума, ещё до речи; по критерию компьютера — 6 из 6;
#  L4 ▶ у примера играет своё предложение: «--es playclip N --es sent 0|1» звучит до разреза и после него
#     (±0,15 с) — после сжатия в Opus;
#  L5 экран «Слов»: вкладки «Слова · N», «Фразы · N», «Знаю · N», карточка «Повторение» со строкой сроков;
#     «на слух» — «Понял / Не понял», «вслух» — «Держите и говорите / Не знаю» (или «всё» на сегодня), и ни
#     один ответ не записан — у известного владельца те же «помню / забыл / когда»;
#  L6 Falar не падал (logcat).
#
# Голоса — живые записи Tatoeba (bench/air/corpus; в сборку не идут). Данные владельца не трогаются:
# тестовый разговор «ТЕСТ слова» — отдельным файлом с самым свежим временем, в конце удаляется вместе со своим
# звуком (его реплики — стендовые, в «Слова» не попадают); выученное, словари, известное и метрика — из снимка;
# «Хранить звук» — как было. Стенд звук повторения не включает. Прогон оборвался, даже kill -9, — следующий
# запуск сначала возвращает телефон (или --restore). Замок общий с test_all_device.sh; Falar запускается только
# поверх себя, рабочего стола или погашенного экрана, и только когда экрана не касались минуту.
R=$(cd "$(dirname "$0")/../.." && pwd); A=$R/bench/apk
ONLY_RESTORE=0; [ "${1:-}" = "--restore" ] && ONLY_RESTORE=1
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
STATE=${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand; mkdir -p "$STATE"; PEND=$STATE/learn-pending
FILES="models/learned.json models/phrasebook_user.json word_ru.json known_words.json models/wordlist.json practice.json sent_ru.json"
PY=$R/.venv/bin/python; [ -x "$PY" ] || PY=python3
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i l; for i in $(seq "$3"); do l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
focus() { sh "dumpsys window" | grep -m1 mCurrentFocus; }
asleep() { sh "dumpsys power" | grep -qE 'mWakefulness=(Asleep|Dozing)'; }
idle() {
  asleep && return 0
  case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) ;; *) return 1;; esac
  local a; a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
  [ "${a:-0}" -ge 60000 ]
}
wait_idle() { local n=0; until idle; do n=$((n+1)); [ $n -eq 1 ] && say "  жду, пока телефон свободен ($(focus))"; sleep 15; done; }
launch() { wait_idle; $ADB shell "am start -n $ACT $*" < /dev/null >/dev/null 2>&1; }
# реплики тестового разговора: python-выражение над t (реплики) и o (разговор)
chat() { $ADB pull "$F/chats/$TID.json" "$D/c.json" < /dev/null >/dev/null 2>&1 || { echo "нет файла"; return; }
  "$PY" -c "import json; o=json.load(open('$D/c.json',encoding='utf-8')); t=o.get('turns',[]); print($1)"; }

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
  # Свободен для установки (как в test_audio_device.sh): экрана не касались 3 минуты, журнал молчит
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
D=$(mktemp -d "$STATE/learn-run.XXXX")
trap 'restore; rm -rf "$D"; say; say "итог: PASS $pass, FAIL $fail"' EXIT
T0=$(sh "date '+%m-%d %H:%M:%S.000'")

# ---- записи: живые голоса Tatoeba -----------------------------------------------------------------
# lp*.wav — L1/L2 (текст записи и чужая цель со словом, которого в записи нет); ls*.wav — L3 (две записи,
# между речью 0,6 с шума, края речи — по энергии, как в tools/asr_timestamps.py).
"$PY" - "$R" "$D" <<'EOF' || { say "записи не собрались"; exit 1; }
import glob, os, re, sys, wave
import numpy as np
R, D = sys.argv[1], sys.argv[2]
sys.path.insert(0, os.path.join(R, 'tools'))
from asr_timestamps import speech, FRAME
SR = 16000
def read(p):
    w = wave.open(p); return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
def write(name, x):
    o = wave.open(f'{D}/{name}', 'wb'); o.setnchannels(1); o.setsampwidth(2); o.setframerate(SR)
    o.writeframes((np.clip(x, -1, 1) * 32767).astype(np.int16).tobytes()); o.close()
def norm(s): return re.sub(r'[^\w\s]', ' ', s.lower()).split()
ok = []
for p in sorted(glob.glob(f'{R}/bench/air/corpus/pt/*.wav')):
    w = wave.open(p)
    if w.getframerate() != SR or w.getnchannels() != 1 or w.getsampwidth() != 2: continue
    x = read(p); a, b, edge = speech(x)
    txt = open(p[:-4] + '.txt', encoding='utf-8').read().strip()
    if edge or not 1.5 <= len(x) / SR <= 4 or len(norm(txt)) < 4: continue
    ok.append((p, x, a, b, txt))
# через одну — чтобы соседние по номеру (часто один диктор) не шли подряд
pick = ok[::2]
with open(f'{D}/practice.tsv', 'w', encoding='utf-8') as f:
    for k in range(6):
        p, x, a, b, txt = pick[k]
        mine = set(norm(txt)); other, key = '', ''
        for j in range(k + 6, len(pick)):              # чужая фраза — с значимым словом, которого в записи нет
            key = next((w for w in norm(pick[j][4]) if len(w) >= 5 and w not in mine), '')
            if key: other = pick[j][4]; break
        write(f'lp{k}.wav', x)
        f.write(f'lp{k}.wav\t{txt}\t{other}\t{key}\n')
# пары, которые компьютер делит на два предложения (tools/asr_timestamps.py, та же модель, 03.10)
PAIRS = [('tat_2457867', 'tat_2605494'), ('tat_2605548', 'tat_2605569'), ('tat_2703234', 'tat_3489553'),
         ('tat_5218298', 'tat_5806748'), ('tat_886190', 'tat_933784'), ('tat_972580', 'tat_974376')]
byname = {os.path.basename(q[0])[:-4]: q for q in ok}
rng = np.random.default_rng(20261003)
with open(f'{D}/segs.tsv', 'w', encoding='utf-8') as f:
    for k in range(6):
        (p1, x1, a1, b1, t1), (p2, x2, a2, b2, t2) = byname[PAIRS[k][0]], byname[PAIRS[k][1]]
        noise = rng.normal(0, 10 ** (-70 / 20), int(0.6 * SR)).astype(np.float32)
        head = x1[:int(b1 * SR)]                        # первая — до конца речи
        tail = x2[int(a2 * SR):]                        # вторая — с начала речи
        y = np.concatenate([head, noise, tail])
        e1, s2 = len(head) / SR, (len(head) + len(noise)) / SR
        write(f'ls{k}.wav', y)
        f.write(f'ls{k}.wav\t{e1:.3f}\t{s2:.3f}\t{len(y) / SR:.3f}\n')
EOF
WAVS=$(cut -f1 "$D/practice.tsv" "$D/segs.tsv" | tr '\n' ' ')

# ---- снимок, тестовый разговор ---------------------------------------------------------------------
say "== снимок перед проверкой"
case "$(sh "grep -E '▶ слушаю|⏹ не слушаю' $LOG | tail -1")" in *"▶ слушаю"*) say "у владельца включено слушание — не начинаю"; exit 1;; esac
SNAP="$STATE/learn-snap-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$SNAP"
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
"$PY" -c "import json; json.dump({'id': $TID, 'name': 'ТЕСТ слова', 'named': True, 'saved': $TID, 'turns': []}, open('$D/t.json','w',encoding='utf-8'), ensure_ascii=False)"
$ADB shell "am force-stop $PKG"; sleep 1
$ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
echo started >> "$PEND"
m=$(mark); launch "--es silent 1 --es keepaudio 1"
wl "$m" '🧩 модули:' 150 >/dev/null || { res 1 "L· приложение не поднялось"; exit 1; }
sleep 2
[ "$(sh "ls -t $F/chats/ | head -1")" = "$TID.json" ] || { res 1 "L· текущий разговор не тестовый"; exit 1; }

# ---- L1, L2: «Скажите сами» на записях -------------------------------------------------------------
say "== L1/L2: «Скажите сами» на живых записях"
own=0; alien=0
while IFS=$'\t' read -r w txt other key <&3; do
  m=$(mark); launch "--es practicewav $F/$w --es practicetarget '$(printf '%s' "$txt" | sed "s/'/ /g")'"
  l=$(wl "$m" '🎙 стенд: скажите сами' 60); s=$(printf '%s' "$l" | sed -n 's/.*доля \([0-9.]*\).*/\1/p')
  "$PY" -c "import sys; sys.exit(0 if float('${s:-0}') >= 0.8 else 1)" && own=$((own+1))
  say "  $w своя фраза: доля ${s:-нет}"
  [ -n "$key" ] || { alien=$((alien+1)); say "  $w: у чужой фразы нет слова, которого нет в записи"; continue; }
  m=$(mark); launch "--es practicewav $F/$w --es practicetarget '$(printf '%s' "$other" | sed "s/'/ /g")' --es practicekey $key"
  l=$(wl "$m" '🎙 стенд: скажите сами' 60)
  case "$l" in *"не распознано"*) ;; *) alien=$((alien+1)); say "  $w чужая фраза: слово «$key» засчитано — ${l##*скажите сами — }";; esac
done 3< "$D/practice.tsv"
[ $own -ge 5 ] && res 0 "L1 своя фраза распознана на ≥ 80 % в $own из 6" || res 1 "L1 своя фраза на ≥ 80 % только в $own из 6"
[ $alien = 0 ] && res 0 "L2 чужая фраза: слово карточки не засчитано ни разу из 6" || res 1 "L2 слово карточки засчитано в $alien из 6"

# ---- L3, L4: разрез реплики на предложения и ▶ одного предложения ------------------------------------
say "== L3: разрез по меткам времени"
split=0; inside=0; : > "$D/segidx.tsv"
while IFS=$'\t' read -r w e1 s2 len <&3; do
  n0=$(chat "len(t)")
  m=$(mark); launch --es testwav "$F/$w" --es dir pt2ru; wl "$m" '^[0-9:.]+ #[0-9]+ ' 60 >/dev/null; sleep 1
  [ "$(chat "len(t)")" = $((n0 + 1)) ] || { say "  $w: реплика не появилась"; continue; }
  printf '%s\t%s\t%s\n' "$n0" "$w" "$len" >> "$D/segidx.tsv"
  v=$(chat "(lambda x: '%d %s' % (len([p for p in __import__('re').split(r'(?<=[.!?…])\s+(?=\S)|\s*\n+\s*', x.get('src','').strip()) if p.strip()]), ','.join(map(str, x.get('cuts', [])))))(t[-1])")
  n=${v%% *}; cu=${v#* }
  if [ "$n" = 2 ]; then split=$((split+1))
    "$PY" -c "import sys; c=[int(v) for v in '$cu'.split(',') if v]; sys.exit(0 if len(c) == 1 and $e1 * 1000 - 50 <= c[0] <= $s2 * 1000 + 50 else 1)" \
      && inside=$((inside+1)) || say "  $w: разрез '$cu' мс, пауза ${e1}–${s2} с"
  else say "  $w: текст не поделился на два предложения ($n), разрез '${cu}'"; fi
done 3< "$D/segs.tsv"
[ $split -ge 5 ] && [ $inside = $split ] && res 0 "L3 текст поделился в $split из 6, разрез внутри паузы у всех $inside" \
  || res 1 "L3 текст поделился в $split из 6, разрез внутри паузы у $inside"

say "== L4: ▶ одного предложения после сжатия"
sdk=$(sh "getprop ro.build.version.sdk")
# сжатие идёт в затишье: подождать, пока у реплик с разрезом не останется WAV
for i in $(seq 40); do
  left=$(chat "' '.join(x['audio'] for x in t if x.get('cuts') and x.get('audio'))")
  wavs=0; for c in $left; do [ -n "$(sh "ls $F/audio/$c 2>/dev/null")" ] && [ "${sdk:-0}" -ge 29 ] && wavs=$((wavs+1)); done
  [ $wavs = 0 ] && break; sleep 2
done
good=0; tried=0
while IFS=$'\t' read -r idx w len <&3; do
  cu=$(chat "','.join(map(str, t[$idx].get('cuts', []))) if len(t) > $idx else ''")
  [ -n "$cu" ] && [ "${cu#*,}" = "$cu" ] || continue
  tried=$((tried+1)); ok=1
  for s in 0 1; do
    m=$(mark); launch --es playclip $idx --es sent $s; l=$(wl "$m" '🎙 звук реплики' 30)
    d=$(printf '%s' "$l" | sed -n 's/.*звук реплики: \([0-9.]*\) с.*/\1/p')
    want=$("$PY" -c "print(round($cu / 1000 if $s == 0 else $len - $cu / 1000, 2))")
    "$PY" -c "import sys; sys.exit(0 if abs(float('${d:-0}') - $want) <= 0.15 else 1)" || { ok=0; say "  реплика $idx, предложение $s: ${d:-нет} с, ждали $want"; }
    sleep 1
  done
  [ $ok = 1 ] && good=$((good+1))
done 3< "$D/segidx.tsv"
[ $tried -gt 0 ] && [ $good = $tried ] && res 0 "L4 ▶ предложения звучит своей длиной у всех $tried" || res 1 "L4 своей длиной $good из $tried"

# ---- L5: экран «Слов» и повторение -------------------------------------------------------------------
say "== L5: экран «Слов» и повторение"
words() {   # открыть «Слова» стендом и вернуть строку видимых подписей; ждём, пока разбор будет готов
  local m l i
  for i in 1 2 3 4 5 6; do
    m=$(mark); launch "$@"; l=$(wl "$m" '🧪 «Слова»:' 20)
    case "$l" in *"Разбираю разговоры"*|"") sleep 5;; *) printf '%s' "$l"; return 0;; esac
  done; printf '%s' "$l"
}
l=$(words --es words words --es review close)
fails=""
for want in "Слова · " "Фразы · " "Знаю · " "Повторение"; do case "$l" in *"$want"*) ;; *) fails="$fails «$want»";; esac; done
printf '%s' "$l" | grep -qE 'Пора повторить: [0-9]+|На сегодня всё|Отмечайте «знаю»' || fails="$fails «строка сроков»"
[ -z "$fails" ] && res 0 "L5 вкладки со счётом, «Повторение» и строка сроков" || res 1 "L5 нет:$fails"
l=$(words --es review ear)
if printf '%s' "$l" | grep -q 'Понял' && printf '%s' "$l" | grep -q 'Не понял'; then res 0 "L5 на слух: «Понял / Не понял»"
elif printf '%s' "$l" | grep -qE 'На сегодня всё|В этом режиме — всё|нечего повторять'; then res 0 "L5 на слух: на сегодня повторять нечего"
else res 1 "L5 на слух: ни кнопок ответа, ни «всё»"; fi
l=$(words --es review say)
if printf '%s' "$l" | grep -q 'Держите и говорите' && printf '%s' "$l" | grep -q 'Не знаю'; then res 0 "L5 вслух: «Держите и говорите / Не знаю»"
elif printf '%s' "$l" | grep -qE 'На сегодня всё|В этом режиме — всё|нечего повторять'; then res 0 "L5 вслух: на сегодня повторять нечего"
else res 1 "L5 вслух: ни кнопки, ни «всё»"; fi
words --es review close >/dev/null
$ADB pull "$F/known_words.json" "$D/known_after.json" >/dev/null 2>&1
v=$("$PY" - "$SNAP/known_words.json" "$D/known_after.json" <<'EOF'
import json, os, sys
def load(p):
    if not os.path.exists(p): return {}
    o = json.load(open(p, encoding='utf-8'))
    return {k: (0, 0, 0) for k in o} if isinstance(o, list) else {k: (v.get('ok', 0), v.get('fail', 0), v.get('last', 0)) for k, v in o.items()}
a, b = load(sys.argv[1]), load(sys.argv[2])
print('same' if a == b else 'изменилось: %d ключей' % sum(1 for k in set(a) | set(b) if a.get(k) != b.get(k)))
EOF
)
[ "$v" = same ] && res 0 "L5 ответов не записано — известное владельца как было" || res 1 "L5 известное: $v"

say "== L6: Falar не падал"
crash=$($ADB shell "logcat -d -v time -t '$T0'" 2>/dev/null | tr -d '\r' | grep -A3 'FATAL EXCEPTION' | grep -A1 'Process: app.falar' | grep -vE 'Process:|^--' | head -1 | cut -c1-160)
[ -z "$crash" ] && res 0 "L6 не падал" || res 1 "L6 упал: $crash"
