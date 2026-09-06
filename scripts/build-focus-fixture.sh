#!/bin/bash
# Test-only APK with a separate UID for real Android audio-focus arbitration.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME}"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
BT="$ANDROID_HOME/build-tools/35.0.0"
API="$ANDROID_HOME/platforms/android-35/android.jar"
BUILD="$ROOT/build/focus-fixture"
mkdir -p "$BUILD/classes" "$BUILD/dex"
"$BT/aapt2" link -o "$BUILD/base.apk" -I "$API" --manifest "$ROOT/tests/focus-player/AndroidManifest.xml"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -bootclasspath "$API:$BT/core-lambda-stubs.jar" -d "$BUILD/classes" "$ROOT/tests/focus-player/PlayerActivity.java"
"$JAVA_HOME/bin/jar" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BT/d8" --lib "$API" --min-api 26 --output "$BUILD/dex" "$BUILD/classes.jar"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -u "$BUILD/unsigned.apk" classes*.dex)
"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
"$BT/apksigner" sign --ks "$ROOT/build/development.p12" --ks-key-alias unisono --ks-pass pass:android --out "$BUILD/focus-fixture.apk" "$BUILD/aligned.apk"
echo "$BUILD/focus-fixture.apk"
