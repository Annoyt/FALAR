#!/bin/bash
# Слушание во время загрузки моделей (владелец 03.10: «запись на распознавание уже надо вести», «главное
# чтобы это не мешало процессу загрузки»): сказанное, удержанное, набранное и снятое сразу после запуска
# переводится, как поднимутся распознавание и перевод, и загрузка от этого не дольше.
#
#   bash bench/apk/test_early_device.sh            # INSTALL=1 — сначала поставить bench/apk/Falar.apk
#   bash bench/apk/test_early_device.sh --restore  # только вернуть телефон после оборванного прогона
#   RUNS=6 …        # троек холодных запусков для E0 (по умолчанию 6 — все порядки по разу)
#   EARLYASR=wait … # в остальных проверках накопленное распознавать после загрузки перевода (--es earlyasr wait)
#
# Проверяет — в тестовом разговоре «ТЕСТ загрузка»; «человек» — фразы корпуса bench/air/corpus, поданные
# вместо микрофона (--es feedonly 1 --es feedwav: микрофон глух, комната не слышна):
#  E0 загрузка не дольше: холодный запуск со слушанием во время загрузки и тремя фразами — накопленное
#     распознаётся сразу (now) или после загрузки перевода (wait) — против запуска без него (--es earlylisten 0),
#     RUNS троек, порядок в тройках — все шесть по разу. Критерий записан до замера: медиана времени до
#     готовности (поднялись распознавание, перевод, словарь и разговоры) и всей загрузки без ожидания голосов
#     (озвучка нарочно ждёт, пока переведут сказанное) — дольше не больше чем на 0,3 с;
#  E1 во время загрузки кнопки удержания, «Слушать» и «Снимок, текст» нажимаются (enabled);
#  E2 три фразы — на распознавании, на переводе, на озвучке — переведены все, по порядку, тем текстом, что
#     сказан (доля слов корпуса ≥ 0,6); когда распознаны и переведены — секунды от начала запуска;
#  E3 фраза удержанием во время загрузки — касание кнопки, звук из записи (--es micfile) — переведена;
#  E4 набранная во время загрузки фраза (--es typed) — переведена;
#  E5 снимок во время загрузки (--es photopick, свой снимок с надписью) — прочитан после загрузки;
#  E6 выгрузка посреди загрузки: остановили до распознавания (поток на диске) и после (фраза на диске) —
#     после нового запуска сказанное переведено, и ровно один раз;
#  E7 после всего очередь на диске (files/inbox) пуста.
#
# Данные владельца не трогаются: разговор «ТЕСТ загрузка» с самым свежим временем, в конце удаляется;
# выученное, словари, свои слова — из снимка; модули, «молчать», слушание (оно выключено — иначе прогон не
# начинается), стендовые earlylisten/earlyasr — как были. Отпечаток голоса на время прогона выключен: в
# тестовом разговоре голосов нет. Озвучки нет (--es silent 1). Прогон оборвался, даже kill -9, — следующий
# запуск сначала возвращает телефон (или --restore). Замок общий с остальными стендами.
R=$(cd "$(dirname "$0")/../.." && pwd)
SER=${SER:-f6lnlrorgi59xwge}; ONLY_RESTORE=0
for a in "$@"; do case "$a" in --restore) ONLY_RESTORE=1;; esac; done
ADB="$R/tools/platform-tools/adb -s $SER"
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log; TSV=$F/at.tsv
STATE=${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand; mkdir -p "$STATE"; PEND=$STATE/early-pending
FILES="models/learned.json models/phrasebook_user.json word_ru.json known_words.json models/wordlist.json"
PY=$R/.venv/bin/python; [ -x "$PY" ] || PY=python3
RUNS=${RUNS:-6}
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
tmark() { sh "wc -l < $TSV" | awk '{print $1+0}'; }
wl() { local i l; for i in $(seq "$3"); do l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
since() { sh "tail -n +$(($1+1)) $LOG"; }
tsince() { sh "tail -n +$(($1+1)) $TSV"; }
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
inbox() { sh "ls $F/inbox 2>/dev/null" | grep -vE '^$'; }

# ---- замок -----------------------------------------------------------------------------------------
if [ -z "$FALAR_STAND_LOCK" ]; then
  exec 9>"$STATE/lock"
  flock -n 9 || { say "  замок занят — жду, пока освободится"; flock -w ${LOCK_WAIT:-7200} 9 || { say "замок так и не освободился — не начинаю"; exit 1; }; }
fi

# ---- возврат: по файлу $PEND, поэтому переживает и kill -9 ------------------------------------------
restore() {
  [ -f "$PEND" ] || return 0
  local snap mods tid set wavs
  snap=$(sed -n 's/^snap //p' "$PEND"); mods=$(sed -n 's/^mods //p' "$PEND"); tid=$(sed -n 's/^chat //p' "$PEND")
  set=$(sed -n 's/^set //p' "$PEND"); wavs=$(sed -n 's/^wavs //p' "$PEND")
  say "== возврат"
  # недоразобранное оборванного прогона — фразы тестового разговора: убрать до запуска, иначе их переведут в текущий
  $ADB shell "am force-stop $PKG; rm -rf $F/inbox"; sleep 1
  if grep -q '^started' "$PEND"; then launch "--es listen off $set --es modules '${mods}'"; sleep 4; fi
  $ADB shell "am force-stop $PKG"; sleep 1
  local f; for f in $FILES; do
    if [ -f "$snap/$(basename $f)" ]; then $ADB push "$snap/$(basename $f)" "$F/$f" >/dev/null 2>&1 && say "  вернул $f"
    elif [ -f "$snap/$(basename $f).none" ]; then $ADB shell "rm -f $F/$f"; fi
  done
  [ -n "$tid" ] && $ADB shell "rm -f $F/chats/$tid.json $F/audio/${tid}_*" && say "  тестовый разговор удалён вместе со звуком"
  local w; for w in $wavs; do $ADB shell "rm -f $F/$w"; done
  $ADB shell "rm -rf $F/inbox"
  say "  текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  rm -f "$PEND"; [ -n "$snap" ] && rm -rf "$snap"
  launch; say "  модули: [${mods}] · ${set} · слушание выключено"
}
$ADB wait-for-device
restore
[ "$ONLY_RESTORE" = 1 ] && exit 0
n=0; until free3; do n=$((n+1)); [ $n -eq 1 ] && say "  жду, пока телефон свободен ($(focus | sed 's/.*{//; s/}.*//'))"; sleep 20; done

# ---- INSTALL=1 ------------------------------------------------------------------------------------
if [ "${INSTALL:-0}" = 1 ]; then
  APK=$R/bench/apk/Falar.apk; [ -f "$APK" ] || { say "нет $APK — сначала build.sh"; exit 1; }
  vnew=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$R/bench/apk/AndroidManifest.xml" | head -1)
  vold=$(sh "dumpsys package $PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  [ -n "$vold" ] && [ "${vnew:-0}" -lt "$vold" ] && { say "сборка $vnew старше той, что на телефоне ($vold): понижение = удаление с данными — не ставлю"; exit 1; }
  free=$(sh "df /data" | awk 'NR==2{print $4}'); [ "${free:-0}" -ge 1500000 ] || { say "мало места для установки ($free КБ) — попросите владельца перезагрузить телефон"; exit 1; }
  before=$(sh "dumpsys package $PKG" | grep -m1 lastUpdateTime)
  out=$($ADB install --no-incremental -r "$APK" 2>&1 | tr -d '\r' | grep -E '^(Success|Failure)'); say "  установка: ${out:-нет ответа}"
  after=$(sh "dumpsys package $PKG" | grep -m1 lastUpdateTime)
  case "$out" in Success*) [ "$before" != "$after" ] || { say "сборка не сменилась"; exit 1; };; *) exit 1;; esac
fi
inst=$(sh "sha256sum \$(pm path $PKG | head -1 | cut -d: -f2)" | cut -d' ' -f1); mine=$(sha256sum "$R/bench/apk/Falar.apk" 2>/dev/null | cut -d' ' -f1)
[ "${ANYBUILD:-0}" = 1 ] || [ "$inst" = "$mine" ] || { say "на телефоне другая сборка (${inst:0:12}…, эта ${mine:0:12}…) — не начинаю; INSTALL=1 поставит эту"; exit 1; }
D=$(mktemp -d "$STATE/early-run.XXXX")
trap 'restore; rm -rf "$D"; say; say "итог: PASS $pass, FAIL $fail"' EXIT

# ---- записи: три фразы с паузами (на распознавании, на переводе, на озвучке), одна — для удержания и выгрузки
C=$R/bench/air/corpus/pt
"$PY" - "$C" "$D" <<'EOF'
import sys, wave, array, json
C, D = sys.argv[1], sys.argv[2]
def rd(n):
    w = wave.open(f"{C}/{n}.wav"); assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2
    return array.array('h', w.readframes(w.getnframes())), open(f"{C}/{n}.txt", encoding='utf-8').read().strip()
def sil(s): return array.array('h', bytes(2 * int(16000 * s)))
import random
def wr(name, parts):
    a = array.array('h'); meta = []
    for p in parts:
        if isinstance(p, float): a.extend(sil(p))
        else:
            x, t = rd(p); b = len(a) / 16000; a.extend(x); meta.append({"name": p, "text": t, "from": round(b, 2), "to": round(len(a) / 16000, 2)})
    # тихая комната (−60 dBFS) поверх всего: между фразами цифровой ноль, и фон нарезки считался по шуму самих
    # записей корпуса — −45 dBFS, шумодав нарезки включался и терял фразу (прогон 03.10, набор three2)
    rnd = random.Random(7); g = 32768 * 10 ** (-60 / 20)
    a = array.array('h', (max(-32768, min(32767, v + int(rnd.gauss(0, g)))) for v in a))
    w = wave.open(f"{D}/{name}.wav", 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(a.tobytes()); w.close()
    return meta
# своя тройка на каждый запуск: одна и та же фраза в разговоре второй раз — «прочли вслух с экрана» (Heard)
three = [["tat_1063144", "tat_1113220", "tat_1179406"], ["tat_1035724", "tat_1063173", "tat_1172131"],
         ["tat_1063245", "tat_1112641", "tat_1146068"], ["tat_1325544", "tat_1061222", "tat_1102494"]]
m = {f"three{k + 1}": wr(f"early_three{k + 1}", [0.5, t[0], 1.8, t[1], 2.0, t[2], 2.0]) for k, t in enumerate(three)}
m["one_asr"] = wr("early_one_asr", [0.2, "tat_1323002", 4.0])        # E6: разные фразы — одна и та же второй раз «прочитана с экрана»
m["one_heard"] = wr("early_one_heard", [0.2, "tat_1403886", 4.0])
m["hold"] = wr("early_hold", [0.1, "tat_2440707", 0.3])
json.dump(m, open(f"{D}/phrases.json", "w", encoding="utf-8"), ensure_ascii=False)
EOF
# снимок со своей надписью (не снимки владельца): вывеска на белом
"$PY" - "$D/early_photo.jpg" <<'EOF'
import sys
from PIL import Image, ImageDraw, ImageFont
im = Image.new("RGB", (1200, 700), "white"); d = ImageDraw.Draw(im)
f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 96)
d.text((80, 160), "SAÍDA DE", fill="black", font=f); d.text((80, 320), "EMERGÊNCIA", fill="black", font=f)
im.save(sys.argv[1], quality=92)
EOF
WAVS="early_three1.wav early_three2.wav early_three3.wav early_three4.wav early_one_asr.wav early_one_heard.wav early_hold.wav early_photo.jpg"
for w in $WAVS; do $ADB push "$D/$w" "$F/$w" >/dev/null 2>&1 || { say "не положил $w на телефон"; exit 1; }; done
HOLD_MS=$("$PY" -c "import json; m=json.load(open('$D/phrases.json')); print(int(m['hold'][-1]['to']*1000)+700)")

# ---- снимок, модули, тестовый разговор --------------------------------------------------------------
say "== снимок перед проверкой"
SNAP="$STATE/early-snap-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$SNAP"
for f in $FILES; do
  n=$(sh "stat -c %s $F/$f 2>/dev/null")
  if [ -n "$n" ]; then $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1
    [ "$(stat -c %s "$SNAP/$(basename $f)" 2>/dev/null)" = "$n" ] || { say "снимок $f не сошёлся — не начинаю"; exit 1; }
  else touch "$SNAP/$(basename $f).none"; fi
done
case "$(sh "grep -E '▶ слушаю|⏹ не слушаю' $LOG | tail -1")" in *"▶ слушаю"*) say "у владельца включено слушание — не начинаю"; exit 1;; esac
[ -z "$(inbox)" ] || { say "в очереди на диске уже что-то лежит (files/inbox) — это не наш прогон, не начинаю"; exit 1; }
m=$(mark); launch --es modules show
MODS=$(wl "$m" '🧩 модули сейчас' 60 | grep -oE '\[[a-z,]*\]' | tr -d '[]')
[ -n "$(wl "$m" '🧩 модули сейчас' 1)" ] || { say "модули не прочлись — не начинаю"; exit 1; }
SET="--es silent 0 --es earlylisten 1 --es earlyasr now"
TID=$(date +%s%3N)
{ echo "snap $SNAP"; echo "mods $MODS"; echo "chat $TID"; echo "set $SET"; echo "wavs $WAVS"; } > "$PEND"
say "  $(ls "$SNAP" | tr '\n' ' ')· модули [$MODS]"
"$PY" -c "import json; json.dump({'id': $TID, 'name': 'ТЕСТ загрузка', 'named': True, 'saved': $TID, 'turns': []}, open('$D/t.json','w',encoding='utf-8'), ensure_ascii=False)"
$ADB shell "am force-stop $PKG"; sleep 1
$ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
echo started >> "$PEND"
MODS_RUN=$(printf '%s' ",$MODS," | tr ',' '\n' | grep -v -e '^$' -e '^speaker$' | paste -sd,)
OCR=0; case ",$MODS_RUN," in *,ocr,*) OCR=1;; esac
m=$(mark); launch "--es modules '$MODS_RUN' --es silent 1 --es listen pt --es earlyasr ${EARLYASR:-now}"
wl "$m" '🧩 модули:' 150 >/dev/null; sleep 2
cur=$(sh "ls -t $F/chats/ | head -1"); [ "$cur" = "$TID.json" ] || { res 1 "E· текущий разговор не тестовый: $cur"; exit 1; }

