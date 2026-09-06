# Unísono

Stream your Mac's system audio to Android over your local network, and listen on both devices at once.

Unísono is a native macOS menu-bar app with a companion Android receiver. It transports uncompressed stereo PCM, encrypts the connection, and uses a configurable playback buffer to prioritize continuity and synchronization.

**Status: experimental personal-use prototype, version 0.1.0.** macOS capture and Android emulator playback have been tested. Physical Galaxy S25 Ultra playback, acoustic synchronization, and long-running stability still need verification.

[Download the prerelease](https://github.com/AbrahamPanama/unisono/releases/tag/v0.1.0) · [Guía en español](LEEME.md) · [Wire protocol](PROTOCOL.md)

<img src="design/mac-preview.png" width="360" alt="Unísono's dark macOS panel with light text, green controls, phone volume and a stop button">

*Native UI preview of the connected state. The app lives in the macOS menu bar, not the Dock.*

## What it does

- Captures system audio with Core Audio Taps, excluding Unísono's own playback.
- Sends stereo Float32 PCM over TCP without a lossy codec.
- Authenticates pairing with a random key and encrypts packets with AES-256-GCM.
- Plays locally on the Mac and remotely on Android, or only on Android.
- Offers 120, 250 and 500 ms playback reserves and a manual Mac timing adjustment.
- Imports a pairing link or QR through the Android camera's link handler.
- Supports phone-volume control and Android background playback with a foreground-service notification.
- Attempts up to three reconnections after a connection failure; intentional stops do not automatically reconnect.

## Requirements

| Platform | Requirements |
|---|---|
| macOS | Apple Silicon, macOS 14.2+, system-audio capture permission |
| Android | Android 8.0 / API 26+, compatible stereo PCM output |
| Network | Both devices on a LAN that allows connections between them; TCP port 45871 |

The currently tested environments are a Mac mini M4 running macOS 26.6.1 and an Android 15 ARM64 emulator. The minimum platform versions are build targets, not a claim that every supported OS/device has been tested.

## Try it

1. Download `Unisono-Mac.zip` and `Unisono-Android.apk` from the [prerelease](https://github.com/AbrahamPanama/unisono/releases/tag/v0.1.0).
2. Extract and open the Mac app. Install the APK on Android using your device's normal sideloading flow.
3. Open Unísono's menu-bar icon, then the gear. Scan its QR with the phone camera, or copy the entire connection link into the Android app.
4. Tap **Conectar** on Android and grant the Mac's audio-capture permission when prompted. Play audio on the Mac. Reconnect once if the first permission prompt interrupted pairing.
5. Start with the 250 ms reserve. Try 500 ms if the network is unreliable, then adjust Mac timing if needed.

The apps currently use a Spanish interface. Closing the Mac panel leaves streaming active. **Detener transmisión** ends the session and restores normal Mac playback; **Salir de Unísono** quits the app.

These are development builds: the Mac app is ad-hoc signed and not notarized for public distribution, and the Android APK uses a development signing identity. A locally rebuilt Android APK uses a different identity unless you preserve its generated keystore, so it cannot update an existing differently signed install directly.

## Audio quality and timing

**Lossless transport is not the same as bit-perfect output.** The transmitted PCM samples are preserved, but the source mixer, output mixer, volume changes, device resampling and Android clock correction can alter the rendered audio. Bluetooth may add lossy encoding and additional delay.

At 48 kHz, stereo Float32 PCM uses about 3.07 Mbps before protocol overhead. The configured 120–500 ms reserve is a buffer setting, **not measured end-to-end latency**.

Clock exchange, scheduled Mac playback and Android `AudioTimestamp` feedback align playback to a shared timeline. Android makes small playback-speed corrections to compensate for independent clocks. The reported timing error is an estimate against that timeline, not an acoustic measurement between the speakers. Precise synchronization on a physical S25 Ultra remains unverified.

## Build from source

### macOS

Install Apple's Command Line Tools with an SDK containing Core Audio Taps, then run:

```sh
bash scripts/build-mac.sh
open dist/Unisono.app
```

The script produces an ARM64 app for macOS 14.2+ and signs it locally.

### Android

Install JDK 17 and the Android SDK packages `platforms;android-35` and `build-tools;35.0.0`. Set the environment variables to your own installation paths:

```sh
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_HOME="/path/to/android-sdk"
bash scripts/build-android.sh
```

Output: `dist/Unisono-Android.apk`. Android Studio and Gradle are not required. The script uses `javac`, AAPT2, D8, zipalign and apksigner. Keep `build/development.p12` private if you want subsequent local builds to update the same installed APK. Build scripts currently target a macOS development host.

### Tests

Stop the Mac app first so TCP port 45871 is available. With JDK 17 configured:

```sh
bash scripts/test.sh
```

This checks the capture ring and Swift/Java protocol interoperability: exact PCM sample bits, authenticated encryption, clock messages, volume messages, explicit stop, reconnection and rejection of incorrect pairing keys. Fixed `1111…` and `2222…` keys in tests are public fixtures, never production pairing keys.

An Android instrumentation smoke test is included in `tests/SmokeTest.java`; it is compiled only when `UNISONO_TEST=1`. It targets an emulator and a test server at `10.0.2.2:45871`, not a physical phone or the production app. The instrumented APK is written under `build/`, not `dist/`.

## Validation so far

- Mac and Android builds compiled and their signatures verified.
- Swift/Java interoperability: 8,192 stereo frames compared bit for bit.
- Capture-ring FIFO, planar/interleaved input and bounded overflow tests passed.
- Actual Mac capture delivered roughly 15 seconds of nonzero stereo audio at 48 kHz. Audio was not saved.
- Android 15 emulator instrumentation exercised link import, connection, six seconds of AudioTrack playback with a foreground service, and explicit disconnect.
- Native UI captures were reviewed for layout and light-on-dark text contrast.

Still pending: physical S25 Ultra testing, actual Wi-Fi performance, acoustic latency/synchronization measurements, longer sessions, and first-use QR/permission flows on Samsung devices.

## Repository layout

```text
mac/          AppKit UI, Core Audio capture, local playback and encrypted server
android/      Native Activity, playback service, AudioTrack and wire codec
scripts/      Local build and test entry points
tests/        Protocol, ring-buffer and Android instrumentation tests
design/       Selected visual reference and non-sensitive UI previews
```

Pairing links contain a private listening key. Do not share real links or screenshots of the pairing panel publicly. Keys are stored locally; the app has no cloud account, audio recording or analytics. The custom protocol has not undergone an independent security audit.

## Licensing

No open-source license has been selected yet. Public repository visibility alone does not grant a general license to reuse or redistribute the code.
