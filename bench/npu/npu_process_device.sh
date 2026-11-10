#!/bin/bash
# NPU в своём процессе (NpuService, <пакет>:npu) на телефоне — само приложение, сборка с библиотеками NPU (build.sh
# MTK=1 или NPU=1). Проверяет то, ради чего процесс отдельный (владелец 10.11: «если не работает — чтобы процесс шёл на
# процессоре; если падает — иметь причину»):
#   1. «как новая версия» (--es npureset 1) и новый запуск: граф собирается заново — до загрузки моделей, если после них
#      он не уместится (npuFirst; снимок экрана со сборкой — .stand/npu/process/npu-first.png), — в журнале «NPU поднят» /
#      «готов» или «отказал» / «не уместился» с причиной;
#   2. приложение живо — тот же pid, что до попытки, а процесса :npu после отказа нет;
#   3. NPU поднялся — подача записи (публичные фразы Tatoeba, bench/air/corpus; микрофон глухой, без озвучки): первые
#      фразы сверяются с процессором («NPU проверен»), дальше распознаёт NPU («фраза на NPU»);
#   4. перезапуск — отказ помнится («уже отказал», без новой попытки) или NPU поднимается из сохранённого графа уже
#      проверенным и сразу распознаёт.
# Модель кладётся в models/npu приложения (MediaTek — encoder-mtk.onnx, Snapdragon — encoder-npu.onnx) — из MODEL.
# Ждёт свободного стенда (LOCK, по умолчанию .stand/lock) до часа: другие проверки на телефоне не прерываются.
# Redmi Note 10 Pro (MT6891, Neuron 5), 10.11: процесс NPU упал (SIGSEGV SEGV_MAPERR · #00 <unknown> · #01–03
# libonnxruntime_providers_neuropilot.so · #04–05 libonnxruntime.so) через 10 с после начала сборки графа; Falar жив (тот же
# pid), процесса :npu нет; после перезапуска — «уже отказал» с той же причиной, без новой попытки.
# POCO X7 Pro (MT6899), 10.11: то же падение через 3 с — приложению не отдавали libneuronusdk_adapter.mtk.so (logcat:
# «NeuronApi error: unable to open library», «uses_libraries=» пусто): с targetSdk 31+ библиотеки производителя нужно
# назвать в манифесте (<uses-native-library>), а дополнение NeuroPilot ошибку не проверяет. С объявлением граф собирается.
# Падение на Redmi могло быть тем же — с объявлением там ещё не проверено.
#
#   [PKG=…] [LOCK=…] bash bench/npu/npu_process_device.sh SERIAL [APK] [MODEL]
#     APK   — по умолчанию bench/apk/Falar-mtk.apk; «-» — не ставить
#     MODEL — по умолчанию .stand/npu/mtk/a16w8/encoder-npu.onnx (рядом — nemo128.onnx)
#     PKG   — имя пакета, по умолчанию app.falar. Другое — пробная копия (build.sh APP_ID=…) рядом с Falar из выпуска,
#             которую отладочной поверх не поставить (POCO владельца): модели заливаются с ПК по манифесту —
#             обязательное, base и NPU своего чипа; модули выключены (--es modules ''), сама копия ничего не качает.
#             Слушать Android даёт только открытому с экрана Falar — скрипт ждёт, пока телефон разблокируют.
#     NPUFREE — порог свободной памяти (МБ) для первой сборки графа; по умолчанию — как у приложения (MediaTek 3000):
#             сборке A16W8 нужно 1,7–2,2 ГБ сверх занятого (стенд 10.11), сторож останавливает её при 800 МБ. POCO 10.11:
#             начатая при 2,3 ГБ свободных (NPUFREE=1500) остановлена сторожем на 774 МБ — Falar жив, NPU «остановлен:
#             мало памяти», после перезапуска ждёт 3 ГБ. Этот POCO — 7,5 ГБ ОЗУ: копия Falar с моделями занимает 1,6 ГБ,
#             свободно и после закрытия приложений 2,1–2,7 ГБ — первая сборка в приложении не помещается (стенд под adb, без
#             Falar, начинал с 3,4–3,9 ГБ и собирал).
set -e
S=$1; [ -n "$S" ] || { echo "нужен SERIAL"; exit 2; }
P=/home/not_me/antigravity-workspace/agentstuff/agenttranslator; ST=$P/.stand
W=$(cd "$(dirname "$0")/../.." && pwd)
APK=${2:-$W/bench/apk/Falar-mtk.apk}; MODEL=${3:-$ST/npu/mtk/a16w8/encoder-npu.onnx}; NEMO=$(dirname $MODEL)/nemo128.onnx
ADB="${ADB_BIN:-$P/tools/platform-tools/adb} -s $S"
PKG=${PKG:-app.falar}; ACT=$PKG/dev.agenttranslator.MainActivity; F=/sdcard/Android/data/$PKG/files
LOCK=${LOCK:-$ST/lock}
OUT=$ST/npu/process; mkdir -p $OUT; LOG=$OUT/run-$S-$(date +%Y%m%d-%H%M%S).log
say() { echo "$(date +%T) $*"; }
sh() { $ADB shell "$@" < /dev/null | tr -d '\r'; }
lines() { sh "wc -l < $F/at.log 2>/dev/null || echo 0" | awk '{print $1+0}'; }
since() { sh "tail -n +$1 $F/at.log"; }
# ждать строки журнала (после строки from) по шаблону, не дольше sec секунд; печатает первую найденную
wait_log() { local from=$1 pat=$2 sec=$3 r=""; for _ in $(seq $(( sec / 2 ))); do sleep 2
  r=$(since $from | grep -E "$pat" | head -1); [ -n "$r" ] && break; done; echo "$r"; }
