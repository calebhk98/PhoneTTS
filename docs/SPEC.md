# Local TTS Android App — Engineering Specification

**Audience:** an engineer building this from scratch.
**Status:** design locked. Build order and architecture below are decided, not suggestions.
**Scope:** a standalone, fully-offline Android text-to-speech app for personal use.

---

## 1. What we are building

A standalone Android app that turns text into speech entirely on-device, with **no network calls at inference time**. It runs several different neural TTS models, lets the user pick a model and a voice, adjust speed, and either listen in real time or export to an audio file.

It targets budget hardware (developed against a Samsung Galaxy A16, ~4GB RAM, no NPU, mid-range CPU) but must run unmodified on better and worse phones. **We do not optimize for one device.** Assume the hardware underneath can change at any time.

### 1.1 Hard requirements (these drive every design decision)

1. **Five models at launch:** CosyVoice2-0.5B, MeloTTS, Piper, KittenTTS, Kokoro-82M.
2. **Two output modes:** real-time streaming playback, and export-to-file. Slow models are allowed — the user may choose to listen to one that can't keep up with playback; it will just be sluggish. File export is the escape hatch for anything too slow for real time.
3. **Speed control** on every model, via the model's own native speed/duration parameter. **Never** by resampling output audio (that shifts pitch).
4. **Voice selection** per model.
5. **Single source of truth:** to add a model, a voice, or a parameter, you edit *one* place. Dropdowns, labels, sliders, and playback config update themselves. No model fact is ever hardcoded in the UI or duplicated across files.
6. **Removable models:** if a model's license ever becomes a problem, deleting its engine removes it cleanly, with no dangling references and no `when(modelType)` switch to hand-edit.
7. **Auto-loading, first-class:** the user can drop a model downloaded from Hugging Face onto the device and the app configures it automatically when it recognizes the family. An auto-loaded model is a first-class citizen — full voice picker, saved, indistinguishable from a built-in.
8. **Auto-load fallback:** if the app cannot identify a dropped-in model, it asks the user to pick which engine handles it, then saves that choice so it never asks again. The user can change that choice later.

### 1.2 Explicit non-goals (for now)

- Not a system-wide Android TTS engine. Standalone app only. (The engine interface is kept clean enough that exposing a `TextToSpeechService` later is possible, but it is out of scope.)
- Not multi-user, not cloud-synced, not published. Personal/local.
- No voice cloning.

---

## 2. The core idea

The app does **not** know about five models. It knows about a **registry of engines**, and each engine advertises what it can do. Everything the UI shows is derived at runtime from that registry and from per-model **descriptors**. Adding, removing, or auto-detecting a model is the same operation seen from different entry points.

Three things vary between models, and each gets its own abstraction:

- **The engine** — loads weights and runs inference.
- **The text frontend** — turns text into whatever the model eats (phoneme IDs, token IDs). This varies *hardest* between models and must be separate from the engine interface.
- **The runtime** — most models run on ONNX Runtime; at least one (CosyVoice2) likely needs a different one.

A fourth abstraction ties them together:

- **The descriptor** — the single authority for every user-visible fact about a model (its voices, its speed range, its sample rate, its display name). The UI reads only from descriptors.

---

## 3. Why the build order is what it is (read this before disagreeing with it)

We implement the models **hardest-first**, not easiest-first.

If the three easy models (Piper, KittenTTS, Kokoro) are built first, the interfaces get born assuming "one ONNX file, espeak phonemes, deterministic output." Then the two awkward models arrive and demand exceptions, and you're refactoring the foundation late. There is no clean way to know your abstraction is right until the awkward cases are in it.

So we build the two that break those assumptions **first**. When they're in, the three easy models are pure conformance — they slot into interfaces that were already shaped by the worst cases. The "does my abstraction survive?" moment happens on day one instead of at the end.

**The cost, stated honestly:** you will not have a model producing clean audio for a while, because you're leading with the complex ones. That's a deliberate trade: slow to first sound, in exchange for zero late-stage abstraction breakage. The seam tests (Section 9) are what keep this from being flying blind.

### 3.1 Difficulty tiers

