#!/bin/bash
# Сборка APK без Gradle: javac + D8 + aapt2 link + zip + zipalign + apksigner.
set -e
A=$(cd "$(dirname "$0")" && pwd); R=$A/../..; AJ=$R/tools/android.jar; R8=$R/tools/r8.jar; OUT=$A/out
rm -rf $OUT && mkdir -p $OUT/classes $OUT/stage/lib/arm64-v8a
# Ресурсы: иконка запуска (адаптивная, с монохромным слоем) и значок уведомления. Компилируются
# первыми, потому что link порождает R.java (пакет app.falar из манифеста), который нужен javac.
aapt2 compile --dir $A/res -o $OUT/res.zip
# Манифест моделей вшивается в APK ресурсом (assets/models_manifest.json): приложение качает по нему
# само. Сборка падает, если версия манифеста не равна версии приложения или файлы из репозитория
# (repo_path: затравка, корпус, частотные слова) разошлись с хэшами — иначе телефон качал бы не то,
# что проверено. Обновление: поправить app и sha256/size в models/manifest.json, выложить tools/models_publish.sh.
python3 - "$A/AndroidManifest.xml" "$R/models/manifest.json" "$R" <<'PY'
import hashlib, json, os, re, sys
am, mp, root = sys.argv[1:4]
ver = re.search(r'versionName="([^"]+)"', open(am, encoding="utf-8").read()).group(1)
m = json.load(open(mp, encoding="utf-8"))
if m.get("app") != ver: sys.exit(f"models/manifest.json: app={m.get('app')} не равно versionName={ver}")
for e in m["files"]:
    if not e.get("repo_path"): continue
    p = os.path.join(root, e["repo_path"]); h = hashlib.sha256(open(p, "rb").read()).hexdigest()
    if h != e["sha256"] or os.path.getsize(p) != e["size"]: sys.exit(f"{e['repo_path']} разошёлся с манифестом ({e['path']})")
PY
mkdir -p $OUT/assets && cp $R/models/manifest.json $OUT/assets/models_manifest.json
aapt2 link -o $OUT/base.apk --manifest $A/AndroidManifest.xml -I "$AJ" --java $OUT/gen -A $OUT/assets --min-sdk-version 28 --target-sdk-version 33 $OUT/res.zip
javac --release 11 -nowarn -cp "$AJ:$A/libs/onnxruntime-1.29.0-classes.jar" -d $OUT/classes $A/src/dev/agenttranslator/*.java $A/sherpa-java-api/*.java $OUT/gen/app/falar/R.java
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
cp $OUT/base.apk $OUT/unaligned.apk; (cd $OUT/stage && zip -q -r ../unaligned.apk classes.dex lib)
zipalign -f 4 $OUT/unaligned.apk $OUT/aligned.apk
# Подпись. По умолчанию отладочный ключ из репозитория — для стенда. Для публичной сборки:
#   FALAR_KEYSTORE=~/falar-release.keystore FALAR_KS_PASS=... bash bench/apk/build.sh
# Пароли передаются через окружение (apksigner понимает env:ИМЯ), а не аргументом: аргумент виден
# в списке процессов любому на машине. Ключ и пароль в репозиторий не попадают никогда; сменить
# ключ после первого публичного релиза нельзя без переустановки у всех, кто поставил.
OUTAPK=$A/Falar${SLIM:+-slim}.apk
if [ -n "$FALAR_KEYSTORE" ]; then
  [ -f "$FALAR_KEYSTORE" ] || { echo "нет файла ключа: $FALAR_KEYSTORE"; exit 1; }
  [ -n "$FALAR_KS_PASS" ] || { echo "нужен FALAR_KS_PASS (пароль хранилища)"; exit 1; }
  export FALAR_KS_PASS FALAR_KEY_PASS=${FALAR_KEY_PASS:-$FALAR_KS_PASS}
  apksigner sign --ks "$FALAR_KEYSTORE" --ks-key-alias "${FALAR_KEY_ALIAS:-falar}" \
    --ks-pass env:FALAR_KS_PASS --key-pass env:FALAR_KEY_PASS --out $OUTAPK $OUT/aligned.apk
  KIND="релизный ключ"
else
  apksigner sign --ks $R/tools/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $OUTAPK $OUT/aligned.apk
  KIND="ОТЛАДОЧНЫЙ ключ, не для раздачи"
fi
ls -la $OUTAPK | awk -v k="$KIND" '{print "APK:",$5/1048576,"MB",$NF,"·",k}'
sha256sum $OUTAPK | awk '{print "sha256:",$1}'