# Открыть Falar с добавками: --es silent 1 — без озвучки, и стендовые режимы не сбрасываются (открытие с экрана сбросило
# бы); --es keepscreen 1 — экран не гаснет, пока Falar на виду (телефон по Wi-Fi не на зарядке, гаснет за минуты)
open() { sh "am start -n $ACT --es silent 1 --es keepscreen 1 $*" > /dev/null; }
# Слушать (видимая служба с микрофоном) Android даёт только Falar, открытому на разблокированном и включённом экране:
# перед каждым новым запуском службы — ждать этого. Открытому Falar добавки доходят и при погасшем экране.
locked() { local w; w=$(sh "dumpsys window | grep -c isKeyguardShowing=true; dumpsys power | grep -c mWakefulness=Awake" | tr '\n' ' ')
  [ "$w" != "0 1 " ]; }
wait_unlock() { locked || return 0
  say "телефон заблокирован или экран погас — разблокируйте его; жду до 15 мин…"
  for _ in $(seq 450); do locked || break; sleep 2; done
  locked && { say "✗ телефон так и не разблокировали — дальше не иду"; exit 1; }
  say "разблокирован"; }
exec > >(tee -a $LOG) 2>&1

exec 9>$LOCK
say "жду свободного стенда ($LOCK)…"
flock -w 3600 9 || { say "стенд занят больше часа — не начинаю"; exit 1; }
say "стенд мой; телефон: $(sh getprop ro.product.model), SoC $(sh getprop ro.soc.model), Android $(sh getprop ro.build.version.release)"
case "$(sh getprop ro.soc.manufacturer)" in [Mm]ediatek|MediaTek|MEDIATEK) CHIP=mediatek; DST=encoder-mtk.onnx;; *) CHIP=qualcomm; DST=encoder-npu.onnx;; esac

