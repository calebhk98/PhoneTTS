# Building PhoneTTS

## TL;DR

- **The pure-JVM modules build and test with no Android SDK** - this is where all the
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
  ANDROID_SDK_ROOT=/path/to/android-sdk ./gradlew :app:assembleDebug
  # -> app/build/outputs/apk/debug/app-debug.apk
  ```
  **If the checked-in Gradle wrapper fails** (e.g. `NoClassDefFoundError:
  org/gradle/wrapper/IDownload`, or it can't fetch its distribution behind a restrictive proxy),
  a system-installed Gradle matching the pinned version in
  `gradle/wrapper/gradle-wrapper.properties` (**8.14.3**) works as a drop-in substitute:
  ```bash
  ANDROID_SDK_ROOT=/path/to/android-sdk gradle :app:assembleDebug
  ```

## Why the split

`:core`, `:engines:*`, and `:integration` are plain Kotlin/JVM. They compile to JVM 17 bytecode
and run their unit tests on any JDK (17+) with **nothing Android installed** - that's the whole
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

(Use `gradle` in place of `./gradlew` if the wrapper doesn't work in your environment - see
above.) The debug APK is ~90 MB (it bundles ONNX Runtime's native libraries for all ABIs and
PDFBox). Release builds are not signing-configured yet - see the "release readiness" notes below.

## Getting the app onto a phone, step by step

1. **Build the debug APK** (needs the SDK, see above):
   ```bash
   ANDROID_SDK_ROOT=/path/to/android-sdk gradle :app:assembleDebug
   ```
   The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.
2. **Install it.** With the phone connected over USB (developer options → USB debugging
   enabled) and `adb` (from `platform-tools`) on your `PATH`:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   `-r` reinstalls over a previous debug build without wiping app-private storage (downloaded
   models, preferences). Without `adb`, copy the `.apk` to the phone by any means (cloud drive,
   email, USB file transfer) and open it in a file manager - Android will prompt to allow
   installs from that source (Settings → Apps → Special access → Install unknown apps).
3. **Open PhoneTTS.** It launches straight into the main screen (`TtsScreen`) - model/voice
   pickers, a text box, and Play/Export.
4. **Get a model onto the device** - see the next section. Nothing synthesizes until at least
   one model is downloaded or sideloaded.

### Optional: the native espeak-ng phonemizer

Piper, KittenTTS, and Kokoro share a `Phonemizer` (`EspeakPhonemizer`) backed by a real
espeak-ng JNI bridge (see [`docs/espeak-ng-integration.md`](espeak-ng-integration.md) for the
full story). Building it in is an **explicit opt-in**, gated by `-PwithEspeak=true` in
`app/build.gradle.kts`, because it needs the espeak-ng source tree and the NDK/CMake:

```bash
sh scripts/fetch-espeak-ng.sh                                    # clones espeak-ng 1.52.0
ANDROID_SDK_ROOT=/path/to/android-sdk gradle :app:assembleDebug -PwithEspeak=true
```

Without `-PwithEspeak=true` (the default), `:app` still builds and installs fine -
`EspeakPhonemizer` detects that the native library isn't usable and falls back to
`PassthroughPhonemizer` (text passed through unphonemized), logging a warning. The app never
crashes for lack of it, but models that depend on real phonemes (Piper/KittenTTS/Kokoro) won't
sound right until it's built with espeak-ng in.

## Getting models onto the device

Model weights are **never bundled in the APK** (see `CLAUDE.md` rule 7) - the app is a shell
until you add at least one model, in-app:

- **One-tap recommended models** - the "Browse models" screen opens with a **Recommended
  (one-tap)** section listing curated, known-good models (Piper Lessac, KittenTTS Nano - the ones
  proven to produce valid audio in `docs/MODEL-VERIFICATION.md`). Tapping one downloads its exact
  file set straight into the auto-load pipeline - no searching, no webpage - so a first-run user
  gets a working model immediately (`BuiltInCatalog` → `HfBrowseViewModel.downloadBuiltIn`).
- **Browse Hugging Face** - tap "Browse models" on the main screen, search, and tap Download on
  a result (`HfBrowseScreen`/`HfBrowseViewModel`, wired through `AppGraph.hfCatalog` /
  `AppGraph.hfDownloader`). If a repo ships more than one weight precision, a dialog lets you
  pick one. The download is verified and imported automatically; unrecognized repos still land
  in the catalog via the user-pick fallback (spec §6.2) instead of being rejected.
- **Sideload a folder** - tap "Sideload folder" to pick an already-downloaded model directory
  (e.g. one you fetched on a computer and copied over) via the system folder picker
  (`SideloadCoordinator`). The resolver identifies it the same way an HF download is identified.
- **Manage what's installed** - tap "Manage models" to see each downloaded model's on-disk size
  and delete it (`ModelManagementScreen`/`ModelManager`); deleting the currently-loaded model
  unloads it first.

All three flows funnel through the same `inspect() → resolve → register` pipeline (spec §6.2),
so a model doesn't need special-case UI to show up in the model dropdown.

## Permissions

From `app/src/main/AndroidManifest.xml`:

| Permission | Why |
|---|---|
| `INTERNET` | Only for the explicit, user-initiated Hugging Face browse/download flow. Never touched during synthesis/playback - the app is fully offline at inference time. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Let `PlaybackService` keep synthesis/playback alive with the screen off, as a media-playback foreground service. |
| `POST_NOTIFICATIONS` | The ongoing Play/Pause/Stop + lock-screen notification `PlaybackService` posts while a foreground service is running (required on API 33+). |

`minSdk = 24`, `targetSdk = 35`, `compileSdk = 35` (`app/build.gradle.kts`).

## Current state of `:app`

`:app` compiles, assembles, and installs. `MainActivity` boots straight into the real UI
(`TtsScreen`, plus "Browse models" / "Manage models" sub-screens) - `AppGraph` wires
`EngineLoader.seed()`, the `RuntimeRegistry` + `EngineManager`, the resolver/catalog/importer,
the Hugging Face downloader, and `SideloadCoordinator` at startup, and `PhoneTtsApplication`
calls `AppGraph.hydrate()` to re-import previously downloaded models on every launch. `Runtime`
(ONNX), `AudioSink` (`AudioTrack`), and `Phonemizer` (`EspeakPhonemizer`) all have concrete
implementations.

What's still unverified because no device/NDK has been available while authoring this:

- **The espeak-ng native build** (`-PwithEspeak=true`, see above) hasn't been exercised on a
  real device - the fallback path (`PassthroughPhonemizer`) is what's actually been runtime
  tested.
- **Per-engine ONNX tensor names/vocab.** Some engines still carry `// ASSUMPTION` comments
  (e.g. `engines/piper/.../PiperEngine.kt`) for input/output tensor names inferred from public
  model docs rather than confirmed against the real exported graphs on-device.
- Release builds are not signing-configured, and R8/minification is off
  (`isMinifyEnabled = false` in `app/build.gradle.kts`) pending on-device validation that the
  `proguard-rules.pro` keeps (ServiceLoader providers, kotlinx.serialization models) are
  sufficient.

## Continuous integration

There is no CI config yet. A minimal split:
- **Core job (no SDK):** the TL;DR JVM command above, on any Ubuntu + JDK 21 runner.
- **App job (SDK):** `android-actions/setup-android`, then `./gradlew :app:assembleDebug` -
  allowed to be non-blocking until the app wiring above lands.
