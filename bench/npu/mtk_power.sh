#!/bin/bash
# Батарея и нагрев на POCO X7 Pro: NPU MediaTek (A16W8, своя сборка дополнения NeuroPilot, сохранённый граф) против
# обычного пути приложения на процессоре (sherpa int8, 4 потока) — один и тот же корпус по кругу заданное время.
#
# Телефон — на батарее: adb по Wi-Fi (adb tcpip 5555; adb connect IP:5555), кабель отключён, экран погашен, телефон
# не трогать. Раз в 5 с: dumpsys battery (счётчик заряда, мкА·ч, шаг 1 мА·ч; напряжение) и dumpsys thermalservice
# (CPU, NPU, SOC, корпус, батарея) — sysfs батареи и термозон оболочке HyperOS закрыты. Режимы по порядку:
#   покой → NPU → остывание → процессор → остывание → NPU снова (проверка, что порядок не исказил итог).
# Итог по режиму: заряд (мА·ч), средняя мощность и сверх покоя (мВт), мДж на фразу сверх покоя, температуры.
# Сторож: свободной памяти меньше GUARD_MB — процесс останавливается (как в mtk_asr_device.sh).
#
#   bash bench/npu/mtk_power.sh SERIAL prepare        # по кабелю: код, sherpa int8 и его библиотеки на телефон
#   bash bench/npu/mtk_power.sh IP:5555 [СЕКУНД=300] [ПОКОЙ=180]
set -e
S=$1; [ -n "$S" ] || { echo "нужен SERIAL"; exit 2; }
P=/home/not_me/antigravity-workspace/agentstuff/agenttranslator; ST=$P/.stand
W=$(cd "$(dirname "$0")/../.." && pwd); A=$W/bench/apk
ADB="${ADB_BIN:-$P/tools/platform-tools/adb} -s $S"; D=/data/local/tmp/falar-mtk-asr
OUT=$ST/npu/mtk/power; mkdir -p $OUT/classes
NPU_OPTS=${NPU_OPTS:-NEURON_FLAG_USE_FP16=1,NEURON_FLAG_SKIP_CAPABILITY_PROBE=1,NEURON_FLAG_MAX_PARTITION_BYTES=167772160,ep.context_embed_mode=0}
GUARD_MB=${GUARD_MB:-800}
say() { echo "$(date +%T) $*"; }
sh() { $ADB shell "$@" < /dev/null | tr -d '\r'; }