# Xiaomi спрашивает про каждую установку через adb на экране телефона: заблокирован — окна нет, установка отменяется
# («INSTALL_FAILED_USER_RESTRICTED: Install canceled by user», POCO 10.11). Поэтому сначала — разблокированный экран.
wait_unlock
if [ "$APK" != "-" ]; then
  say "ставлю $(basename $APK) — если телефон спросит, разрешить ли установку, нажмите «Установить»"
  r=$($ADB install -r -g "$APK" < /dev/null 2>&1 | grep -E "^Success|Failure|failed" | tail -1)   # adb 37: последней — время
  case "$r" in
    Success*) say "   поставлено";;
    *USER_RESTRICTED*) say "   ✗ установку отменил телефон: $r — подтвердите её на экране; нет окна — в настройках разработчика включите «Установка через USB»"; exit 1;;
    *) say "   ✗ не поставилось: $r"; exit 1;;
  esac
fi
FRESH=; [ "$PKG" = app.falar ] || FRESH=1
open "--es feedonly 1${FRESH:+ --es modules ''}"; sleep 6        # каталоги приложения создаёт само приложение
if [ -n "$FRESH" ]; then
  say "0. пробная копия $PKG: модули выключены, модели — с ПК"
  [ -n "$(sh "ls -d $F 2>/dev/null")" ] || { say "   ✗ приложение не создало $F — не заливаю (каталог от adb ему недоступен)"; exit 3; }
  LIST=$(python3 - "$W/models/manifest.json" "$P/models" "$W" <<'PY'
import json, os, sys
mf, models, root = sys.argv[1:4]
for e in json.load(open(mf, encoding="utf-8"))["files"]:
    if e.get("tier") != "core" and e.get("module") != "base": continue
    src = os.path.join(root, e["repo_path"]) if e.get("repo_path") else os.path.join(models, e["path"])
    print(f"{src}\t{e['path']}\t{e['size']}")
PY
)
  { echo models; echo models/npu; echo "$LIST" | cut -f2 | xargs -n1 dirname | grep -v '^\.$' | sed 's|^|models/|'; } | sort -u > $OUT/dirs.txt
  $ADB push $OUT/dirs.txt $F/dirs.txt < /dev/null > /dev/null
  m=$(( $(lines) + 1 )); open "--es mkdirs 1"
  say "   $(wait_log $m '📁 каталогов' 30 | cut -c1-120)"
  while IFS=$'\t' read -r src dst size; do
    [ "$(stat -c %s "$src" 2>/dev/null)" = "$size" ] || { say "   ✗ на ПК $src не той длины, что в манифесте ($size) — не заливаю"; exit 3; }
    [ "$(sh "stat -c %s $F/models/$dst 2>/dev/null || echo 0")" = "$size" ] \
      || { $ADB push "$src" "$F/models/$dst" < /dev/null > /dev/null; say "   залито $dst ($(( size >> 20 )) МБ)"; }
  done <<< "$LIST"