# cold <extras…> — остановить и запустить заново; в $D/run.t — строка at.tsv до запуска, run.m — at.log
cold() { $ADB shell "am force-stop $PKG"; sleep 1; wait_idle; tmark > "$D/run.t"; mark > "$D/run.m"; $ADB shell "am start -n $ACT $*" >/dev/null 2>&1; }
loaded() { wl "$(cat "$D/run.m")" '🧩 модули:' "${1:-60}" >/dev/null; }
# разбор прогона из at.tsv: этапы загрузки, события early, реплики — JSON в $1
parse() {
  tsince "$(cat "$D/run.t")" > "$D/run.tsv"
  "$PY" - "$D/run.tsv" "$1" <<'EOF'
import sys, json
L = [l.rstrip('\n').split('\t') for l in open(sys.argv[1], encoding='utf-8', errors='replace') if '\t' in l]
r = {"t0": None, "stage": {}, "early": [], "turns": [], "feed": None}
for f in L:
    try: t = int(f[0])
    except ValueError: continue
    k = f[1]
    if k == 'models_check' and r["t0"] is None: r["t0"] = t
    elif k == 'busy' and len(f) > 3 and f[2] == 'load' and f[3] not in r["stage"]: r["stage"][f[3]] = t
    elif k == 'early': r["early"].append([f[2], t] + f[3:])
    elif k == 'feed_begin' and r["feed"] is None: r["feed"] = t
    elif k in ('asr', 'набрано') and len(f) > 4: r["turns"].append({"t": t, "kind": k, "dir": f[2], "src": f[3], "dst": f[4]})
    elif k == 'ocr': r["turns"].append({"t": t, "kind": "фото", "dir": "pt2ru", "src": "", "dst": ""})
json.dump(r, open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False)
EOF
}
# ждать N реплик в журнале машины после запуска (до T с)
turns() { local i n; for i in $(seq "${2:-40}"); do n=$(tsince "$(cat "$D/run.t")" | awk -F'\t' '$2=="asr"||$2=="набрано"||$2=="ocr"' | wc -l); [ "$n" -ge "$1" ] && return 0; sleep 1; done; return 1; }

