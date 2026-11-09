#!/bin/bash
# Проба операций на NPU MediaTek: модели bench/npu/mtk_ops.py → MtkOpProbe через app_process с дополнением ORT
# NeuroPilot (библиотеки — как в mtk_asr_device.sh, уже на телефоне в /data/local/tmp/falar-mtk-asr). Итог — по строке
# на модель: OK (сборка, прогон NPU и процессора, разница) или FAIL (ошибка), и что дополнение взяло на NPU.
#   bash bench/npu/mtk_ops_device.sh SERIAL [ключ=значение,…]     # по умолчанию NEURON_FLAG_USE_FP16=1
set -e
S=$1; [ -n "$S" ] || { echo "нужен SERIAL"; exit 2; }
OPTS=${2:-NEURON_FLAG_USE_FP16=1}
P=/home/not_me/antigravity-workspace/agentstuff/agenttranslator; ST=$P/.stand
W=$(cd "$(dirname "$0")/../.." && pwd); A=$W/bench/apk
ADB="${ADB_BIN:-$P/tools/platform-tools/adb} -s $S"; D=/data/local/tmp/falar-mtk-asr
OPS=$ST/npu/mtk/ops; OUT=$ST/npu/mtk/device/ops; mkdir -p $OUT/classes
say() { echo "$(date +%T) $*"; }
sh() { $ADB shell "$@" < /dev/null | tr -d '\r'; }
[ "$(sh "ls $D/libonnxruntime_providers_neuropilot.so 2>/dev/null")" ] || { echo "сначала bench/npu/mtk_asr_device.sh (библиотеки)"; exit 2; }
javac --release 11 -nowarn -cp "$A/libs/onnxruntime-1.29.0-classes.jar" -d $OUT/classes $A/src/dev/agenttranslator/NpuAsr.java $W/bench/npu/MtkOpProbe.java
java -cp $P/tools/r8.jar com.android.tools.r8.D8 --release --min-api 28 --lib $P/tools/android.jar --output $OUT \
  $(find $OUT/classes -name '*.class') $A/libs/onnxruntime-1.29.0-classes.jar 2>&1 | grep -v -E "warning|Warning" || true
sh "mkdir -p $D/ops"
$ADB push $OUT/classes.dex $D/ops-classes.dex >/dev/null
for f in $OPS/*.onnx; do
  [ "$(sh "stat -c %s $D/ops/$(basename $f) 2>/dev/null || echo 0")" = "$(stat -c %s $f)" ] || $ADB push $f $D/ops/ >/dev/null
done
say "модели на телефоне: $(sh "ls $D/ops | wc -l"); параметры: $OPTS"
LOG=$OUT/probe-$(date +%Y%m%d-%H%M%S).txt
sh "cd $D && CLASSPATH=$D/ops-classes.dex LD_LIBRARY_PATH=$D app_process $D dev.agenttranslator.MtkOpProbe $D/ops \
    $D/libonnxruntime_providers_neuropilot.so '$OPTS'" > $LOG 2>&1 || true
grep -E "^== |^OK |^FAIL |GetCapability|Some ops not supported|Exception" $LOG | cut -c1-230
say "полный вывод → $LOG"
