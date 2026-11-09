#!/bin/bash
# Своя сборка дополнения ONNX Runtime для NeuroPilot (onnxruntime-ep-neuropilot v0.3.0, MIT © Microsoft) с нашими
# правками (*.patch рядом, по порядку имён):
#   01-int16.patch — пропускать симметричные 16-битные активации (A16W8, TENSOR_QUANT16_SYMM): в v0.3.0 их отсекали
#     три проверки открытой части, а сам Neuron такие типы знает;
#   02-int16-cap.patch — у узлов с int16-квантованием не спрашивать закрытую libneuron_op_capability.so (у неё своя
#     копия тех же проверок, POCO 09.11): решает Neuron — дополнение пробно компилирует каждую часть графа;
#   03-int16-out.patch — выход квантованных MatMul/Gemm/Conv при int16 — TENSOR_QUANT16_SYMM (было всегда 8 бит:
#     A16W8 упирался в ±127, на POCO выход = знак × 127 × шкала).
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
# пути в правках — a/onnxruntime-ep-neuropilot-0.3.0/…: -p1 от каталога распаковки. Не легла — стоп (09.11 первые две
# сборки шли без правок: patch запускался не из того каталога, а сбой глотался в цепочке &&)
for p in $H/*.patch; do
  patch -p1 -d $W/src --forward < $p > $W/$(basename $p).log 2>&1 || { echo "правка $(basename $p) не легла:"; cat $W/$(basename $p).log; exit 1; }
  echo "правка: $(basename $p)"
done
n=$(grep -rl "Falar patch" $S/src | wc -l); [ "$n" -gt 0 ] || { echo "правок в исходниках нет"; exit 1; }
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
strings -n 8 $OUT/libonnxruntime_providers_neuropilot.so | grep -q "closed capability check skipped" || { echo "в .so нет правки 02-int16-cap"; exit 1; }
echo "файлов с правками: $n; sha256 $(sha256sum $OUT/libonnxruntime_providers_neuropilot.so | cut -c1-12)"
cp $E/v0.3.0/libneuron_op_capability.so $OUT/
ls -la $OUT
