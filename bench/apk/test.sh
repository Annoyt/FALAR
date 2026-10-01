#!/bin/bash
# Настольные тесты того кода из APK, который не зависит от Android:
#   ModelStore — загрузка моделей против локального сервера с обрывами, битыми файлами, 404, 503;
#   Heard      — «это прочли вслух с экрана», с упором на то, чтобы не съесть ответ собеседника;
#   Updates    — сравнение версий и разбор описания релиза, включая мусор вместо описания;
#   Brief      — бюджет контекста уточнителя, чтобы запрос не перерастал окно модели;
#   Chats      — правка человека неприкосновенна для автоматики («два перевода подряд» после правки);
#   Memo       — память разговора: кто говорит (по грамматике исходников), ключевые детали, потолок;
#                собеседники по голосам: сколько, имя из представления, о чём говорит каждый;
#   Voices     — голоса разговора: фраза кнопкой FALAR — знакомый голос или новый, номера, имена, файл;
#   WordList   — свои слова: искажённое имя находится, обычное слово («sábado») именем не подменяется;
#   Pressure   — когда выгружать уточнитель по сигналу памяти: пик от подъёма пережидаем;
#   Gain       — чувствительность микрофона: ограничитель держит потолок и форму волны, срез — нет;
#   Hearing    — как слышно фразу: перегруз главнее тишины и шума, пороги, цифра в строке;
#   Cloud      — порядок облачных моделей: «быстрее» и «точнее», размер по имени;
#   Modules    — модули: по умолчанию под телефон, «как было» у обновившегося, какие кнопки есть;
#   TextRules  — текст вывески: регистр прописного текста, название улицы только с заглавной;
#   OcrCore    — офлайн-чтение снимка без моделей: рамки, вырез, строки и абзацы, цвета наложения;
#                со сверкой с эталоном tools/ocr_ref.py, если он собран (tools/ocr_golden.py);
#   OcrWords   — правка слов снимка по словарю: пропущенная буква, ударения, слипшиеся слова, но
#                не имена и не английский; со сверкой с tools/ocr_words.py (… golden).
# Мутационное тестирование тех же классов — bench/apk/mutate.sh.
#   bash bench/apk/test.sh
# org.json на столе берётся с Maven (на телефоне он в системе); tools/json.jar не в git.
# Cloud.java собирается с заглушками Android (tools/android.jar) — нужен только разбор ответа Cloud.Review.
set -e
A=$(cd "$(dirname "$0")" && pwd); R=$A/../..; J=$R/tools/json.jar; AJ=$R/tools/android.jar; OUT=$A/out/test
[ -f "$J" ] || curl -sSL -o "$J" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
rm -rf "$OUT"; mkdir -p "$OUT"
javac --release 11 -nowarn -cp "$J:$AJ" -d "$OUT" \
  $A/src/dev/agenttranslator/ModelStore.java $A/test/ModelStoreTest.java \
  $A/src/dev/agenttranslator/Heard.java $A/test/HeardTest.java \
  $A/src/dev/agenttranslator/Updates.java $A/test/UpdatesTest.java \
  $A/src/dev/agenttranslator/Brief.java $A/test/BriefTest.java \
  $A/src/dev/agenttranslator/Chats.java $A/src/dev/agenttranslator/Phrasebook.java \
  $A/src/dev/agenttranslator/TextRules.java $A/src/dev/agenttranslator/Translit.java $A/test/ChatsTest.java \
  $A/src/dev/agenttranslator/Memo.java $A/src/dev/agenttranslator/Cloud.java $A/test/MemoTest.java \
  $A/src/dev/agenttranslator/Voices.java $A/test/VoicesTest.java \
  $A/src/dev/agenttranslator/WordList.java $A/test/WordListTest.java \
  $A/src/dev/agenttranslator/Pressure.java $A/test/PressureTest.java \
  $A/src/dev/agenttranslator/Gain.java $A/test/GainTest.java $A/test/CloudTest.java \
  $A/src/dev/agenttranslator/Hearing.java $A/test/HearingTest.java \
  $A/src/dev/agenttranslator/OcrCore.java $A/test/OcrCoreTest.java \
  $A/src/dev/agenttranslator/OcrWords.java $A/test/OcrWordsTest.java \
  $A/src/dev/agenttranslator/Modules.java $A/test/ModulesTest.java $A/test/TextRulesTest.java
fail=0
java -cp "$OUT:$J" dev.agenttranslator.ModelStoreTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.HeardTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.UpdatesTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.BriefTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.ChatsTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.MemoTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.VoicesTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.WordListTest "$R/data/common_words.txt" || fail=1
java -cp "$OUT:$J" dev.agenttranslator.PressureTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.GainTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.HearingTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.CloudTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.OcrCoreTest "$R/bench/ocr/runs/golden" || fail=1
java -cp "$OUT:$J" dev.agenttranslator.OcrWordsTest "$R/data/ocr_words_pt.txt.gz" "$R/bench/ocr/runs/golden" || fail=1
java -cp "$OUT:$J" dev.agenttranslator.ModulesTest || fail=1
java -cp "$OUT:$J" dev.agenttranslator.TextRulesTest || fail=1
exit $fail