if [ "$2" = prepare ]; then
  javac --release 11 -nowarn -cp "$A/libs/onnxruntime-1.29.0-classes.jar" -d $OUT/classes $A/src/dev/agenttranslator/NpuAsr.java \
    $W/bench/npu/NpuAsrPc.java $W/bench/npu/SherpaAsrPc.java $A/sherpa-java-api/*.java
  java -cp $P/tools/r8.jar com.android.tools.r8.D8 --release --min-api 28 --lib $P/tools/android.jar --output $OUT \
    $(find $OUT/classes -name '*.class') $A/libs/onnxruntime-1.29.0-classes.jar 2>&1 | grep -v -E "warning|Warning" || true
  $ADB push $OUT/classes.dex $D/classes.dex > /dev/null
  for f in $A/jni/arm64-v8a/libsherpa-onnx-jni.so $A/jni/arm64-v8a/libonnxruntime_sherpa.so $P/models/asr_multi/encoder.int8.onnx; do
    t=$D; case $f in *.onnx) t=$D/asr;; esac
    [ "$(sh "stat -c %s $t/$(basename $f) 2>/dev/null || echo 0")" = "$(stat -L -c %s $f)" ] || { $ADB push $f $t/ > /dev/null; say "залито $(basename $f)"; }
  done
  for f in encoder-ctx.onnx npu/encoder-npu.onnx libonnxruntime_providers_neuropilot.so asr/decoder.int8.onnx; do
    [ "$(sh "[ -s $D/$f ] && echo y")" = y ] || { echo "на телефоне нет $D/$f — сначала mtk_asr_device.sh (MODEL=a16w8)"; exit 2; }
  done
  say "готово: код с ASR_SECONDS, sherpa int8; сохранённый граф NPU на месте"; exit 0
fi

SECS=${2:-300}; IDLE=${3:-180}
TAG=$(date +%Y%m%d-%H%M%S); CSV=$OUT/power-$TAG.csv; LOG=$OUT/power-$TAG.log
exec > >(tee -a $LOG) 2>&1

# строка замера: время режим заряд_мкАч напряжение_мВ уровень usb ac память_кБ cpu npu soc skin batt
sample() {
  sh "dumpsys battery | grep -E 'USB powered|AC powered|Charge counter|voltage:|level:'; grep MemAvailable /proc/meminfo; \
      dumpsys thermalservice | sed -n '/Current temperatures from HAL/,/Current cooling/p'" | awk -v t="$(date +%s)" -v m="$1" '
    function val(s) { match(s, /mValue=[-0-9.]+/); return substr(s, RSTART + 7, RLENGTH - 7) }
    /AC powered:/ {ac=$3} /USB powered:/ {usb=$3} /Charge counter:/ {cc=$3} /^ *voltage:/ {v=$2} /^ *level:/ {lv=$2}
    /MemAvailable/ {mem=$2}
    /mName=CPU,/ {cpu=val($0)} /mName=NPU,/ {npu=val($0)} /mName=SOC,/ {soc=val($0)} /mName=SKIN,/ {skin=val($0)}
    /mName=BATTERY,/ {bat=val($0)}
    END {print t, m, cc, v, lv, usb, ac, mem, cpu, npu, soc, skin, bat}'
}
sampler() { while :; do sample $1 >> $CSV; sleep 5; done; }
skin_now() { sample probe | awk '{print $12}'; }

first=$(sample start); echo "$first" >> $CSV
read -r _ _ cc0 v0 lv0 usb0 ac0 mem0 cpu0 npu0 soc0 skin0 bat0 <<< "$first"
say "телефон: заряд $lv0 %, $((cc0 / 1000)) мА·ч, $v0 мВ; USB $usb0, сеть $ac0; CPU $cpu0 °C, корпус $skin0 °C; свободно $((mem0 / 1024)) МБ"
[ "$usb0" = false ] && [ "$ac0" = false ] || { echo "телефон питается от кабеля — отключите его (замер только на батарее)"; exit 2; }
[ "$(sh "dumpsys power | grep -c 'mWakefulness=Awake'")" = 0 ] || say "внимание: экран включён — лучше погасить (подсветка — та же во всех режимах, но шумнее)"

asr() {   # $1 — режим (npu/cpu); корпус по кругу SECS секунд; сторож памяти по PID
  local out=$OUT/out-$TAG-$1.txt; : > $out
  if [ "${1#npu}" != "$1" ]; then          # npu и npu2 — NPU, иначе процессор
    $ADB shell "cd $D && export CLASSPATH=$D/classes.dex LD_LIBRARY_PATH=$D NPU_OPTS='$NPU_OPTS' ASR_SECONDS=$SECS; echo PID \$\$; \
      exec app_process $D dev.agenttranslator.NpuAsrPc $D/asr $D/npu $D/corpus $D/hyps-power.tsv 1 \
      $D/libonnxruntime_providers_neuropilot.so $D/encoder-ctx.onnx" < /dev/null >> $out 2>&1 &
  else
    $ADB shell "cd $D && export CLASSPATH=$D/classes.dex LD_LIBRARY_PATH=$D ASR_SECONDS=$SECS; echo PID \$\$; \
      exec app_process $D dev.agenttranslator.SherpaAsrPc $D/asr $D/corpus $D/hyps-power-cpu.tsv 1 4" < /dev/null >> $out 2>&1 &
  fi
  local job=$! pid=""
  while kill -0 $job 2>/dev/null; do
    sample $1 >> $CSV
    [ -n "$pid" ] || pid=$(sed -n 's/^PID \([0-9]*\).*/\1/p' $out | head -1)
    m=$(tail -1 $CSV | awk '{print $8}')
    if [ -n "$pid" ] && [ -n "$m" ] && [ "$m" -lt $((GUARD_MB * 1024)) ]; then
      sh "kill -9 $pid"; say "СТОРОЖ: свободно $((m / 1024)) МБ — процесс $pid остановлен"; break
    fi
    sleep 5
  done
  wait $job 2>/dev/null || true
  grep -E "RESULT" $out | sed 's/^/    /'
}
cool() {   # до исходной температуры корпуса (+0,5 °C), не дольше 10 минут
  local t0=$(date +%s)
  while :; do
    s=$(sample cool); echo "$s" >> $CSV; sk=$(echo "$s" | awk '{print $12}')
    awk -v a="$sk" -v b="$skin0" 'BEGIN {exit !(a <= b + 0.5)}' && break
    [ $(( $(date +%s) - t0 )) -lt 600 ] || { say "остывание: 10 минут, корпус $sk °C (было $skin0)"; break; }
    sleep 10
  done
  say "остыл: корпус $sk °C за $(( $(date +%s) - t0 )) с"
}

