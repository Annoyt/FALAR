#!/bin/bash
# Заливает модели в каталог приложения. APK их не содержит — только код.
# Все источники лежат в репозитории, в models/. Запускать после установки APK.
#
#   bash bench/apk/push_models.sh
#
# Обязательное не найдено — скрипт говорит об этом и продолжает, чтобы залить остальное;
# в конце печатает, чего не хватает. Приложение без обязательного не стартует.
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=dev.agenttranslator
DST=/sdcard/Android/data/$PKG/files/models
M=$R/models
MISSING=""

say() { printf '%s\n' "$*"; }
need() { MISSING="$MISSING\n  - $1 ($2)"; say "  НЕТ: $1 — $2"; }

# путь_источника  назначение  обязательность  откуда взять
push_dir() {   # каталог целиком
  if [ -d "$1" ]; then $ADB shell "mkdir -p $DST/$2" && $ADB push "$1/." "$DST/$2/" >/dev/null && say "  ок:  $2"
  elif [ "$3" = req ]; then need "$2" "$4"; else say "  нет (необязательно): $2"; fi
}
push_file() { # один файл
  if [ -f "$1" ]; then $ADB push "$1" "$DST/$2" >/dev/null && say "  ок:  $2"
  elif [ "$3" = req ]; then need "$2" "$4"; else say "  нет (необязательно): $2"; fi
}

$ADB wait-for-device

# Каталоги создаёт приложение, не adb. На Android 16 каталог, созданный shell, остаётся
# shell:ext_data_rw 0770 — приложение внутрь не входит и говорит «моделей нет». Файлы,
# наоборот, ложатся 0666 и читаются, поэтому руками делаются только каталоги.
# Список собираем здесь: приложение чужие пути читать не может, а свой files/ — может.
say "каталоги (создаёт приложение):"
LIST=$(mktemp)
for d in asr_multi mt/pt2ru mt/ru2pt tts_ru tts_pt speaker denoiser llm; do
  [ -d "$M/$d" ] || continue
  echo "models/$d" >> "$LIST"
  (cd "$M/$d" && find . -type d -not -name . | sed "s|^\./|models/$d/|") >> "$LIST"
done
sort -u "$LIST" -o "$LIST"
$ADB shell "rm -rf $DST" >/dev/null 2>&1            # старое дерево может быть shell'овым
$ADB push "$LIST" "$(dirname $DST)/dirs.txt" >/dev/null
$ADB shell am start -n $PKG/.MainActivity --es mkdirs 1 >/dev/null 2>&1
for _ in $(seq 30); do
  sleep 1
  $ADB shell "grep -c '📁 каталогов готово' $(dirname $DST)/at.log 2>/dev/null || echo 0" | tr -d '\r' | grep -qv '^0$' && break
done
say "  $($ADB shell "grep -h '📁 каталогов готово' $(dirname $DST)/at.log | tail -1" | tr -d '\r')"
rm -f "$LIST"

say "обязательное:"
push_file "$M/silero_vad.onnx" "silero_vad.onnx" req "models/manifest.json → tools/models_fetch.py"
push_dir  "$M/asr_multi" "asr_multi" req "HF csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 (encoder/decoder/joiner + tokens.txt)"
for d in pt2ru ru2pt; do
  push_file "$M/mt/$d/encoder_model.onnx"           "mt/$d/encoder_model.onnx" req "models/manifest.json → tools/models_fetch.py"
  push_file "$M/mt/$d/decoder_model.onnx"           "mt/$d/decoder_model.onnx" req "там же"
  push_file "$M/mt/$d/decoder_with_past_model.onnx" "mt/$d/decoder_with_past_model.onnx" req "там же"
  push_file "$M/mt/$d/${d}_source_pieces.tsv" "mt/$d/${d}_source_pieces.tsv" req "свой токенизатор: пьесы из source.spm"
  push_file "$M/mt/$d/${d}_vocab.json"        "mt/$d/${d}_vocab.json"        req "vocab.json модели Marian"
done
push_dir  "$M/tts_ru" "tts_ru" req "sherpa-onnx vits-piper-ru_RU-dmitri-medium (onnx + tokens.txt + espeak-ng-data)"
push_dir  "$M/tts_pt" "tts_pt" req "sherpa-onnx vits-piper-pt_BR-faber-medium"
push_file "$R/bench/apk/phrasebook_seed.json" "phrasebook.json" req "лежит в репозитории"

say "необязательное:"
push_dir  "$M/speaker"  "speaker"  opt   # отпечаток голоса; нет — разделение говорящих выключено
push_dir  "$M/denoiser" "denoiser" opt   # шумоподавитель; в конвейере выключен, см. results/2026-09-12-asr-upgrade.md
push_dir  "$M/llm"      "llm"      opt   # контекстный уточнитель; в slim-сборке всё равно не запустится
push_file "$R/data/tatoeba/phrasebook_tatoeba.tsv" "phrasebook_tatoeba.tsv" opt
push_file "$R/data/common_words.txt" "common_words.txt" opt
push_file "$M/openrouter.json" "openrouter.json" opt   # ключ и список :free-моделей для кнопки «получше»

say ""
$ADB shell "du -sh $DST; find $DST -maxdepth 2 -type f | wc -l"
if [ -n "$MISSING" ]; then printf '\nне залито обязательное:%b\n' "$MISSING"; exit 1; fi
