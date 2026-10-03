#!/bin/bash
# Замер формата хранения кусочков речи на телефоне — без установки приложения: ClipCodec (класс
# приложения) и ClipBench собираются в dex и запускаются через app_process от имени shell.
# Приложение и его данные не трогаются; файлы замера — в /data/local/tmp/clipbench, в конце удаляются.
#   bash bench/audio/clip_bench.sh [набор…]        # по умолчанию near-pt far-pt из bench/air/rec
# Переменные: CLIPS (кусочков на конфиг, 40), SEC (длина кусочка, 5), CFGS (конфиги), LOCK_WAIT (с, 7200).
# Итог — по строке JSON на конфиг в ~/.cache/falar-stand/clipbench/<время>/<набор>.jsonl.
set -u
A=$(cd "$(dirname "$0")" && pwd); R=$(cd "$A/../.." && pwd)
AJ=$R/tools/android.jar; R8=$R/tools/r8.jar; ADB=$R/tools/platform-tools/adb
STATE=$HOME/.cache/falar-stand; OUT=$STATE/clipbench/$(date +%Y%m%d-%H%M%S); mkdir -p "$OUT"
E=/data/local/tmp/clipbench; PKG=app.falar
CLIPS=${CLIPS:-40}; SEC=${SEC:-5}; CFGS=${CFGS:-ogg:16000,ogg:24000,m4a:24000,m4a:32000,wav:0}
SETS=${*:-near-pt far-pt}
say() { printf '%s\n' "$*"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
focus() { sh "dumpsys window" | grep -m1 mCurrentFocus; }
asleep() { sh "dumpsys power" | grep -qE 'mWakefulness=(Asleep|Dozing)'; }
idle() {
  asleep && return 0
  case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) ;; *) return 1;; esac
  local a; a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
  [ "${a:-0}" -ge 60000 ]
}

# ---- сборка: тот же ClipCodec, что в приложении ----------------------------------------------------
B=$OUT/build; mkdir -p "$B/classes"
javac --release 11 -nowarn -cp "$AJ" -d "$B/classes" "$R/bench/apk/src/dev/agenttranslator/ClipCodec.java" "$A/ClipBench.java" || exit 1
java -cp "$R8" com.android.tools.r8.D8 --release --min-api 28 --lib "$AJ" --output "$B" $(find "$B/classes" -name '*.class') || exit 1

# ---- замок стенда: замер процессора не должен идти поверх чужого прогона -------------------------------
if [ -z "${FALAR_STAND_LOCK:-}" ]; then
  exec 9>"$STATE/lock"
  flock -n 9 || { say "замок занят — жду, пока освободится"; flock -w "${LOCK_WAIT:-7200}" 9 || { say "замок не освободился — не начинаю"; exit 1; }; }
fi
n=0; until idle; do n=$((n+1)); [ $n -eq 1 ] && say "жду, пока телефон свободен ($(focus))"; sleep 15; done

cleanup() { sh "rm -rf $E"; }
trap cleanup EXIT
sh "rm -rf $E; mkdir -p $E/out"
$ADB push "$B/classes.dex" $E/clip.dex >/dev/null || exit 1
for s in $SETS; do
  $ADB push "$R/bench/air/rec/$s/room.wav" "$E/$s.wav" >/dev/null || { say "нет записи $s"; continue; }
  pid=$(sh "pidof media.swcodec" | awk '{print $1}')
  say "== $s · $CLIPS × $SEC с · $CFGS · media.swcodec $pid · $(date +%H:%M:%S)"
  sh "cd $E && CLASSPATH=$E/clip.dex app_process $E dev.agenttranslator.ClipBench $E/$s.wav $E/out/$s $SEC $CLIPS $pid $CFGS" | tee "$OUT/$s.jsonl"
  sh "rm -rf $E/out/$s $E/$s.wav"
done
say "итог: $OUT"
