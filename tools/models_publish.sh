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
# С 0.23.0 перевод — encoder_kv_model.onnx (tools/mt_encoder_kv.py) и decoder_with_past_model.onnx.
# Прежние encoder_model.onnx и decoder_model.onnx в репозитории остаются — их качают сборки до 0.23;
# hf upload удалённые файлы, которых нет в выкладке, не трогает.
for d in pt2ru ru2pt; do
  cp $M/mt/$d/encoder_kv_model.onnx $M/mt/$d/decoder_with_past_model.onnx $STAGE/mt/$d/
  cp $M/mt/$d/${d}_source_pieces.tsv $M/mt/$d/${d}_vocab.json $STAGE/mt/$d/
done
(cd $M && zip -q -r -X $STAGE/tts_ru.zip tts_ru && zip -q -r -X $STAGE/tts_pt.zip tts_pt)
cp $R/bench/apk/phrasebook_seed.json $STAGE/phrasebook.json
cp $R/data/tatoeba/phrasebook_tatoeba.tsv $R/data/common_words.txt $STAGE/
cp $M/manifest.json $STAGE/manifest.json
# Модели распознавания на NPU (модуль npu, с 0.37.0; приложение качает только модель своего чипа): кодировщик parakeet
# для Snapdragon (bench/npu/app_model.py) и для MediaTek (bench/npu/mtk_quant.py, A16W8) и признаки nemo128 — из .stand
# основной копии проекта. Выкладывается ровно то, что в манифесте: размер и sha256 сверяются до выкладки.
MAIN=$(cd "$(git -C $R rev-parse --path-format=absolute --git-common-dir)/.." && pwd); NS=$MAIN/.stand/npu
mkdir -p $STAGE/npu
cp $NS/parakeet/nemo128.onnx $STAGE/npu/nemo128.onnx
cp $NS/app/parakeet-encoder-npu.onnx $STAGE/npu/encoder-npu.onnx
cp $NS/mtk/a16w8/encoder-npu.onnx $STAGE/npu/encoder-mtk.onnx
python3 - "$M/manifest.json" "$STAGE" <<'PY2'
import hashlib, json, os, sys
m = json.load(open(sys.argv[1], encoding="utf-8"))
for e in m["files"]:
    if e.get("module") != "npu": continue
    p = os.path.join(sys.argv[2], e["source"]["file"]); h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""): h.update(c)
    if os.path.getsize(p) != e["size"] or h.hexdigest() != e["sha256"]: sys.exit(f"{e['path']} разошёлся с манифестом — не выкладываю")
print("модели NPU сверены с манифестом")
PY2
cat > $STAGE/README.md <<MD
---
license: cc-by-4.0
language: [pt, ru]
tags: [translation, onnx, opus-mt, falar]
---
# falar-models

Производные файлы офлайн-переводчика pt-BR ↔ ru Falar (бывший AgentTranslator). Не веса, а форматы:
- \`mt/*\`: экспорт Helsinki-NLP opus-mt-tc-big-pt-zle и -zle-pt в ONNX int8 (optimum) и токенизатор — CC-BY-4.0, авторство Helsinki-NLP / OPUS-MT; с 0.23.0 приложение берёт \`encoder_kv_model.onnx\` (кодировщик вместе с K/V перекрёстного внимания из \`decoder_model.onnx\`) и \`decoder_with_past_model.onnx\`;
- \`tts_ru.zip\`, \`tts_pt.zip\`: голоса Piper dmitri и faber из тарболов sherpa-onnx, перепакованы в zip — CC0;
- \`phrasebook_tatoeba.tsv\`, \`common_words.txt\`: корпус фраз и частотный словарь, добытые из Tatoeba — CC-BY 2.0 FR;
- \`phrasebook.json\`: затравка разговорника проекта;
- \`npu/encoder-npu.onnx\`, \`npu/encoder-mtk.onnx\`: кодировщик nvidia/parakeet-tdt-0.6b-v3, квантованный для NPU Snapdragon (QDQ w8a16, Qualcomm AI Hub) и MediaTek (A16W8) — CC-BY-4.0, авторство NVIDIA; \`npu/nemo128.onnx\`: признаки NeMo из istupakov/onnx-asr — MIT, Copyright (c) 2025 Ilya Stupakov;
- \`manifest.json\`: по нему приложение и \`tools/models_fetch.py\` собирают полный набор, включая модели из исходных репозиториев.
MD
du -sh $STAGE; find $STAGE -type f | sed "s|$STAGE/||"
if [ "$1" = "--dry-run" ]; then rm -rf $STAGE; exit 0; fi
hf repo create "$REPO" --type model 2>/dev/null || true
hf upload "$REPO" "$STAGE" . --commit-message "models for Falar $(python3 -c "import json;print(json.load(open('$M/manifest.json'))['app'])")"
rm -rf $STAGE
