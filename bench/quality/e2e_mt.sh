#!/bin/bash
# Сквозной тест «речь → перевод» (results/2026-10-27-asr-confidence.md, шаг 6): записи комнаты → parakeet (кэш
# tools/asr_confidence.py) → перевод → COMET и chrF против перевода от людей из Tatoeba (tools/e2e_set.py).
# Перевод приложения — его же код на настольной JVM (MtRun, луч 4), другие модели — mt_bench.py. Каждый — дважды:
# на распознанном и на сказанном (потолок без ошибок слуха). Сравнение — tools/e2e_compare.py.
#
#   bench/quality/e2e_mt.sh [движок mt_bench …]          например: bench/quality/e2e_mt.sh hymt
#
# E2E_GPU=1 — движки mt_bench на видеокарте (нужны POCO_PROFILE=poco-gpu и для llama.cpp PYTHONPATH на сборку с
# CUDA, см. mt_bench.py); FALAR_COMET_PY — окружение COMET (с видеокартой — быстрее). Готовые прогоны не
# пересчитываются. Выход — results/asr_conf/e2e/runs/ (вне git). На ПК с андервольтом — одно ядро (E2E_CORE, по
# умолчанию 0), COMET — одним процессом; долгий прогон — под ~/.cache/falar-stand/thermal_guard.sh.
set -u
R=$(cd "$(dirname "$0")/../.." && pwd); Q=$R/bench/quality; A=$R/bench/apk
MAIN=$(cd "$(git -C "$R" rev-parse --git-common-dir)/.." && pwd)
pick() { [ -e "$R/$1" ] && echo "$R/$1" || echo "$MAIN/$1"; }
STATE=${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand
PY=$(pick .venv)/bin/python; CPY=${FALAR_COMET_PY:-$(pick .venv-comet)/bin/python}
MODELS=$R/models; [ -d "$MODELS/mt" ] || MODELS=$MAIN/models
ORTV=$(ls "$A"/libs/onnxruntime-*-classes.jar 2>/dev/null | sed -n 's/.*onnxruntime-\(.*\)-classes\.jar/\1/p' | head -1)
ORT=$STATE/jars/onnxruntime-$ORTV.jar
[ -f "$ORT" ] || { echo "нет $ORT — его скачивает bench/quality/gate.sh"; exit 1; }
J=$(pick tools/json.jar); AJ=$(pick tools/android.jar)
E=$R/results/asr_conf/e2e; OUT=$E/runs; mkdir -p "$OUT"
CORE=${E2E_CORE:-0}
PT="near-pt far-pt noisy-pt nearbg45-pt nearbg40-pt st-laptop-near-pt st-laptop-end-pt st-laptop-bottom-pt"
RU="near-ru far-ru nearbg40-ru st-laptop-near-ru st-laptop-end-ru st-laptop-bottom-ru"
recs() { [ "$1" = pt2ru ] && echo $PT || echo $RU; }

taskset -c $CORE $PY "$R/tools/e2e_set.py" $PT $RU || exit 1

# перевод приложения: его код на столе, одно ядро
C=$OUT/classes; mkdir -p "$C"
javac --release 11 -nowarn -encoding UTF-8 -cp "$ORT:$J:$AJ" -sourcepath "$A/src" -d "$C" \
  "$A/src/dev/agenttranslator/Engine.java" "$A"/sherpa-java-api/*.java "$Q/MtRun.java" > "$OUT/javac.log" 2>&1 \
  || { echo "MtRun не собрался: $OUT/javac.log"; exit 1; }
files=()
for d in pt2ru ru2pt; do for s in "" .clean; do
  o=$OUT/app_$d$s.json; sets=(); for r in $(recs $d); do sets+=("$E/$r$s.json=$r"); done
  if [ ! -f "$o" ]; then
    taskset -c $CORE java -Dfalar.threads=1 -cp "$C:$ORT:$J:$AJ" dev.agenttranslator.MtRun "$MODELS" $d "$o.part" "${sets[@]}" \
      2>> "$OUT/mt.log" && mv "$o.part" "$o" || { echo "MtRun $d$s упал: $OUT/mt.log"; exit 1; }
  fi
  files+=("$o")
done; done

# другие модели — mt_bench
for eng in "$@"; do for d in pt2ru ru2pt; do for s in "" .clean; do
  o=$OUT/${eng}_$d$s.json; sf=(); for r in $(recs $d); do sf+=(--setfile "$E/$r$s.json=$r"); done
  if [ ! -f "$o" ]; then
    (cd "$R" && taskset -c $CORE $PY mt_bench.py --engine $eng --direction $d "${sf[@]}" --no-comet --resume --out "$o" \
      ${E2E_GPU:+--gpu}) >> "$OUT/mt.log" 2>&1 || { echo "mt_bench $eng $d$s упал: $OUT/mt.log"; exit 1; }
  fi
  files+=("$o")
done; done; done

# COMET оценивает перевод сказанного: исходник — true; потом один процесс COMET на все прогоны
$PY "$R/tools/e2e_compare.py" --fix "${files[@]}" > /dev/null || exit 1
taskset -c $CORE "$CPY" "$R/tools/mt_metrics.py" "${files[@]}" > "$OUT/comet.log" 2>&1 || { echo "COMET упал: $OUT/comet.log"; exit 1; }

echo "== цена слуха: перевод приложения распознанного против сказанного"
for d in pt2ru ru2pt; do $PY "$R/tools/e2e_compare.py" "$OUT/app_$d.clean.json" "$OUT/app_$d.json"; done
for eng in "$@"; do
  echo "== $eng против приложения: на распознанном, на сказанном, цена слуха"
  for d in pt2ru ru2pt; do
    for s in "" .clean; do $PY "$R/tools/e2e_compare.py" --pooled "$OUT/app_$d$s.json" "$OUT/${eng}_$d$s.json"; done
    $PY "$R/tools/e2e_compare.py" --gap "$OUT/app_$d.clean.json" "$OUT/app_$d.json" "$OUT/${eng}_$d.clean.json" "$OUT/${eng}_$d.json"
  done
done
