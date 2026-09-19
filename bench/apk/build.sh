#!/bin/bash
# Сборка APK без Gradle: javac + D8 + aapt2 link + zip + zipalign + apksigner.
set -e
A=$(cd "$(dirname "$0")" && pwd); R=$A/../..; AJ=$R/tools/android.jar; R8=$R/tools/r8.jar; OUT=$A/out
rm -rf $OUT && mkdir -p $OUT/classes $OUT/stage/lib/arm64-v8a
javac --release 11 -nowarn -cp "$AJ:$A/libs/onnxruntime-1.29.0-classes.jar" -d $OUT/classes $A/src/dev/agenttranslator/*.java $A/sherpa-java-api/*.java
java -cp $R8 com.android.tools.r8.D8 --release --min-api 28 --lib "$AJ" --output $OUT/stage $(find $OUT/classes -name '*.class') $A/libs/onnxruntime-1.29.0-classes.jar 2>&1 | grep -vE 'warning|Warning|^Note' || true
# SLIM=1 — сборка без llama.cpp: это 228 МБ из 285 ради необязательного контекстного уточнителя.
# Приложение без него работает, переключатель «🧠 контекст» просто сообщает об ошибке.
if [ -n "$SLIM" ]; then
  for f in $A/jni/arm64-v8a/*.so; do case "$(basename $f)" in libllama*|libggml*|libmtmd*) ;; *) cp $f $OUT/stage/lib/arm64-v8a/;; esac; done
else
  cp $A/jni/arm64-v8a/*.so $OUT/stage/lib/arm64-v8a/
fi
# Версию берём только из манифеста: здесь оставались --version-code 10 --version-name 1.0 от
# старой нумерации, и сборка расходилась бы с тем, что записано в проекте.
aapt2 link -o $OUT/base.apk --manifest $A/AndroidManifest.xml -I "$AJ" --min-sdk-version 28 --target-sdk-version 33
cp $OUT/base.apk $OUT/unaligned.apk; (cd $OUT/stage && zip -q -r ../unaligned.apk classes.dex lib)
zipalign -f 4 $OUT/unaligned.apk $OUT/aligned.apk
apksigner sign --ks $R/tools/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $A/AgentTranslator${SLIM:+-slim}.apk $OUT/aligned.apk
ls -la $A/AgentTranslator${SLIM:+-slim}.apk | awk '{print "APK:",$5/1048576,"MB",$NF}'
