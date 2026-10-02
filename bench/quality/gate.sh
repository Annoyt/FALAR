#!/bin/bash
# Проверка качества распознавания и перевода: свежий прогон против эталона в bench/quality/baseline.
#
#   bash bench/quality/gate.sh                     # на ПК: перевод и распознавание
#   bash bench/quality/gate.sh --device            # плюс полный путь распознавания на телефоне
#   bash bench/quality/gate.sh --ocr-device        # плюс чтение снимков на телефоне (test_ocr_device.sh O2)
#   bash bench/quality/gate.sh --accept [--device] # прогнать и принять за эталон — закоммитить с правкой
#   bash bench/quality/gate.sh --release v0.27.0   # из tools/release.sh: ПК всегда; телефон — если с
#                                                  # прошлого выпуска менялось распознавание
#
# Владелец, 02.10.2026: «тесты надо автоматизировать и применять в процессе наших изменений в
# распознавании и переводе». Что проверяется:
#   перевод, ПК — Engine.translate, тот самый код приложения, на настольной JVM (bench/quality/MtRun.java)
#     с моделями models/mt, сверенными с манифестом: ~1000 фраз Tatoeba на направление и 40 из ситуаций;
#     chrF, chrF++ и COMET (results/2026-10-02-mt-metrics.md). COMET досчитывается только для
#     изменившихся переводов (кэш), так что прогон без изменений — около 4 минут;
#   распознавание, ПК — та же модель parakeet с теми же настройками, что Engine.asr, на 162 живых
#     записях Tatoeba (bench/quality/asr_pc.py), WER;
#   настольные тесты приложения — bench/apk/test.sh целиком: логика, в том числе чтения снимков со
#     сверкой с эталоном tools/ocr_ref.py (golden пересобирается, если устарел); не прошли — стоп;
#   чтение снимков, ПК — модели приложения (models/ocr) на наборе bench/ocr: 32 вывески, 831 слово
#     (tools/ocr_eval.py --models); доля слов и CER строк по снимкам, ~30 с;
#   чтение снимков, телефон — приложение читает тот же набор (test_ocr_device.sh O2, ~3 мин): если с
#     прошлого выпуска менялись Ocr*, TextRules, словарь правки слов или манифест моделей;
#   распознавание, телефон — запись комнаты подаётся вместо микрофона через всё слушание приложения
#     (bench/air/gain_sweep.sh: чувствительность авто с ограничителем, шумодав нарезки «только при шуме» —
#     как по умолчанию), WER по фразам; под замком стенда, телефон рабочий.
# Вердикт — bench/quality/verdict.py: без изменений / не хуже / лучше / хуже (значимо и не меньше
# порога). Код выхода 1 — хуже эталона. Эталон ПК — bench/quality/baseline (в git), эталон телефона —
# ~/.cache/falar-stand/quality-baseline (записи комнаты личные). Журналы и результаты —
# ~/.cache/falar-stand/quality/<время>/.
# Всё тело — в фигурных скобках: bash прочтёт его целиком до запуска, и правка файла посреди прогона
# (02.10 так сбился прогон) не подсунет ему середину строки.
{
set -u
R=$(cd "$(dirname "$0")/../.." && pwd); Q=$R/bench/quality; A=$R/bench/apk; BASE=$Q/baseline
STATE=${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand
RUN=$STATE/quality/$(date +%Y%m%d-%H%M%S); mkdir -p "$RUN"
# Эталон телефона — вне git, рядом с записями комнаты: записи личные (bench/air/rec не в репозитории),
# и услышанное в них тоже не выкладываем. Нет эталона — первый прогон на телефоне его и создаёт.
DBASE=$STATE/quality-baseline
# Рабочие деревья (falar-wt/*) держат модели и окружения не целиком: чего нет здесь, берётся из
# основного дерева репозитория. Манифест — всегда этого дерева: сверяется то, что уедет из него.
MAIN=$(cd "$(git -C "$R" rev-parse --git-common-dir)/.." && pwd)
pick() { [ -e "$R/$1" ] && echo "$R/$1" || echo "$MAIN/$1"; }
# Снимки набора, записи комнаты и модели чтения не в git, а tools/ocr_*.py, test_ocr_device.sh и
# gain_sweep.sh берут их по путям своего дерева, мимо pick(): чего нет в рабочем дереве — ссылка из
# основного (в .gitignore, git её не видит).
for d in bench/ocr/photos bench/ocr/runs/cyl-down bench/air/rec models/ocr models/ocr-cand; do
  [ -e "$R/$d" ] || [ ! -e "$MAIN/$d" ] || { mkdir -p "$(dirname "$R/$d")"; ln -s "$MAIN/$d" "$R/$d"; }
done
PY=$(pick .venv)/bin/python; CPY=$(pick .venv-comet)/bin/python; ADB=${ADB:-$(pick tools/platform-tools)/adb}
MODELS=$R/models; for m in mt asr_multi ocr; do [ -d "$MODELS/$m" ] || MODELS=$MAIN/models; done; export FALAR_MODELS=$MODELS
COMET_CKPT=$(pick models/comet/wmt22-comet-da/checkpoints/model.ckpt)
RECS=${RECS:-near-pt near-ru far-pt noisy-pt}
DEVICE=0; OCRDEV=0; ACCEPT=0; RELEASE=
while [ $# -gt 0 ]; do
  case "$1" in --device) DEVICE=1; shift;; --ocr-device) OCRDEV=1; shift;; --accept) ACCEPT=1; shift;; --release) RELEASE=$2; shift 2;;
    *) echo "не знаю ключ: $1"; exit 2;; esac
