#!/bin/bash
# Библиотеки для сборки с NPU (bench/apk/build.sh NPU=1) — в .stand/qnn-android, не в репозиторий:
#   ort/  — ONNX Runtime 1.29.0 с QNN для Android (Microsoft, Maven com.microsoft.onnxruntime:onnxruntime-android-qnn,
#           MIT): libonnxruntime.so вместо обычного и его libonnxruntime4j_jni.so; 9,4 МБ архив.
#   qnn/  — QNN 2.42 из публичного QAIRT SDK 2.42.0.251225 (Qualcomm, лицензия AI Stack License: распространять только
#           внутри своего приложения). Не весь SDK (1,54 ГБ): zip_peek.py читает по сети оглавление и 15 нужных файлов
#           (~58 МБ передачи, 155 МБ на диске) + LICENSE.pdf и NOTICE.txt. Версия — та же, что у qnn-runtime 2.42.0,
#           от которого зависит onnxruntime-android-qnn 1.29.0.
# Скачивание — только с разрешения владельца (правило проекта: что, откуда, сколько).
set -e
P=/home/not_me/antigravity-workspace/agentstuff/agenttranslator; Q=${QNN_DIR:-$P/.stand/qnn-android}
W=$(cd "$(dirname "$0")/../.." && pwd)
V=2.42.0.251225
SDK="https://apigwx-aws.qualcomm.com/qsc/public/v1/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/$V/v$V.zip"
AAR="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/1.29.0/onnxruntime-android-qnn-1.29.0.aar"
mkdir -p $Q/ort $Q/qnn $Q/tmp
# ONNX Runtime с QNN: из AAR только две .so (Python читает zip в памяти, ничего не исполняет)
curl -sfL --max-time 300 "$AAR" -o $Q/tmp/ort-qnn.aar
python3 -I - "$Q/tmp/ort-qnn.aar" "$Q/ort" <<'PY'
import sys, zipfile, pathlib
z = zipfile.ZipFile(sys.argv[1]); out = pathlib.Path(sys.argv[2])
for n in ("jni/arm64-v8a/libonnxruntime.so", "jni/arm64-v8a/libonnxruntime4j_jni.so"):
    (out / pathlib.Path(n).name).write_bytes(z.read(n)); print(n, "→", out)
PY
rm -f $Q/tmp/ort-qnn.aar
# QNN: только библиотеки Android и HTP v68…v81
L=qairt/$V/lib
python3 -I $W/bench/npu/zip_peek.py get "$SDK" --to $Q/qnn \
  $L/aarch64-android/libQnnHtp.so $L/aarch64-android/libQnnSystem.so $L/aarch64-android/libQnnHtpPrepare.so \
  $L/aarch64-android/libQnnHtpV68Stub.so $L/aarch64-android/libQnnHtpV69Stub.so $L/aarch64-android/libQnnHtpV73Stub.so \
  $L/aarch64-android/libQnnHtpV75Stub.so $L/aarch64-android/libQnnHtpV79Stub.so $L/aarch64-android/libQnnHtpV81Stub.so \
  $L/hexagon-v68/unsigned/libQnnHtpV68Skel.so $L/hexagon-v69/unsigned/libQnnHtpV69Skel.so \
  $L/hexagon-v73/unsigned/libQnnHtpV73Skel.so $L/hexagon-v75/unsigned/libQnnHtpV75Skel.so \
  $L/hexagon-v79/unsigned/libQnnHtpV79Skel.so $L/hexagon-v81/unsigned/libQnnHtpV81Skel.so \
  qairt/$V/LICENSE.pdf qairt/$V/NOTICE.txt
pdftotext -layout $Q/qnn/LICENSE.pdf $Q/qnn/AI-Stack-License.txt
grep -q "AI Stack License" $Q/qnn/AI-Stack-License.txt || { echo "LICENSE.pdf — не AI Stack License, проверить"; exit 1; }
rmdir $Q/tmp 2>/dev/null || true
ls -la $Q/ort $Q/qnn
