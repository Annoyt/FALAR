#!/bin/bash
# Проба операций на NPU MediaTek: модели bench/npu/mtk_ops.py → MtkOpProbe через app_process с дополнением ORT
# NeuroPilot (библиотеки — как в mtk_asr_device.sh, уже на телефоне в /data/local/tmp/falar-mtk-asr). Итог — по строке
# на модель: OK (сборка, прогон NPU и процессора, разница) или FAIL (ошибка), и что дополнение взяло на NPU.
#   bash bench/npu/mtk_ops_device.sh SERIAL [ключ=значение,…]     # по умолчанию NEURON_FLAG_USE_FP16=1
#   PROBE_ONLY='p0[679].*|r.*' bash …                             # только эти модели
#   EP_DIR=.stand/mtk-ep/v0.3.0-int16 bash …                       # своя сборка дополнения (neuropilot-ep/build.sh)
set -e
S=$1; [ -n "$S" ] || { echo "нужен SERIAL"; exit 2; }
OPTS=${2:-NEURON_FLAG_USE_FP16=1}
P=/home/not_me/antigravity-workspace/agentstuff/agenttranslator; ST=$P/.stand
W=$(cd "$(dirname "$0")/../.." && pwd); A=$W/bench/apk
ADB="${ADB_BIN:-$P/tools/platform-tools/adb} -s $S"; D=/data/local/tmp/falar-mtk-asr
OPS=$ST/npu/mtk/ops; OUT=$ST/npu/mtk/device/ops; mkdir -p $OUT/classes
say() { echo "$(date +%T) $*"; }
sh() { $ADB shell "$@" < /dev/null | tr -d '\r'; }
# библиотеки: ORT 1.29 из APK и дополнение из EP_DIR (выпуск v0.3.0 или своя сборка bench/npu/neuropilot-ep/build.sh)
EP=${EP_DIR:-$ST/mtk-ep/v0.3.0}
sh "mkdir -p $D"
for f in $A/jni/arm64-v8a/libonnxruntime.so $A/jni/arm64-v8a/libonnxruntime4j_jni.so $EP/libonnxruntime_providers_neuropilot.so $EP/libneuron_op_capability.so; do
  [ "$(sh "stat -c %s $D/$(basename $f) 2>/dev/null || echo 0")" = "$(stat -L -c %s $f)" ] || $ADB push $f $D/ >/dev/null
done
say "дополнение: $EP ($(sha256sum $EP/libonnxruntime_providers_neuropilot.so | cut -c1-12))"
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
sh "cd $D && CLASSPATH=$D/ops-classes.dex LD_LIBRARY_PATH=$D PROBE_ONLY='$PROBE_ONLY' PROBE_VALUES='$PROBE_VALUES' app_process $D dev.agenttranslator.MtkOpProbe $D/ops \
    $D/libonnxruntime_providers_neuropilot.so '$OPTS'" > $LOG 2>&1 || true
grep -E "^== |^OK |^FAIL |^VAL |GetCapability|Some ops not supported|Exception" $LOG | cut -c1-260
say "полный вывод → $LOG"
