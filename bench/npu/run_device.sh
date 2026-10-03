#!/bin/bash
# Стенд NPU Qualcomm на телефоне по adb — без установки приложений: QnnBench (bench/npu/QnnBench.java) через
# app_process, ONNX Runtime 1.29 с QNN и библиотеки QNN 2.42 из Maven (~/.cache/falar-stand/jars/qnn).
# Всё лежит в /data/local/tmp/falar-npu и удаляется командой clean.
#
#   bash bench/npu/run_device.sh SERIAL setup                      # dex + библиотеки (QNN — под SoC телефона)
#   bash bench/npu/run_device.sh SERIAL model ФАЙЛ.onnx [имя]      # залить модель (замеряет скорость заливки)
#   bash bench/npu/run_device.sh SERIAL bench МОДЕЛЬ РАЗМЕРЫ ПРОГОНОВ cpu|npu [потоков] [soc] [эталон]
#   bash bench/npu/run_device.sh SERIAL clean
#
# SERIAL обязателен: к ПК бывает подключено несколько телефонов (Redmi, POCO, удалённый Galaxy через rdb).
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
MAIN=$(cd "$(git -C "$R" rev-parse --git-common-dir)/.." && pwd)
ADB="$MAIN/tools/platform-tools/adb -s $1"; shift
Q=~/.cache/falar-stand/jars/qnn; OUT=$R/bench/npu/out; D=/data/local/tmp/falar-npu
say() { echo "$(date +%T) $*"; }
sh() { $ADB shell "$@" < /dev/null | tr -d '\r'; }
case "$1" in
  setup)
    [ -f $OUT/qnnbench.dex ] || { say "нет $OUT/qnnbench.dex — соберите (см. bench/npu/README)"; exit 2; }
    mkdir -p $OUT/qnn && (cd $OUT/qnn && unzip -q -o -j $Q/qnn-runtime-2.42.0.aar 'jni/arm64-v8a/*.so' && unzip -q -o -j $Q/onnxruntime-android-qnn-1.29.0.aar 'jni/arm64-v8a/*.so')
    soc=$(sh getprop ro.soc.model); arch=""
    case "$soc" in SM8450|SM8475|SM7450|SM7475) arch=69;; SM8550|SM8635|SM7675|SM7550|SM7435|SM6450) arch=73;;
      SM8650) arch=75;; SM8750) arch=79;; SM8850|SM8845) arch=81;; esac
    say "телефон: $(sh getprop ro.product.model), SoC $soc, HTP v${arch:-?}"
    sh "mkdir -p $D"
    files="$OUT/qnnbench.dex $OUT/qnn/libonnxruntime.so $OUT/qnn/libonnxruntime4j_jni.so"
    if [ -n "$arch" ]; then
      files="$files $OUT/qnn/libQnnHtp.so $OUT/qnn/libQnnHtpPrepare.so $OUT/qnn/libQnnSystem.so $OUT/qnn/libQnnHtpV${arch}Stub.so $OUT/qnn/libQnnHtpV${arch}Skel.so"
    fi
    t=$(date +%s.%N); bytes=0
    for f in $files; do $ADB push "$f" $D/ >/dev/null; bytes=$((bytes + $(stat -c %s "$f"))); done
    say "залито $((bytes / 1048576)) МБ за $(echo "$(date +%s.%N) - $t" | bc | cut -c1-5) с";;
  model)
    f=$2; n=${3:-$(basename "$f")}
    t=$(date +%s.%N); $ADB push "$f" $D/$n >/dev/null
    s=$(echo "$(date +%s.%N) - $t" | bc); say "$n: $(( $(stat -c %s "$f") / 1048576 )) МБ за ${s%.*} с";;
  bench)
    shift
    sh "cd $D && CLASSPATH=$D/qnnbench.dex LD_LIBRARY_PATH=$D ADSP_LIBRARY_PATH='$D;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/dsp' app_process $D QnnBench $*" 2>&1 | grep -E 'RESULT|COMPARE|Exception|Error|error' | head -20;;
  clean) sh "rm -rf $D"; say "удалено $D";;
  *) echo "команды: setup | model | bench | clean"; exit 2;;
esac