done
say() { printf '%s\n' "$*" | tee -a "$RUN/gate.log"; }
# Телефон свободен: экран погашен — или впереди Falar либо рабочий стол, и экрана не касались минуту
# (как idle() в bench/apk/test_all_device.sh): телефон рабочий, его могут держать в руках.
phone_idle() {
  $ADB shell dumpsys power 2>/dev/null | grep -qE 'mWakefulness=(Asleep|Dozing)' && return 0
  case "$($ADB shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)" in *app.falar*|*com.miui.home*|*launcher*) ;; *) return 1;; esac
  local a; a=$($ADB shell dumpsys power 2>/dev/null | tr -d '\r' | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
  [ "${a:-0}" -ge 60000 ]
}
# Мерить надо код этого дерева, а не то, что стоит на телефоне: 02.10 там стояла сборка другой ветки, а
# сверялась одна versionName. Отладочная сборка HEAD ставится поверх (install -r, данные приложения
# целы), один раз за прогон — для распознавания и снимков на телефоне. Не встала — например, стоит
# релизная с GitHub с другой подписью — проверка останавливается: удалять приложение ради неё нельзя.
# Звать под замком стенда.
INSTALLED=0
install_head() {
  [ $INSTALLED = 1 ] && return 0
  bash "$A/build.sh" > "$RUN/build.log" 2>&1 || fail "сборка для телефона не собралась: $RUN/build.log"
  local n=0 was res
  until phone_idle; do n=$((n+1)); [ $n = 1 ] && say "  жду, пока телефон свободен: экран погашен или впереди Falar/рабочий стол и минуту не трогали"; sleep 15; done
  was=$($ADB shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)
  res=$($ADB install --no-incremental -r "$A/Falar.apk" 2>&1 | grep -E '^(Success|Failure)')
  [ "$res" = Success ] || fail "сборка этого дерева не встала на телефон: ${res:-нет ответа} (другая подпись? поставьте отладочную сборку вручную)"
  case "$was" in *app.falar*) $ADB shell "am start -n app.falar/dev.agenttranslator.MainActivity" > /dev/null 2>&1;; esac
  INSTALLED=1
  say "  телефон: поставлена сборка этого дерева ($(git -C "$R" rev-parse --short HEAD)$(git -C "$R" diff --quiet || echo ', с незакоммиченными правками'))"
}
fail() { say "ОШИБКА: $*"; exit 2; }
t0=$(date +%s); since() { echo "$(( $(date +%s) - $1 )) с"; }
say "== проверка качества · $(git -C "$R" rev-parse --short HEAD) · $RUN"

# ---- что менялось с прошлого выпуска: телефон нужен, только если трогали слушание или модели
if [ -n "$RELEASE" ]; then
  CH=$(git -C "$R" diff --name-only "$RELEASE"..HEAD) || fail "нет тега $RELEASE"
  if printf '%s\n' "$CH" | grep -qE '^bench/apk/src/dev/agenttranslator/(TranslatorService|Engine|DenoiseGate|Gain|Hearing)\.java$|^models/manifest\.json$'; then
    DEVICE=1; say "  с $RELEASE менялось слушание или модели — нужен и этап на телефоне"
  fi
  if printf '%s\n' "$CH" | grep -qE '^bench/apk/src/dev/agenttranslator/(Ocr|OcrCore|OcrWords|TextRules)\.java$|^data/ocr_words|^models/manifest\.json$'; then
    OCRDEV=1; say "  с $RELEASE менялось чтение снимков или модели — нужно и чтение снимков на телефоне"
  fi
