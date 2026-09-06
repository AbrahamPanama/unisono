#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build/mac"
APP="$ROOT/dist/Unisono.app"
mkdir -p "$BUILD" "$APP/Contents/MacOS" "$APP/Contents/Resources"
xcrun clang -target arm64-apple-macos14.2 -O2 -c "$ROOT/mac/AudioRing.c" -o "$BUILD/AudioRing.o"
xcrun swiftc -swift-version 5 -O -target arm64-apple-macos14.2 -import-objc-header "$ROOT/mac/AudioRing.h" "$ROOT/mac/Protocol.swift" "$ROOT/mac/AACEncoder.swift" "$ROOT/mac/Capture.swift" "$ROOT/mac/main.swift" "$BUILD/AudioRing.o" -framework AppKit -framework CoreAudio -framework AVFoundation -framework Network -framework CryptoKit -o "$APP/Contents/MacOS/Unisono"
cp "$ROOT/mac/Info.plist" "$APP/Contents/Info.plist"
cp "$ROOT/mac/Resources/Unisono.icns" "$ROOT/mac/Resources/"MenuBarTemplate*.png "$APP/Contents/Resources/"
codesign --force --sign - --identifier app.unisono.mac "$APP"
echo "$APP"
