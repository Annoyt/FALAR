#!/bin/bash
# Запись комнаты в два канала — шаг 2 проверки второго микрофона (results/2026-10-15-dual-mic.md). Redmi пишет оба
# микрофона (стенд --es mictest rec_<сек>: VOICE_RECOGNITION, стерео, 16 кГц, как у слушания), фразы корпуса играют
# колонки ПК. Один проход — один язык и одна расстановка; разбор потом на ПК, без телефона (шаг 3).
#
#   bash bench/air/record_air2.sh --label laptop-near --lang pt [--gap 3000] [--vol 1.0] [--norm -20] [--n 0]
#   SER=192.168.0.175:37419 bash bench/air/record_air2.sh …     # Redmi по беспроводной отладке; INSTALL=1 — поставить сборку
#
# Кладётся в bench/air/rec/st-<label>-<lang>/ (bench/air/rec не в git: в записи весь звук комнаты): room2.wav — два
# канала, player.tsv — какая фраза и когда играла (часы ПК, мс), rec.txt — расстановка, громкость, сдвиг часов телефона.
# Нормировка — как у playDir приложения: RMS фразы к --norm dBFS. Громкость колонок в системе не трогается: --vol —
# громкость только этого проигрывания (pw-play): 16.10 при 0,6 фразы у телефона рядом с ноутбуком тонули в шуме его
# вентилятора (речь не видна над фоном −33 dBFS), при 1,0 — речь −15…−21 dBFS над фоном −36. --n N — первые N фраз.
#
# Телефон должен лежать: экран погашен или впереди Falar/рабочий стол, минуту не трогали, слушание выключено. Пока
# идёт проход, телефон не трогать — запись сырая, но человек у телефона попадёт в неё.
. "$(dirname "$0")/../stand.sh"   # $STAND, $PRIVATE — состояние стенда и данные телефонов внутри проекта
R=$(cd "$(dirname "$0")/../.." && pwd)
SER=${SER:-${ANDROID_SERIAL:-f6lnlrorgi59xwge}}; ADB="$R/tools/platform-tools/adb -s $SER"
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
LABEL=""; LANG_=""; GAP=3000; VOL=1.0; NORM=-20; N=0
while [ $# -gt 0 ]; do
  case "$1" in
    --label) LABEL=$2; shift 2;; --lang) LANG_=$2; shift 2;; --gap) GAP=$2; shift 2;;
    --vol) VOL=$2; shift 2;; --norm) NORM=$2; shift 2;; --n) N=$2; shift 2;;
    *) echo "не знаю ключ: $1"; exit 2;;
  esac
done
[ -n "$LABEL" ] && [ -n "$LANG_" ] || { echo "нужны --label и --lang pt|ru"; exit 2; }
REF=$R/bench/air/corpus/$LANG_; [ -d "$REF" ] || { echo "нет корпуса $REF"; exit 2; }
OUT=$R/bench/air/rec/st-$LABEL-$LANG_
[ -e "$OUT/room2.wav" ] && [ "${FORCE:-0}" != 1 ] && { echo "запись $OUT уже есть — FORCE=1 перепишет"; exit 1; }
PY=$R/.venv/bin/python; [ -x "$PY" ] || PY=python3
STATE=$STAND; mkdir -p "$STATE"
say() { printf '%s\n' "$*"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i l; for i in $(seq "$3"); do l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
focus() { sh "dumpsys window" | grep -m1 mCurrentFocus; }
asleep() { sh "dumpsys power" | grep -qE 'mWakefulness=(Asleep|Dozing)'; }
# Связь по беспроводной отладке рвётся (10.10 и 15.10: после установки телефон ушёл с новой парой адрес:порт), а пустой
# ответ adb в арифметике оболочки — ошибка: всё, что считается из ответа телефона, — с проверкой на число.
num() { case "$1" in ''|*[!0-9]*) return 1;; esac; }
free1() {
  local a now mt listening
  [ "$($ADB get-state 2>/dev/null)" = device ] || return 1
  a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
  now=$(sh "date +%s"); mt=$(sh "stat -c %Y $LOG 2>/dev/null || echo 0")
  num "$now" && num "$mt" || return 1
  listening=$(sh "grep -E '▶ слушаю|⏹ не слушаю' $LOG | tail -1" | grep -c '▶ слушаю')
  [ "$listening" = 0 ] && [ $((now - mt)) -ge 60 ] && { asleep || { case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) true;; *) false;; esac && [ "${a:-0}" -ge 60000 ]; }; }
}