fi
for pair in "$MODEL:$DST" "$NEMO:nemo128.onnx"; do
  src=${pair%%:*}; dst=${pair##*:}
  [ "$(sh "stat -c %s $F/models/npu/$dst 2>/dev/null || echo 0")" = "$(stat -c %s $src)" ] \
    || { $ADB push $src $F/models/npu/$dst < /dev/null > /dev/null; say "залито $dst ($(( $(stat -c %s $src) >> 20 )) МБ)"; }
done
# 1. заново, как новая версия. Сброс (--es npureset 1) — в работающий Falar, потом новый запуск: первая сборка графа
# идёт при запуске до моделей (npuFirst), если после них она не уместится, иначе — после моделей (startNpu). Порог памяти
# (NPUFREE) — в том же запуске, что и сборка. Во время сборки до моделей — снимок экрана: что видит человек.
PAT="⚡ NPU( отказал| поднят| готов| остановлен|: граф не уместился|: для первой сборки графа нужно)"
relaunch() {   # новый запуск Falar; m0 — первая строка журнала этого запуска, pid0 — его pid
  wait_unlock; sh "am force-stop $PKG"; sleep 1; m0=$(( $(lines) + 1 ))
  open "--es feedonly 1${NPUFREE:+ --es npufree $NPUFREE}"; sleep 4; pid0=$(sh "pidof $PKG")
}
outcome() {    # ждать итога NPU этого запуска; сборка до моделей — снимок экрана
  local pre; pre=$(wait_log $m0 "⚡ NPU: первый запуск этой версии|$PAT" 120)
  if echo "$pre" | grep -q "первый запуск этой версии"; then
    sleep 4; open "--es uishot npu-first"; sleep 3
    $ADB pull $F/npu-first.png $OUT/npu-first.png < /dev/null > /dev/null 2>&1 && say "   снимок экрана во время сборки — $OUT/npu-first.png"
  fi
  res=$(wait_log $m0 "$PAT" 300)
}
wait_unlock
open "--es feedonly 1 --es npureset 1"; sleep 3
relaunch
say "1. как новая версия (npureset) и новый запуск; приложение pid $pid0; свободно $(sh "grep MemAvailable /proc/meminfo" | awk '{print int($2/1024)}') МБ"
outcome
# Мало памяти и до моделей — Falar распознаёт процессором и соберёт граф при следующем запуске. Стенд закрывает Falar,
# просит освободить память, ждёт её и запускает заново.
if echo "$res" | grep -q "для первой сборки графа нужно"; then
  need=$(echo "$res" | grep -oE "нужно [0-9]+" | grep -oE "[0-9]+")
  say "   свободно мало: $(echo "$res" | grep -oE "сейчас [0-9]+ МБ") при нужных $need МБ — закрываю Falar; закройте на телефоне недавние приложения, жду до 15 мин…"
  since $m0 | grep "⚡" | cut -c1-300 | sed 's/^/   журнал: /'
  sh "am force-stop $PKG"
  for _ in $(seq 180); do
    free=$(sh "grep MemAvailable /proc/meminfo" | awk '{print int($2/976.5625)}')   # кБ → МБ по 10^6 байт, как порог в журнале Falar
    [ "$free" -ge $(( need + 400 )) ] && break; sleep 5     # +400: сам Falar до моделей (~250 МБ) и запас
  done
  say "   свободно $free МБ при закрытом Falar"
  [ "$free" -ge $(( need + 400 )) ] || { say "   ✗ памяти так и не стало — дальше не иду"; exit 1; }
  relaunch; say "   открыт заново (pid $pid0)"
  outcome
fi
since $m0 | grep "⚡" | cut -c1-400 | sed 's/^/   журнал: /'
pid1=$(sh "pidof $PKG"); npup=$(sh "pidof $PKG:npu")
[ -n "$res" ] || say "   ✗ за 5 минут NPU не сказал, чем кончилось"
[ "$pid1" = "$pid0" ] && say "   ✓ приложение живо (pid $pid1)" || say "   ✗ приложение перезапустилось: pid $pid0 → ${pid1:-нет}"
case "$res" in
  *отказал*|*не\ уместился*) [ -z "$npup" ] && say "   ✓ процесса NPU нет — распознаёт процессор" || say "   ✗ процесс NPU жив после отказа (pid $npup)";;
  *поднят*|*готов*) [ -n "$npup" ] && say "   ✓ процесс NPU работает (pid $npup, $(sh "dumpsys meminfo $npup" | awk '/TOTAL PSS/{print int($3/1024)" МБ PSS"; exit}'))" || say "   ✗ NPU поднят, а процесса нет";;
esac

