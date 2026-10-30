# libsherpa-onnx-jni.so с уверенностью распознавателя

В Java API sherpa-onnx нет вероятностей токенов (`ys_log_probs`): их нет ни в 1.13.8 (наша сборка), ни в master на
27.10.2026. А в C++ они есть, и на ПК (Python) пометка сомнительных слов по ним проходит критерии
(results/2026-10-27-asr-confidence.md). Поэтому мост JNI собран заново из тех же исходников v1.13.8 с одной правкой:
`sherpa-onnx-1.13.8-ys-log-probs.patch`.

Правка в `getResult`: если у Java-класса `OfflineRecognizerResult` есть конструктор с восемью аргументами, восьмым
передаются `ys_log_probs`; если нет — прежний конструктор с семью. Сборка подходит и к старым Java-обёрткам.

Как собрано (27.10.2026, ПК владельца):
- NDK r27d (27.3.13750724), ONNX Runtime 1.28.2 для Android — как у v1.13.8 по умолчанию;
- `build-android-arm64-v8a.sh` с `make -j2` вместо `-j4`: андервольт ПК, лишняя нагрузка опасна;
- `patchelf --replace-needed libonnxruntime.so libonnxruntime_sherpa.so libsherpa-onnx-jni.so`: в приложении ORT
  sherpa переименована, чтобы ужиться с ORT 1.29 перевода (bench/apk/README.md, «Как две ORT ужились в одном процессе»).

```bash
git clone --depth 1 --branch v1.13.8 https://github.com/k2-fsa/sherpa-onnx
cd sherpa-onnx && git apply …/sherpa-onnx-1.13.8-ys-log-probs.patch
sed 's/^make -j4$/make -j2/' build-android-arm64-v8a.sh > build-falar.sh
ANDROID_NDK=…/android-ndk-r27d bash build-falar.sh
patchelf --replace-needed libonnxruntime.so libonnxruntime_sherpa.so build-android-arm64-v8a/install/lib/libsherpa-onnx-jni.so
```

Итог 27.10: sha256 начинается на 6c428468827f8698, размер 4 860 929 байт. Прежний — e6ad967b9875ba7b, 4 795 441 байт.
Проверен на телефоне 30.10 (Redmi, `bench/apk/test_wordmarks_device.sh`): распознаватель отдаёт уверенность, доля
сомнительных слов — как на ПК (5,1 % против 4,4 % на near-pt). С 30.10 лежит в общем `bench/apk/jni`; прежний сохранён
в проекте, вне git (`third_party/falar-jni/libsherpa-onnx-jni.so.orig`; исходники и сборка — `third_party/sherpa-onnx-1.13.8`). Запасной путь — Java-класс без восьмого поля (ветки до
0.35) — на телефоне не проверялся, только по коду.