# ---- E1: кнопки нажимаются во время загрузки --------------------------------------------------------
say "== E1: кнопки во время загрузки"
cold "--es feedonly 1 --es silent 1"
sleep 1.2
sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" > "$D/ui.xml"
dumped=$(sh "date +%s%3N")
loaded 60
"$PY" - "$D/ui.xml" > "$D/e1" <<'EOF'
import re, sys
x = open(sys.argv[1], encoding='utf-8', errors='replace').read()
def node(desc):
    m = re.search(r'<node [^>]*content-desc="' + desc + r'[^"]*"[^>]*>', x)
    if not m: return None
    n = m.group(0); en = re.search(r'enabled="(\w+)"', n).group(1); b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()
    return en, b
mic = node("Удерживайте и говорите"); lis = node("Обе вместе"); inp = node("Снимок или набрать")
print(" ".join(["mic", mic[0] if mic else "нет", "listen", lis[0] if lis else "нет", "input", inp[0] if inp else "нет"]))
print(" ".join(mic[1]) if mic else "")
EOF
parse "$D/e1.json"
tend=$("$PY" -c "import json; r=json.load(open('$D/e1.json')); print(r['stage'].get('-', 0))")
read -r _ m_en _ l_en _ i_en < "$D/e1"
BTN=$(sed -n 2p "$D/e1")
[ "$tend" -gt "$dumped" ] 2>/dev/null && inload=1 || inload=0
[ "$inload" = 1 ] && [ "$m_en" = true ] && [ "$l_en" = true ] && [ "$i_en" = true ] \
  && res 0 "E1 во время загрузки нажимаются: удержание, «Слушать», «Снимок, текст»" \
  || res 1 "E1 кнопки во время загрузки: удержание $m_en, «Слушать» $l_en, «Снимок, текст» $i_en (снято до конца загрузки: $inload)"