fi

# ---- зависимости
[ -x "$PY" ] || fail "нет $PY (окружение .venv, см. README)"
ORTV=$(ls "$A"/libs/onnxruntime-*-classes.jar 2>/dev/null | sed -n 's/.*onnxruntime-\(.*\)-classes\.jar/\1/p' | head -1)
[ -n "$ORTV" ] || fail "не нашёл версию ONNX Runtime приложения (bench/apk/libs/onnxruntime-*-classes.jar)"
ORT=$STATE/jars/onnxruntime-$ORTV.jar
if [ ! -f "$ORT" ]; then   # та же версия, что в приложении, но с библиотеками для ПК; сверка по sha1 Maven Central
  U=https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/$ORTV/onnxruntime-$ORTV.jar
  mkdir -p "$STATE/jars"; say "  скачиваю onnxruntime-$ORTV.jar (~55 МБ, Maven Central)"
  curl -sSL -o "$ORT.part" "$U" && [ "$(sha1sum < "$ORT.part" | cut -d' ' -f1)" = "$(curl -sSL "$U.sha1")" ] \
    && mv "$ORT.part" "$ORT" || { rm -f "$ORT.part"; fail "onnxruntime-$ORTV.jar не скачался или не сошёлся sha1"; }
fi
J=$(pick tools/json.jar); AJ=$(pick tools/android.jar)
[ -f "$J" ] || { J=$R/tools/json.jar; curl -sSL -o "$J" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar; }
[ -f "$AJ" ] || fail "нет tools/android.jar — откуда взять, в bench/apk/README.md"
$PY "$Q/models_check.py" "$R/models/manifest.json" "$MODELS" mt/ asr_multi/ ocr/ | tee -a "$RUN/gate.log"
[ "${PIPESTATUS[0]}" = 0 ] || fail "модели в models/ не те, что в манифесте: проверка мерила бы не то, что уедет на телефон (tools/models_fetch.py)"

# ---- настольные тесты приложения: логика, в том числе чтения снимков. Эталон для OcrCoreTest и
# OcrWordsTest (bench/ocr/runs/golden) — данные tools/ocr_ref.py, не в git: нет его или эталонный код
# новее — собирается заново (~15 с), иначе тесты молча пропустили бы сверку.
t=$(date +%s); G=$R/bench/ocr/runs/golden
if [ ! -f "$G/words.tsv" ] || [ -n "$(find "$R/tools/ocr_ref.py" "$R/tools/ocr_golden.py" "$R/tools/ocr_words.py" -newer "$G/words.tsv" 2>/dev/null)" ]; then
  [ -d "$R/bench/ocr/photos" ] || fail "нет bench/ocr/photos — снимки набора скачивает python3 tools/ocr_photos.py"
  say "  эталон для OcrCoreTest и OcrWordsTest собираю заново (tools/ocr_golden.py, tools/ocr_words.py golden)"
  { $PY "$R/tools/ocr_golden.py" && $PY "$R/tools/ocr_words.py" golden; } > "$RUN/golden.log" 2>&1 \
    || fail "эталон для настольных тестов не собрался: $RUN/golden.log"
fi
if ! bash "$A/test.sh" > "$RUN/test.log" 2>&1; then
  grep -E 'ПРОВАЛ|провал' "$RUN/test.log" | head -20 | tee -a "$RUN/gate.log"
  fail "настольные тесты не прошли: $RUN/test.log"
fi
grep -E 'пропущен' "$RUN/test.log" | tee -a "$RUN/gate.log"
say "  настольные тесты: наборов $(grep -c 'все прошли' "$RUN/test.log"), все прошли · $(since $t)"

