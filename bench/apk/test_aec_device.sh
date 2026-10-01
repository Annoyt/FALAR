#!/bin/bash
# Эхо озвучки в микрофоне телефона: сколько его остаётся при разных настройках записи.
#
#   bash bench/apk/test_aec_device.sh [vr,vr_aec,vc,vc_aec]      # INSTALL=1 — сначала поставить сборку
#
# Телефон говорит одну фразу через динамик и сам себя пишет — по конфигурации за раз (--es aectest,
# TranslatorService.aecStand): vr — источник VOICE_RECOGNITION, как у приложения; vc — VOICE_COMMUNICATION;
# _aec — со встроенным эхоподавителем Android на сессии записи. Режим звука и маршрут не трогаются;
# VOICE_COMMUNICATION при подключённом Bluetooth пропускается (§5 плана). Записи и что играли —
# в ~/.cache/falar-stand/aec-<время>/, на телефоне удаляются; разбор — tools/aec_eval.py.
#
# Только на свободный телефон: экран погашен или впереди Falar/рабочий стол, экрана не касались 3 минуты,
# журнал Falar молчит 3 минуты и владелец не слушает. Замок общий с test_all_device.sh.
R=$(cd "$(dirname "$0")/../.." && pwd)
SER=${SER:-f6lnlrorgi59xwge}; ADB="$R/tools/platform-tools/adb -s $SER"
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
CFGS=${1:-vr,vr_aec,vc,vc_aec}; [ "$CFGS" = "--restore" ] && CFGS=vr
STATE=${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand; mkdir -p "$STATE"; OUT=$STATE/aec-$(date +%Y%m%d-%H%M%S)
say() { printf '%s\n' "$*"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i l; for i in $(seq "$3"); do l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
focus() { sh "dumpsys window" | grep -m1 mCurrentFocus; }
asleep() { sh "dumpsys power" | grep -qE 'mWakefulness=(Asleep|Dozing)'; }
free3() {
  local a quiet listening
  a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
  quiet=$(( $(sh "date +%s") - $(sh "stat -c %Y $LOG 2>/dev/null || echo 0") ))
  listening=$(sh "grep -E '▶ слушаю|⏹ не слушаю' $LOG | tail -1" | grep -c '▶ слушаю')
  [ "$listening" = 0 ] && [ "$quiet" -ge 180 ] && { asleep || { case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) true;; *) false;; esac && [ "${a:-0}" -ge 180000 ]; }; }
}
wait_free() { local n=0; until free3; do n=$((n+1)); [ $n -eq 1 ] && say "  жду, пока телефон свободен ($(focus | sed 's/.*{//; s/}.*//'))"; sleep 20; done; }

PENDV=$STATE/aec-volume-pending
# Вернуть громкость из $PENDV и сверить; файл убирается, только если громкость на телефоне та самая.
putvol() {
  [ -f "$PENDV" ] || return 0
  local v now; v=$(cat "$PENDV")
  sh "cmd media_session volume --stream 3 --set $v" >/dev/null
  now=$(sh "cmd media_session volume --stream 3 --get" | sed -n 's/.*volume is \([0-9]*\).*/\1/p')
  if [ "$now" = "$v" ]; then rm -f "$PENDV"; say "  громкость возвращена: $v"; else say "  ГРОМКОСТЬ НЕ ВОЗВРАЩЕНА (нужно $v, сейчас ${now:-нет связи}) — вернёт следующий запуск или --restore"; fi
}
if [ -z "$FALAR_STAND_LOCK" ]; then
  exec 9>"$STATE/lock"; flock -n 9 || { say "  замок занят — жду, пока освободится"; flock -w ${LOCK_WAIT:-3600} 9 || { say "замок так и не освободился — не начинаю"; exit 1; }; }
fi
$ADB wait-for-device
putvol
[ "$1" = "--restore" ] && exit 0
wait_free

if [ "${INSTALL:-0}" = 1 ]; then
  APK=$R/bench/apk/Falar.apk
  vnew=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$R/bench/apk/AndroidManifest.xml" | head -1)
  vold=$(sh "dumpsys package $PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  [ -n "$vold" ] && [ "${vnew:-0}" -lt "$vold" ] && { say "сборка $vnew старше той, что на телефоне ($vold) — не ставлю"; exit 1; }
  out=$($ADB install --no-incremental -r "$APK" 2>&1 | tr -d '\r' | grep -E '^(Success|Failure)'); say "  установка: ${out:-нет ответа}"
  case "$out" in Success*) ;; *) exit 1;; esac