# ---- E0 + E2: загрузка со слушанием и без него, попеременно ------------------------------------------
# now — накопленное распознаётся сразу, как поднялось распознавание; wait — после загрузки перевода; off — как было.
say "== E0/E2: $RUNS троек холодных запусков в разном порядке — слушание во время загрузки (now, wait — три фразы) и без него (off)"
: > "$D/now.list"; : > "$D/wait.list"; : > "$D/off.list"; n3=0
PERM=("now wait off" "wait off now" "off now wait" "now off wait" "off wait now" "wait now off")   # порядок — каждый на каждом месте поровну
for k in $(seq "$RUNS"); do
  for mode in ${PERM[$(( (k - 1) % 6 ))]}; do
    if [ $mode = off ]; then launch --es earlylisten 0; else launch "--es earlylisten 1 --es earlyasr $mode"; fi; sleep 2
    if [ $mode = off ]; then cold "--es feedonly 1 --es silent 1"            # микрофон глух и здесь: слушание включится в конце загрузки
    else n3=$((n3 + 1)); echo "three$(( (n3 - 1) % 4 + 1 ))" > "$D/$mode$k.set"; cold "--es feedonly 1 --es feedwav $F/early_three$(( (n3 - 1) % 4 + 1 )).wav --es silent 1"; fi
    loaded 60 || say "  запуск $k ($mode): загрузка не кончилась за минуту"
    [ $mode = off ] || turns 3 30 || say "  запуск $k ($mode): три реплики не пришли за 30 с"
    sleep 1; parse "$D/$mode$k.json"; echo "$D/$mode$k.json" >> "$D/$mode.list"
  done