- **Tier A (easy): Piper, KittenTTS, Kokoro.** Single ONNX model. Phoneme input via espeak-ng. `synthesize` is basically one inference call. (Kokoro has its own g2p, "misaki," but can fall back to espeak.)
- **Tier B (medium): MeloTTS.** Runs an *extra* BERT model at inference for prosody, and its text frontend is language-specific and heavier. Porting that frontend on-device is the real work — not the inference. Its true on-device footprint is larger than "lightweight" marketing implies (the BERT component alone is substantial); measure APK/RAM impact.
- **Tier C (hard): CosyVoice2-0.5B.** LLM-based and **autoregressive** (generates token by token, so slow on CPU and **non-deterministic**). Multi-component (LLM backbone + flow-matching decoder + vocoder). ~1GB+. Likely needs a second runtime, not plain ONNX. This is the one that may be too slow to be *pleasant* on the A16 — that's fine, it goes to file export. Its real job here is to prove the runtime layer is pluggable.

---

## 4. Build order (locked)

**Phase 1 — Skeleton.**
Interfaces (`VoiceEngine`, `TextFrontend`, `Runtime`), the runtime **registry**, the **resolver**, the `EngineManager` (memory/loading), the **dual-consumer audio layer** (streaming + file), and the espeak-ng NDK build (shared by three of the five). No model sings yet; the machinery exists and is tested.

**Phase 2 — The five models**, registered through the runtime registry, hardest-first:
1. CosyVoice2-0.5B — forces the second runtime and the file-export path to exist.
2. MeloTTS — forces the text-frontend abstraction (BERT + tokenizer) to be real.
3. Piper — first Tier-A, proves the clean path end to end.
4. KittenTTS — conformance.
5. Kokoro-82M — conformance.

**Phase 3 — Auto-load.**
This is *almost nothing new*. Auto-load is the **same** `inspect() → resolve → register` pipeline the five built-ins already use, triggered by a file drop instead of app startup. If Phase 2 is built correctly, Phase 3 is a file picker plus a loop over `inspect()` plus the user-pick fallback. That's the payoff of this ordering.

---

## 5. Architecture

### 5.1 The engine interface

Each engine advertises what it can handle and runs inference. It never references another engine.

```kotlin
interface VoiceEngine {
    val id: String
    val displayName: String

    // Probe. Given a downloaded model bundle, can I run it?
    // Return a match (with the info needed to build a descriptor) or null.
    // MUST fail closed: null means "not mine," never a guess.
    fun inspect(bundle: ModelBundle): EngineMatch?

    suspend fun load(descriptor: ModelDescriptor)   // pull weights into memory
    fun unload()                                     // free them

    fun voices(): List<Voice>

    // The ONE generation path. Real-time and file-export both consume this.
    fun synthesize(text: String, voiceId: String, speed: Float): Flow<FloatArray>
}

data class Voice(val id: String, val name: String, val language: String)
```

### 5.2 The text frontend (separate on purpose)

This is where models diverge hardest, so it is **not** part of `VoiceEngine`. It lives inside each engine and the app never sees it.

```kotlin
interface TextFrontend {
    fun toModelInput(text: String, language: String): ModelInput  // phoneme IDs or token IDs
}
```

- Piper / KittenTTS / Kokoro → an espeak-ng-backed frontend (shared).
- MeloTTS → its own frontend (runs the BERT prosody step, language-specific tokenizer).
- CosyVoice2 → a token-based frontend.

### 5.3 The runtime

Keep runtime behind an interface so adding one later touches nothing else.
- ONNX Runtime for Android — Piper, KittenTTS, Kokoro, MeloTTS.
- CosyVoice2 — probably a separate LLM-style runtime. Confirm during Phase 2.1.

### 5.4 The registry (runtime, not static)

The registry holds engine/model registrations **at runtime**. The five built-ins register through the exact same path a sideloaded model does — they are just pre-seeded at startup. **There is no "built-in vs sideloaded" branch downstream.** The only difference is an `origin` field, recorded for display, never used to fork logic.

Consequence: removing a model = removing its engine registration. Nothing else references it. Adding a model = registering it. The UI (Section 7) recomputes itself.

### 5.5 The EngineManager (memory)

On a 4GB phone you **cannot** hold all models loaded at once (MeloTTS+BERT and CosyVoice alone blow the budget). The `EngineManager` keeps **exactly one** engine loaded, calling `unload()` on the previous when the user switches. Design this in from day one — retrofitting it is painful.

### 5.6 The resolver — the single source of truth for model facts

One function turns *any* input (a curated built-in entry, or a dropped-in HF bundle) into an in-memory `ModelDescriptor`. There is exactly one resolve path, so curated and detected models cannot drift apart.

