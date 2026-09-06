#!/bin/bash
# Requires a booted Android 15 emulator; never installs on a physical phone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME}"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
ADB=("$ANDROID_HOME/platform-tools/adb" -e)
TEST_RESERVE="${UNISONO_TEST_RESERVE:-500}"
if lsof -nP -iTCP:45871 -sTCP:LISTEN >/dev/null 2>&1; then
  echo 'Stop Unisono to free TCP port 45871 before running the emulator tests.' >&2
  exit 1
fi
UNISONO_TEST=1 bash "$ROOT/scripts/build-android.sh"
bash "$ROOT/scripts/build-focus-fixture.sh"
"${ADB[@]}" install -r -g "$ROOT/build/android/Unisono-test.apk"
"${ADB[@]}" install -r "$ROOT/build/focus-fixture/focus-fixture.apk"
mkdir -p "$ROOT/build/tests"
xcrun swiftc -swift-version 5 "$ROOT/mac/Protocol.swift" "$ROOT/mac/AACEncoder.swift" "$ROOT/mac/FLACEncoder.swift" "$ROOT/mac/LatencySettings.swift" "$ROOT/mac/DebugSettings.swift" "$ROOT/tests/main.swift" -o "$ROOT/build/tests/wire-server"
SERVER_ARGS=(--long --reserve "$TEST_RESERVE")
TEST_ARGS=(-e stability false)
if [ "${UNISONO_JITTER:-0}" = "1" ]; then SERVER_ARGS+=(--jitter); TEST_ARGS=(-e stability true); fi
if [ "${UNISONO_TEST_NO_FLAC:-0}" = "1" ]; then SERVER_ARGS+=(--no-flac); TEST_ARGS+=(-e noFlac true); fi
DEBUG_CASE="${UNISONO_DEBUG_CASE:-}"
if [ -n "$DEBUG_CASE" ]; then
  SERVER_ARGS+=(--debug-test)
  if [ "$DEBUG_CASE" = "jitter" ]; then SERVER_ARGS+=(--jitter); fi
  if [ "$DEBUG_CASE" = "legacy" ]; then SERVER_ARGS+=(--legacy-debug); fi
fi
"$ROOT/build/tests/wire-server" "${SERVER_ARGS[@]}" > "$ROOT/build/tests/emulator-server.log" 2>&1 &
SERVER_PID=$!
cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  "${ADB[@]}" shell am force-stop app.unisono.focusfixture >/dev/null 2>&1 || true
  "${ADB[@]}" shell am force-stop app.unisono >/dev/null 2>&1 || true
}
trap cleanup EXIT
sleep 1
kill -0 "$SERVER_PID"
TEST_ARGS+=(-e quality "${UNISONO_TEST_QUALITY:-balanced}" -e reserve "$TEST_RESERVE")
if [ -n "$DEBUG_CASE" ]; then
  RESULT="$("${ADB[@]}" shell am instrument -w -e scenario "$DEBUG_CASE" app.unisono/app.unisono.DebugTest)"
else
  RESULT="$("${ADB[@]}" shell am instrument -w "${TEST_ARGS[@]}" app.unisono/app.unisono.SmokeTest)"
fi
printf '%s\n' "$RESULT"
[[ "$RESULT" == *'PASS: audio playback'* || "$RESULT" == *'PASS: latency debugging'* ]]
