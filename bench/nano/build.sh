#!/bin/bash
# Сборка стенда Gemini Nano (bench/nano/NanoProbe.apk) без Gradle, как и сам Falar:
# зависимости ML Kit — fetch_deps.py в ~/.cache/falar-stand/jars/nano; ресурсы AAR — aapt2 (R-классы всех
# пакетов через --extra-packages); код — javac + D8 (multidex); META-INF/services из jar — в APK
# (без них ServiceLoader не найдёт, например, Dispatchers.Main из kotlinx-coroutines-android).
set -e
A=$(cd "$(dirname "$0")" && pwd); R=$A/../..; AJ=$R/tools/android.jar; R8=$R/tools/r8.jar
J=~/.cache/falar-stand/jars/nano; OUT=$A/out
[ -f $J/deps.txt ] || python3 $A/fetch_deps.py
rm -rf $OUT; mkdir -p $OUT/aar $OUT/flat $OUT/gen $OUT/classes $OUT/dex $OUT/meta
CPS=(); PKGS=(); RES=()
while read -r f; do
  case "$f" in
    *.aar)
      d=$OUT/aar/${f%.aar}; mkdir -p $d; (cd $d && unzip -q -o $J/$f)
      [ -f $d/classes.jar ] && CPS+=($d/classes.jar)
      for l in $d/libs/*.jar; do [ -f "$l" ] && CPS+=("$l"); done
      PKGS+=($(grep -o 'package="[^"]*"' $d/AndroidManifest.xml | head -1 | cut -d'"' -f2))
      if [ -d $d/res ] && [ -n "$(ls -A $d/res)" ]; then
        aapt2 compile --dir $d/res -o $OUT/flat/${f%.aar}.zip
        RES+=(-R $OUT/flat/${f%.aar}.zip)
      fi;;
    *.jar) CPS+=($J/$f);;
  esac
done < $J/deps.txt
aapt2 link -o $OUT/base.apk --manifest $A/AndroidManifest.xml -I $AJ --java $OUT/gen --auto-add-overlay \
  --extra-packages "$(IFS=:; echo "${PKGS[*]}")" --min-sdk-version 26 --target-sdk-version 35 "${RES[@]}"
CP=$(IFS=:; echo "${CPS[*]}")
javac --release 11 -nowarn -encoding UTF-8 -cp "$AJ:$CP" -d $OUT/classes $(find $A/src $OUT/gen -name '*.java')
java -cp $R8 com.android.tools.r8.D8 --release --min-api 26 --lib $AJ --output $OUT/dex \
  $(find $OUT/classes -name '*.class') "${CPS[@]}" 2>&1 | grep -v -E '^Warning|^\s*$' | head -20 || true
ls $OUT/dex/classes*.dex >/dev/null
# META-INF/services: одноимённые файлы разных jar склеиваются
for j in "${CPS[@]}"; do
  for s in $(unzip -Z1 "$j" 2>/dev/null | grep -E '^META-INF/services/[^/]+$'); do
    mkdir -p $OUT/meta/META-INF/services; unzip -p "$j" "$s" >> $OUT/meta/$s; echo >> $OUT/meta/$s
  done
done
cp $OUT/base.apk $OUT/unaligned.apk
(cd $OUT/dex && zip -q $OUT/unaligned.apk classes*.dex)
[ -d $OUT/meta/META-INF ] && (cd $OUT/meta && zip -q -r $OUT/unaligned.apk META-INF)
zipalign -f 4 $OUT/unaligned.apk $OUT/aligned.apk
apksigner sign --ks $R/tools/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android \
  --key-pass pass:android --out $A/NanoProbe.apk $OUT/aligned.apk
ls -la $A/NanoProbe.apk; unzip -l $A/NanoProbe.apk | grep -E 'classes.*dex|META-INF/services' | awk '{print $1, $4}'
