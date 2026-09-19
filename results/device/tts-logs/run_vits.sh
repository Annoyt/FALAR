#!/bin/sh
# usage: run_vits.sh <bundle-dir-name> <model.onnx> <lang: ru|pt> <tag>
V=/data/local/tmp/sh/tts/v/$1; MODEL=$V/$2; LANG=$3; TAG=$4
cd /data/local/tmp/sh; export LD_LIBRARY_PATH=/data/local/tmp/sh
i=0
while IFS= read -r line; do
  i=$((i+1))
  echo "### $TAG $i"
  taskset f0 ./sherpa-onnx-offline-tts --vits-model=$MODEL --vits-tokens=$V/tokens.txt --vits-data-dir=$V/espeak-ng-data --num-threads=4 --output-filename=/data/local/tmp/sh/tts/out/${TAG}_$i.wav "$line" 2>&1 | grep -E 'Elapsed|Audio duration|RTF|progress=|rror'
done < /data/local/tmp/sh/tts/$LANG.txt
echo "### $TAG END"
