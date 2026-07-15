# PhoneTTS

A standalone, **fully-offline** Android text-to-speech app for personal use. It runs several
neural TTS models entirely on-device — **no network calls at inference time** — lets you pick
a model and voice, adjust speed, and either stream playback in real time or export to an
audio file.

Built to run on budget hardware (developed against a Samsung Galaxy A16, ~4 GB RAM, no NPU),
but designed to run unmodified on better and worse phones.

> **Status: early development (Phase 1 — skeleton).** The architecture and the deterministic
> "seams" are being built and tested first; no model produces audio yet. See
> [build order](#build-order) below and the full [engineering spec](docs/SPEC.md).

## Highlights

- **Five models planned:** CosyVoice2-0.5B, MeloTTS, Piper, KittenTTS, Kokoro-82M.
- **Two output modes:** real-time streaming playback, and export-to-file (the escape hatch
  for models too slow for real-time).
- **Native speed control** on every model — via the model's own duration/speed parameter,
  never by resampling (which would shift pitch).
- **Per-model voice selection.**
- **Drop-in models:** download a model from Hugging Face onto the device and the app
  configures it automatically when it recognizes the family — a sideloaded model is a
  first-class citizen. If it can't identify the model, it asks you once which engine to use
  and remembers.
- **Single source of truth:** to add a model, a voice, or a parameter you edit *one* place;
  dropdowns, labels, sliders and playback config update themselves.

## Architecture in one paragraph

The app doesn't hardcode a list of models. It holds a **registry of engines**; each engine
advertises what it can do through a **descriptor**, and the entire UI is *derived at runtime*
from that registry and those descriptors. Four abstractions carry it: `VoiceEngine` (loads
weights, runs inference), `TextFrontend` (text → phonemes/tokens, kept separate because it
varies hardest between models), `Runtime` (pluggable inference backend — ONNX for most, a
second LLM-style one for CosyVoice2), and `ModelDescriptor` (the sole authority for every
user-visible model fact). Adding, removing, or auto-detecting a model are the same operation
seen from different entry points. Full detail in [`docs/SPEC.md`](docs/SPEC.md).

## Project layout

| Module | What | Buildable without Android SDK? |
|---|---|---|
| `:core` | Pure Kotlin/JVM. Contracts, registry, resolver, descriptors, WAV encoder, streaming driver, manifest + SHA-256. All the testable "seam" logic. | **Yes** — its unit tests run on any JVM. |
| `:app`  | Android app: Compose UI, `AudioTrack` playback, ONNX/LLM runtimes, concrete engines, downloader. | No (needs the SDK). Enable by uncommenting `include(":app")` in `settings.gradle.kts`. |

## Building

Requires a JDK (17+). The `:core` module builds and tests with **no Android SDK**:

```bash
./gradlew :core:test          # run the seam tests
./gradlew ktlintCheck detekt  # formatting + never-nesting / size rules
```

To build the Android app, install the Android SDK, uncomment `include(":app")` in
`settings.gradle.kts`, then use the standard `./gradlew :app:assembleDebug`.

### Pre-commit hook

Commits are gated on **ktlint + detekt + `:core` tests** (see `scripts/`). Install the hook
as described in [`docs/SPEC.md` §8.1](docs/SPEC.md).

## Models

| Model | Params (approx) | Sample rate | Notes |
|---|---|---|---|
| Piper | ~5–30M / voice | 16k or 22.05k | Voices are independent ONNX files |
| KittenTTS | 15M / 40M / 80M | 24k | Dev-preview; English-only for now |
| Kokoro-82M | 82M | 24k | 54 voices, 8 languages; own g2p (misaki) w/ espeak fallback |
| MeloTTS | small core + ~94M BERT | 44.1k | Multilingual; heavier real footprint |
| CosyVoice2-0.5B | ~500M | *verify* | Autoregressive, non-deterministic, multi-component |

Per-model facts are confirmed against the actual shipped files and recorded in descriptors —
see `docs/research/model-facts.md`. **Model weights are never bundled in the app**; they are
downloaded into app-private storage and verified by SHA-256 before use.

## Build order

Deliberately **hardest-first**, so the abstractions are proven against the worst cases on day
one rather than refactored late:

1. **Phase 1 — Skeleton:** interfaces, registry, resolver, `EngineManager`, dual-consumer
   audio layer, espeak-ng frontend. Every seam testable and green; no audio yet.
2. **Phase 2 — Models** in order: CosyVoice2 → MeloTTS → Piper → KittenTTS → Kokoro.
3. **Phase 3 — Auto-load:** the same `inspect() → resolve → register` pipeline, triggered by
   a file drop instead of app startup.

## Development

Work is tracked as [GitHub issues](https://github.com/calebhk98/PhoneTTS/issues), one per
ticket. See [`CLAUDE.md`](CLAUDE.md) for the invariants and conventions that govern changes.

## License

[MIT](LICENSE) © Caleb Kirschbaum. Note that some models/frontends carry their own licenses
(e.g. espeak-ng is GPLv3; check each model before relying on it) — the app's clean-removal
design exists so any component can be pulled without collateral damage.
