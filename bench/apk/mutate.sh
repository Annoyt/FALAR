#!/bin/bash
# Мутационное тестирование (PIT) кода из APK, который не зависит от Android.
# PIT вносит в байткод мелкие ошибки — «<» вместо «<=», «+1» вместо «-1», пустой возврат — и
# гоняет тесты. Мутант убит, если хоть один тест упал; выжил — значит, тесты код исполняют,
# но его поведение на этом месте не проверяют. Процент убитых честнее покрытия строк.
#   bash bench/apk/mutate.sh                 # все чистые классы
#   bash bench/apk/mutate.sh Brief,Heard     # только эти
# Отчёт: bench/apk/out/pit/index.html. Нужные jar-файлы — в tools/pit (не в git), качаются сами.
set -e
A=$(cd "$(dirname "$0")" && pwd); R=$A/../..; P=$R/tools/pit; J=$R/tools/json.jar
M=https://repo1.maven.org/maven2
mkdir -p "$P"
for a in org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar \
         org/pitest/pitest/1.17.4/pitest-1.17.4.jar org/pitest/pitest-entry/1.17.4/pitest-entry-1.17.4.jar \
         org/pitest/pitest-command-line/1.17.4/pitest-command-line-1.17.4.jar \
         org/pitest/pitest-junit5-plugin/1.2.1/pitest-junit5-plugin-1.2.1.jar \
         org/apache/commons/commons-text/1.12.0/commons-text-1.12.0.jar \
         org/apache/commons/commons-lang3/3.17.0/commons-lang3-3.17.0.jar; do
  [ -f "$P/$(basename $a)" ] || curl -sSfL -o "$P/$(basename $a)" "$M/$a"
done
[ -f "$J" ] || curl -sSfL -o "$J" "$M/org/json/json/20240303/json-20240303.jar"
T=${1:-Brief,Heard,Updates,ModelStore,Memo}
TARGETS=$(echo "$T" | tr ',' '\n' | sed 's/^/dev.agenttranslator./' | paste -sd,)
OUT=$A/out/pit; C=$A/out/pit-classes; rm -rf "$C"; mkdir -p "$C" "$OUT"
JU=$P/junit-platform-console-standalone-1.11.4.jar
# Компилируем все наборы: обёртка SuitesTest ссылается на каждый. Ограничиваем только то, что мутируется.
SRC=""; for t in Brief Heard Updates ModelStore Chats Memo WordList; do SRC="$SRC $A/src/dev/agenttranslator/$t.java $A/test/${t}Test.java"; done
SRC="$SRC $A/src/dev/agenttranslator/Phrasebook.java $A/src/dev/agenttranslator/TextRules.java $A/src/dev/agenttranslator/Translit.java"
SRC="$SRC $A/src/dev/agenttranslator/Cloud.java"      # ради Cloud.Review; заглушки Android — только для сборки
javac --release 11 -nowarn -g -cp "$J:$JU:$R/tools/android.jar" -d "$C" $SRC $A/test/SuitesTest.java
CP=$(ls $P/pitest*.jar $P/commons-*.jar | paste -sd:):$JU
java -cp "$CP" org.pitest.mutationtest.commandline.MutationCoverageReport \
  --classPath "$C,$J,$JU" --reportDir "$OUT" --targetClasses "$TARGETS" \
  --targetTests dev.agenttranslator.SuitesTest --sourceDirs "$A/src" \
  --outputFormats HTML,CSV --timestampedReports=false --threads 4 --timeoutConst 8000 \
  --jvmArgs "-Dfalar.common=$R/data/common_words.txt" \
  --mutators STRONGER 2>&1 | grep -E "^>>|Generated|Killed|mutations|test strength|Line Coverage|ERROR|Exception" | grep -v "^\s*$" || true
