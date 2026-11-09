#!/bin/bash
# NpuAsr на NPU MediaTek (POCO X7 Pro, Dimensity 8400) без установки приложения: тот же Java-класс через app_process.
# ONNX Runtime 1.29 — обычный, из APK (bench/apk/jni); дополнение ORT для NeuroPilot — libonnxruntime_providers_neuropilot.so
# и закрытая libneuron_op_capability.so (onnxruntime-ep-neuropilot v0.3.0, в репозитории их нет — EP_DIR); адаптер
# Neuron — системный (/system_ext/lib64/libneuronusdk_adapter.mtk.so, открыт приложениям — bench/npu/mtk_probe.sh).
# Модель — bench/npu/mtk_model.py: MODEL=fp16 (по умолчанию) или fp32 (тогда NPU_OPTS=NEURON_FLAG_USE_FP16=1).
# По корпусу bench/air/corpus (162 фразы, публичный Tatoeba):
#   прогон 1 — граф Neuron собирается на телефоне и сохраняется (как первый запуск приложения);
#   прогон 2 — граф из сохранённого (как каждый следующий запуск);
# время загрузки и фразы, гипотезы → WER (asr_wer.py --hyps). Журнал: logcat только нашего процесса (--pid) и строки
# служб NPU (neuron/apu/mdla) — чужие приложения телефона в файл не попадают. Всё в /data/local/tmp/falar-mtk-asr.
#
#   bash bench/npu/mtk_asr_device.sh SERIAL [КАЖДАЯ_N=1]
#   MODEL=fp32 NPU_OPTS=NEURON_FLAG_USE_FP16=1 bash bench/npu/mtk_asr_device.sh SERIAL 8
#   NPU_LOG=verbose …                 # журнал ORT подробно: какие узлы ушли на NPU
#   EP_DIR=.stand/mtk-ep/v0.3.0-int16 MODEL=a16w8 …  # своя сборка дополнения (A16W8) и модель mtk_quant.py
#   GUARD_MB=800 …                    # сторож памяти: меньше — процесс останавливается (по умолчанию 800 МБ)
#   WARM=1 …                          # только прогон из сохранённого графа (первый уже был)
#   ORT_DIR=.stand/qnn-android/ort DEV_DIR=/data/local/tmp/falar-mtk-combo …   # ядро ORT сборки выпуска (с QNN)
#   CPU=1 bash bench/npu/mtk_asr_device.sh SERIAL [КАЖДАЯ_N]   # для сравнения — обычный путь приложения на
#                                     # процессоре: sherpa-onnx, parakeet int8, 4 потока (SherpaAsrPc)
#   bash bench/npu/mtk_asr_device.sh SERIAL clean
set -e
S=$1; [ -n "$S" ] || { echo "нужен SERIAL"; exit 2; }
P=/home/not_me/antigravity-workspace/agentstuff/agenttranslator; ST=$P/.stand
W=$(cd "$(dirname "$0")/../.." && pwd); A=$W/bench/apk
ADB="${ADB_BIN:-$P/tools/platform-tools/adb} -s $S"; D=${DEV_DIR:-/data/local/tmp/falar-mtk-asr}
# ORT_DIR — другое ядро ONNX Runtime (libonnxruntime.so и libonnxruntime4j_jni.so): у сборки выпуска с NPU оно с QNN
# (.stand/qnn-android/ort) — так проверяется, что дополнение NeuroPilot работает и с ним (NPU=1 MTK=1)
ORT=${ORT_DIR:-$A/jni/arm64-v8a}
EP=${EP_DIR:-$ST/mtk-ep/v0.3.0}; MODEL=${MODEL:-fp16}; M=$ST/npu/mtk/$MODEL
OUT=$ST/npu/mtk/device; mkdir -p $OUT/classes
say() { echo "$(date +%T) $*"; }
sh() { $ADB shell "$@" < /dev/null | tr -d '\r'; }
if [ "$2" = clean ]; then sh "rm -rf $D"; say "удалено $D"; exit 0; fi
EVERY=${2:-1}
if [ -z "$CPU" ]; then
  [ -s $EP/libonnxruntime_providers_neuropilot.so ] && [ -s $EP/libneuron_op_capability.so ] || { echo "нет дополнения в $EP"; exit 2; }
