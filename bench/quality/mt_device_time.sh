#!/bin/bash
# Время перевода на телефоне по вариантам луча — без установки приложения: код перевода (Engine) и
# TimeRun идут отдельным процессом (app_process) на моделях, которые уже лежат в приложении, по одному
# направлению: рядом с работающим приложением это вдвое меньше памяти. Экрана скрипт не касается.
#
#   bash bench/quality/mt_device_time.sh [фраз Tatoeba на направление, по умолчанию 100] [варианты, по умолчанию 1,2,4]
#   CFGS="4:f0 1:80 2:c0 4:f0 1:80 2:c0" DIRS=pt2ru bash bench/quality/mt_device_time.sh 60 1,4   # сравнить потоки
#
# Две конфигурации: 4 потока на четырёх больших ядрах (на Redmi cpu4–7, Cortex-A78; так переводит
# приложение) и 1 поток на одном большом ядре (телефон занят распознаванием и голосом; владелец 03.10 —
# сначала эмулировать одно крупное ядро). Варианты — как у TimeRun: ширина луча, «s» — черновики по
# одному. Итог — ~/.cache/falar-stand/mt-time/<время>/time-<потоки>-<направление>.json и таблица.
#
# Телефон — рабочий телефон человека. Один прогон на стенде за раз (замок, как у test_*_device.sh), и
# каждый прогон ждёт, пока телефоном не пользуются: экран погашен или последнее касание больше
# минуты назад, на экране FALAR или рабочий стол, само приложение почти не нагружает процессор, свободно
# не меньше гигабайта памяти. Файлы на телефоне — только в /data/local/tmp/falar-mt, в конце удаляются.
R=$(cd "$(dirname "$0")/../.." && pwd)
MAIN=$(cd "$(git -C "$R" rev-parse --git-common-dir)/.." && pwd)
pick() { [ -e "$R/$1" ] && echo "$R/$1" || echo "$MAIN/$1"; }
ADB=${ADB:-$(pick tools/platform-tools)/adb}
N=${1:-100}; VARS=${2:-1,2,4}
CFGS=${CFGS:-"4:f0 1:80"}; DIRS=${DIRS:-"pt2ru ru2pt"}   # «потоки:маска ядер» по порядку, направления
STATE=${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand; OUT=$STATE/mt-time/$(date +%Y%m%d-%H%M%S); mkdir -p "$OUT"
DT=/data/local/tmp/falar-mt; MODELS=/sdcard/Android/data/app.falar/files/models
say() { printf '%s %s\n' "$(date +%T)" "$*" | tee -a "$OUT/run.log"; }

# К ПК бывает подключено два телефона (Redmi и POCO): тогда без ANDROID_SERIAL adb отвечает «more than one
# device», и 03.10 скрипт ждал «простоя», держа замок стенда (до двух часов). Теперь — выход до замка.
[ -n "$ANDROID_SERIAL" ] || [ "$($ADB devices | grep -c 'device$')" -le 1 ] \
  || { say "подключено несколько телефонов — укажите ANDROID_SERIAL (Redmi: f6lnlrorgi59xwge)"; exit 1; }
if [ -z "$FALAR_STAND_LOCK" ]; then
  exec 9>"$STATE/lock"
  flock -n 9 || { say "на телефоне идёт другая проверка — жду замок стенда"; flock 9; }
  export FALAR_STAND_LOCK=1
fi
$ADB get-state >/dev/null 2>&1 || { say "телефон не подключён"; exit 1; }
$ADB shell "[ -f $MODELS/mt/pt2ru/encoder_kv_model.onnx ] && [ -f $MODELS/mt/ru2pt/encoder_kv_model.onnx ]" \
  || { say "на телефоне нет моделей перевода с K/V кодировщика ($MODELS/mt)"; exit 1; }

# ---- сборка: тот же код перевода, что в приложении, — в dex
B=$(mktemp -d); trap 'rm -rf "$B"; $ADB shell rm -rf $DT >/dev/null 2>&1' EXIT
AJ=$(pick tools/android.jar); OC=$R/bench/apk/libs/onnxruntime-1.29.0-classes.jar
javac --release 11 -nowarn -encoding UTF-8 -cp "$AJ:$OC" -sourcepath "$R/bench/apk/src" -d "$B/classes" \
  "$R/bench/apk/src/dev/agenttranslator/Engine.java" "$R"/bench/apk/sherpa-java-api/*.java "$R/bench/quality/TimeRun.java" \
  > "$OUT/javac.log" 2>&1 || { say "не собралось: $OUT/javac.log"; exit 2; }
java -cp "$(pick tools/r8.jar)" com.android.tools.r8.D8 --release --min-api 28 --lib "$AJ" --output "$B/mt.jar" \
  $(find "$B/classes" -name '*.class') "$OC" > "$OUT/d8.log" 2>&1 || { say "dex не собрался: $OUT/d8.log"; exit 2; }
J=$(pick bench/apk/jni)/arm64-v8a
$ADB shell "rm -rf $DT && mkdir -p $DT" && for f in "$B/mt.jar" "$J/libonnxruntime.so" "$J/libonnxruntime4j_jni.so" \
  "$R/data/mt_test/tatoeba.json" "$R/data/test_set.json"; do $ADB push "$f" $DT/ >/dev/null || { say "не залилось: $f"; exit 2; }; done

# ---- телефоном не пользуются?
idle() {
  local p now last foc cpu mem
  $ADB get-state >/dev/null 2>&1 || { echo "телефон пропал"; return 2; }
  p=$($ADB shell dumpsys power 2>/dev/null | tr -d '\r')
  mem=$($ADB shell grep MemAvailable /proc/meminfo | tr -dc '0-9')
  [ "${mem:-0}" -ge 1000000 ] || { echo "свободно ${mem:-?} КБ памяти"; return 1; }
  cpu=$($ADB shell top -b -n 1 -q -o %CPU,ARGS 2>/dev/null | tr -d '\r' | awk '$2 == "app.falar" {print int($1); exit}')
  [ "${cpu:-0}" -lt 15 ] || { echo "приложение занято: ${cpu}% процессора"; return 1; }
  echo "$p" | grep -q 'mWakefulness=Awake' || return 0          # экран погашен
  now=$(echo "$p" | sed -n 's/.*mLastWakeTime=\([0-9]*\) (\([0-9]*\) ms ago).*/\1 \2/p' | awk '{print $1+$2}')
  last=$(echo "$p" | sed -n 's/.*mLastUserActivityTime[^=]*=\([0-9]*\).*/\1/p' | head -1)
  [ -n "$now" ] && [ -n "$last" ] && [ $((now - last)) -ge 60000 ] || { echo "касание меньше минуты назад"; return 1; }
  foc=$($ADB shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r')
  echo "$foc" | grep -qE 'app\.falar|launcher|com\.miui\.home|NotificationShade' || { echo "на экране чужое: ${foc##* }"; return 1; }
}
wait_idle() {
  local why i
  for i in $(seq 240); do
    why=$(idle); case $? in 0) return 0;; 2) say "$why — замер прерван"; exit 1;; esac
    [ $((i % 10)) = 1 ] && say "жду: $why"; sleep 30
  done
  say "телефоном пользуются уже два часа — замер не начат"; exit 1
}

