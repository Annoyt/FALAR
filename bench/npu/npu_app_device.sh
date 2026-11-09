#!/bin/bash
# Само приложение с NPU на телефоне со Snapdragon (Samsung Remote Test Lab или свой): сборка NPU=1, модели уже в
# каталоге приложения (обязательные + models/npu). Записи корпуса подаются стендовой подачей (--es feedwav), как в
# test_hearing_device.sh: 10 фраз pt на NPU, те же 10 на процессоре (--es npu 0), 10 фраз ru на NPU. Итог —
# время распознавания каждой фразы (at.tsv), гипотезы и журнал NPU (at.log), WER против эталонов.
#
#   ADB_BIN=… bash bench/npu/npu_app_device.sh SERIAL [APK]      # APK по умолчанию — bench/apk/Falar-slim-npu.apk
#
# Записи для подачи: .stand/npu/app/a52s/feed_{pt,ru}.wav и .txt (эталоны) — по 10 фраз корпуса через 1,5 с тишины.
set -e
S=$1; [ -n "$S" ] || { echo "нужен SERIAL"; exit 2; }
P=/home/not_me/antigravity-workspace/agentstuff/agenttranslator; ST=$P/.stand
W=$(cd "$(dirname "$0")/../.." && pwd); APK=${2:-$W/bench/apk/Falar-slim-npu.apk}
ADB="${ADB_BIN:-$P/tools/platform-tools/adb} -s $S"
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity; F=/sdcard/Android/data/$PKG/files
FD=$ST/npu/app/a52s; OUT=$FD/run-$(date +%Y%m%d-%H%M%S); mkdir -p $OUT
say() { echo "$(date +%T) $*"; }
sh() { $ADB shell "$@" < /dev/null | tr -d '\r'; }
lines() { sh "wc -l < $1 2>/dev/null || echo 0" | awk '{print $1+0}'; }
waitlog() {   # ждать в журнале строку по образцу (не дольше $2 с), начиная со строки $3
  for _ in $(seq $(( $2 / 2 ))); do sh "tail -n +$3 $F/at.log" | grep -q -E "$1" && return 0; sleep 2; done; return 1; }
exec > >(tee -a $OUT/run.log) 2>&1

say "телефон: $(sh getprop ro.product.model), SoC $(sh getprop ro.soc.model), Android $(sh getprop ro.build.version.release)"
say "обязательных моделей на телефоне: $(sh "find $F/models -type f | wc -l") файлов, $(sh "du -sm $F/models | cut -f1") МБ"
[ -n "$NOINSTALL" ] || { t=$(date +%s); $ADB install -r -g "$APK" | tail -1; say "установка $(( $(date +%s) - t )) с"; }
$ADB push $FD/feed_pt.wav $F/replay_pt.wav >/dev/null; $ADB push $FD/feed_ru.wav $F/replay_ru.wav >/dev/null
sh "am force-stop $PKG"; sleep 1
m0=$(( $(lines $F/at.log) + 1 ))
sh "am start -n $ACT --es npufree 0 --es listen pt --es silent 1 --es feedonly 1 --es refineevery 0 --es cloudevery 0" >/dev/null
say "жду загрузку моделей…"
waitlog "🧩 модули:" 240 $m0 && say "приложение загрузилось" || say "НЕ загрузилось за 4 минуты"
waitlog "⚡ NPU готов|⚡ NPU не поднялся|⚡ NPU: для первой" 300 $m0 || true
sh "tail -n +$m0 $F/at.log" | grep -E "⚡|ASR\+VAD|Готово|модели|ошибк|Ошибка" | head -12 | sed 's/^/  журнал: /'

feed() {   # $1 — метка, $2 — файл на телефоне, $3 — секунд ждать
  local a=$(( $(lines $F/at.log) + 1 )) b=$(( $(lines $F/at.tsv) + 1 ))
  sh "am start -n $ACT --es feedwav $2 --es speed 2" >/dev/null; sleep $3
  sh "tail -n +$a $F/at.log" | grep "⚡" | sed "s/^/  [$1] /"
  sh "tail -n +$b $F/at.tsv" | awk -F'\t' -v L="$1" 'NF>11 && $4!="" {print L"\t"$3"\t"$12"\t"$7"\t"$4}' > $OUT/$1.tsv
  local n=$(grep -c . $OUT/$1.tsv || true)
  say "$1: фраз $n, распознавание (мс): $(cut -f3 $OUT/$1.tsv | tr '\n' ' ')"
}
say "== pt на NPU"; feed npu_pt $F/replay_pt.wav 45
sh "am start -n $ACT --es npu 0" >/dev/null; sleep 2
say "== pt на процессоре"; feed cpu_pt $F/replay_pt.wav 45
sh "am start -n $ACT --es npu 1 --es listen ru --es silent 1 --es feedonly 1" >/dev/null; sleep 3
say "== ru на NPU"; feed npu_ru $F/replay_ru.wav 45
$ADB pull $F/at.log $OUT/at.log >/dev/null 2>&1; $ADB pull $F/at.tsv $OUT/at.tsv >/dev/null 2>&1
$ST/venv-qnn/bin/python -I - $OUT $FD <<'PY'
import sys, unicodedata, statistics
out, fd = sys.argv[1:3]
def norm(s):
    s = s.lower().replace("ё", "е"); return "".join(c if not unicodedata.category(c).startswith("P") else " " for c in s).split()
def ed(r, h):
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        p, d[0] = d[0], i
        for j in range(1, len(h) + 1): c = min(d[j] + 1, d[j - 1] + 1, p + (r[i - 1] != h[j - 1])); p, d[j] = d[j], c
    return d[len(h)]
for tag, lang in (("npu_pt", "pt"), ("cpu_pt", "pt"), ("npu_ru", "ru")):
    try: rows = [l.split("\t") for l in open(f"{out}/{tag}.tsv", encoding="utf-8").read().splitlines() if l.strip()]
    except FileNotFoundError: print(tag, "— нет строк"); continue
    refs = [l for l in open(f"{fd}/feed_{lang}.txt", encoding="utf-8").read().splitlines() if l.strip()]
    hyp = " ".join(r[4] for r in rows); ref = " ".join(refs)
    e, n = ed(norm(ref), norm(hyp)), len(norm(ref))
    ms = [int(r[2]) for r in rows if r[2].isdigit()]
    print(f"{tag}: фраз {len(rows)} из {len(refs)} · распознавание медиана {statistics.median(ms) if ms else '—'} мс · WER {100*e/max(n,1):.1f} % ({e}/{n} слов)")
PY
say "итог — $OUT"
