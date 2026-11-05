#!/bin/bash
# «Слова» и «Фразы» на копии разговоров телефона (bench/apk/test/CardsSim.java): только числа в вывод,
# сами сочетания — в файл рядом с данными. Данные телефона — только в private/ (правило владельца 27.10).
#   bash bench/apk/cards_sim.sh <каталог с chats/> [файл сочетаний]
#   CARDS_SIM_OPTS="-Dsample=82 -Dseed=5 -Dsampleout=<набор.json>" … — выборка сочетаний для проверки перевода (MtRun)
set -e
A=$(cd "$(dirname "$0")" && pwd); R=$A/../..; J=$R/tools/json.jar; OUT=$A/out/cards-sim
[ -f "$J" ] || curl -sSL -o "$J" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
rm -rf "$OUT"; mkdir -p "$OUT"
javac --release 11 -nowarn -cp "$J:$R/tools/android.jar" -d "$OUT" $A/src/dev/agenttranslator/{BargeIn,Brief,Cards,Chats,Clips,Cloud,DenoiseGate,Fbank,Feedback,Gain,Heard,Hearing,Inbox,L,Learn,Memo,MicPair,ModelStore,Modules,Numbers,OcrCore,OcrWords,Phrasebook,Practice,Pressure,Screen,Slicer,Telemetry,TextRules,Translit,Updates,VoiceMsg,VoiceOut,Voices,WhatsNew,Wiener,WordConf,WordList}.java $A/test/CardsSim.java 2>&1 | grep -v "^Note:" || true
java $CARDS_SIM_OPTS -cp "$OUT:$J" dev.agenttranslator.CardsSim "$1" "${2:-}" "$R/data" $R/bench/air/corpus/pt $R/bench/air/corpus/ru $R/bench/air/fresh/pt $R/bench/air/fresh/ru
