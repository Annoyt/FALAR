#!/bin/bash
# Замер словаря из Tatoeba на телефоне: время загрузки, память, попадания и задержка.
# Запускать с подключённым по adb телефоном: bash bench/apk/measure_phrasebook.sh
set -e
A=$(cd "$(dirname "$0")" && pwd); R=$(cd "$A/../.." && pwd)
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar
DST=/sdcard/Android/data/$PKG/files/models
WAV=${WAV:-/data/local/tmp/sh/audio}

$ADB wait-for-device
echo "== установка =="
$ADB install -r "$A/AgentTranslator.apk"
echo "== словарь =="
$ADB push "$R/data/tatoeba/phrasebook_tatoeba.tsv" $DST/
$ADB push "$A/phrasebook_seed.json" $DST/phrasebook.json
$ADB shell "ls -la $DST/phrasebook*"

echo "== запуск =="
$ADB shell am force-stop $PKG
$ADB logcat -c
$ADB shell am start -n $PKG/dev.agenttranslator.MainActivity >/dev/null
sleep 25
echo "--- загрузка словаря ---"
$ADB logcat -d | grep -E "корпус:|словарь [0-9]+" | tail -5
echo "--- память процесса ---"
$ADB shell dumpsys meminfo $PKG 2>/dev/null | grep -E "TOTAL PSS|Java Heap|Native Heap" | head -3

echo "== прогон тестовых фраз =="
for f in $($ADB shell "ls $WAV/pt/*.wav 2>/dev/null | head -8"); do
  $ADB shell am start -n $PKG/dev.agenttranslator.MainActivity --es testwav "$f" --es dir pt2ru >/dev/null
  sleep 6
done
for f in $($ADB shell "ls $WAV/ru/*.wav 2>/dev/null | head -8"); do
  $ADB shell am start -n $PKG/dev.agenttranslator.MainActivity --es testwav "$f" --es dir ru2pt >/dev/null
  sleep 6
done
echo "--- результаты ---"
$ADB logcat -d -s AT:* System.out:* | grep -E "^#|→|корпус|словарь|мс" | tail -60