# ---- перевод: код приложения на столе
t=$(date +%s); C=$RUN/classes; mkdir -p "$C"
javac --release 11 -nowarn -encoding UTF-8 -cp "$ORT:$J:$AJ" -sourcepath "$A/src" -d "$C" \
  "$A/src/dev/agenttranslator/Engine.java" "$A"/sherpa-java-api/*.java "$Q/MtRun.java" > "$RUN/javac.log" 2>&1 \
  || fail "код перевода не собрался на столе: $RUN/javac.log"
for d in pt2ru ru2pt; do
  java -cp "$C:$ORT:$J:$AJ" dev.agenttranslator.MtRun "$MODELS" $d "$RUN/mt_$d.json" \
    "$R/data/mt_test/tatoeba.json=tatoeba" "$R/data/test_set.json=situations" 2>> "$RUN/mt.log" \
    || fail "перевод $d упал: $RUN/mt.log"
done
if [ -x "$CPY" ] && [ -f "$COMET_CKPT" ]; then
  "$CPY" "$R/tools/mt_metrics.py" --threads 4 "$RUN/mt_pt2ru.json" "$RUN/mt_ru2pt.json" 2>> "$RUN/mt.log" | tee -a "$RUN/gate.log"
  [ "${PIPESTATUS[0]}" = 0 ] || fail "COMET упал: $RUN/mt.log (ПК с андервольтом под нагрузкой — повторите)"
else
  say "  COMET не посчитан: нет .venv-comet или модели (как поставить — в начале tools/mt_metrics.py)"
  $PY "$R/tools/mt_metrics.py" --no-comet "$RUN/mt_pt2ru.json" "$RUN/mt_ru2pt.json" | tee -a "$RUN/gate.log"
fi
say "  перевод: $(since $t)"

# ---- распознавание на ПК: та же модель
t=$(date +%s)
$PY "$Q/asr_pc.py" "$RUN/asr_pc.json" 2>> "$RUN/asr.log" | tee -a "$RUN/gate.log"
[ "${PIPESTATUS[0]}" = 0 ] || fail "распознавание на ПК упало: $RUN/asr.log"
say "  распознавание на ПК: $(since $t)"

# ---- чтение снимков на ПК: модели приложения на наборе вывесок
t=$(date +%s)
$PY "$R/tools/ocr_eval.py" --models "$MODELS/ocr" --json "$RUN/ocr_pc.json" --save "$RUN/ocr_ref" 2>> "$RUN/ocr.log" | tee -a "$RUN/gate.log"
[ "${PIPESTATUS[0]}" = 0 ] || fail "чтение снимков на ПК упало: $RUN/ocr.log"
say "  чтение снимков на ПК: $(since $t)"

# ---- распознавание на телефоне: всё слушание приложения
STAGES="mt asr ocr"
if [ $DEVICE = 1 ]; then
  if [ "$($ADB get-state 2>/dev/null)" != device ]; then
    say "  телефон не подключён: полный путь распознавания не проверен"
    if [ -n "$RELEASE" ]; then
      printf 'выпустить без проверки на телефоне? [y/N] ' > /dev/tty; read -r ans < /dev/tty
      [ "$ans" = y ] || { say "остановлено: подключите телефон и повторите"; exit 1; }
    fi
  else
    for r in $RECS; do [ -f "$R/bench/air/rec/$r/room.wav" ] || fail "нет записи комнаты bench/air/rec/$r (они личные и в git не лежат)"; done
    t=$(date +%s)
    exec 8>"$STATE/lock"
    flock -n 8 || { say "  жду замок стенда (на телефоне идёт другая проверка)…"; flock -w 3600 8 || fail "замок стенда занят больше часа"; }
    install_head
    say "  телефон: записи $RECS"
    FALAR_STAND_LOCK=1 bash "$R/bench/air/gain_sweep.sh" --rec "$RECS" --gains auto --modes limit --vaddn auto > "$RUN/device.log" 2>&1
    GS=$?
    exec 8>&-
    # Прерванный или неполный прогон вердикта не даёт: 02.10 после kill gain_sweep сравнились три записи из
    # четырёх, и вышло «не хуже» с p = 0,050.
    [ $GS = 0 ] || fail "прогон на телефоне прерван (gain_sweep, код $GS): $RUN/device.log"
    D=$(sed -n 's/^прогоны: //p' "$RUN/device.log" | tail -1)
    [ -n "$D" ] || fail "прогон на телефоне не дал результата: $RUN/device.log"
    $PY "$Q/asr_device.py" "$D" "$RUN/asr_device.json" --expect "$RECS" | tee -a "$RUN/gate.log"
    case "${PIPESTATUS[0]}" in
      0) ;;
      3) fail "прогон на телефоне недействителен — приложение сбросило стендовые режимы посреди прогона: $RUN/device.log";;
      4) fail "прогон на телефоне неполный — не все записи дали результат: $RUN/device.log";;
      *) fail "прогон на телефоне не разобрался: $RUN/device.log";;
    esac
    STAGES="$STAGES device"
    if [ ! -f "$DBASE/asr_device.json" ]; then
      mkdir -p "$DBASE"; cp "$RUN/asr_device.json" "$DBASE/"
      say "  эталона на телефоне не было — создан из этого прогона ($DBASE)"
    fi
    say "  распознавание на телефоне: $(since $t)"
  fi
fi

# ---- чтение снимков на телефоне: то же приложение, тот же набор (test_ocr_device.sh, только O2)
if [ $OCRDEV = 1 ]; then
  if [ "$($ADB get-state 2>/dev/null)" != device ]; then
    say "  телефон не подключён: чтение снимков на телефоне не проверено"
    if [ -n "$RELEASE" ]; then
      printf 'выпустить без проверки чтения снимков на телефоне? [y/N] ' > /dev/tty; read -r ans < /dev/tty
      [ "$ans" = y ] || { say "остановлено: подключите телефон и повторите"; exit 1; }
    fi
  else
    t=$(date +%s)
    exec 8>"$STATE/lock"
    flock -n 8 || { say "  жду замок стенда (на телефоне идёт другая проверка)…"; flock -w 3600 8 || fail "замок стенда занят больше часа"; }
    install_head          # холодный старт после установки не страшен: ocrbench сам ждёт моделей до 2 минут
    say "  телефон: чтение набора bench/ocr"
    # эталон строка в строку — свежий, из этапа ПК этого же прогона: сохранённый bench/ocr/runs/ref
    # снят 28.09 старым эталонным кодом и с тех пор разошёлся с ним
    ONLY=O2 OUT="$RUN/ocr_device" OCRREF="$RUN/ocr_ref" FALAR_STAND_LOCK=1 bash "$A/test_ocr_device.sh" > "$RUN/ocr_device.log" 2>&1
    exec 8>&-
    grep -E '^(PASS|FAIL|ПРОПУСК) O2' "$RUN/ocr_device.log" | sed 's/^/  /' | tee -a "$RUN/gate.log"
    for f in ocr_device.json ocr_device_png.json; do [ -f "$RUN/ocr_device/$f" ] && cp "$RUN/ocr_device/$f" "$RUN/"; done
    [ -f "$RUN/ocr_device.json" ] || fail "чтение снимков на телефоне не дало результата: $RUN/ocr_device.log"
    STAGES="$STAGES ocr-device"
    # Эталон — только из прогона, где O2 прошла: иначе эталоном стал бы сбой (02.10 прочитано 0 из 32)
    if grep -qE '^FAIL O2' "$RUN/ocr_device.log"; then
      [ -f "$DBASE/ocr_device.json" ] || fail "чтение снимков на телефоне не прошло O2, а эталона нет — сначала разобраться: $RUN/ocr_device.log"
    elif [ ! -f "$DBASE/ocr_device.json" ]; then
      mkdir -p "$DBASE"; cp "$RUN/ocr_device.json" "$DBASE/"
      say "  эталона чтения снимков на телефоне не было — создан из этого прогона ($DBASE)"
    fi
    say "  чтение снимков на телефоне: $(since $t)"
  fi
fi

# ---- вердикт
say ""
$PY "$Q/verdict.py" --base "$BASE" --device-base "$DBASE" --run "$RUN" --stages "$STAGES" --report "$RUN/report.md" | tee -a "$RUN/gate.log"
V=${PIPESTATUS[0]}
say "всего: $(since $t0) · журнал: $RUN/gate.log"
if [ $ACCEPT = 1 ]; then
  mkdir -p "$BASE" "$DBASE"
  for f in mt_pt2ru.json mt_ru2pt.json asr_pc.json ocr_pc.json; do [ -f "$RUN/$f" ] && cp "$RUN/$f" "$BASE/$f"; done
  [ -f "$RUN/asr_device.json" ] && cp "$RUN/asr_device.json" "$DBASE/"
  [ -f "$RUN/ocr_device.json" ] && ! grep -qE '^FAIL O2' "$RUN/ocr_device.log" && cp "$RUN/ocr_device.json" "$DBASE/"
  say "эталон обновлён из этого прогона — закоммитьте bench/quality/baseline вместе с правкой"
  exit 0
fi
[ $V = 1 ] && say "ХУЖЕ эталона. Исправить — или, если ухудшение осознанное, принять: bash bench/quality/gate.sh --accept и закоммитить эталон."
exit $V
}
