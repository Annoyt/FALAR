#!/bin/bash
# Настольные тесты того кода из APK, который не зависит от Android:
#   ModelStore — загрузка моделей против локального сервера с обрывами, битыми файлами, 404, 503;
#   Heard      — «это прочли вслух с экрана», с упором на то, чтобы не съесть ответ собеседника.
#   bash bench/apk/test.sh
# org.json на столе берётся с Maven (на телефоне он в системе); tools/json.jar не в git.
set -e
A=$(cd "$(dirname "$0")" && pwd); R=$A/../..; J=$R/tools/json.jar; OUT=$A/out/test
[ -f "$J" ] || curl -sSL -o "$J" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
rm -rf "$OUT"; mkdir -p "$OUT"
javac --release 11 -nowarn -cp "$J" -d "$OUT" \
  $A/src/dev/agenttranslator/ModelStore.java $A/test/ModelStoreTest.java \
  $A/src/dev/agenttranslator/Heard.java $A/test/HeardTest.java
fail=0
java -cp "$OUT:$J" dev.agenttranslator.ModelStoreTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.HeardTest || fail=1
exit $fail
