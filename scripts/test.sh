#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME}"
mkdir -p "$ROOT/build/tests"
xcrun clang -O2 "$ROOT/tests/ring_test.c" "$ROOT/mac/AudioRing.c" -framework CoreAudio -o "$ROOT/build/tests/ring-test"
"$ROOT/build/tests/ring-test"
xcrun swiftc -swift-version 5 "$ROOT/mac/LatencySettings.swift" "$ROOT/tests/LatencySettingsTest.swift" -o "$ROOT/build/tests/latency-settings-test"
"$ROOT/build/tests/latency-settings-test"
xcrun swiftc -swift-version 5 "$ROOT/mac/Protocol.swift" "$ROOT/mac/FLACEncoder.swift" "$ROOT/tests/FLACEncoderTest.swift" -o "$ROOT/build/tests/flac-encoder-test"
"$ROOT/build/tests/flac-encoder-test"
xcrun swiftc -swift-version 5 "$ROOT/mac/Protocol.swift" "$ROOT/mac/AACEncoder.swift" "$ROOT/mac/FLACEncoder.swift" "$ROOT/mac/LatencySettings.swift" "$ROOT/tests/main.swift" -o "$ROOT/build/tests/wire-server"
"$JAVA_HOME/bin/javac" -d "$ROOT/build/tests" "$ROOT/android/app/src/main/java/app/unisono/Wire.java" "$ROOT/tests/WireTest.java" "$ROOT/android/app/src/main/java/app/unisono/PlaybackTuning.java" "$ROOT/tests/PlaybackTuningTest.java"
"$JAVA_HOME/bin/java" -cp "$ROOT/build/tests" PlaybackTuningTest
"$ROOT/build/tests/wire-server" > "$ROOT/build/tests/server.log" 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT
sleep 1
"$JAVA_HOME/bin/java" -cp "$ROOT/build/tests" WireTest
