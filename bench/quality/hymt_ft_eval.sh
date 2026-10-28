#!/bin/bash
# Дообученная Hy-MT2 (tools/colab) против исходной, сжатой тем же путём, — шаг 7 results/2026-10-27-asr-confidence.md:
# сквозной тест «речь → перевод» (bench/quality/e2e_mt.sh) и наборы проверки качества (Tatoeba, ситуации).
# Настоящие фразы владельца — отдельно, локально, вне репозитория.
#
#   bench/quality/hymt_ft_eval.sh ИСХОДНАЯ.gguf ДООБУЧЕННАЯ.gguf
#
# Переводы — на видеокарте ПК (POCO_PROFILE=poco-gpu, CUDA-сборка llama.cpp в PYTHONPATH — см. mt_bench.py --gpu),
# COMET — FALAR_COMET_PY (с видеокартой — быстрее) или .venv-comet. На ПК с андервольтом запускать под общим замком
# тяжёлых прогонов: flock ~/.cache/falar-stand/heavy.lock bench/quality/hymt_ft_eval.sh …
set -u
[ $# = 2 ] || { echo "нужно: ИСХОДНАЯ.gguf ДООБУЧЕННАЯ.gguf"; exit 2; }
R=$(cd "$(dirname "$0")/../.." && pwd)
MAIN=$(cd "$(git -C "$R" rev-parse --git-common-dir)/.." && pwd)
pick() { [ -e "$R/$1" ] && echo "$R/$1" || echo "$MAIN/$1"; }
PY=$(pick .venv)/bin/python; CPY=${FALAR_COMET_PY:-$(pick .venv-comet)/bin/python}
BASE=$(realpath "$1"); FT=$(realpath "$2")
OUT=$R/results; E=$R/results/asr_conf/e2e/runs
export POCO_PROFILE=${POCO_PROFILE:-poco-gpu} E2E_GPU=${E2E_GPU:-1}

# сквозной тест: распознанное и сказанное, у каждой модели — свой прогон (E2E_TAG)
for tag in base ft; do
  m=$([ $tag = base ] && echo "$BASE" || echo "$FT")
  FALAR_HYMT_GGUF=$m E2E_TAG=$tag "$R/bench/quality/e2e_mt.sh" hymt > "$E/hymt_ft_eval_$tag.log" 2>&1 \
    || { echo "сквозной тест ($tag) упал: $E/hymt_ft_eval_$tag.log"; exit 1; }
done

# наборы проверки качества: простая подсказка, как mt_bench hymt
files=()
for tag in base ft; do
  m=$([ $tag = base ] && echo "$BASE" || echo "$FT")
  for d in pt2ru ru2pt; do
    o=$OUT/mt_hymt-$tag-colab_$d.json
    [ -f "$o" ] || (cd "$R" && FALAR_HYMT_GGUF=$m taskset -c 0 $PY mt_bench.py --engine hymt --direction $d --gpu \
      --no-comet --resume --out "$o") > "$OUT/mt_hymt-$tag-colab_$d.log" 2>&1 \
      || { echo "перевод $tag $d упал: $OUT/mt_hymt-$tag-colab_$d.log"; exit 1; }
    files+=("$o")
  done
done
taskset -c 0 "$CPY" "$R/tools/mt_metrics.py" "${files[@]}" > "$OUT/mt_hymt-colab_comet.log" 2>&1 \
  || { echo "COMET упал: $OUT/mt_hymt-colab_comet.log"; exit 1; }

echo "== 1. Сквозной тест: дообученная против исходной (B − A), распознанное и сказанное, цена слуха"
for d in pt2ru ru2pt; do
  for s in "" .clean; do $PY "$R/tools/e2e_compare.py" --pooled "$E/hymt-base_$d$s.json" "$E/hymt-ft_$d$s.json"; done
  $PY "$R/tools/e2e_compare.py" --gap "$E/hymt-base_$d.clean.json" "$E/hymt-base_$d.json" "$E/hymt-ft_$d.clean.json" "$E/hymt-ft_$d.json"
done
echo "== 2. Наборы проверки качества: дообученная против исходной"
for d in pt2ru ru2pt; do
  "$CPY" "$R/tools/mt_metrics.py" --compare "$OUT/mt_hymt-base-colab_$d.json" "$OUT/mt_hymt-ft-colab_$d.json"
done
