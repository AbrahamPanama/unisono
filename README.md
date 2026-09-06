# Unísono

Stream your Mac's system audio to Android over your local network, and listen on both devices at once.

Unísono is a native macOS menu-bar app with a companion Android receiver. It offers adaptive AAC streaming and a lossless PCM mode, encrypts the connection, and uses larger playback reserves to prioritize continuity and synchronization.

**Status: experimental personal-use prototype, version 0.2.0.** macOS capture and Android emulator playback have been tested. Physical Galaxy S25 Ultra playback, acoustic synchronization, and long-running stability still need verification.

[Download the prerelease](https://github.com/AbrahamPanama/unisono/releases/tag/v0.2.0) · [Guía en español](LEEME.md) · [Wire protocol](PROTOCOL.md)

<img src="design/mac-preview.png" width="360" alt="Unísono's dark macOS panel with light text, green controls, phone volume and a stop button">

*Native UI preview of the connected state. The app lives in the macOS menu bar, not the Dock.*

## What it does

- Captures system audio with Core Audio Taps, excluding Unísono's own playback.
- Sends AAC-LC at a 256 or 160 kbps target, or uncompressed stereo Float32 PCM, over encrypted TCP.
- Authenticates pairing with a random key and encrypts packets with AES-256-GCM.
- Plays locally on the Mac and remotely on Android, or only on Android.
- Offers a 500–1000 ms shared playback reserve, adaptive Android output buffering, and a manual Mac timing adjustment.
- Imports a pairing link or QR through the Android camera's link handler.
- Supports phone-volume control and Android background playback with a foreground-service notification.
- Offers **Mezclar con otras apps**, enabled by default, to listen alongside another music app.
- Attempts up to three reconnections after a connection failure; intentional stops do not automatically reconnect.

## Requirements

| Platform | Requirements |
|---|---|
| macOS | Apple Silicon, macOS 14.2+, system-audio capture permission |
| Android | Android 8.0 / API 26+, compatible stereo PCM output |
| Network | Both devices on a LAN that allows connections between them; TCP port 45871 |

The currently tested environments are a Mac mini M4 running macOS 26.6.1 and an Android 15 ARM64 emulator. The minimum platform versions are build targets, not a claim that every supported OS/device has been tested.

## Try it

1. Download `Unisono-Mac.zip` and `Unisono-Android.apk` from the [prerelease](https://github.com/AbrahamPanama/unisono/releases/tag/v0.2.0).
2. Extract and open the Mac app. Install the APK on Android using your device's normal sideloading flow.
3. Open Unísono's menu-bar icon, then the gear. Scan its QR with the phone camera, or copy the entire connection link into the Android app.
4. Tap **Conectar** on Android and grant the Mac's audio-capture permission when prompted. Play audio on the Mac. Reconnect once if the first permission prompt interrupted pairing.
5. Start with **Equilibrado · AAC adaptable** on Android. Choose **Más estable · AAC 160 kbps** for a larger starting reserve or **Sin pérdida · PCM** for lossless transport. Disconnect to change the mode.

The apps currently use a Spanish interface. Closing the Mac panel leaves streaming active. **Detener transmisión** ends the session and restores normal Mac playback; **Salir de Unísono** quits the app.

These are development builds: the Mac app is ad-hoc signed and not notarized for public distribution, and the Android APK uses a development signing identity. A locally rebuilt Android APK uses a different identity unless you preserve its generated keystore, so it cannot update an existing differently signed install directly.

## Background playback and other music apps

Opening another app leaves the foreground audio service running. To listen to Unísono alongside Spotify or another music app, leave **Mezclar con otras apps** enabled in Android before connecting. Disconnect to change this setting. Uncheck it if you prefer Unísono to request audio focus and stop when another app takes over.

Mixing mode deliberately does not request audio focus; it keeps normal media/music audio attributes. Simply ignoring focus-loss callbacks after acquiring focus would still allow Android 12+ to fade the player. See [Android's audio-focus documentation](https://developer.android.com/media/optimize/audio-focus). The service also stops when Android reports call, ringtone, or communication mode; reconnect after the call. This check does not detect communication apps that fail to report their audio mode.

Background playback, both music-app start orders, and normal-mode focus loss are tested on an Android 15 emulator with an independent media player. The mixer reports both tracks active and unmuted in mixing mode. This does not verify acoustic output or guarantee Spotify/Samsung firmware behavior on the physical S25 Ultra. Device battery restrictions can also interrupt long background sessions.

## Quality modes and popping

| Android mode | Audio transport | Initial reserve |
|---|---|---|
| Equilibrado · AAC adaptable (default) | AAC-LC, 256 kbps target; 160 kbps on recovery | 500 ms |
| Más estable · AAC 160 kbps | AAC-LC, 160 kbps target | 750 ms |
| Sin pérdida · PCM | Original Float32 PCM; never automatically switches to AAC | 500 ms |

**AAC is lossy.** Its lower network usage is a deliberate quality/stability tradeoff. The Android status and Mac panel show the actual codec, bitrate target where applicable, and shared reserve. AAC uses native Mac encoding and Android decoding at 48 kHz, with encoder priming removed before scheduling playback. Unavailable AAC initialization falls back to PCM, which is reported in the status. Legacy receivers request PCM; legacy Mac builds respond with PCM. Update both apps to use all adaptation features.

Android now fills approximately 100 ms of output audio before playback, coalesces small PCM packets, increases its output buffer after underruns toward 250 ms (subject to device limits), smooths startup/volume changes over 10 ms, and filters clock corrections instead of adjusting aggressively every half-second. Corrections change at most every two seconds, with a 3 ms deadband and a 200 ppm step limit.

After a failed session, repeated underruns, or persistent large timing error, the receiver reconnects with a reserve increased by 250 ms, capped at 1000 ms. The Mac uses the larger of its configured minimum and the receiver's request, so both restart on the same timeline. This introduces a brief pause; it is not seamless bitrate switching. Automatic reductions in quality only occur within an AAC mode. There are at most three connection attempts per start, and settings reset to the selected profile on a fresh manual start.

Strong Wi-Fi signal does not establish the cause of a pop. Small buffers, scheduling stalls, clock correction, source clipping, or the output device can also contribute. These changes address several plausible software causes; eliminating the reported pops on the physical S25 Ultra is still unverified.

## Audio quality and timing

**PCM lossless transport is not the same as bit-perfect output.** In PCM mode, transmitted samples are preserved, but the source mixer, output mixer, volume changes, device resampling and Android clock correction can alter the rendered audio. Bluetooth may add lossy encoding and additional delay.

At 48 kHz, stereo Float32 PCM uses about 3.07 Mbps before protocol overhead. The configured 500–1000 ms reserve is a buffer setting, **not measured end-to-end latency**.

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

With an Android 15 emulator running and `ANDROID_HOME` configured, run `bash scripts/test-android.sh`. It builds the test APK and a separate-UID media-player fixture, validates both native AAC bitrates and priming alignment against a synthetic tone, and verifies advancing background playback, both mixing start orders, unmuted mixer state, normal-mode focus loss, and explicit disconnect. It targets the emulator and a test server at `10.0.2.2:45871`; test APKs stay under `build/` and are excluded from releases.

Use `UNISONO_JITTER=1 bash scripts/test-android.sh` to inject an 850 ms network stall and verify AAC recovery at a larger reserve/lower bitrate. Add `UNISONO_TEST_QUALITY=lossless` to verify that recovery preserves PCM. `scripts/test.sh` also checks adaptive-buffer bounds, clock-correction limits, and gain ramps in pure Java.

Mac appearance regression captures exercise a real popover in light and dark host windows:

```sh
mkdir -p build/mac
./dist/Unisono.app/Contents/MacOS/Unisono --preview --light-host --popover "$PWD/build/mac/popover-light.png"
./dist/Unisono.app/Contents/MacOS/Unisono --preview --popover "$PWD/build/mac/popover-dark.png"
```

Preview mode uses synthetic connection state and does not start the network listener.

## Validation so far

- Mac and Android builds compiled and their signatures verified.
- Swift/Java interoperability: 8,192 stereo frames compared bit for bit.
- Capture-ring FIFO, planar/interleaved input and bounded overflow tests passed.
- Actual Mac capture delivered roughly 15 seconds of nonzero stereo audio at 48 kHz. Audio was not saved.
- Android 15 emulator instrumentation exercised native AAC decoding at both bitrate targets, encrypted playback in the background, simultaneous unmuted playback with a separate music app in both start orders, normal-mode focus loss, and explicit disconnect.
- Forced 850 ms stalls recovered at a 750 ms reserve in AAC and PCM modes; PCM stayed lossless. These are synthetic interruptions, not a physical Wi-Fi benchmark.
- Actual Mac capture encoded and transmitted approximately eight seconds of AAC without capture overflow; no audio was saved.
- Native UI captures were reviewed for layout and light-on-dark text contrast. Real popover captures are identical under light and dark host appearances; settings also use explicit light text.

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
