#!/bin/bash
# Настольные тесты того кода из APK, который не зависит от Android:
#   ModelStore — загрузка моделей против локального сервера с обрывами, битыми файлами, 404, 503;
#   Heard      — «это прочли вслух с экрана», с упором на то, чтобы не съесть ответ собеседника;
#   Updates    — сравнение версий и разбор описания релиза, включая мусор вместо описания;
#   Brief      — бюджет контекста уточнителя, чтобы запрос не перерастал окно модели;
#   Chats      — правка человека неприкосновенна для автоматики («два перевода подряд» после правки).
# Мутационное тестирование тех же классов — bench/apk/mutate.sh.
#   bash bench/apk/test.sh
# org.json на столе берётся с Maven (на телефоне он в системе); tools/json.jar не в git.
set -e
A=$(cd "$(dirname "$0")" && pwd); R=$A/../..; J=$R/tools/json.jar; OUT=$A/out/test
[ -f "$J" ] || curl -sSL -o "$J" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
rm -rf "$OUT"; mkdir -p "$OUT"
javac --release 11 -nowarn -cp "$J" -d "$OUT" \
  $A/src/dev/agenttranslator/ModelStore.java $A/test/ModelStoreTest.java \
  $A/src/dev/agenttranslator/Heard.java $A/test/HeardTest.java \
  $A/src/dev/agenttranslator/Updates.java $A/test/UpdatesTest.java \
  $A/src/dev/agenttranslator/Brief.java $A/test/BriefTest.java \
  $A/src/dev/agenttranslator/Chats.java $A/src/dev/agenttranslator/Phrasebook.java \
  $A/src/dev/agenttranslator/TextRules.java $A/src/dev/agenttranslator/Translit.java $A/test/ChatsTest.java
fail=0
java -cp "$OUT:$J" dev.agenttranslator.ModelStoreTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.HeardTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.UpdatesTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.BriefTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.ChatsTest || fail=1
exit $fail
