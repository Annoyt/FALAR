#!/bin/bash
# NpuAsr приложения на ПК, кодировщик на процессоре: признаки nemo128 → кодировщик QDQ (bench/npu/app_model.py) →
# жадный TDT по decoder.int8/joiner.int8 sherpa-onnx — по корпусу bench/air/corpus, затем WER (asr_wer.py --hyps).
# Проверяет Java-путь целиком до телефона. Тяжёлое — под .stand/heavy.lock, одно ядро (владелец 03.10).
#   bash bench/npu/npu_asr_pc.sh [КАЖДАЯ_N=1]
set -e
A=$(cd "$(dirname "$0")/../apk" && pwd); W=$(cd "$A/../.." && pwd)
P=/home/not_me/antigravity-workspace/agentstuff/agenttranslator; S=$P/.stand
ORT=$S/jars/onnxruntime-1.29.0.jar
NPU=$S/npu/app/model          # nemo128.onnx + encoder-npu.onnx
mkdir -p $NPU
[ -e $NPU/nemo128.onnx ] || ln -s $S/npu/parakeet/nemo128.onnx $NPU/nemo128.onnx
[ -e $NPU/encoder-npu.onnx ] || ln -s $S/npu/app/parakeet-encoder-npu.onnx $NPU/encoder-npu.onnx
OUT=$S/npu/app/pc; mkdir -p $OUT/classes
javac --release 11 -nowarn -cp "$ORT" -d $OUT/classes $A/src/dev/agenttranslator/NpuAsr.java $W/bench/npu/NpuAsrPc.java
flock $S/heavy.lock taskset -c 2 java -cp "$OUT/classes:$ORT" dev.agenttranslator.NpuAsrPc \
  $P/models/asr_multi $NPU $W/bench/air/corpus $OUT/hyps.tsv "${1:-1}"
$S/venv-qnn/bin/python -I $W/bench/npu/asr_wer.py --hyps $OUT/hyps.tsv