done
launch "--es earlylisten 1 --es earlyasr ${EARLYASR:-now}"; sleep 2
"$PY" - "$D" "$D/phrases.json" > "$D/e0" <<'EOF'
import sys, json, statistics as st, re, unicodedata
D = sys.argv[1]; PP = json.load(open(sys.argv[2], encoding='utf-8'))
order = ["распознавание", "перевод", "словарь и разговоры", "озвучка", "-"]
def stages(r):
    s = r["stage"]
    if any(k not in s for k in order) or r["t0"] is None: return None
    ready = [e[1] for e in r["early"] if e[0] == "ready"]
    late = sum(int(e[2]) for e in r["early"] if e[0] == "tts_after")      # озвучка ждала перевода сказанного — не загрузка
    if not ready: return None
    return {"asr": s["перевод"] - s["распознавание"], "mt": s["словарь и разговоры"] - s["перевод"],
            "ready": ready[0] - r["t0"], "tts": s["-"] - s["озвучка"], "wait": late, "all": s["-"] - r["t0"] - late}
def load(m): return [(f.strip(), json.load(open(f.strip(), encoding='utf-8'))) for f in open(f"{D}/{m}.list") if f.strip()]
runs = {m: load(m) for m in ("now", "wait", "off")}
S = {m: [x for x in (stages(r) for _, r in runs[m]) if x] for m in runs}
def med(a, k): return st.median([x[k] for x in a]) if a else float('nan')
print("мс                    now (сразу)                       wait (после перевода)             off (без слушания)")
for k, n in [("asr", "распознавание"), ("mt", "перевод"), ("ready", "до готовности"), ("tts", "озвучка"), ("wait", "голоса ждали"), ("all", "вся без ожидания")]:
    print(f"  {n:14s}" + "".join(f" {med(S[m],k):6.0f} ({' '.join(str(x[k]) for x in S[m])})" for m in ("now", "wait", "off")))
