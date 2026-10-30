#!/bin/bash
# Правило Brief.swallowed (ответ уточнителя, вобравший фон разговора) на ответах Hy-MT2 с открытого набора:
# сначала tools/refine_guard_eval.py (видеокарта ПК), потом это (bench/apk/test/RefineGuardSim.java). Телефон не нужен.
#   bash bench/apk/refine_guard_sim.sh [.stand/refine-guard/gen.jsonl] [примеров на группу] [признаки.tsv]
set -e
. "$(dirname "$0")/../stand.sh"   # $STAND — состояние стенда внутри проекта
[ $# -gt 0 ] || set -- "$STAND/refine-guard/gen.jsonl"
A=$(cd "$(dirname "$0")" && pwd); R=$A/../..; J=$R/tools/json.jar; OUT=$A/out/refine-guard
[ -f "$J" ] || curl -sSL -o "$J" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
rm -rf "$OUT"; mkdir -p "$OUT"
javac --release 11 -nowarn -cp "$J" -d "$OUT" $A/src/dev/agenttranslator/Brief.java $A/test/RefineGuardSim.java
java -cp "$OUT:$J" dev.agenttranslator.RefineGuardSim "$@"
