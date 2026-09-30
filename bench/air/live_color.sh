#!/bin/bash
# Цвет кнопки удержания по ходу записи: прежний счёт против Hearing.Live на записях комнаты
# (bench/apk/test/LiveColorSim.java; results/2026-09-29-mic-live.md). Телефон не нужен.
#
#   bash bench/air/live_color.sh [near-pt noisy-pt …]
set -e
R=$(cd "$(dirname "$0")/../.." && pwd); OUT=$(mktemp -d /tmp/falar-livecolor.XXXX)
javac --release 11 -nowarn -d "$OUT" $R/bench/apk/src/dev/agenttranslator/Hearing.java $R/bench/apk/src/dev/agenttranslator/Gain.java $R/bench/apk/test/LiveColorSim.java
java -cp "$OUT" dev.agenttranslator.LiveColorSim "$R/bench/air/rec" "$@"
rm -rf "$OUT"
