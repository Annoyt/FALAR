#!/bin/bash
# Проверка словаря и нормализатора на настольной JVM (классы приложения без Android API).
set -e
B=$(cd "$(dirname "$0")" && pwd); R=$(cd "$B/../.." && pwd)
SRC=$R/bench/apk/src/dev/agenttranslator
OUT=${OUT:-/tmp/pbtest}; M=$OUT/m
mkdir -p $M $OUT/cls
[ -f $OUT/json.jar ] || curl -sL -o $OUT/json.jar "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"
cp -f $R/data/tatoeba/phrasebook_tatoeba.tsv $M/ 2>/dev/null || true
cp -f $R/bench/apk/phrasebook_seed.json $M/phrasebook.json
javac --release 11 -nowarn -cp $OUT/json.jar -d $OUT/cls $SRC/Phrasebook.java $SRC/TextRules.java $B/*.java 2>&1 | grep -v '^Note' || true
echo "=== словарь ==="; java -cp $OUT/cls:$OUT/json.jar PbTest $M
echo; echo "=== нормализатор: точечные случаи ==="; java -cp $OUT/cls TrCase
echo; echo "=== нормализатор: весь корпус ==="; java -cp $OUT/cls TrTest $M/phrasebook_tatoeba.tsv