```
resolve(bundle):
    match = engines.firstNotNullOf { it.inspect(bundle) }   // confident auto-detect
    if match == null:
        match = userPicksEngine(bundle)                      // fallback: ask the user (Section 6.2)
    persist(bundle.id -> match.engineId)                     // remember the decision
    return descriptor(match)
```

- A curated built-in is just a **cached/seeded result** of this same step.
- A user's manual pick is stored as the **same kind of override** a curated entry is.
- "Change it later" = editing that one stored mapping.

So every model-to-engine decision — detected, curated, or user-chosen — lives in exactly one place.

### 5.7 The descriptor — the sole authority for user-visible facts

```kotlin
data class ModelDescriptor(
    val modelId: String,
    val engineId: String,
    val displayName: String,
    val origin: Origin,               // BuiltIn | Sideloaded (display only, never branches logic)
    val sampleRate: Int,              // varies per model — playback & WAV read THIS, never a constant
    val voices: List<Voice>,          // the voice dropdown reads THIS
    val speedRange: ClosedFloatingPointRange<Float>, // the speed slider configures itself from THIS
    val defaultVoiceId: String,
    val defaultSpeed: Float,
    // ...anything else the UI or engine needs, added HERE and only here
)
```

**The rule that makes SSOT real:** no model fact ever appears as a literal outside the descriptor/resolver layer. Not a sample rate, not a voice name, not a speed bound. If any of these shows up hardcoded in the UI or an engine, you now have two sources and the guarantee is already broken. Make this a review reflex (and a detekt rule if you can express it): **a model constant outside the resolver layer is a bug.**

Known per-model facts to verify and place in descriptors (do not trust these blindly — confirm against the actual model files you ship):

| Model | Approx params | Sample rate | Notes |
|---|---|---|---|
| Piper | ~5–30M/voice | 16k or 22.05k | voices are independent ONNX files |
| KittenTTS | 15M / 40M / 80M | 24k | dev-preview; API may shift; English-only for now |
| Kokoro-82M | 82M | 24k | 54 voices, 8 languages; own g2p (misaki) w/ espeak fallback |
| MeloTTS | small core + ~94M BERT | 44.1k | multilingual; heavier real footprint |
| CosyVoice2-0.5B | ~500M | **verify** | autoregressive, non-deterministic, multi-component |

---

## 6. Two features that fold into the resolver

### 6.1 Real-time vs file export — one generation path, two consumers

`synthesize()` always returns `Flow<FloatArray>`. That flow is the single source of truth for generated audio. Two consumers sit on top:

- **Real-time:** feed chunks to `AudioTrack` as they arrive, at `descriptor.sampleRate`.
- **File export:** collect the full flow, encode to WAV at `descriptor.sampleRate`.

A slow model just uses the file path. No capability flag, no benchmark gating. The user picks the mode; the file path is the escape hatch.

### 6.2 Auto-load and the user-pick fallback

- **Detected:** every registered engine's `inspect()` is asked. The one that claims the bundle (fail-closed) wins; `resolve()` builds a full descriptor.
- **Not detected:** the app asks the user which engine to use. That choice is what **completes** the descriptor — the chosen engine supplies its family defaults (e.g. "Piper-family → single voice, speed 0.5–2.0"), which is what makes the sideloaded model first-class (the dropdowns have something to render). The choice is saved and editable later.

**Honest scope on auto-detection:** this works for models from *known families that ship their standard companion files* (`config.json`, tokenizer, phoneme map, voice/speaker table). A bare `.onnx` with no side files is often **not self-describing enough** — sample rate, phonemization scheme, and speaker table frequently live outside the graph. So `inspect()` **fails closed**: if nothing matches with confidence, the app refuses to guess and drops to the user-pick fallback. That refusal is a feature, not a gap.

---

## 7. The UI is derived, never authored

The UI holds **zero** model facts. Everything is computed from the registry and descriptors:

- **Model dropdown** ← the registry's list of models. Register one → it appears. Remove one → it vanishes. No menu file edited.
- **Voice dropdown** ← `descriptor.voices`. No voice lists in UI code.
- **Speed slider** ← `descriptor.speedRange` + `descriptor.defaultSpeed`. The control configures its own bounds.
- **Playback / WAV sample rate** ← `descriptor.sampleRate`. Never a constant.
- **Display names/labels** ← `descriptor.displayName`, `Voice.name`.

If adding a fake engine to the registry does **not** make it show up everywhere with no other code change, the single-source-of-truth requirement is broken. (There is a test for exactly this — Section 9.)

