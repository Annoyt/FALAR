#!/bin/bash
# NpuAsr приложения на настоящем Snapdragon (Samsung Remote Test Lab или свой телефон) без установки приложения:
# тот же Java-класс через app_process, те же библиотеки, что в APK сборки NPU=1 (ORT 1.29 с QNN, QNN 2.42 из
# .stand/qnn-android — bench/npu/fetch_qnn.sh). По корпусу bench/air/corpus (162 фразы, публичный Tatoeba):
#   прогон 1 — граф QNN собирается на телефоне и сохраняется (как первый запуск приложения);
#   прогон 2 — граф из сохранённого (как каждый следующий запуск);
# время загрузки и фразы, гипотезы → WER (asr_wer.py --hyps). Всё в /data/local/tmp/falar-npu-asr, clean удаляет.
#
#   bash bench/npu/npu_asr_device.sh SERIAL [КАЖДАЯ_N=1]      # SERIAL — как в `adb devices` (rdb: localhost:ПОРТ)
#   bash bench/npu/npu_asr_device.sh SERIAL clean
#
# Заливка через туннель RTL — 2–4 МБ/с: модель NPU 0,59 ГБ — 3–5 минут, это самая долгая часть.
set -e
S=$1; [ -n "$S" ] || { echo "нужен SERIAL"; exit 2; }
P=/home/not_me/antigravity-workspace/agentstuff/agenttranslator; ST=$P/.stand
W=$(cd "$(dirname "$0")/../.." && pwd); A=$W/bench/apk
ADB="${ADB_BIN:-$P/tools/platform-tools/adb} -s $S"; D=/data/local/tmp/falar-npu-asr
Q=${QNN_DIR:-$ST/qnn-android}; OUT=$ST/npu/app/device; mkdir -p $OUT/classes
say() { echo "$(date +%T) $*"; }
sh() { $ADB shell "$@" < /dev/null | tr -d '\r'; }
if [ "$2" = clean ]; then sh "rm -rf $D"; say "удалено $D"; exit 0; fi
EVERY=${2:-1}
exec > >(tee -a $OUT/run-$(date +%Y%m%d-%H%M%S).log) 2>&1

soc=$(sh getprop ro.soc.model); arch=""
case "$soc" in SM8350|SM7325|SM7350|SM8250|SM7225) arch=68;; SM8450|SM8475|SM7450|SM7475) arch=69;;
  SM8550|SM8635|SM7675|SM7550|SM7435|SM6450|SM7750) arch=73;; SM8650) arch=75;; SM8750) arch=79;; SM8850|SM8845) arch=81;; esac
say "телефон: $(sh getprop ro.product.model), SoC $soc, HTP v${arch:-?}, Android $(sh getprop ro.build.version.release)"
[ -n "$arch" ] || { say "SoC $soc не в таблице поколений HTP — допишите case"; exit 2; }

# dex: NpuAsr + стенд + классы ONNX Runtime Java
javac --release 11 -nowarn -cp "$A/libs/onnxruntime-1.29.0-classes.jar" -d $OUT/classes $A/src/dev/agenttranslator/NpuAsr.java $W/bench/npu/NpuAsrPc.java
java -cp $P/tools/r8.jar com.android.tools.r8.D8 --release --min-api 28 --lib $P/tools/android.jar --output $OUT \
  $(find $OUT/classes -name '*.class') $A/libs/onnxruntime-1.29.0-classes.jar 2>&1 | grep -v -E "warning|Warning" || true
sh "mkdir -p $D/asr $D/npu $D/corpus/pt $D/corpus/ru"
push() { local t=$(date +%s.%N) bytes=0; for f in "${@:2}"; do $ADB push "$f" "$1/" >/dev/null; bytes=$((bytes + $(stat -L -c %s "$f"))); done
  say "залито в $1: $((bytes / 1048576)) МБ за $(echo "$(date +%s.%N) - $t" | bc | cut -c1-5) с"; }
if [ -z "$PUSHED" ]; then
push $D $OUT/classes.dex $Q/ort/libonnxruntime.so $Q/ort/libonnxruntime4j_jni.so $Q/qnn/libQnnHtp.so $Q/qnn/libQnnSystem.so \
  $Q/qnn/libQnnHtpPrepare.so $Q/qnn/libQnnHtpV${arch}Stub.so $Q/qnn/libQnnHtpV${arch}Skel.so
push $D/asr $P/models/asr_multi/decoder.int8.onnx $P/models/asr_multi/joiner.int8.onnx $P/models/asr_multi/tokens.txt
push $D/corpus/pt $W/bench/air/corpus/pt/*.wav
push $D/corpus/ru $W/bench/air/corpus/ru/*.wav
push $D/npu $ST/npu/parakeet/nemo128.onnx
fi
# код выхода — через echo: у `adb shell … | tr` он был бы кодом tr (всегда 0), и модель «уже была» на любом телефоне
[ "$(sh "stat -c %s $D/npu/encoder-npu.onnx 2>/dev/null || echo 0")" = "$(stat -L -c %s $ST/npu/app/parakeet-encoder-npu.onnx)" ] \
  && say "модель NPU уже на телефоне" \
  || { t=$(date +%s.%N); $ADB push $ST/npu/app/parakeet-encoder-npu.onnx $D/npu/encoder-npu.onnx >/dev/null; say "модель NPU: $(echo "$(date +%s.%N) - $t" | bc | cut -c1-5) с"; }

run() {
  sh "cd $D && CLASSPATH=$D/classes.dex LD_LIBRARY_PATH=$D ADSP_LIBRARY_PATH='$D;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/dsp' \
      app_process $D dev.agenttranslator.NpuAsrPc $D/asr $D/npu $D/corpus $D/hyps-$1.tsv $EVERY $D $D/encoder-ctx.onnx" 2>&1 \
    | grep -E 'RESULT|Exception|Error|error|at ai\.|at dev\.' | head -20
  $ADB pull $D/hyps-$1.tsv $OUT/hyps-$1-$soc.tsv >/dev/null && $ST/venv-qnn/bin/python -I $W/bench/npu/asr_wer.py --hyps $OUT/hyps-$1-$soc.tsv
}
sh "rm -f $D/encoder-ctx.onnx*"
say "прогон 1: сборка графа на телефоне"; run first
sh "ls -la $D/encoder-ctx.onnx* 2>/dev/null" | sed 's/^/    /'
say "прогон 2: граф из сохранённого"; run warm
say "готово; итог — $OUT"