for m in ("now", "wait"):
    ok = bool(S[m] and S["off"]) and med(S[m], "ready") <= med(S["off"], "ready") + 300 and med(S[m], "all") <= med(S["off"], "all") + 300
    print(f"E0{m}", "ok" if ok else "worse", f"{med(S[m],'ready')-med(S['off'],'ready'):+.0f}", f"{med(S[m],'all')-med(S['off'],'all'):+.0f}", len(S[m]), len(S["off"]))
# E2: три фразы каждого запуска со слушанием — все, по порядку, тем текстом; когда распознаны и переведены
def words(s): return [w for w in re.sub(r"[^\w\s]", " ", unicodedata.normalize("NFC", s.lower())).split() if w]
def share(ref, got):
    r, g = words(ref), set(words(got)); return sum(1 for w in r if w in g) / max(1, len(r))
bad = 0; n = 0; rows = []
for m in ("now", "wait"):
    for f, r in runs[m]:
        n += 1; P = PP[open(f[:-5] + ".set").read().strip()]
        tu = [t for t in r["turns"] if t["kind"] == "asr"]; feed = r["feed"]; t0 = r["t0"]
        heard = [e for e in r["early"] if e[0] == "heard"]
        if len(tu) != 3 or feed is None or t0 is None: bad += 1; rows.append(f"  {m}: реплик {len(tu)} из 3"); continue
        sh = [share(p["text"], t["src"]) for p, t in zip(P, tu)]
        if min(sh) < 0.6: bad += 1
        for i, (p, t) in enumerate(zip(P, tu)):
            said = (feed - t0) / 1000 + p["to"]; got = (t["t"] - t0) / 1000
            hv = f"{(heard[i][1] - t0) / 1000:5.1f}" if i < len(heard) else "  —  "
            rows.append(f"  {m} · фраза {i+1}: сказана до {said:4.1f} с · распознана {hv} с · переведена {got:4.1f} с (через {got - said:3.1f} с) · совпало слов {sh[i]:.2f}")
print("E2", "ok" if bad == 0 and n else "bad", bad, n)
print("\n".join(rows))
for m in ("now", "wait"):
    up = [(e[1] - r["t0"]) / 1000 for _, r in runs[m] for e in r["early"] if e[0] == "asr" and r["t0"]]
    rd = [(e[1] - r["t0"]) / 1000 for _, r in runs[m] for e in r["early"] if e[0] == "ready" and r["t0"]]
    if up and rd: print(f"  {m}: распознавание поднималось на {st.median(up):.1f} с, кнопки становились обычными на {st.median(rd):.1f} с (медианы)")