# подача: n фраз pt и ru вперемешку (Tatoeba, лицензия указана), паузы 1,8 с; на телефон — в files/ приложения
feed() {   # feed ИМЯ N ПРОПУСК → путь на телефоне
  python3 - "$W/bench/air/corpus" "$OUT/$1.wav" "$2" "$3" <<'PY'
import csv, os, struct, sys, wave
corp, out, n, skip = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
lic = {r[0]: r[-1] for r in csv.reader(open(os.path.join(corp, "LICENSES.tsv"), encoding="utf-8"), delimiter="\t") if r}
pick = {}
for lang in ("pt", "ru"):
    fs = []
    for f in sorted(os.listdir(os.path.join(corp, lang))):
        if not f.endswith(".wav") or lic.get(f[:-4], "не указана") == "не указана": continue
        w = wave.open(os.path.join(corp, lang, f))
        if w.getframerate() == 16000 and w.getnchannels() == 1 and 1.5 <= w.getnframes() / 16000 <= 4.5: fs.append(f)
    pick[lang] = fs[skip:skip + (n + 1) // 2]
order = [x for pair in zip(pick["pt"], pick["ru"]) for x in pair][:n]
sil = lambda s: b"\0\0" * int(16000 * s)
data = sil(1.0); refs = []
for f in order:
    lang = "pt" if f in pick["pt"] else "ru"
    data += wave.open(os.path.join(corp, lang, f)).readframes(10 ** 7) + sil(1.8)
    refs.append(f"{f}\t{open(os.path.join(corp, lang, f[:-4] + '.txt'), encoding='utf-8').read().strip()}")
data += sil(1.5)
with wave.open(out, "wb") as w: w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(data)
open(out[:-4] + ".tsv", "w", encoding="utf-8").write("\n".join(refs) + "\n")
PY
  $ADB push $OUT/$1.wav $F/$1.wav < /dev/null > /dev/null
  echo $F/$1.wav
}
heard() { since $1 | grep -c "⚡ фраза на NPU" || true; }

if echo "$res" | grep -q -E "поднят|готов"; then
  say "2. подача 8 фраз — сверка с процессором, потом NPU"
  wav=$(feed npu-feed1 8 0); m=$(( $(lines) + 1 )); open "--es feedonly 1 --es feedwav $wav"
  r=$(wait_log $m "⚡ NPU( проверен| отказал| остановлен)" 180)
  wait_log $m "▷ подача закончена" 120 > /dev/null
  since $m | grep "⚡" | cut -c1-300 | sed 's/^/   журнал: /'
  case "$r" in
    *проверен*) say "   ✓ сверка прошла; на NPU распознано фраз: $(heard $m) (журнал пишет первые три)";;
    *) say "   ✗ сверка: ${r:-за 3 минуты ни «проверен», ни «отказал»}";;
  esac
  [ -n "$(sh "pidof $PKG:npu")" ] && say "   ✓ процесс NPU работает" || say "   ✗ процесса NPU нет"
fi

say "3. перезапуск приложения"
wait_unlock
sh "am force-stop $PKG"; sleep 1; m1=$(( $(lines) + 1 ))
open "--es feedonly 1"
r2=$(wait_log $m1 "⚡ NPU( на этой версии и прошивке уже отказал| отказал| поднят| готов| остановлен|: граф не уместился)" 180)
since $m1 | grep "⚡" | cut -c1-400 | sed 's/^/   журнал: /'
case "$res" in
  *отказал*) echo "$r2" | grep -q "уже отказал" && say "   ✓ отказ помнится до новой версии" || say "   ✗ после перезапуска: ${r2:-ничего}";;
  *поднят*|*готов*)
    echo "$r2" | grep -q "уже проверен" && say "   ✓ поднят из сохранённого графа, уже проверен" || say "   ✗ после перезапуска: ${r2:-ничего}"
    wav=$(feed npu-feed2 4 4); m=$(( $(lines) + 1 )); open "--es feedonly 1 --es feedwav $wav"
    wait_log $m "▷ подача закончена" 120 > /dev/null
    n=$(heard $m); [ "$n" -gt 0 ] && say "   ✓ после перезапуска фразы сразу на NPU ($n)" || say "   ✗ после перезапуска на NPU ни одной фразы"
    since $m | grep "⚡" | cut -c1-300 | sed 's/^/   журнал: /';;
esac
open "--es feedonly 0 --es keepscreen 0"
say "журнал — $LOG"