---

## 8. Cross-cutting engineering rules

- **All inference off the main thread.** Coroutines. Never block the UI thread.
- **Chunk long text into sentences** and synthesize sequentially so playback (or file writing) can start before the whole thing is generated.
- **Speed always routes to the model's native parameter.** Piper `length_scale`, Kokoro/Kitten/Melo `speed` arg, CosyVoice its own control. Never resample audio to change speed.
- **Never-nesting.** Guard clauses, early `return` / `continue`, in the frontends and the streaming loop. No deep `if` pyramids.
- **No large files / no large functions.** Enforced by detekt (method + class size).
- **Commented code** where intent isn't obvious from names — especially the `inspect()` fingerprints and any model-specific frontend quirks.
- **Model weights are never bundled in the APK.** APK size limits, and you want to add voices/models without shipping an app update. Ship a manifest, download into app-private storage, verify SHA-256, then load.

### 8.1 Pre-commit hooks (git + Gradle)

Block the commit on any failure:
1. **ktlint** — formatting.
2. **detekt** — nesting depth, method/class size (enforces never-nesting + no-large-files).
3. **unit test suite** — the seam tests below.

---

## 9. TDD — test the plumbing, not the audio

Voice quality is the model's job, not this codebase's. So tests target the **deterministic seams** — the wires that, when crossed, break the app regardless of which model is loaded. You should do red tests green tests, before any feature, write a failing test, then write the minimum code to pass that test.

Write tests for:

1. **`inspect()` fail-closed** — claims models of its own family, rejects everything else, never guesses.
2. **`resolve()`**
   - returns a *complete* descriptor for a known bundle,
   - drops to the user-pick branch for an unknown bundle,
   - on the second call for the same model, reads the **saved override** instead of re-detecting.
3. **Registry dynamism (the SSOT guard):** adding a fake engine to the registry makes it appear in the model list with **zero** other changes. This is the one test that actually protects the single-source-of-truth goal from regressing. Do not skip it.
4. **Speed routing:** the slider value reaches the engine's native parameter.
5. **Sample rate routing:** playback and WAV both read `descriptor.sampleRate`.
6. **WAV encoder round-trips.**
7. **Dual consumer:** streaming consumer and file consumer both fully drain the same `Flow`.

**No golden audio hashes for CosyVoice.** It samples autoregressively, so its output is **not reproducible** — an exact-match test will fail nondeterministically. For that one model, test **invariants** instead: output length in the expected range, sample values bounded, no NaNs. (For the deterministic models, a golden-hash-of-audio-for-fixed-input test is fine if you want it, but it's testing the model, not your code.)

---

## 10. Verify before shipping (licensing + facts)

This is a personal/local app, but you still want removability to actually work, and you shouldn't hardcode facts you haven't checked.

- **Licenses:** Kokoro (Apache-2.0), KittenTTS (Apache-2.0), MeloTTS (MIT) are permissive. **Piper:** original code is MIT, but the actively maintained fork may carry a different (possibly copyleft) license — check the specific fork you build against. **CosyVoice2:** confirm its license before relying on it. Requirement #6 (clean removal) exists precisely so any of these can be pulled later without collateral damage.
- **Per-model facts** (sample rate, voice list, speed bounds, param name): confirm against the real model files you ship and put them in descriptors. The table in 5.7 is a starting point, not gospel — CosyVoice2's sample rate in particular is marked "verify."

---

## 11. Suggested first tickets (Phase 1)

1. Define `VoiceEngine`, `TextFrontend`, `Runtime`, `ModelBundle`, `EngineMatch`, `ModelDescriptor`, `Voice`. No implementations.
2. Build the runtime **registry** + `EngineManager` (one-loaded-at-a-time). Test 9.3 against a fake engine.
3. Build the **resolver** with the user-pick fallback and persisted override. Tests 9.2.
4. Build the **dual-consumer audio layer** (AudioTrack streaming + WAV writer over one `Flow`). Tests 9.5, 9.6, 9.7.
5. Compile **espeak-ng** with the NDK, ship its data files, wrap it as the shared espeak frontend. (Fiddly — budget time.)
6. Wire the **manifest + download + SHA-256 + app-private storage** path.
7. Set up **git pre-commit** (ktlint, detekt, tests).

At the end of Phase 1 nothing produces audio, but every seam is testable and green. Then start Phase 2 at CosyVoice2.
