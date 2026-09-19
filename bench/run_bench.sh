#!/data/data/com.termux/files/usr/bin/bash
# Замеры на POCO X7 Pro (Dimensity 8400 Ultra) в Termux.
# Цель: заменить экстраполяции в PLAN.md реальными числами. ~30 минут.
#
#   pkg install git cmake clang wget python
#   bash run_bench.sh
#
# Все числа PLAN.md — оценки с соседнего кремния. Ни одного публичного
# бенчмарка llama.cpp/whisper.cpp на этом SoC не существует.
set -u
BENCH_DIR="${BENCH_DIR:-$HOME/agenttranslator-bench}"
THREADS="${THREADS:-4}"   # только большие ядра: 1x3.25 + 3x3.0 ГГц
mkdir -p "$BENCH_DIR" && cd "$BENCH_DIR"
RESULTS="$BENCH_DIR/results-$(date +%Y%m%d-%H%M%S).txt"
log(){ echo "$*" | tee -a "$RESULTS"; }

log "=== agenttranslator bench ==="
log "date: $(date -Iseconds)"
log "threads: $THREADS"
log ""
log "--- CPU ---"
grep -m1 -E 'Hardware|Processor' /proc/cpuinfo 2>/dev/null | tee -a "$RESULTS"
# КРИТИЧНО: нужны asimddp (dotprod) и i8mm. Без i8mm KleidiAI падает на медленный путь.
log "features: $(grep -m1 '^Features' /proc/cpuinfo | cut -d: -f2-)"
log "cores: $(nproc)"
log "mem: $(grep MemTotal /proc/meminfo)"
log ""

# ---------------------------------------------------------------- llama.cpp
# Про квант: измерено 2026-09-10 (D1100, официальная android-сборка без KleidiAI) —
# Q4_0 быстрее Q4_K_M только на префилле (1.5-1.65x), на генерации разницы нет.
# Сборка ниже включает KleidiAI, чтобы проверить, меняет ли это картину на i8mm-чипе.
log "--- llama.cpp (KleidiAI, Q4_0) ---"
if [ ! -d llama.cpp ]; then
  git clone --depth 1 https://github.com/ggml-org/llama.cpp || log "!! клон не удался"
fi
if [ -d llama.cpp ] && [ ! -x llama.cpp/build/bin/llama-bench ]; then
  ( cd llama.cpp && cmake -B build \
      -DGGML_CPU_KLEIDIAI=ON -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
      -DCMAKE_C_FLAGS="-march=armv9-a+dotprod+fp16+i8mm" \
      -DCMAKE_CXX_FLAGS="-march=armv9-a+dotprod+fp16+i8mm" \
      -DLLAMA_CURL=OFF -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build -j"$(nproc)" --target llama-bench ) || log "!! сборка не удалась"
fi
if [ -x llama.cpp/build/bin/llama-bench ]; then
  # Q4_0 сборка Hy-MT2-1.8B (переводческая модель) — при отсутствии подставь любую 1B Q4_0.
  MODEL="${MODEL:-$BENCH_DIR/mt-q4_0.gguf}"
  if [ ! -f "$MODEL" ]; then
    log "нет $MODEL — скачай Q4_0 GGUF, например:"
    log "  wget -O $MODEL https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/<Q4_0-файл>"
    log "  (официальный Q4_K_M = 1 133 080 448 B; для KleidiAI нужен именно Q4_0)"
  else
    # -p 20 -n 30 = реальная форма нагрузки: короткая фраза на входе, короткий перевод на выходе.
    log "$(llama.cpp/build/bin/llama-bench -m "$MODEL" -t "$THREADS" -p 20,512 -n 30,128 2>&1 | tail -25)"
  fi
  # Проверь, что в выводе видны i8mm/dotprod: llama.cpp исторически их не детектит (issue #10662).
fi
log ""

# ---------------------------------------------------------------- whisper.cpp
# Только как база сравнения для ASR. Для непрерывного слушания whisper непригоден:
# ничего не отдаёт до конца сегмента (discussion #3567).
log "--- whisper.cpp (база сравнения ASR) ---"
if [ ! -d whisper.cpp ]; then
  git clone --depth 1 https://github.com/ggml-org/whisper.cpp || log "!! клон не удался"
fi
if [ -d whisper.cpp ] && [ ! -x whisper.cpp/build/bin/whisper-bench ]; then
  ( cd whisper.cpp && cmake -B build -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_C_FLAGS="-march=armv9-a+dotprod+fp16+i8mm" \
      -DCMAKE_CXX_FLAGS="-march=armv9-a+dotprod+fp16+i8mm" \
    && cmake --build build -j"$(nproc)" --target whisper-bench ) || log "!! сборка не удалась"
fi
if [ -x whisper.cpp/build/bin/whisper-bench ]; then
  for M in base small; do
    F="whisper.cpp/models/ggml-$M.bin"
    [ -f "$F" ] || ( cd whisper.cpp && bash ./models/download-ggml-model.sh "$M" ) >/dev/null 2>&1
    [ -f "$F" ] && log "-- whisper $M --" && log "$(whisper.cpp/build/bin/whisper-bench -m "$F" -t "$THREADS" 2>&1 | tail -12)"
  done
fi
log ""

# ---------------------------------------------------------------- термика
# Все цифры выше сняты на холодном устройстве. 4нм средний класс троттлит
# за 15–25 минут; в установившемся режиме ждать -25..40%.
log "--- термика после нагрузки ---"
for z in /sys/class/thermal/thermal_zone*/; do
  t=$(cat "$z/temp" 2>/dev/null) || continue
  n=$(cat "$z/type" 2>/dev/null)
  [ -n "$t" ] && [ "$t" -gt 1000 ] && log "  $n: $((t/1000))C"
done
log ""
log "результаты: $RESULTS"
log ""
log "ГЛАВНЫЙ НЕЗАМЕРЕННЫЙ ВОПРОС (см. PLAN.md §10):"
log "  nemotron-3.5-asr-streaming-0.6b int8 @560ms — единственная true-streaming"
log "  модель, покрывающая и pt-BR (5.48% WER), и ru-RU (9.17%) одним чекпойнтом."
log "  RTF < 0.5  -> выкидываем оба движка ASR, получаем пословный португальский."
log "  RTF > 0.7  -> отбрасываем, оставляем двухмодельную схему."
log "  Тарбол: sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11.tar.bz2"
log "  ARM-бенчмарки не публиковал никто. Померить это надо первым."
