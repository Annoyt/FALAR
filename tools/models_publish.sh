#!/bin/bash
# Выкладывает НАШИ производные файлы в репозиторий моделей на Hugging Face — те, у которых в
# models/manifest.json source.repo = own_repo. Чужие модели (parakeet, Piper, CAM++, Hy-MT2) не
# перевыкладываются: приложение и tools/models_fetch.py берут их из исходных репозиториев.
#
# Токен берётся из окружения hf (после `hf auth login`), в скрипт и в репозиторий не попадает.
#   bash tools/models_publish.sh            # залить
#   bash tools/models_publish.sh --dry-run  # только показать, что уйдёт
set -e
R=$(cd "$(dirname "$0")/.." && pwd); M=$R/models
REPO=$(python3 -c "import json;print(json.load(open('$M/manifest.json'))['own_repo'])")
STAGE=$(mktemp -d)
mkdir -p $STAGE/mt/pt2ru $STAGE/mt/ru2pt
for d in pt2ru ru2pt; do
  cp $M/mt/$d/encoder_model.onnx $M/mt/$d/decoder_model.onnx $M/mt/$d/decoder_with_past_model.onnx $STAGE/mt/$d/
  cp $M/mt/$d/${d}_source_pieces.tsv $M/mt/$d/${d}_vocab.json $STAGE/mt/$d/
done
(cd $M && zip -q -r -X $STAGE/tts_ru.zip tts_ru && zip -q -r -X $STAGE/tts_pt.zip tts_pt)
cp $R/bench/apk/phrasebook_seed.json $STAGE/phrasebook.json
cp $R/data/tatoeba/phrasebook_tatoeba.tsv $R/data/common_words.txt $STAGE/
cp $M/manifest.json $STAGE/manifest.json
cat > $STAGE/README.md <<MD
---
license: cc-by-4.0
language: [pt, ru]
tags: [translation, onnx, opus-mt, falar]
---
# falar-models

Производные файлы офлайн-переводчика pt-BR ↔ ru Falar (бывший AgentTranslator). Не веса, а форматы:
- \`mt/*\`: экспорт Helsinki-NLP opus-mt-tc-big-pt-zle и -zle-pt в ONNX int8 (optimum) и токенизатор — CC-BY-4.0, авторство Helsinki-NLP / OPUS-MT;
- \`tts_ru.zip\`, \`tts_pt.zip\`: голоса Piper dmitri и faber из тарболов sherpa-onnx, перепакованы в zip — CC0;
- \`phrasebook_tatoeba.tsv\`, \`common_words.txt\`: корпус фраз и частотный словарь, добытые из Tatoeba — CC-BY 2.0 FR;
- \`phrasebook.json\`: затравка разговорника проекта;
- \`manifest.json\`: по нему приложение и \`tools/models_fetch.py\` собирают полный набор, включая модели из исходных репозиториев.
MD
du -sh $STAGE; find $STAGE -type f | sed "s|$STAGE/||"
if [ "$1" = "--dry-run" ]; then rm -rf $STAGE; exit 0; fi
hf repo create "$REPO" --type model 2>/dev/null || true
hf upload "$REPO" "$STAGE" . --commit-message "models for Falar $(python3 -c "import json;print(json.load(open('$M/manifest.json'))['app'])")"
rm -rf $STAGE
