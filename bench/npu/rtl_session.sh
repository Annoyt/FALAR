#!/bin/bash
# Один сеанс Samsung Remote Test Lab (15 мин) — замер перевода на настоящем Snapdragon: CPU (int8, как в приложении)
# против NPU (fp16, QNN HTP). Сначала декодер (он идёт 10–20 раз на фразу и решает скорость), потом кодировщик.
#
#   bash bench/npu/rtl_session.sh SERIAL      # SERIAL — как его показывает `adb devices` после запуска rdb
#
# Модели: int8 — из приложения (models/mt/pt2ru), fp16 — bench/npu/to_fp16.py из models/opus-onnx/pt2ru
# (декодер — fp16 и на входах, файл *_fp16io.onnx). Все публичные (OPUS-MT, CC-BY-4.0). Итог — в bench/npu/runs/.
set -e
S=$1; [ -n "$S" ] || { echo "нужен SERIAL"; exit 2; }
R=$(cd "$(dirname "$0")/../.." && pwd); RUN="bash $R/bench/npu/run_device.sh $S"
M=/home/not_me/antigravity-workspace/agentstuff/agenttranslator/models; F=${NPU_FP16:-$HOME/.cache/falar-stand/npu}
mkdir -p $R/bench/npu/runs; LOG=$R/bench/npu/runs/rtl-$(date +%Y%m%d-%H%M%S).log
exec > >(tee -a $LOG) 2>&1
echo "== $(date +%T) сеанс, лог $LOG"
$RUN setup
soc=$(/home/not_me/antigravity-workspace/agentstuff/agenttranslator/tools/platform-tools/adb -s $S shell getprop ro.soc.model 2>/dev/null | tr -d '\r' || true)
case "$soc" in SM8450|SM8475) id=36;; SM8550) id=43;; SM8650) id=57;; SM8750) id=69;; SM8850) id=87;; *) id="";; esac
DEC="batch_size=1,encoder_sequence_length=32,past_decoder_sequence_length=16,decoder_sequence_length=1"
ENC="batch_size=1,encoder_sequence_length=32"
$RUN model $M/mt/pt2ru/decoder_with_past_model.onnx dec_int8.onnx
$RUN model $F/mt_decoder_with_past_model_fp16io.onnx dec_fp16.onnx
for t in 4 1; do $RUN bench /data/local/tmp/falar-npu/dec_int8.onnx $DEC 30 cpu $t; done
$RUN bench /data/local/tmp/falar-npu/dec_fp16.onnx $DEC 30 npu 1 "$id" /data/local/tmp/falar-npu/dec_fp16.onnx
$RUN model $M/mt/pt2ru/encoder_kv_model.onnx enc_int8.onnx
$RUN model $F/mt_encoder_model_fp16.onnx enc_fp16.onnx
for t in 4 1; do $RUN bench /data/local/tmp/falar-npu/enc_int8.onnx $ENC 30 cpu $t; done
$RUN bench /data/local/tmp/falar-npu/enc_fp16.onnx $ENC 30 npu 1 "$id"
$RUN clean
echo "== $(date +%T) готово"
