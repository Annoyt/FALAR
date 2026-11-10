#!/bin/bash
# NPU в своём процессе (NpuService, app.falar:npu) на телефоне — само приложение, сборка с библиотеками NPU (build.sh
# MTK=1 или NPU=1). Проверяет то, ради чего процесс отдельный (владелец 10.11: «если не работает — чтобы процесс шёл на
# процессоре; если падает — иметь причину»):
#   1. «как новая версия» (--es npureset 1): граф собирается заново — в журнале «NPU поднят» / «готов» или «отказал» /
#      «не уместился» с причиной;
#   2. приложение живо — тот же pid app.falar, что до попытки, а процесса :npu после отказа нет;
#   3. перезапуск — отказ помнится («уже отказал», без новой попытки) или NPU поднимается из сохранённого графа.
# Модель кладётся в models/npu приложения (MediaTek — encoder-mtk.onnx, Snapdragon — encoder-npu.onnx) — из MODEL.
# Ждёт свободного стенда (.stand/lock) до часа: другие проверки на телефоне не прерываются.
# Redmi Note 10 Pro (MT6891, Neuron 5), 10.11: процесс NPU упал (SIGSEGV SEGV_MAPERR · #00 <unknown> · #01–03
# libonnxruntime_providers_neuropilot.so · #04–05 libonnxruntime.so) через 10 с после начала сборки графа; Falar жив (тот же
# pid), процесса :npu нет; после перезапуска — «уже отказал» с той же причиной, без новой попытки.
#
#   bash bench/npu/npu_process_device.sh SERIAL [APK] [MODEL]
#     APK   — по умолчанию bench/apk/Falar-mtk.apk; «-» — не ставить
#     MODEL — по умолчанию .stand/npu/mtk/a16w8/encoder-npu.onnx (рядом — nemo128.onnx)
set -e
S=$1; [ -n "$S" ] || { echo "нужен SERIAL"; exit 2; }
P=/home/not_me/antigravity-workspace/agentstuff/agenttranslator; ST=$P/.stand
W=$(cd "$(dirname "$0")/../.." && pwd)
APK=${2:-$W/bench/apk/Falar-mtk.apk}; MODEL=${3:-$ST/npu/mtk/a16w8/encoder-npu.onnx}; NEMO=$(dirname $MODEL)/nemo128.onnx
ADB="${ADB_BIN:-$P/tools/platform-tools/adb} -s $S"
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity; F=/sdcard/Android/data/$PKG/files
OUT=$ST/npu/process; mkdir -p $OUT; LOG=$OUT/run-$S-$(date +%Y%m%d-%H%M%S).log
say() { echo "$(date +%T) $*"; }
sh() { $ADB shell "$@" < /dev/null | tr -d '\r'; }
lines() { sh "wc -l < $F/at.log 2>/dev/null || echo 0" | awk '{print $1+0}'; }
exec > >(tee -a $LOG) 2>&1

exec 9>$ST/lock
say "жду свободного стенда (.stand/lock)…"
flock -w 3600 9 || { say "стенд занят больше часа — не начинаю"; exit 1; }
say "стенд мой; телефон: $(sh getprop ro.product.model), SoC $(sh getprop ro.soc.model), Android $(sh getprop ro.build.version.release)"

[ "$APK" = "-" ] || { $ADB install -r -g "$APK" | tail -1; }
sh "am start -n $ACT" > /dev/null; sleep 6                   # каталоги моделей создаёт само приложение
case "$(sh getprop ro.soc.manufacturer)" in [Mm]ediatek|MediaTek) DST=encoder-mtk.onnx;; *) DST=encoder-npu.onnx;; esac
for pair in "$MODEL:$DST" "$NEMO:nemo128.onnx"; do
  src=${pair%%:*}; dst=${pair##*:}
  [ "$(sh "stat -c %s $F/models/npu/$dst 2>/dev/null || echo 0")" = "$(stat -c %s $src)" ] \
    || { $ADB push $src $F/models/npu/$dst > /dev/null; say "залито $dst ($(( $(stat -c %s $src) >> 20 )) МБ)"; }
done

pid0=$(sh "pidof $PKG"); m0=$(( $(lines) + 1 ))
say "1. как новая версия: npureset; приложение pid $pid0; свободно $(sh "grep MemAvailable /proc/meminfo" | awk '{print int($2/1024)}') МБ"
sh "am start -n $ACT --es npureset 1 --es npufree 1500" > /dev/null
res=""
for _ in $(seq 150); do
  sleep 2
  res=$(sh "tail -n +$m0 $F/at.log" | grep -E "⚡ NPU (отказал|поднят|готов|остановлен|: граф не уместился)" | head -1)
  [ -n "$res" ] && break
done
sh "tail -n +$m0 $F/at.log" | grep "⚡" | cut -c1-400 | sed 's/^/   журнал: /'
pid1=$(sh "pidof $PKG"); npup=$(sh "pidof $PKG:npu")
[ -n "$res" ] || say "   ✗ за 5 минут NPU не сказал, чем кончилось"
[ "$pid1" = "$pid0" ] && say "   ✓ приложение живо (pid $pid1)" || say "   ✗ приложение перезапустилось: pid $pid0 → ${pid1:-нет}"
case "$res" in
  *отказал*|*не\ уместился*) [ -z "$npup" ] && say "   ✓ процесса NPU нет — распознаёт процессор" || say "   ✗ процесс NPU жив после отказа (pid $npup)";;
  *поднят*|*готов*) [ -n "$npup" ] && say "   ✓ процесс NPU работает (pid $npup)" || say "   ✗ NPU поднят, а процесса нет";;
esac

say "2. перезапуск приложения"
sh "am force-stop $PKG"; sleep 1; m1=$(( $(lines) + 1 ))
sh "am start -n $ACT" > /dev/null
for _ in $(seq 90); do
  sleep 2
  r2=$(sh "tail -n +$m1 $F/at.log" | grep -E "⚡ NPU (на этой версии и прошивке уже отказал|отказал|поднят|готов|остановлен|: граф не уместился)" | head -1)
  [ -n "$r2" ] && break
done
sh "tail -n +$m1 $F/at.log" | grep "⚡" | cut -c1-400 | sed 's/^/   журнал: /'
case "$res" in
  *отказал*) echo "$r2" | grep -q "уже отказал" && say "   ✓ отказ помнится до новой версии" || say "   ✗ после перезапуска: ${r2:-ничего}";;
  *поднят*|*готов*) echo "$r2" | grep -q -E "поднят|готов" && say "   ✓ снова поднят" || say "   ✗ после перезапуска: ${r2:-ничего}";;
esac
say "журнал — $LOG"
