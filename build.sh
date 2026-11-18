#!/bin/bash
# VLC Cast v0.6.0 — build WITHOUT Gradle (same toolchain as mlite-sdr):
# kotlinc 1.4.31 + JDK 11, aapt2 + d8 + zipalign + apksigner from Android SDK 34.
# Java files (AirCastApp + CrashLog) are compiled FIRST with javac/JDK17:
# Kotlin code references CrashLog, so its .class must exist before kotlinc runs.
# Usage: ./build.sh [output-apk-name]
set -e
cd "$(dirname "$0")"

OUT="${1:-}" # v0.4.8: без аргумента — AirCast-<VERSION>.apk (раньше
# дефолт был захардкожен AirCast-v0.4.0-mirror.apk, и сборка v0.4.8
# без аргумента молча перезаписала старый файл v0.4.0 чужим содержимым)
JDK11=~/tools/jdk11
JDK17=~/tools/jdk17
SDK=~/android-sdk
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
KOTLINC=~/tools/kotlinc/bin/kotlinc
STDLIB=~/tools/kotlinc/lib/kotlin-stdlib.jar
export PATH="$JDK11/bin:$JDK17/bin:$PATH"
export JAVA_HOME="$JDK11"

echo "== resources =="
# v0.3.2: version-name — НЕ хардкод, а из MainActivity.VERSION
# (единственный источник истины: шапка приложения и --version-name
# больше не могут разъехаться, как в v0.3.1 — шапка показывала
# 0.3.0-command при коде 0.3.1). version-code — вручную, +1 на релиз.
VER_NAME=$(grep -o 'const val VERSION = "[^"]*"' app/src/main/java/com/alexey/aircast/MainActivity.kt | sed 's/.*"\([^"]*\)".*/\1/')
[ -n "$VER_NAME" ] || { echo "cannot extract VERSION from MainActivity.kt"; exit 1; }
echo "version: code=35 name=$VER_NAME"
[ -n "$OUT" ] || OUT="VLC-Cast-$VER_NAME.apk"
rm -rf build/gen build/classes build/dex build/stage build/compiled.zip
mkdir -p build/gen build/classes build/dex build/stage
"$BT/aapt2" compile --dir app/src/main/res -o build/compiled.zip
"$BT/aapt2" link -o build/app.unaligned.apk -I "$AJAR" \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 36 --version-name "$VER_NAME" \
  build/compiled.zip --java build/gen

echo "== java (AirCastApp + CrashLog: pure Java, no Kotlin runtime dependency) =="
find app/src/main/java -name '*.java' > build/java-sources.txt
"$JDK17/bin/javac" -encoding UTF-8 --release 8 -cp "$AJAR" -d build/classes @build/java-sources.txt > build/javac.log 2>&1
JAVAC_RC=$?
cat build/javac.log
if [ $JAVAC_RC -ne 0 ]; then echo "JAVAC FAILED (rc=$JAVAC_RC), see build/javac.log"; exit 1; fi

echo "== kotlin =="
find app/src/main/java -name '*.kt' > build/sources.txt
"$KOTLINC" -cp "$AJAR:build/classes" -jvm-target 1.8 -d build/classes @build/sources.txt > build/kotlinc.log 2>&1
KOTLIN_RC=$?
grep -v "illegal reflective" build/kotlinc.log | grep -v "Please consider" | grep -v "Use --illegal" | grep -v "^WARNING" || true
if [ $KOTLIN_RC -ne 0 ]; then echo "KOTLIN FAILED (rc=$KOTLIN_RC), see build/kotlinc.log"; exit 1; fi
jar cf build/classes.jar -C build/classes .

echo "== dex =="
"$BT/d8" --lib "$AJAR" --output build/dex build/classes.jar "$STDLIB"

echo "== assemble =="
rm -f build/app.unsigned.apk
cp build/app.unaligned.apk build/app.unsigned.apk
mkdir -p build/add
cp build/dex/classes.dex build/add/classes.dex
cd build/add
zip -q ../app.unsigned.apk classes.dex
cd ../..
"$BT/zipalign" -f 4 build/app.unsigned.apk build/app.aligned.apk

echo "== sign (test key only, NOT a release key) =="
if [ ! -f build/aircast.keystore ]; then
  keytool -genkeypair -alias aircast -keyalg RSA -keysize 2048 -validity 3650 \
    -storepass aircasttest -keypass aircasttest \
    -dname "CN=AirCast Test, OU=Test, O=Test, C=UA" \
    -keystore build/aircast.keystore
fi
"$BT/apksigner" sign --ks build/aircast.keystore --ks-pass pass:aircasttest --key-pass pass:aircasttest \
  --out "build/$OUT" build/app.aligned.apk
cp "build/$OUT" "./$OUT"
"$BT/apksigner" verify --verbose "./$OUT"
echo "BUILT: $OUT"
ls -l "./$OUT"
