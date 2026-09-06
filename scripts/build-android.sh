#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or newer}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
BT="$ANDROID_HOME/build-tools/35.0.0"
API="$ANDROID_HOME/platforms/android-35/android.jar"
BUILD="$ROOT/build/android"
mkdir -p "$BUILD/classes" "$BUILD/generated" "$BUILD/dex" "$ROOT/dist"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
APK="$ROOT/dist/Unisono-Android.apk"
if [ "${UNISONO_TEST:-0}" = "1" ]; then
  sed 's#</manifest>#<queries><package android:name="app.unisono.focusfixture" /></queries><instrumentation android:name="app.unisono.SmokeTest" android:targetPackage="app.unisono" /><instrumentation android:name="app.unisono.DeviceTest" android:targetPackage="app.unisono" /><instrumentation android:name="app.unisono.DebugTest" android:targetPackage="app.unisono" /></manifest>#' "$MANIFEST" > "$BUILD/test-manifest.xml"
  MANIFEST="$BUILD/test-manifest.xml"
  APK="$BUILD/Unisono-test.apk"
fi
# Keep test-only classes out of release builds.
find "$BUILD/classes" -type f -name '*.class' -delete
"$BT/aapt2" compile --dir "$ROOT/android/app/src/main/res" -o "$BUILD/resources.zip"
"$BT/aapt2" link -o "$BUILD/base.apk" -I "$API" --manifest "$MANIFEST" --java "$BUILD/generated" "$BUILD/resources.zip"
find "$ROOT/android/app/src/main/java" "$BUILD/generated" -name '*.java' > "$BUILD/sources.txt"
if [ "${UNISONO_TEST:-0}" = "1" ]; then printf '%s\n' "$ROOT/tests/SmokeTest.java" "$ROOT/tests/AacRoundTrip.java" "$ROOT/tests/FlacRoundTrip.java" "$ROOT/tests/DeviceTest.java" "$ROOT/tests/DebugTest.java" >> "$BUILD/sources.txt"; fi
"$JAVA_HOME/bin/javac" -source 8 -target 8 -bootclasspath "$API:$BT/core-lambda-stubs.jar" -d "$BUILD/classes" @"$BUILD/sources.txt"
"$JAVA_HOME/bin/jar" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BT/d8" --lib "$API" --min-api 26 --output "$BUILD/dex" "$BUILD/classes.jar"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -u "$BUILD/unsigned.apk" classes*.dex)
"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
KEYSTORE="$ROOT/build/development.p12"
if [ ! -f "$KEYSTORE" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias unisono -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Unisono Local Development' >/dev/null
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias unisono --ks-pass pass:android --out "$APK" "$BUILD/aligned.apk"
"$BT/apksigner" verify "$APK"
echo "$APK"
