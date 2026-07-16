# Building PhoneTTS

## TL;DR

- **The pure-JVM modules build and test with no Android SDK** — this is where all the
  correctness lives:
  ```bash
  ./gradlew ktlintCheck detekt :core:test :integration:test \
            :engines:common:test :engines:cosyvoice2:test :engines:melotts:test \
            :engines:piper:test :engines:kittentts:test :engines:kokoro:test
  ```
- **The `:app` (Android) module needs the Android SDK.** It is included in the build
  automatically **only when an SDK is detected** (see below), so an SDK-less machine or a
  core-only CI job just skips it. With an SDK present:
  ```bash
  ./gradlew :app:assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
  ```

## Why the split

`:core`, `:engines:*`, and `:integration` are plain Kotlin/JVM. They compile to JVM 17 bytecode
and run their unit tests on any JDK (17+) with **nothing Android installed** — that's the whole
point of keeping the deterministic "seams" (registry, resolver, descriptors, WAV encoder,
extractors, HF client) out of the Android layer. Everything you need to prove the app *correct*
runs without the SDK.

`:app` is the Android application (Compose UI, `AudioTrack`, the ONNX runtime, the espeak
frontend, downloaders). It uses the Android Gradle Plugin, which **requires the Android SDK** to
even configure. That's the only reason it's gated.

## Installing the Android SDK

Either install **Android Studio** (bundles the SDK), or install just the command-line tools:

```bash
# 1. command-line tools
mkdir -p "$HOME/android-sdk/cmdline-tools"
curl -o /tmp/cmdtools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
cd "$HOME/android-sdk/cmdline-tools" && unzip -q /tmp/cmdtools.zip && mv cmdline-tools latest

# 2. platform + build tools (match app/build.gradle.kts: compileSdk = 35)
export ANDROID_HOME="$HOME/android-sdk"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Then tell Gradle where the SDK is, one of:
- export `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) in your shell, **or**
- create `local.properties` in the repo root (git-ignored):
  ```properties
  sdk.dir=/absolute/path/to/android-sdk
  ```

`settings.gradle.kts` detects any of these and includes `:app` automatically. To force it off
even with an SDK present (e.g. a core-only CI job on an Android machine), pass `-PskipApp=true`.

## Building the app

```bash
./gradlew :app:assembleDebug          # debug APK (debug-signed)
./gradlew :app:installDebug           # build + install to a connected device/emulator (adb)
```

The debug APK is ~90 MB (it bundles ONNX Runtime's native libraries for all ABIs and PDFBox).
Release builds are not signing-configured yet — see the "release readiness" notes below.

## Current state of `:app` (important)

`:app` **compiles and assembles**, but it is still a skeleton — `MainActivity` shows a
placeholder and the pieces below are **not yet wired**, so the app does not produce audio yet:

- No startup wiring calls `EngineLoader.seed()` / builds the `RuntimeRegistry` + `EngineManager`.
- No concrete `Runtime` (ONNX), `Phonemizer` (espeak-ng), or `AudioSink` (`AudioTrack`)
  implementation exists yet — the interfaces are in `:core`, the implementations are Phase-2
  on-device work.
- The five engines' ONNX input/output tensor names and vocab are **unverified assumptions**
  (marked `// ASSUMPTION` in each engine) — they must be validated against the real exported
  model graphs before any audio is correct.
- `HfBrowseScreen`, `ImportFileButton`, and `SideloadCoordinator` exist but aren't connected to
  `MainActivity` navigation yet.

Before a release: add the Compose UI wiring + the three runtime implementations, add
`<uses-permission android:name="android.permission.INTERNET"/>` (needed for model downloads),
configure release signing + R8 (keep `ServiceLoader`/`META-INF/services` provider classes and
kotlinx.serialization models from being stripped), and restrict native ABIs to the target device.

## Continuous integration

There is no CI config yet. A minimal split:
- **Core job (no SDK):** the TL;DR JVM command above, on any Ubuntu + JDK 21 runner.
- **App job (SDK):** `android-actions/setup-android`, then `./gradlew :app:assembleDebug` —
  allowed to be non-blocking until the app wiring above lands.