if [ -z "$FALAR_STAND_LOCK" ]; then
  exec 9>"$STATE/lock"; flock -n 9 || { say "  замок занят — жду, пока освободится"; flock -w ${LOCK_WAIT:-3600} 9 || { say "замок так и не освободился — не начинаю"; exit 1; }; }
fi
$ADB wait-for-device
[ "$(sh 'getprop ro.serialno')" = f6lnlrorgi59xwge ] || { say "это не Redmi стенда ($(sh 'getprop ro.product.model')) — не начинаю"; exit 1; }
if [ "${INSTALL:-0}" = 1 ]; then
  vnew=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$R/bench/apk/AndroidManifest.xml" | head -1)
  vold=$(sh "dumpsys package $PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  [ -n "$vold" ] && [ "${vnew:-0}" -lt "$vold" ] && { say "сборка $vnew старше той, что на телефоне ($vold) — не ставлю"; exit 1; }
  out=$($ADB install --no-incremental -r "$R/bench/apk/Falar.apk" 2>&1 | tr -d '\r' | grep -E '^(Success|Failure)'); say "  установка: ${out:-нет ответа}"
  case "$out" in Success*) ;; *) exit 1;; esac
fi
inst=$(sh "sha256sum \$(pm path $PKG | head -1 | cut -d: -f2)" | cut -d' ' -f1); mine=$(sha256sum "$R/bench/apk/Falar.apk" 2>/dev/null | cut -d' ' -f1)
[ "$inst" = "$mine" ] || { say "на телефоне другая сборка (${inst:0:12}…, эта ${mine:0:12}…) — не начинаю; INSTALL=1 поставит эту"; exit 1; }
n=0; until free1; do
  n=$((n+1)); [ $n -eq 1 ] && say "  жду, пока телефон лежит: экран погашен или впереди Falar, минуту не трогали ($(focus | sed 's/.*{//; s/}.*//'))"
  [ "$($ADB get-state 2>/dev/null)" = device ] || { say "  телефон пропал со связи ($SER) — не начинаю"; exit 1; }
  sleep 15
done

# Фразы — нормированные копии во временном каталоге; длительности — для длины записи.
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
"$PY" - "$REF" "$TMP" "$NORM" "$N" > "$TMP/list.tsv" <<'PYEOF' || { say "фразы не подготовились"; exit 1; }
import glob, os, sys, wave
import numpy as np
ref, tmp, norm, n = sys.argv[1], sys.argv[2], float(sys.argv[3]), int(sys.argv[4])
files = sorted(glob.glob(os.path.join(ref, "*.wav")))
files = files[:n] if n > 0 else files
for f in files:
    with wave.open(f) as w:
        rate, ch, sw = w.getframerate(), w.getnchannels(), w.getsampwidth()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768.0
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    rms = np.sqrt(np.mean(x ** 2)) if len(x) else 0.0
    g = 10 ** (norm / 20) / max(rms, 1e-9)
    peak = np.max(np.abs(x)) * g if len(x) else 0.0
    if peak > 0.99:
        g *= 0.99 / peak
    y = np.clip(np.round(x * g * 32767), -32768, 32767).astype("<i2")
    out = os.path.join(tmp, os.path.basename(f))
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(y.tobytes())
    print(f"{os.path.basename(f)[:-4]}\t{out}\t{len(x) / rate:.3f}")
PYEOF
NF=$(wc -l < "$TMP/list.tsv")
SEC=$("$PY" -c "import sys; d=[float(l.split('\t')[2]) for l in open('$TMP/list.tsv')]; print(int(sum(d) + len(d) * $GAP / 1000 + 25))")
say "== $LABEL-$LANG_: $NF фраз, запись $SEC с, пауза $GAP мс, уровень $NORM dBFS, громкость проигрывания $VOL (системная $(wpctl get-volume @DEFAULT_AUDIO_SINK@ 2>/dev/null | awk '{print $2}'))"