run() {   # потоки маска направление фраз файл
  wait_idle
  say "$3: потоков $1, маска ядер $2, фраз Tatoeba $4 · батарея $($ADB shell dumpsys battery | grep -m1 temperature | tr -dc '0-9' | sed 's/\(.\)$/,\1 °C/')"
  $ADB shell "cd $DT && taskset $2 app_process -Djava.class.path=$DT/mt.jar -Djava.library.path=$DT -Dfalar.threads=$1 \
    /system/bin dev.agenttranslator.TimeRun $MODELS $3 $DT/$5 $VARS $4 $DT/tatoeba.json=tatoeba $DT/test_set.json=situations" \
    > "$OUT/${5%.json}.log" 2>&1
  $ADB pull $DT/$5 "$OUT/" >/dev/null 2>&1 || { say "прогон $5 не дал результата: $OUT/${5%.json}.log"; tail -5 "$OUT/${5%.json}.log"; return 1; }
}

# проба на трёх фразах: запуск без приложения вообще работает
run 4 f0 pt2ru 3 probe.json || exit 2
k=0
for cfg in $CFGS; do
  k=$((k + 1))
  for d in $DIRS; do run ${cfg%:*} ${cfg#*:} $d "$N" "time-${cfg%:*}-$d-$k.json"; done
done

python3 - "$OUT" <<'EOF' | tee -a "$OUT/run.log"
import json, sys
from pathlib import Path
for f in sorted(Path(sys.argv[1]).glob("time-*.json"), key=lambda p: (p.stem.split("-")[2], int(p.stem.split("-")[-1]))):
    s = json.loads(f.read_text())["summary"]
    print(f"{f.stem}: {s['n']} фраз, потоков {s['threads']}")
    for v, x in s["variants"].items():
        print(f"  {v:3s} среднее {x['ms_avg']:7.1f} мс · p50 {x['ms_p50']:7.1f} · p95 {x['ms_p95']:7.1f} · к жадному {x['ratio_avg']:.2f} (медиана {x['ratio_p50']:.2f})")
EOF
say "готово: $OUT"
