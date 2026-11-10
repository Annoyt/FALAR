#!/bin/bash
# Стенд озвучки на телефоне (TtsBench): фразы bench/tts/phrases.tsv — нынешним Piper (dmitri, faber; внутри eSpeak NG
# под GPL) и системным синтезом Android (у Redmi — Google, голос без сети). По каждой фразе — время до первого звука и
# синтез на секунду звука; WAV — на ПК, где их распознаёт parakeet (bench/tts/tts_wer.py): WER = разборчивость.
#
#   bash bench/tts/tts_bench_device.sh [APK]          # APK по умолчанию — bench/apk/Falar.apk этой копии; NOINSTALL=1
#   ENGINES=system bash bench/tts/tts_bench_device.sh # только один движок
#
# Нужны включённый экран и Falar или лаунчер впереди; модели приложения на месте (озвучка включена).
. "$(dirname "$0")/../stand.sh"
R=$(cd "$(dirname "$0")/../.." && pwd); A=$R/bench/apk
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "$STAND"; exec 9>"$STAND/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
ADB=${ADB:-$R/tools/platform-tools/adb}; [ -x "$ADB" ] || ADB=$(cd "$R/../.." && pwd)/tools/platform-tools/adb
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity; F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
APK=${1:-$A/Falar.apk}; ENGINES=${ENGINES:-piper,system}
MAIN=$(cd "$(git -C "$R" rev-parse --path-format=absolute --git-common-dir)/.." && pwd)
OUT=$MAIN/.stand/tts/run-$(date +%Y%m%d-%H%M%S); mkdir -p $OUT
say() { echo "$(date +%T) $*"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
count() { sh "test -f $1 && wc -l < $1 || echo 0" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
free_phone() {
  local n=0
  while :; do
    local f; f=$(sh "dumpsys window | grep -m1 mCurrentFocus")
    sh "dumpsys power" | grep -q "mWakefulness=Awake" && case "$f" in *$PKG*|*com.miui.home*|*launcher*) return 0;; esac
    n=$((n+1)); [ $n -eq 1 ] && say "нужен включённый экран и Falar или лаунчер впереди ($f), жду…"; sleep 10
  done
}
exec > >(tee -a $OUT/run.log) 2>&1
say "телефон: $(sh getprop ro.product.model), SoC $(sh getprop ro.soc.model), Android $(sh getprop ro.build.version.release), синтез по умолчанию: $(sh settings get secure tts_default_synth)"
free_phone
[ -n "$NOINSTALL" ] || { $ADB install -r -g "$APK" | tail -1; }
$ADB push $R/bench/tts/phrases.tsv $F/tts_phrases.tsv >/dev/null
m=$(count $LOG); sh "am start -n $ACT --es listen off" >/dev/null
wl $m "🧩 модули:|TTS загружен" 240 >/dev/null && say "приложение загрузилось" || say "журнал не показал загрузку за 4 минуты — пробую всё равно"
sleep 3; m=$(count $LOG)
sh "am start -n $ACT --es ttsbench tts_phrases.tsv --es ttsengines $ENGINES" >/dev/null
wl $m "🔊 стенд озвучки: (готово|ошибка)" 900 >/dev/null || say "стенд не закончил за 15 минут"
sh "tail -n +$((m+1)) $LOG" | grep '🔊' | sed 's/^/  журнал: /'
$ADB pull $F/tts_bench $OUT/ >/dev/null && say "WAV и таблица: $OUT/tts_bench ($(ls $OUT/tts_bench/*.wav 2>/dev/null | wc -l) WAV)"
[ -s $OUT/tts_bench/voices.tsv ] && { say "голоса системы ru/pt:"; column -t -s $'\t' $OUT/tts_bench/voices.tsv | sed 's/^/  /'; }
flock $MAIN/.stand/heavy.lock $MAIN/.venv/bin/python $R/bench/tts/tts_wer.py $OUT/tts_bench
say "итог — $OUT"