EOF
grep -vE '^E[02]' "$D/e0"
# Проверка — у того, как стоит в приложении (earlyasr now); wait — для сравнения: прогон 03.10 — он не короче
# (вся загрузка +356 мс против +272), а текст сказанного показывает только вместе с переводом.
l=$(grep "^E0now " "$D/e0"); lw=$(grep "^E0wait " "$D/e0")
case "$l" in "E0now ok"*) res 0 "E0 загрузка со слушанием не дольше: до готовности $(echo $l | cut -d' ' -f3) мс, вся $(echo $l | cut -d' ' -f4) мс (медианы, запусков $(echo $l | cut -d' ' -f5)+$(echo $l | cut -d' ' -f6))";;
  *) res 1 "E0 загрузка со слушанием дольше: до готовности $(echo $l | cut -d' ' -f3) мс, вся $(echo $l | cut -d' ' -f4) мс (критерий +300)";; esac
say "  для сравнения, накопленное — после загрузки перевода (wait): до готовности $(echo $lw | cut -d' ' -f3) мс, вся $(echo $lw | cut -d' ' -f4) мс"
l2=$(grep '^E2 ' "$D/e0")
case "$l2" in "E2 ok"*) res 0 "E2 три фразы, сказанные во время загрузки, переведены все, по порядку, во всех $(echo $l2 | cut -d' ' -f4) запусках";;
  *) res 1 "E2 фразы во время загрузки: неудачных запусков $(echo $l2 | cut -d' ' -f3) из $(echo $l2 | cut -d' ' -f4)";; esac

# ---- E3 + E4 + E5: удержание, набранное и снимок во время загрузки -----------------------------------
say "== E3/E4/E5: удержание, набранная фраза и снимок во время загрузки"
launch --es listen off; sleep 2
TYPED="Onde fica a estação de trem?"
cold "--es micfile $F/early_hold.wav --es silent 1 --es typed '$TYPED'"
sleep 1.0
[ "$OCR" = 1 ] && $ADB shell "am start -n $ACT --es photopick $F/early_photo.jpg" >/dev/null 2>&1
if [ -n "$BTN" ]; then
  read -r x0 y0 x1 y1 <<< "$BTN"; cx=$(( (x0 + x1) / 2 )); cy=$(( (y0 + y1) / 2 ))
  sleep 0.5; $ADB shell "input swipe $cx $cy $cx $cy $HOLD_MS"
fi
loaded 60
want=$(( 2 + OCR )); turns $want 40
sleep 1; parse "$D/e3.json"
since "$(cat "$D/run.m")" | grep -F '📷 OCR за' | tail -1 > "$D/e3.ocr"
"$PY" - "$D/e3.json" "$D/phrases.json" "$TYPED" "$OCR" "$D/e3.ocr" > "$D/e3" <<'EOF'
import sys, json, re, unicodedata
r = json.load(open(sys.argv[1], encoding='utf-8')); P = json.load(open(sys.argv[2], encoding='utf-8')); typed = sys.argv[3]; ocr = sys.argv[4] == "1"
def words(s): return [w for w in re.sub(r"[^\w\s]", " ", unicodedata.normalize("NFC", s.lower())).split() if w]
def share(ref, got): rr, g = words(ref), set(words(got)); return sum(1 for w in rr if w in g) / max(1, len(rr))
end = r["stage"].get("-", 0); t0 = r["t0"] or 0
h = [t for t in r["turns"] if t["kind"] == "asr"]; ty = [t for t in r["turns"] if t["kind"] == "набрано"]; ph = [t for t in r["turns"] if t["kind"] == "фото"]
hs = max([share(P["hold"][0]["text"], t["src"]) for t in h] or [0])
print("E3", "ok" if len(h) == 1 and hs >= 0.6 else "bad", len(h), f"{hs:.2f}", f"{(h[0]['t'] - t0) / 1000:.1f}" if h else "—")
print("E4", "ok" if len(ty) == 1 and ty[0]["src"].strip() == typed else "bad", len(ty), f"{(ty[0]['t'] - t0) / 1000:.1f}" if ty else "—")
ocrline = open(sys.argv[5], encoding='utf-8', errors='replace').read()
ps = share("SAÍDA DE EMERGÊNCIA", ocrline.split(" · ")[-1] if ocrline else "")
print("E5", "skip" if not ocr else ("ok" if len(ph) == 1 and ps >= 0.6 else "bad"), len(ph), f"{ps:.2f}", f"{(ph[0]['t'] - t0) / 1000:.1f}" if ph else "—")
EOF
read -r _ s3 n3 sh3 t3 <<< "$(grep '^E3 ' "$D/e3")"; read -r _ s4 n4 t4 <<< "$(grep '^E4 ' "$D/e3")"; read -r _ s5 n5 sh5 t5 <<< "$(grep '^E5 ' "$D/e3")"
[ -n "$BTN" ] || s3=nobutton
[ "$s3" = ok ] && res 0 "E3 удержание во время загрузки переведено (на $t3 с, совпало слов $sh3)" || res 1 "E3 удержание во время загрузки: реплик $n3, совпало слов $sh3 ($s3)"
[ "$s4" = ok ] && res 0 "E4 набранное во время загрузки переведено (на $t4 с)" || res 1 "E4 набранное во время загрузки: реплик $n4"
case "$s5" in ok) res 0 "E5 снимок во время загрузки прочитан после неё (на $t5 с)";; skip) say "SKIP E5 модуль «Чтение снимков» выключен";;
  *) res 1 "E5 снимок во время загрузки: реплик «📷» $n5, совпало слов $sh5";; esac
