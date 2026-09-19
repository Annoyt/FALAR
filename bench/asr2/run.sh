#!/bin/bash
# Сравнение вариантов распознавания на телефоне: декодер, модель.
# Выводит сырые логи в bench/asr2/logs/, WER считает wer2.py.
set -e
R=$(cd "$(dirname "$0")/../.." && pwd); A=${ADB:-$R/tools/platform-tools/adb}
S=/data/local/tmp/sh; M=$S/m; OUT=$(dirname "$0")/logs
mkdir -p $OUT
PT=$M/sherpa-onnx-nemo-transducer-stt_pt_fastconformer_hybrid_large_pc-int8
PK=$M/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8
RU=$M/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16

run() {  # имя, язык, доп.аргументы, бинарник
  local name=$1 lang=$2 bin=$4; shift 4
  echo "-- $name"
  $A shell "cd $S && LD_LIBRARY_PATH=. ./$bin $* \$(ls $S/audio/$lang/*.wav)" \
     > $OUT/$name.out 2> $OUT/$name.err || true
}
run pt_fast_greedy pt "" sherpa-onnx-offline \
  --tokens=$PT/tokens.txt --encoder=$PT/encoder.int8.onnx --decoder=$PT/decoder.int8.onnx \
  --joiner=$PT/joiner.int8.onnx --model-type=nemo_transducer --num-threads=4 --decoding-method=greedy_search
run pt_fast_beam pt "" sherpa-onnx-offline \
  --tokens=$PT/tokens.txt --encoder=$PT/encoder.int8.onnx --decoder=$PT/decoder.int8.onnx \
  --joiner=$PT/joiner.int8.onnx --model-type=nemo_transducer --num-threads=4 --decoding-method=modified_beam_search --max-active-paths=4
run pt_parakeet_greedy pt "" sherpa-onnx-offline \
  --tokens=$PK/tokens.txt --encoder=$PK/encoder.int8.onnx --decoder=$PK/decoder.int8.onnx \
  --joiner=$PK/joiner.int8.onnx --model-type=nemo_transducer --num-threads=4 --decoding-method=greedy_search
run ru_zip_greedy ru "" sherpa-onnx \
  --tokens=$RU/tokens.txt --encoder=$RU/encoder.int8.onnx --decoder=$RU/decoder.onnx \
  --joiner=$RU/joiner.int8.onnx --num-threads=4 --decoding-method=greedy_search
run ru_zip_beam ru "" sherpa-onnx \
  --tokens=$RU/tokens.txt --encoder=$RU/encoder.int8.onnx --decoder=$RU/decoder.onnx \
  --joiner=$RU/joiner.int8.onnx --num-threads=4 --decoding-method=modified_beam_search --max-active-paths=4
run ru_parakeet_greedy ru "" sherpa-onnx-offline \
  --tokens=$PK/tokens.txt --encoder=$PK/encoder.int8.onnx --decoder=$PK/decoder.int8.onnx \
  --joiner=$PK/joiner.int8.onnx --model-type=nemo_transducer --num-threads=4 --decoding-method=greedy_search
echo "готово, логи в $OUT"
