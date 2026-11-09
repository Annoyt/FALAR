#!/bin/bash
# NPU MediaTek на телефоне: что есть и что открыто приложениям. Только чтение: ничего не ставит и не меняет.
# Повод — ORT NeuroPilot plugin EP (onnxruntime/onnxruntime-ep-neuropilot, v0.3.0 от 03.11.2026) и LiteRT NPU
# сборкой на телефоне: обоим нужен адаптер NeuroPilot (libneuronusdk_adapter*.so) — системный, открытый
# приложениям (public.libraries*.txt), или свой в APK. На D8300 с Android 16 он лежал в
# /system_ext/lib64/libneuronusdk_adapter.9.mtk.so (LiteRT #9250); на POCO X8 Pro (тот же MT6899) — не нашли, но
# /system_ext там не смотрели.
#   bash bench/npu/mtk_probe.sh SERIAL        # POCO X7 Pro — FMTO9DMNQGZH55GY, Redmi Note 10 Pro — f6lnlrorgi59xwge
set -e
S=$1; [ -n "$S" ] || { echo "нужен SERIAL"; exit 2; }
P=/home/not_me/antigravity-workspace/agentstuff/agenttranslator
ADB="${ADB_BIN:-$P/tools/platform-tools/adb} -s $S"
OUT=$P/.stand/npu/mtk; mkdir -p $OUT; LOG=$OUT/probe-$S-$(date +%Y%m%d-%H%M%S).txt
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
{
echo "== телефон"
for p in ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.board.platform ro.hardware \
         ro.soc.manufacturer ro.soc.model ro.vendor.mediatek.platform ro.build.display.id; do echo "$p=$(sh getprop $p)"; done
echo "== свойства NeuroPilot / APU"
sh "getprop" | grep -i -E "neuron|nn\.|apu|mdla|vpu|neuropilot|nnapi" | head -40
echo "== библиотеки NeuroPilot и APU"
sh "for d in /vendor/lib64 /system/lib64 /system_ext/lib64 /odm/lib64 /product/lib64; do ls \$d 2>/dev/null | grep -i -E 'neuron|apusys|mdla|nnapi|neuropilot|tflite|apu_' | sed \"s|^|\$d/|\"; done"
echo "== в apex"
sh "ls -d /apex/*/ 2>/dev/null" | grep -i -E "neuron|nn|apu" || true
echo "== открытые приложениям (public.libraries*.txt)"
for f in $(sh "ls /vendor/etc/public.libraries*.txt /system/etc/public.libraries*.txt /system_ext/etc/public.libraries*.txt /product/etc/public.libraries*.txt /odm/etc/public.libraries*.txt 2>/dev/null"); do
  m=$(sh "cat $f" | grep -i -E "neuron|apu|mdla|nn" || true); [ -n "$m" ] && { echo "-- $f"; echo "$m"; }
done
echo "== сервисы NNAPI"
sh "service list" | grep -i -E "neural|neuron|apu" | head -10
} | tee $LOG
echo "→ $LOG"