launch --es listen pt; sleep 2

# ---- E6: выгрузка посреди загрузки -------------------------------------------------------------------
say "== E6: выгрузка посреди загрузки — до распознавания и после"
e6() {   # $1 — когда останавливать: asr (до того, как поднялось распознавание) или heard (фраза распознана)
  local i t ok=0 left ONE
  ONE=$("$PY" -c "import json; print(json.load(open('$D/phrases.json'))['one_$1'][0]['text'])")
  cold "--es feedonly 1 --es feedwav $F/early_one_$1.wav --es silent 1"
  if [ "$1" = asr ]; then
    # фраза (2,3 с с 0,2 с) подана целиком, распознавание ещё грузится (оно идёт 3,1–4,3 с)
    for i in $(seq 40); do t=$(tsince "$(cat "$D/run.t")" | awk -F'\t' '$2=="feed_begin"{print $1; exit}'); [ -n "$t" ] && break; sleep 0.1; done
    while [ $(( $(sh "date +%s%3N") - ${t:-0} )) -lt 2700 ]; do sleep 0.1; done
  else
    for i in $(seq 100); do tsince "$(cat "$D/run.t")" | awk -F'\t' '$2=="early" && $3=="heard"' | grep -q . && break; sleep 0.1; done
  fi
  local early; early=$(tsince "$(cat "$D/run.t")" | awk -F'\t' '$2=="early" && $3=="asr"' | wc -l)
  $ADB shell "am force-stop $PKG"; sleep 1
  left=$(inbox | tr '\n' ' ')
  cold "--es feedonly 1 --es silent 1"
  loaded 60; turns 1 20; sleep 2
  parse "$D/e6$1.json"
  local n; n=$("$PY" -c "
import json, re
r = json.load(open('$D/e6$1.json', encoding='utf-8'))
def w(s): return [x for x in re.sub(r'[^\w\s]', ' ', s.lower()).split() if x]
ref = w('''$ONE'''); tu = [t for t in r['turns'] if t['kind'] == 'asr' and sum(1 for x in ref if x in set(w(t['src']))) / max(1, len(ref)) >= 0.6]
print(len(tu))")
  say "  остановлено $( [ "$early" -gt 0 ] && echo 'после того, как поднялось распознавание' || echo 'до распознавания'); на диске было: ${left:-ничего}"
  [ "$n" = 1 ] && res 0 "E6 выгрузка ($1): сказанное переведено после нового запуска, один раз" || res 1 "E6 выгрузка ($1): та же фраза в разговоре $n раз(а)"
}
e6 asr
e6 heard

# ---- E7: очередь на диске пуста ------------------------------------------------------------------------
left=$(inbox | tr '\n' ' ')
[ -z "$left" ] && res 0 "E7 очередь на диске пуста" || res 1 "E7 в очереди на диске осталось: $left"
say; say "время по запускам — $D/e0 (до возврата телефона); журнал прогона — at.log/at.tsv на телефоне"
cp "$D/e0" "$STATE/early-last.txt" 2>/dev/null
