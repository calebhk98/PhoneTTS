# CLAUDE.md

Guidance for Claude Code (and any engineer) working in this repository.

## What this is

**PhoneTTS** — a standalone, **fully-offline** Android text-to-speech app for personal
use. It runs several neural TTS models entirely on-device (no network calls at inference
time), lets the user pick a model + voice, adjust speed, and either stream playback in real
time or export to a file. Target: budget hardware (developed against a Samsung Galaxy A16,
~4 GB RAM, no NPU), but it must run unmodified on better/worse phones.

The full engineering specification lives in [`docs/SPEC.md`](docs/SPEC.md). **Read it before
making architectural changes** — the build order and abstractions there are *decided, not
suggestions*.

## The one idea that governs everything

The app does **not** know about a fixed set of models. It knows about a **registry of
engines**, and each engine advertises what it can do via a **descriptor**. Everything the UI
shows is derived at runtime from the registry + descriptors.

Four abstractions (all in `:core`):
- **`VoiceEngine`** — loads weights, runs inference. Never references another engine.
- **`TextFrontend`** — turns text into model input (phonemes/tokens). Varies hardest between
  models, so it is deliberately **not** part of `VoiceEngine`; it lives *inside* each engine.
- **`Runtime`** — pluggable inference backend (ONNX for most; a second, LLM-style one for
  CosyVoice2). Behind an interface so adding one touches nothing else.
- **`ModelDescriptor`** — the single authority for every user-visible model fact.

## The rules that must never be broken

These come straight from the spec and are the whole point of the design:

1. **SSOT — a model constant outside the resolver/descriptor layer is a bug.** No sample
   rate, voice name, speed bound, or display name may appear as a literal in the UI or an
   engine. If it does, you have two sources of truth and the guarantee is broken. This is a
   review reflex and, where expressible, a detekt rule.
2. **Speed always routes to the model's native parameter** (Piper `length_scale`, others a
   `speed`/duration arg). **Never** resample output audio to change speed — that shifts pitch.
3. **One generation path.** `synthesize()` returns `Flow<FloatArray>`. Real-time playback and
   file export are two *consumers* of that one flow. No second synthesis path.
4. **`inspect()` fails closed.** `null` means "not mine," never a guess. If a dropped-in model
   can't be identified with confidence, the app drops to the user-pick fallback rather than
   guessing. That refusal is a feature.
5. **No `when(modelType)` switches.** Removing a model = removing its engine registration,
   nothing else. Adding a model = registering it; the UI recomputes itself. The only
   built-in-vs-sideloaded distinction is the `Origin` field, used for **display only**.
6. **One engine loaded at a time.** `EngineManager` calls `unload()` on the previous engine
   before `load()` on the next. A 4 GB phone cannot hold them all.
7. **Model weights are never bundled in the APK.** Ship a manifest, download into app-private
   storage, verify SHA-256, then load.
8. **All inference off the main thread** (coroutines). **Chunk long text into sentences** and
   synthesize sequentially so output can start before the whole thing is generated.
9. **Never-nesting.** Guard clauses / early `return`/`continue`. No deep `if` pyramids.
   Enforced by detekt (`NestedBlockDepth`, `LongMethod`, `LargeClass`).

## Module layout

- **`:core`** — pure Kotlin/JVM. All deterministic "seam" logic: contracts, registry,
  resolver, descriptors, WAV encoder, streaming driver, manifest/SHA-256. **No Android
  dependencies**, so its unit tests (the seam tests, spec §9) run on any JVM with no SDK.
  This is where correctness is proven.
- **`:app`** — the Android application (Compose UI, `AudioTrack` sink, ONNX/LLM runtimes,
  concrete engines, SharedPreferences-backed override store, downloader). Requires the
  Android SDK. It is **commented out in `settings.gradle.kts`** until the SDK is available;
  uncomment `include(":app")` to enable it locally / in CI.

Package root: `com.phonetts`. Core lives under `com.phonetts.core.{engine,model,runtime,
registry,resolver,audio}`.

## Build & test

This environment has **no Android SDK**, so work against `:core`:

```bash
./gradlew :core:test          # run the seam tests (this is the important one)
./gradlew :core:compileKotlin  # compile the main sources
./gradlew ktlintCheck detekt   # style + never-nesting / size rules
```

(If the wrapper can't fetch its distribution behind a proxy, a system Gradle 8.14.3 also
works: `gradle :core:test`.)

`:core` compiles with the running JDK (21 here) but **emits JVM 17 bytecode** so the Android
`:app` module can consume it unchanged.

## TDD — test the plumbing, not the audio

Voice quality is the model's job, not this codebase's. Tests target the **deterministic
seams** (spec §9): `inspect()` fail-closed, `resolve()` + saved override, registry dynamism
(the SSOT guard — **do not skip**), speed routing, sample-rate routing, WAV round-trip, dual
consumer draining one flow. Write the failing test first, then the minimum code to pass it.

**No golden audio hashes for CosyVoice2** — it samples autoregressively and is
non-deterministic. Test invariants instead (length in range, samples bounded, no NaNs).

Shared test fixtures (`FakeEngine`, `testDescriptor`) live in
`core/src/test/kotlin/com/phonetts/core/testing/Fakes.kt` — reuse them for new seam tests.

## Workflow conventions in this repo

- Work is tracked as **GitHub issues** (one per ticket). Comment progress on the issue you're
  working; close it with `state_reason: completed` when its tests are green.
- Branch: feature work lands on `claude/local-tts-android-setup-8xshx0` (per session config).
- Commits: clear, descriptive messages. Pre-commit hook runs ktlint + detekt + `:core:test`
  and blocks on failure (see `scripts/`).
- Weights, `.onnx`/`.bin` files, and `models/` are git-ignored — never commit model data.

## Build order (locked — hardest first, see spec §3–§4)

Phase 1 (skeleton, no audio yet) → Phase 2 models in order: **CosyVoice2 → MeloTTS → Piper →
KittenTTS → Kokoro** → Phase 3 auto-load (which is just the same `inspect() → resolve →
register` pipeline triggered by a file drop). Leading with the two awkward models forces the
abstractions to be right on day one instead of refactoring the foundation late.
