#!/bin/bash
# Настольные тесты загрузчика моделей: тот же ModelStore.java, что уходит в APK, против локального
# сервера с обрывами, битыми файлами, 404, 503, отменой и архивом с путём наружу.
#   bash bench/apk/test.sh
# org.json на столе берётся с Maven (на телефоне он в системе); tools/json.jar не в git.
set -e
A=$(cd "$(dirname "$0")" && pwd); R=$A/../..; J=$R/tools/json.jar; OUT=$A/out/test
[ -f "$J" ] || curl -sSL -o "$J" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
rm -rf "$OUT"; mkdir -p "$OUT"
javac --release 11 -nowarn -cp "$J" -d "$OUT" $A/src/dev/agenttranslator/ModelStore.java $A/test/ModelStoreTest.java
java -cp "$OUT:$J" dev.agenttranslator.ModelStoreTest