fi
mkdir -p "$OUT"
# Громкость — как при разговоре через стол (VOL из 15, по умолчанию 10); прежняя возвращается в конце и
# сверяется. Телефон отключили посреди прогона — прежняя громкость остаётся в $PENDV, и её вернёт
# следующий запуск (или --restore), как только телефон снова на связи.
VOL0=$(sh "cmd media_session volume --stream 3 --get" | sed -n 's/.*volume is \([0-9]*\).*/\1/p')
[ -n "$VOL0" ] || { say "громкость не прочлась — не начинаю"; exit 1; }
echo "$VOL0" > "$PENDV"
trap 'putvol' EXIT
sh "cmd media_session volume --stream 3 --set ${VOL:-10}" >/dev/null; say "  громкость: ${VOL:-10} из 15 (была $VOL0)"
m=$(mark); $ADB shell "am start -n $ACT" >/dev/null 2>&1
wl "$m" '🧩 модули:' 150 >/dev/null; sleep 3
m=$(mark)
# NEAR=1 — «человек» из колонок ПК: на каждую метку стенда («играю …» или «тишина …») через 1 с колонки
# говорят следующую живую фразу из корпуса; микрофон ПК пишет всё (по нему видно, не тише ли сама
# озвучка телефона в другом режиме записи). Какие фразы и когда — в near.tsv.
if [ "${NEAR:-0}" = 1 ]; then
  PY=$R/.venv/bin/python; [ -x "$PY" ] || PY=python3
  "$PY" - "$R" "$OUT" <<'EOF'
import os, sys, random
R, OUT = sys.argv[1], sys.argv[2]
random.seed(20261002)
c = []
for lang in ('pt', 'ru'):
    d = f'{R}/bench/air/corpus/{lang}'
    for f in sorted(os.listdir(d)):
        if f.endswith('.wav'):
            c.append(f'{d}/{f[:-4]}')
random.shuffle(c)
open(f'{OUT}/near-list.txt', 'w').write('\n'.join(c[:40]) + '\n')
EOF
  pw-record --rate 16000 --channels 1 "$OUT/pc_mic.wav" & PCREC=$!
  (
    k=0; seen=$m
    while :; do
      l=$(sh "tail -n +$((seen+1)) $LOG | grep -n -E -m1 '🔁 эхо-стенд: (играю|тишина|готово)'")
      [ -z "$l" ] && { sleep 0.3; continue; }
      seen=$((seen + ${l%%:*})); case "$l" in *готово*) break;; esac
      cfg=$(printf '%s' "$l" | sed -n 's/.*: \(играю\|тишина\) \([a-z_0-9]*\).*/\2/p')
      clip=$(sed -n "$((k % 40 + 1))p" "$OUT/near-list.txt"); k=$((k+1))
      sleep 1.0
      printf '%s\t%s\t%s\n' "$cfg" "$clip" "$(date +%s%3N)" >> "$OUT/near.tsv"
      pw-play "$clip.wav" 2>/dev/null
    done
  ) & PLAYER=$!
fi
$ADB shell "am start -n $ACT --es aectest $CFGS" >/dev/null 2>&1
wl "$m" '🔁 эхо-стенд: (готово|ошибка|движок)' 300 >/dev/null
if [ -n "$PLAYER" ]; then sleep 1; kill $PLAYER 2>/dev/null; kill -INT $PCREC 2>/dev/null; wait $PCREC 2>/dev/null; fi
sh "tail -n +$((m+1)) $LOG" | grep '🔁' | tee "$OUT/log.txt" | sed 's/^/  /'
for f in aec.json aec_ref.wav $(printf '%s\n' "$CFGS" | tr ',' '\n' | sed 's/^/aec_/; s/$/.wav/'); do
  $ADB pull "$F/$f" "$OUT/" >/dev/null 2>&1 && $ADB shell "rm -f $F/$f"
done
ls "$OUT" | tr '\n' ' '; say; say "записи: $OUT"
