# PhoneTTS

A standalone, **fully-offline** Android text-to-speech app for personal use. It runs several
neural TTS models entirely on-device - **no network calls at inference time** - lets you pick
a model and voice, adjust speed, and either stream playback in real time or export to an
audio file.

Built to run on budget hardware (developed against a Samsung Galaxy A16, ~4 GB RAM, no NPU),
but designed to run unmodified on better and worse phones.

> **Status: in development.** Phase 1 (the deterministic "seams") and Phase 2 (all five engine
> modules) are implemented and unit-tested on the JVM, and the `:app` module compiles into an
> installable APK. All five models are verified to produce real audio off-device (see
> [`docs/MODEL-VERIFICATION.md`](docs/MODEL-VERIFICATION.md)); the espeak-ng phonemizer and the
> CosyVoice native ggml runtime are opt-in native builds (`-PwithEspeak` / `-PwithCosyVoice`).
> On-device/emulator verification of those native bridges is the remaining work. See the full
> [engineering spec](docs/SPEC.md).

## Download

Grab the latest APK from the [**Releases page**](https://github.com/calebhk98/PhoneTTS/releases)
(built by CI - see [`.github/workflows/android.yml`](.github/workflows/android.yml)).

1. Download `phonetts-*.apk` from the newest release.
2. On your phone, allow "install unknown apps" for your browser or files app.
3. Open the APK to install.

It's a **debug-signed** build (installs without a Play account, ideal for personal/sideload use).
The ONNX engines (Piper, Kokoro, KittenTTS, MeloTTS) are included. espeak-ng phonemization and the
CosyVoice engine are **opt-in native builds** not bundled in this baseline APK - build from source
with `-PwithEspeak=true` / `-PwithCosyVoice=true` to include them (see
[`docs/COSYVOICE2.md`](docs/COSYVOICE2.md) and
[`docs/espeak-ng-integration.md`](docs/espeak-ng-integration.md)).

No release yet? Push a `v*` tag (or run the **Android CI + APK release** workflow manually) and CI
publishes the APK; every push also attaches the APK to its Actions run as a build artifact.

## Highlights

- **Five models planned:** CosyVoice2-0.5B, MeloTTS, Piper, KittenTTS, Kokoro-82M.
- **Two output modes:** real-time streaming playback, and export-to-file (the escape hatch
  for models too slow for real-time).
- **Native speed control** on every model - via the model's own duration/speed parameter,
  never by resampling (which would shift pitch).
- **Per-model voice selection.**
- **Drop-in models:** download a model from Hugging Face onto the device and the app
  configures it automatically when it recognizes the family - a sideloaded model is a
  first-class citizen. If it can't identify the model, it asks you once which engine to use
  and remembers.
- **Single source of truth:** to add a model, a voice, or a parameter you edit *one* place;
  dropdowns, labels, sliders and playback config update themselves.

## Architecture in one paragraph

The app doesn't hardcode a list of models. It holds a **registry of engines**; each engine
advertises what it can do through a **descriptor**, and the entire UI is *derived at runtime*
from that registry and those descriptors. Four abstractions carry it: `VoiceEngine` (loads
weights, runs inference), `TextFrontend` (text → phonemes/tokens, kept separate because it
varies hardest between models), `Runtime` (pluggable inference backend - ONNX for most, a
second LLM-style one for CosyVoice2), and `ModelDescriptor` (the sole authority for every
user-visible model fact). Adding, removing, or auto-detecting a model are the same operation
seen from different entry points. Full detail in [`docs/SPEC.md`](docs/SPEC.md).

## Project layout

| Module | What | Buildable without Android SDK? |
|---|---|---|
| `:core` | Pure Kotlin/JVM. Contracts, registry, resolver, descriptors, WAV encoder, streaming driver, manifest + SHA-256. All the testable "seam" logic. | **Yes** - its unit tests run on any JVM. |
| `:app`  | Android app: Compose UI, `AudioTrack` playback, ONNX/native runtimes, concrete engines, downloader. | No (needs the SDK). `settings.gradle.kts` includes it automatically when an SDK is present. |

## Building

Requires a JDK (17+). The pure-JVM modules build and test with **no Android SDK**:

```bash
./gradlew :core:test :integration:test   # the seam tests
./gradlew ktlintCheck detekt             # formatting + never-nesting / size rules
```

The Android `:app` module needs the Android SDK; it's included in the build **automatically
when an SDK is detected** (via `ANDROID_HOME`/`ANDROID_SDK_ROOT` or a `local.properties` with
`sdk.dir`). With an SDK present, `./gradlew :app:assembleDebug` produces the APK. Full details,
including installing just the SDK command-line tools, are in [`docs/BUILDING.md`](docs/BUILDING.md).

## Installing on your phone

PhoneTTS is not on the Play Store (it's a personal/offline app) - you build a debug APK and
sideload it. **Full step-by-step instructions (building, installing over `adb`, the optional
native espeak-ng phonemizer, getting models onto the device, and the permissions the app
requests) are in [`docs/BUILDING.md`](docs/BUILDING.md#getting-the-app-onto-a-phone-step-by-step).**

Short version:

```bash
ANDROID_SDK_ROOT=/path/to/android-sdk gradle :app:assembleDebug   # -> app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open PhoneTTS and download a voice model from inside the app (models are never bundled in
the APK - you pick and download them on first use, and they're stored privately on the device).
You can also sideload a model folder, or import a document (.txt/.md/.html/.docx/.pdf) to read
aloud. Everything after the download runs **fully offline** - no network is used during speech.

**Which models to download - and the gotchas** (which Hugging Face repos work, the exact files
each engine needs, sizes, and troubleshooting) are in **[`docs/MODELS.md`](docs/MODELS.md)**.

### Pre-commit hook

Commits are gated on **ktlint + detekt + `:core` tests** (see `scripts/`). Install the hook
as described in [`docs/SPEC.md` §8.1](docs/SPEC.md).

## Models

| Model | Params (approx) | Sample rate | Notes |
|---|---|---|---|
| Piper | ~5-30M / voice | 16k or 22.05k | Voices are independent ONNX files |
| KittenTTS | 15M / 40M / 80M | 24k | Dev-preview; English-only for now |
| Kokoro-82M | 82M | 24k | 54 voices, 8 languages; own g2p (misaki) w/ espeak fallback |
| MeloTTS | small core + ~94M BERT | 44.1k | Multilingual; heavier real footprint |
| CosyVoice2-0.5B | ~500M | *verify* | Autoregressive, non-deterministic, multi-component |

Per-model facts are confirmed against the actual shipped files and recorded in descriptors -
see `docs/research/model-facts.md`. **Model weights are never bundled in the app**; they are
downloaded into app-private storage and verified by SHA-256 before use.

## Build order

Deliberately **hardest-first**, so the abstractions are proven against the worst cases on day
one rather than refactored late:

1. **Phase 1 - Skeleton:** interfaces, registry, resolver, `EngineManager`, dual-consumer
   audio layer, espeak-ng frontend. Every seam testable and green; no audio yet.
2. **Phase 2 - Models** in order: CosyVoice2 → MeloTTS → Piper → KittenTTS → Kokoro.
3. **Phase 3 - Auto-load:** the same `inspect() → resolve → register` pipeline, triggered by
   a file drop instead of app startup.

## Development

Work is tracked as [GitHub issues](https://github.com/calebhk98/PhoneTTS/issues), one per
ticket. See [`CLAUDE.md`](CLAUDE.md) for the invariants and conventions that govern changes.

## License

[MIT](LICENSE) © Caleb Kirschbaum. Note that some models/frontends carry their own licenses
(e.g. espeak-ng is GPLv3; check each model before relying on it) - the app's clean-removal
design exists so any component can be pulled without collateral damage.
