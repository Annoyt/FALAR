#!/bin/bash
# libsherpa-onnx-jni.so без синтеза речи (с 0.38.0): sherpa-onnx v1.13.8 с той же правкой уверенности распознавателя
# (sherpa-onnx-1.13.8-ys-log-probs.patch), но SHERPA_ONNX_ENABLE_TTS=OFF. Тогда в мост не собираются ни eSpeak NG
# (GPL-3.0), ни piper-phonemize, ни код Piper/VITS: озвучку с 0.38.0 делает синтез телефона (SysTts), а GPL и
# закрытые библиотеки Qualcomm не могут лежать в одном APK (решение владельца 10.11).
#
#   bash tools/sherpa-jni/build-notts.sh        # ~/tools/android-ndk-r27d; итог — third_party/falar-jni/notts/
#
# Исходники — third_party/sherpa-onnx-1.13.8 (уже с правкой, см. README). Зависимости (_deps/*-src) берутся из прежней
# сборки build-android-arm64-v8a, сеть не нужна (FETCHCONTENT_FULLY_DISCONNECTED). Нагрузка — make -j2 (андервольт
# ПК); запускать под замком и сторожем: flock .stand/heavy.lock .stand/thermal_guard.sh bash …
set -e
R=$(cd "$(git -C "$(dirname "$0")" rev-parse --path-format=absolute --git-common-dir)/.." && pwd)
S=$R/third_party/sherpa-onnx-1.13.8; OLD=$S/build-android-arm64-v8a; B=$S/build-android-arm64-v8a-notts
NDK=${ANDROID_NDK:-$HOME/tools/android-ndk-r27d}; PATCHELF=${PATCHELF:-$HOME/tools/venv-patchelf/bin/patchelf}
OUT=$R/third_party/falar-jni/notts
[ -d "$NDK" ] || { echo "нет NDK: $NDK"; exit 1; }
grep -q ys_log_probs $S/sherpa-onnx/jni/offline-recognizer.cc || { echo "в $S нет правки ys_log_probs — git apply tools/sherpa-jni/*.patch"; exit 1; }
mkdir -p $B/_deps
for d in $OLD/_deps/*-src; do
  n=$(basename $d); case $n in espeak_ng-src|piper_phonemize-src) continue;; esac
  [ -d $B/_deps/$n ] || cp -a $d $B/_deps/
done
[ -d $B/1.28.2 ] || cp -a $OLD/1.28.2 $B/
cd $B
ORT=$B/1.28.2
# ONNX Runtime для сборки — через окружение, как в build-android-arm64-v8a.sh (cmake/onnxruntime.cmake читает ENV)
export SHERPA_ONNXRUNTIME_LIB_DIR=$ORT/jni/arm64-v8a/ SHERPA_ONNXRUNTIME_INCLUDE_DIR=$ORT/headers/
cmake -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DFETCHCONTENT_FULLY_DISCONNECTED=ON \
  -DSHERPA_ONNX_ENABLE_TTS=OFF -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON -DSHERPA_ONNX_ENABLE_BINARY=OFF \
  -DBUILD_PIPER_PHONMIZE_EXE=OFF -DBUILD_PIPER_PHONMIZE_TESTS=OFF -DBUILD_ESPEAK_NG_EXE=OFF -DBUILD_ESPEAK_NG_TESTS=OFF \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DSHERPA_ONNX_ENABLE_PYTHON=OFF -DSHERPA_ONNX_ENABLE_TESTS=OFF \
  -DSHERPA_ONNX_ENABLE_CHECK=OFF -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF -DSHERPA_ONNX_ENABLE_JNI=ON -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF \
  -DSHERPA_ONNX_ENABLE_C_API=OFF -DCMAKE_INSTALL_PREFIX=./install -DANDROID_ABI="arm64-v8a" -DANDROID_PLATFORM=android-21 \
  -DSHERPA_ONNX_ENABLE_RKNN=OFF -DSHERPA_ONNX_ENABLE_QNN=OFF \
  .. > cmake.log 2>&1 || { tail -30 cmake.log; exit 1; }
make -j${JOBS:-2} > make.log 2>&1 || { tail -40 make.log; exit 1; }
make install/strip >> make.log 2>&1
so=$B/install/lib/libsherpa-onnx-jni.so
# ORT sherpa в приложении переименована (уживается с ORT 1.29 перевода) — как у прежнего моста
$PATCHELF --replace-needed libonnxruntime.so libonnxruntime_sherpa.so $so
# Проверка: ни строк, ни символов eSpeak/Piper в мосте. «espeak» — только отдельным словом: в «OfflineSpeaker» и
# «wespeaker» те же буквы, но перед ними буква. Счёт строк, а не grep -q: с pipefail ранний выход grep ломал бы проверку.
bad=$(strings -a $so | grep -i -E '(^|[^a-z])espeak|piper' | wc -l)
[ "$bad" = 0 ] || { echo "в мосте нашлось eSpeak/Piper:"; strings -a $so | grep -i -E '(^|[^a-z])espeak|piper' | head; exit 1; }
sym=$(nm -D $so | grep -i -E '(^|[^a-z])espeak|piper|OfflineTts' | wc -l)
[ "$sym" = 0 ] || { echo "в мосте остались символы eSpeak/Piper/OfflineTts"; exit 1; }
nm -D --defined-only $so | grep -q 'Java_com_k2fsa_sherpa_onnx_OfflineRecognizer' || { echo "в мосте нет распознавателя"; exit 1; }
mkdir -p $OUT && cp $so $OUT/
readelf -d $OUT/libsherpa-onnx-jni.so | grep NEEDED
echo "итог: $OUT/libsherpa-onnx-jni.so · $(stat -c %s $OUT/libsherpa-onnx-jni.so) байт · sha256 $(sha256sum $OUT/libsherpa-onnx-jni.so | cut -c1-64)"