# Сдвиг часов телефона относительно ПК: лучший из пяти замеров по самому короткому ответу.
OFF=$(for i in 1 2 3 4 5 6 7 8; do a=$(date +%s%3N); d=$(sh "date +%s%3N"); b=$(date +%s%3N); num "$d" && echo "$((b - a)) $(( d - (a + b) / 2 ))"; done | sort -n | head -1)
[ -n "$OFF" ] || { say "часы телефона не прочлись — связи нет"; exit 1; }
say "  часы телефона: $(echo $OFF | cut -d' ' -f2) мс от ПК (ответ за $(echo $OFF | cut -d' ' -f1) мс)"

mkdir -p "$OUT"; : > "$OUT/player.tsv"
# Falar уже запущен — модули второй раз о себе не пишут, ждать их строку незачем (16.10 ждали её 3,5 мин впустую).
up=$(sh "pidof $PKG")
m=$(mark); $ADB shell "am start -n $ACT" >/dev/null 2>&1
[ -n "$up" ] || wl "$m" '🧩 модули:' 150 >/dev/null; sleep 2
m=$(mark)
$ADB shell "am start -n $ACT --es mictest rec_$SEC" >/dev/null 2>&1
L0=$(wl "$m" '🎤 запись двух каналов: (началась|не создалась)' 30) || { say "запись не началась"; exit 1; }
case "$L0" in *"не создалась"*) say "запись двух каналов не создалась"; exit 1;; esac
T0=$(printf '%s' "$L0" | sed -n 's/.*началась · \([0-9]*\) .*/\1/p')
RECORDING=1
trap 'rm -rf "$TMP"; [ "$RECORDING" = 1 ] && { $ADB shell "am start -n $ACT --es mictest stop" >/dev/null 2>&1; say "  запись на телефоне остановлена"; }' EXIT
say "  телефон пишет с $T0 (часы телефона)"
asleep || $ADB shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
sleep 3
k=0
while IFS=$'\t' read -r id file dur; do
  a=$(date +%s%3N); pw-play --volume "$VOL" "$file" 2>/dev/null; b=$(date +%s%3N)
  printf '%s\t%s\t%s\t%s\n' "$id" "$a" "$b" "$dur" >> "$OUT/player.tsv"
  k=$((k+1)); [ $((k % 10)) = 0 ] && say "  сыграно $k из $NF"
  sleep "$(awk -v g="$GAP" 'BEGIN { printf "%.3f", g / 1000 }')"
done < "$TMP/list.tsv"
say "  сыграно всё ($k), жду конца записи"
wl "$m" '🎤 запись двух каналов: готово' $((SEC + 60)) >/dev/null || say "  запись не сообщила «готово»"
RECORDING=0
$ADB pull "$F/mic_rec.wav" "$OUT/room2.wav" >/dev/null 2>&1 && $ADB shell "rm -f $F/mic_rec.wav" < /dev/null
sh "tail -n +$((m+1)) $LOG" | grep '🎤 запись' > "$OUT/log.txt"
printf 'метка=%s язык=%s фраз=%s пауза=%s уровень=%s громкость_проигрывания=%s громкость_системы=%s запись_с=%s начало_телефон=%s сдвиг_часов=%s ответ_мс=%s сборка=%s\n' \
  "$LABEL" "$LANG_" "$NF" "$GAP" "$NORM" "$VOL" "$(wpctl get-volume @DEFAULT_AUDIO_SINK@ 2>/dev/null | awk '{print $2}')" "$SEC" "$T0" \
  "$(echo $OFF | cut -d' ' -f2)" "$(echo $OFF | cut -d' ' -f1)" "${mine:0:12}" > "$OUT/rec.txt"
[ -f "$OUT/room2.wav" ] || { say "запись не забралась с телефона"; exit 1; }
say "== записано: $OUT"
"$PY" "$R/tools/air2_check.py" "$OUT" "$REF"