fi
[ -s $M/encoder-npu.onnx ] || { echo "нет модели $M — bench/npu/mtk_model.py"; exit 2; }
exec > >(tee -a $OUT/run-$(date +%Y%m%d-%H%M%S).log) 2>&1

say "телефон: $(sh getprop ro.product.model), SoC $(sh getprop ro.soc.model), Android $(sh getprop ro.build.version.release); модель $MODEL; параметры: ${NPU_OPTS:-нет}"
say "память: $(sh "grep MemAvailable /proc/meminfo" | tr -s ' ')"

# dex: NpuAsr + стенд + классы ONNX Runtime Java
javac --release 11 -nowarn -cp "$A/libs/onnxruntime-1.29.0-classes.jar" -d $OUT/classes $A/src/dev/agenttranslator/NpuAsr.java \
  $W/bench/npu/NpuAsrPc.java $W/bench/npu/SherpaAsrPc.java $A/sherpa-java-api/*.java
java -cp $P/tools/r8.jar com.android.tools.r8.D8 --release --min-api 28 --lib $P/tools/android.jar --output $OUT \
  $(find $OUT/classes -name '*.class') $A/libs/onnxruntime-1.29.0-classes.jar 2>&1 | grep -v -E "warning|Warning" || true
sh "mkdir -p $D/asr $D/npu $D/corpus/pt $D/corpus/ru"
push() { local t=$(date +%s.%N) bytes=0; for f in "${@:2}"; do $ADB push "$f" "$1/" >/dev/null; bytes=$((bytes + $(stat -L -c %s "$f"))); done
  say "залито в $1: $((bytes / 1048576)) МБ за $(echo "$(date +%s.%N) - $t" | bc | cut -c1-5) с"; }
push $D $OUT/classes.dex
if [ -z "$PUSHED" ]; then
  push $D $ORT/libonnxruntime.so $ORT/libonnxruntime4j_jni.so \
    $EP/libonnxruntime_providers_neuropilot.so $EP/libneuron_op_capability.so
  push $D/asr $P/models/asr_multi/decoder.int8.onnx $P/models/asr_multi/joiner.int8.onnx $P/models/asr_multi/tokens.txt
  push $D/corpus/pt $W/bench/air/corpus/pt/*.wav
  push $D/corpus/ru $W/bench/air/corpus/ru/*.wav
fi
if [ -n "$CPU" ]; then          # обычный путь приложения: sherpa int8 на процессоре, тот же корпус
  for f in $A/jni/arm64-v8a/libsherpa-onnx-jni.so $A/jni/arm64-v8a/libonnxruntime_sherpa.so $P/models/asr_multi/encoder.int8.onnx; do
    t=$D; case $f in *.onnx) t=$D/asr;; esac
    [ "$(sh "stat -c %s $t/$(basename $f) 2>/dev/null || echo 0")" = "$(stat -L -c %s $f)" ] || push $t $f
  done
  sh "cd $D && CLASSPATH=$D/classes.dex LD_LIBRARY_PATH=$D app_process $D dev.agenttranslator.SherpaAsrPc $D/asr $D/corpus \
      $D/hyps-cpu.tsv $EVERY 4" > $OUT/out-cpu.txt 2>&1 || true
  grep -E 'RESULT|Exception|Error' $OUT/out-cpu.txt | head -10
  $ADB pull $D/hyps-cpu.tsv $OUT/hyps-cpu.tsv >/dev/null 2>&1 && $ST/venv-qnn/bin/python -I $W/bench/npu/asr_wer.py --hyps $OUT/hyps-cpu.tsv || true
  exit 0
fi
# модель — по размеру каждого файла (другая — fp16/fp32 — заливается заново); код выхода — через echo, не через tr
for f in nemo128.onnx encoder-npu.onnx encoder-npu.onnx.data; do
  [ -e $M/$f ] || continue
  [ "$(sh "stat -c %s $D/npu/$f 2>/dev/null || echo 0")" = "$(stat -L -c %s $M/$f)" ] && continue
  t=$(date +%s.%N); $ADB push $M/$f $D/npu/$f >/dev/null
  say "$f ($(( $(stat -L -c %s $M/$f) / 1048576 )) МБ): $(echo "$(date +%s.%N) - $t" | bc | cut -c1-5) с"
done

# Сторож памяти: POCO 09.11 дважды перезагрузился (kernel_panic … oom), когда граф NPU не влез. Раз в секунду —
# MemAvailable телефона; меньше GUARD_MB — наш процесс (по PID из вывода) останавливается, пока ядро цело.
GUARD_MB=${GUARD_MB:-800}
guard() {
  local out=$1 pid="" m
  while sleep 1; do
    [ -n "$pid" ] || pid=$(sed -n 's/^PID \([0-9]*\).*/\1/p' $out 2>/dev/null | head -1)
    [ -n "$pid" ] || continue
    m=$(sh "grep MemAvailable /proc/meminfo" | awk '{print $2}')
    [ -n "$m" ] || break
    echo "$(date +%T) $m" >> $out.mem
    if [ "$m" -lt $((GUARD_MB * 1024)) ]; then
      sh "kill -9 $pid"; echo "СТОРОЖ: MemAvailable $((m / 1024)) МБ < $GUARD_MB — процесс $pid остановлен" | tee -a $out; break
    fi
    [ "$(sh "[ -d /proc/$pid ] && echo y")" = y ] || break
  done
}

