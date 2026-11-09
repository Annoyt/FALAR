#!/bin/bash
# Своя сборка дополнения ONNX Runtime для NeuroPilot (onnxruntime-ep-neuropilot v0.3.0, MIT © Microsoft) с нашими
# правками (*.patch рядом): int16.patch — пропускать симметричные 16-битные активации (A16W8, TENSOR_QUANT16_SYMM);
# в выпуске v0.3.0 их отсекали три проверки в открытой части, а сам Neuron такие типы знает.
#
# Исходники — архивы в .stand/mtk-ep/src (в репозиторий не кладём): ep/ep-v0.3.0.tar.gz, protobuf-cpp-3.21.12,
# onnx e709452 (v1.18.0), GSL 4.0.0 — те же версии, что тянет его CMakeLists (FETCHCONTENT_SOURCE_DIR_*, без git).
# Заголовки ORT 1.29 — из onnxruntime-android-qnn-1.29.0.aar (headers/). Закрытая libneuron_op_capability.so —
# из выпуска v0.3.0 (.stand/mtk-ep/v0.3.0). NDK r27d. Тяжёлое — под .stand/heavy.lock, 3 потока (PC перегревается).
#   bash bench/npu/neuropilot-ep/build.sh            # → .stand/mtk-ep/v0.3.0-int16/{libonnxruntime_providers_neuropilot.so, libneuron_op_capability.so}
set -e
H=$(cd "$(dirname "$0")" && pwd)
P=/home/not_me/antigravity-workspace/agentstuff/agenttranslator; E=$P/.stand/mtk-ep
NDK=${ANDROID_NDK_HOME:-/home/not_me/tools/android-ndk-r27d}
W=$E/work; OUT=$E/v0.3.0-int16; JOBS=${JOBS:-3}
rm -rf $W/src && mkdir -p $W/src $OUT
tar -xzf $E/src/ep/ep-v0.3.0.tar.gz -C $W/src
S=$W/src/onnxruntime-ep-neuropilot-0.3.0
for p in $H/*.patch; do (cd $W/src && patch -p1 --directory=onnxruntime-ep-neuropilot-0.3.0 < $p >/dev/null) && echo "правка: $(basename $p)"; done
flock $P/.stand/heavy.lock nice -n 10 bash -c "
  cmake -S $S -B $W/build -G 'Unix Makefiles' -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE=$S/cmake/toolchain-android.cmake -DANDROID_NDK_HOME=$NDK \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
    -DORT_INCLUDE_DIR=$E/ort-headers -DNEURON_OP_CAPABILITY_LIB_DIR=$E/v0.3.0 \
    -DFETCHCONTENT_FULLY_DISCONNECTED=ON \
    -DFETCHCONTENT_SOURCE_DIR_PROTOBUF=$E/src/protobuf/protobuf-3.21.12 \
    -DFETCHCONTENT_SOURCE_DIR_ONNX=$E/src/onnx/onnx-e709452ef2bbc1d113faf678c24e6d3467696e83 \
    -DFETCHCONTENT_SOURCE_DIR_GSL=$E/src/gsl/GSL-4.0.0 > $W/configure.log 2>&1 \
  && cmake --build $W/build -j $JOBS > $W/build.log 2>&1"
SO=$(find $W/build -name libonnxruntime_providers_neuropilot.so | head -1)
[ -s "$SO" ] || { echo "сборка не дала .so — $W/build.log"; tail -20 $W/build.log; exit 1; }
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded -o $OUT/libonnxruntime_providers_neuropilot.so $SO
cp $E/v0.3.0/libneuron_op_capability.so $OUT/
ls -la $OUT
