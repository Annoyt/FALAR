#!/bin/bash
# Перевод сочетаний «Фраз» кодом приложения (results/2026-11-05-learn-cards.md): MtRun — Engine.translate pt→ru,
# луч 4, на столе; одно ядро и один поток (сверка с приложением), под общим замком тяжёлых замеров и сторожем
# температуры. Наборы — от bench/apk/cards_sim.sh (-Dsampleout), файл владельцу — рядом с ними (private/…).
#   bash bench/apk/colloc_check.sh <каталог с наборами> <набор.json>=<имя> …
set -e
. "$(dirname "$0")/../stand.sh"
A=$(cd "$(dirname "$0")" && pwd); R=$A/../..
D=$1; shift
MODELS=$R/models; [ -d "$MODELS/mt" ] || MODELS=$_falar_root/models
ORTV=$(ls "$A"/libs/onnxruntime-*-classes.jar | sed -n 's/.*onnxruntime-\(.*\)-classes\.jar/\1/p' | head -1)
ORT=$STAND/jars/onnxruntime-$ORTV.jar
J=$R/tools/json.jar; AJ=$R/tools/android.jar; C=$D/classes; rm -rf "$C"; mkdir -p "$C"
javac --release 11 -nowarn -encoding UTF-8 -cp "$ORT:$J:$AJ" -sourcepath "$A/src" -d "$C" \
  "$A/src/dev/agenttranslator/Engine.java" "$A"/sherpa-java-api/*.java "$R/bench/quality/MtRun.java"
flock -o "$STAND/heavy.lock" env HOT=85 COOL=70 "$STAND/thermal_guard.sh" taskset -c 2 \
  java -Dfalar.threads=1 -cp "$C:$ORT:$J:$AJ" dev.agenttranslator.MtRun "$MODELS" pt2ru "$D/colloc-mt.json" "$@" 2> "$D/colloc-mt.log"
python3 -I "$R/tools/colloc_check.py" "$D/colloc-mt.json" "$D/colloc-check.txt" "$@"