run() {
  local since; since=$(sh "date '+%m-%d %H:%M:%S.000'")
  rm -f $OUT/out-$1-$MODEL.txt $OUT/out-$1-$MODEL.txt.mem; touch $OUT/out-$1-$MODEL.txt
  guard $OUT/out-$1-$MODEL.txt & local gp=$!
  # PID оболочки = PID app_process (exec): по нему журнал только нашего процесса
  $ADB shell "cd $D && export CLASSPATH=$D/classes.dex LD_LIBRARY_PATH=$D NPU_OPTS='$NPU_OPTS' NPU_LOG='$NPU_LOG'; echo PID \$\$; \
      exec app_process $D dev.agenttranslator.NpuAsrPc $D/asr $D/npu $D/corpus $D/hyps-$1.tsv $EVERY \
      $D/libonnxruntime_providers_neuropilot.so $D/encoder-ctx.onnx" < /dev/null >> $OUT/out-$1-$MODEL.txt 2>&1 || true
  kill $gp 2>/dev/null; wait $gp 2>/dev/null || true      # код убитого сторожа ≠ 0 — set -e не должен обрывать
  [ -s $OUT/out-$1-$MODEL.txt.mem ] && say "память телефона в прогоне: меньше всего $(sort -k2 -n $OUT/out-$1-$MODEL.txt.mem | head -1 | awk '{print int($2/1024)}') МБ свободно"
  grep -E 'RESULT|Exception|Error|error|at ai\.|at dev\.|Aborted|Killed' $OUT/out-$1-$MODEL.txt | head -20
  local pid; pid=$(sed -n 's/^PID \([0-9]*\).*/\1/p' $OUT/out-$1-$MODEL.txt | head -1)
  { [ -n "$pid" ] && sh "logcat -d -T '$since' --pid=$pid"
    echo "== службы NPU"; sh "logcat -d -T '$since'" | grep -i -E "neuron|apusys|apuware|mdla|vpu" | grep -v -E "\b$pid\b" | head -200
  } > $OUT/logcat-$1-$MODEL.txt
  say "журнал: $(wc -l < $OUT/logcat-$1-$MODEL.txt) строк → $OUT/logcat-$1-$MODEL.txt"
  grep -i -E "neuron|partition|placed|fallback|unsupported|not supported|error|fail" $OUT/logcat-$1-$MODEL.txt | head -25 | cut -c1-260
  $ADB pull $D/hyps-$1.tsv $OUT/hyps-$1-$MODEL.tsv >/dev/null 2>&1 && $ST/venv-qnn/bin/python -I $W/bench/npu/asr_wer.py --hyps $OUT/hyps-$1-$MODEL.tsv || true
}
if [ -z "$WARM" ]; then
  sh "rm -f $D/encoder-ctx.onnx* $D/encoder-ctx_neuron_*.bin $D/hyps-*.tsv"
  say "прогон 1: сборка графа на телефоне"; run first
  sh "ls -la $D/encoder-ctx* 2>/dev/null" | sed "s/^/    /"
  say "память после: $(sh "grep MemAvailable /proc/meminfo" | tr -s ' ')"
fi
say "прогон 2: граф из сохранённого"; run warm
say "готово; итог — $OUT"