say "покой $IDLE с"; sampler idle & sp=$!; sleep $IDLE; kill $sp; wait $sp 2>/dev/null || true
say "NPU $SECS с"; asr npu
cool
say "процессор $SECS с"; asr cpu
cool
say "NPU снова $SECS с"; asr npu2
sample end >> $CSV

$ST/venv-qnn/bin/python -I - "$CSV" "$OUT/out-$TAG-npu.txt" "$OUT/out-$TAG-cpu.txt" "$OUT/out-$TAG-npu2.txt" <<'PY'
import re, sys
rows = [l.split() for l in open(sys.argv[1]) if l.strip()]
def num(x):
    try: return float(x)
    except ValueError: return float("nan")
phr = {}
for m, f in (("npu", sys.argv[2]), ("cpu", sys.argv[3]), ("npu2", sys.argv[4])):
    try:
        t = open(f, encoding="utf-8").read()
        g = re.search(r"фраз (\d+)", t); phr[m] = int(g.group(1)) if g else 0
    except OSError: phr[m] = 0
res = {}
for m in ("idle", "npu", "cpu", "npu2"):
    r = [x for x in rows if x[1] == m]
    if len(r) < 2: continue
    t = num(r[-1][0]) - num(r[0][0]); dq = (num(r[0][2]) - num(r[-1][2])) / 1000.0          # мА·ч
    v = sum(num(x[3]) for x in r) / len(r) / 1000.0                                         # В
    p = dq * v * 3600.0 / t if t > 0 else float("nan")                                      # мВт (мА·ч·В = мВт·ч)
    temps = {k: (num(r[0][i]), max(num(x[i]) for x in r)) for k, i in (("CPU", 8), ("NPU", 9), ("корпус", 11), ("батарея", 12))}
    res[m] = (t, dq, p, temps)
idle = res.get("idle", (0, 0, float("nan"), {}))[2]
print("\nрежим    время  заряд    мощность  сверх покоя  фраз   мДж/фразу   CPU °C        NPU °C        корпус °C")
for m, (t, dq, p, temps) in res.items():
    n = phr.get(m, 0); extra = p - idle
    mj = extra * t / n if n else float("nan")
    tt = "  ".join(f"{a:.1f}→{b:.1f}" for a, b in (temps["CPU"], temps["NPU"], temps["корпус"]))
    print(f"{m:6} {t:5.0f} с {dq:5.1f} мА·ч {p:7.0f} мВт {extra:8.0f} мВт {n:6d} {mj:9.1f}   {tt}")
PY
say "итог → $LOG, замеры → $CSV"
