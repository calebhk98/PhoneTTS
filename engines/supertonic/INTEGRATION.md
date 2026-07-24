# Wiring `:engines:supertonic` into the app (parent-owned steps)

This module (`:engines:supertonic`) is a plain-Kotlin ONNX engine - no native/NDK component, unlike
`:engines:ggmltts`/`:engines:cosyvoice2`'s native bridges. Per this ticket's hard rules,
`settings.gradle.kts` and `app/build.gradle.kts` were **not** touched (they are shared/parent-owned
files). This document is the exact diff the parent session needs to apply to finish the wiring.
Every snippet mirrors the already-merged sibling engines' entries line for line.

## What already exists (this session's output)

| File | Status |
|---|---|
| `engines/supertonic/build.gradle.kts` | Done - mirrors `engines/kittentts/build.gradle.kts` exactly |
| `engines/supertonic/src/main/kotlin/.../SupertonicEngine.kt` + `SupertonicEngineProvider.kt` + `SupertonicFrontend.kt` + `SupertonicStyle.kt` + `SupertonicUnicodeIndexer.kt` | Done |
| `engines/supertonic/src/main/resources/META-INF/services/com.phonetts.core.engine.EngineProvider` | Done |
| `engines/supertonic/src/test/kotlin/...` (7 test files: provider, inspect, synthesize, frontend, style, indexer parsing) | Done - plain-JVM, no real ONNX weights needed (fakes/injected readers throughout) |
| `docs/research/supertonic-facts.md` | Done - every model fact cited to a live-fetched source, with an explicit VALIDATED/ASSUMPTION table |
| `settings.gradle.kts` (`:engines:supertonic` include) | **Not done** - this document specifies it |
| `app/build.gradle.kts` (`runtimeOnly(project(":engines:supertonic"))`) | **Not done** - this document specifies it |
| Real ONNX weights / a download manifest entry | **Not done** - out of scope (spec: weights are never bundled, and this ticket didn't touch the manifest/downloader) |

## 1. `settings.gradle.kts` - one more include

Add alongside the existing engine includes (currently lines 26-37, alphabetically/insertion-order
doesn't matter to Gradle but matches the existing list's general pattern - insert wherever reads
cleanest, e.g. after `:engines:pytorch`):

```kotlin
include(":engines:supertonic")
```

No other change to this file - `:core`/`:engines:common` are already included and this module
depends on nothing else shared.

## 2. `app/build.gradle.kts` - one more runtime dependency

Add alongside the existing `runtimeOnly(project(":engines:..."))` block (currently lines 196-206):

```kotlin
runtimeOnly(project(":engines:supertonic"))
```

That is the **entire** app-wiring change needed for the engine to be discovered: `SupertonicEngine`
is registered purely via `ServiceLoader`
(`engines/supertonic/src/main/resources/META-INF/services/com.phonetts.core.engine.EngineProvider`),
so once this module is on the runtime classpath, `EngineLoader.discoverProviders()`/`.seed()` find
`SupertonicEngineProvider` automatically - no `AppGraph.kt` change, no runtime registration (this
engine runs on the ALREADY-registered `OnnxRuntime`, `RUNTIME_ID = "onnx"`, exactly like
KittenTTS/Kokoro/MMS/F5-TTS - not a new `Runtime` implementation), no `when(modelType)` switch
anywhere (CLAUDE.md rule 5 - adding a model is registering it, nothing else).

## 3. What is genuinely still missing after §1-§2

Passing the two lines above makes the app **compile and discover** `SupertonicEngine`, but does
**not** make it usable end-to-end yet:

1. **No real weights are shipped anywhere.** Per spec/CLAUDE.md rule 7, model weights are never
   bundled in the APK - they are downloaded into app-private storage via a manifest entry + SHA-256
   verification (see `core/.../manifest`, out of this module's scope). This ticket did not add a
   `Supertone/supertonic-3` entry to that manifest (that manifest lives outside `engines/**`,
   also parent-owned) nor obtain a SHA-256 for any weight file - `docs/research/supertonic-facts.md`
   §8 flags this explicitly. Whoever wires the manifest should point at the exact 6 files this
   engine's `inspect()`/`forcedMatch()` look for:
   `onnx/duration_predictor.onnx`, `onnx/text_encoder.onnx`, `onnx/vector_estimator.onnx`,
   `onnx/vocoder.onnx`, `onnx/tts.json`, `onnx/unicode_indexer.json`, plus at least one
   `voice_styles/<name>.json` (ten real ones - `F1`-`F5`, `M1`-`M5` - ship in the real
   `Supertone/supertonic-3` repo).
2. **Output tensor names are an unverified assumption** (see `SupertonicEngine`'s own KDoc "OUTPUT
   TENSOR NAMES" section and `docs/research/supertonic-facts.md` §6/§8): this engine reads every
   ONNX stage's output **positionally**, assuming exactly one output per graph. The first real
   on-device run against the actual downloaded `.onnx` files is what will confirm or refute that
   assumption - if a graph turns out to report more than one named output, `synthesizeSentence`'s
   `singleTensorOrError`/`singleFloatsOrError` calls will fail loudly with a clear "expected exactly
   1 output tensor, got N" message rather than silently reading the wrong one, so this fails safe
   either way; it just hasn't been proven correct against real weights yet.
3. **The pipeline was never run against real weights in this research environment** - there is no
   ONNX Runtime / real `.onnx` files available here to load-and-run for a golden-output check. Every
   input tensor name, shape, and the speed/duration-routing formula are cross-confirmed between TWO
   independent official reference implementations (`supertonic-py`'s Python and `supertone-inc/
   supertonic`'s Java example) reading the exact same file layout, which is a high confidence level,
   but "two reference implementations agree" is not the same as "verified against the loaded
   graph's own reported I/O signature" - see the ASSUMPTION callouts throughout this engine's KDoc.
4. **The upstream repo carries a "will be archived" notice as of 2026-07-23** (one day before this
   research) - see `docs/research/supertonic-facts.md` §1. The already-published `Supertone/
   supertonic-3` weights remain fetchable and this integration doesn't depend on any live service,
   but don't expect upstream fixes if a real-weights run surfaces a graph-contract surprise.

So the honest state after this ticket: the **Kotlin engine is done and unit-tested** (fingerprinting,
descriptor-building including the discovered `language`/`speed` `ModelParameter`s, four-graph
orchestration wiring, text-frontend character mapping - all plain-JVM tests, no SDK/weights needed).
The **app wiring is two one-line additions** (§1-§2), genuinely trivial once a maintainer applies
them. What remains unproven is exclusively "does this survive contact with the real weights" - items
1-3 above - which requires downloading the real ~400 MiB `onnx/` directory and running it, something
this research-only ticket's environment could not do.
