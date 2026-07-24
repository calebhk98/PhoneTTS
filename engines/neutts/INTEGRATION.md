# Wiring `:engines:neutts` into the app (parent-owned steps)

This module (`:engines:neutts`) is written and COMPILES with no native library present - exactly
like `NativeCosyVoiceRuntime`/`CosyVoiceNative` do before `-PwithCosyVoice=true` is passed, and
exactly like `engines/ggmltts`'s own `INTEGRATION.md` before `-PwithGgmlTts=true`. Per this
ticket's hard rules, `app/build.gradle.kts`, `settings.gradle.kts`, `app/.../AppGraph.kt`, `:core`,
and every other `engines/**` directory were **not** touched (they're shared/parent-owned or another
session's files). This document is the exact diff the parent session needs to apply to finish the
wiring, plus - unlike `engines/ggmltts`'s case - the genuinely new native design work this engine
needs, since (per `docs/research/neutts-facts.md` §6) NeuTTS is **not** one of CrispASR's existing
backends. Nothing here is speculative beyond what's marked PENDING; every claim of fact cites
`docs/research/neutts-facts.md`.

## What already exists (this session's output)

| File | Status |
|---|---|
| `engines/neutts/src/main/kotlin/.../NeuTtsEngine.kt` + `NeuTtsEngineProvider.kt` | Done, unit-tested (JVM-only seam tests - see below) |
| `engines/neutts/src/main/resources/META-INF/services/com.phonetts.core.engine.EngineProvider` | Done |
| `engines/neutts/src/test/kotlin/.../*Test.kt` + `TestSupport.kt` | Done - inspect() fail-closed, descriptor SSOT, phonemizer routing, native-request invariants |
| `docs/research/neutts-facts.md` | Done - every model fact this engine relies on, cited |
| `app/src/main/kotlin/com/phonetts/app/runtime/NeuTtsNative.kt` (JNI declarations) | **Not done** - §2 below specifies it |
| `app/src/main/kotlin/com/phonetts/app/runtime/NativeNeuTtsRuntime.kt` (`NativeTtsRuntime` impl) | **Not done** - §2 below specifies it |
| `app/build.gradle.kts` (`:engines:neutts` on the classpath) | **Not done** - §1 below |
| `-PwithNeuTts` Gradle flag, CMake target, `AppGraph` registration | **Not done** - §1, §3, §5 below |
| Native `.so` (llama.cpp GGUF backbone + NeuCodec decoder, NDK cross-compile) | **Not done** - see §6, genuinely unbuilt, unlike ggmltts's "just link one more CrispASR target" case |

## 1. `app/build.gradle.kts` - add the module to the classpath, plus a new opt-in flag

Two changes, both mirroring the `:engines:ggmltts` precedent exactly:

```kotlin
// Alongside the existing runtimeOnly(project(":engines:ggmltts")) line:
runtimeOnly(project(":engines:neutts"))
```

```kotlin
// A fourth native flag, following the exact buildEspeak/buildCosyVoice/buildGgmlTts pattern:
// NeuTTS is NOT one of CrispASR's 34 backends (docs/research/neutts-facts.md §6, verified
// directly against CrispASR's own docs/tts.md) - this flag builds a SEPARATE native library
// (libphonetts_neutts.so) linking llama.cpp (for the GGUF LLM backbone) and a NeuCodec decoder,
// gated the same opt-in way as the other three bridges so the app still assembles everywhere.
val buildNeuTts = (project.findProperty("withNeuTts") as String?)?.toBooleanStrictOrNull() ?: false

// Any of the four native bridges pulls in the NDK + CMake externalNativeBuild.
val buildNative = buildEspeak || buildCosyVoice || buildGgmlTts || buildNeuTts
```

And extend the two `if (buildNative) { externalNativeBuild { cmake { ... } } }` blocks with one
more define/target pair, parallel to the existing `buildGgmlTts` lines:

```kotlin
if (buildNeuTts) arguments += "-DPHONETTS_BUILD_NEUTTS=ON"
```
```kotlin
if (buildNeuTts) nativeTargets += "phonetts_neutts"
```

## 2. `app/src/main/kotlin/com/phonetts/app/runtime/` - two new Kotlin files (parent writes these)

Mirror `NativeGgmlTtsRuntime`/`GgmlTtsNative`'s shape exactly - a `NativeTtsRuntime` implementation
registered under id `"neutts"` ([`NeuTtsEngine.NATIVE_RUNTIME_ID`][engine]) whose `openTtsSession`
calls a JNI `nativeInit(modelDir, codecDecoderPath, ...)` and whose returned session's
`synthesize()` calls a JNI `nativeSynthesize(handle, text, voiceName)`, both declared in
`NeuTtsNative.kt` as `external fun`s that compile with no `.so` present (Kotlin doesn't need the
library to exist at compile time, only at `System.loadLibrary` call time - same trick
`CosyVoiceNative`/`GgmlTtsNative` use). `isAvailable()` reports whether `System.loadLibrary` (or an
explicit init call) succeeded, exactly like the other two native runtimes.

`RuntimeOptions.extras[NeuTtsEngine.CODEC_DECODER_OPTION_KEY]` carries the on-device path to the
codec-decoder asset the engine discovered from the bundle manifest (§7 below) - `NativeNeuTtsRuntime`
should read it and pass it into `nativeInit` alongside `modelDir` (which points at the directory
holding the `.gguf` backbone).

[engine]: src/main/kotlin/com/phonetts/engines/neutts/NeuTtsEngine.kt

## 3. `app/src/main/cpp/CMakeLists.txt` - one more opt-in subdirectory

Mirror the existing `PHONETTS_BUILD_GGMLTTS` block:

```cmake
# --- NeuTTS Nano JNI bridge: llama.cpp (GGUF LLM backbone) + NeuCodec decoder. NOT a CrispASR
#     backend (docs/research/neutts-facts.md §6) - a genuinely separate native target.
if(PHONETTS_BUILD_NEUTTS)
    add_subdirectory(neutts)
endif()
```

## 4. `app/src/main/cpp/neutts/CMakeLists.txt` + JNI - new, genuinely new native work

Unlike `engines/ggmltts` (which only had to link one more target inside an *already-vendored*
34-backend library), this native bridge has **no existing vendored implementation to link against**
in this repo. It needs, in order:

1. **A llama.cpp checkout** (a new fetch script, `scripts/fetch-neutts-llamacpp.sh`, mirroring
   `scripts/fetch-cosyvoice-ggml.sh`'s shape but pointed at `ggml-org/llama.cpp` rather than
   CrispStrobe/CrispASR - llama.cpp's own README is what NeuTTS's own Python client depends on for
   `.gguf` inference, `docs/research/neutts-facts.md` §2) to run the GGUF backbone and generate
   speech-token ids, driven the same way `neutts/neutts.py` drives `llama-cpp-python`
   (`Llama.from_pretrained(..., filename="*.gguf")`, greedy/sampled decode until
   `<|SPEECH_GENERATION_END|>`, extracting `<|speech_N|>` ids via the pattern
   `neutts.py` itself uses - `docs/research/neutts-facts.md` §2).
2. **A NeuCodec decoder implementation** that turns those integer codes into 24 kHz PCM
   (`codes` shaped `(1, 1, T)` → waveform, per `docs/research/neutts-facts.md` §2). Two honestly
   different paths, **neither built here**:
   - Port NeuCodec's decoder (FSQ dequantize → the codec's upsampling decoder network) to
     ggml/C++ by hand - genuinely new numerical work, unverified to exist anywhere (facts doc §8).
   - OR embed `neuphonic/neucodec-onnx-decoder` (a real, published ONNX export of just the decoder
     half, confirmed to exist - facts doc §1/§7) behind ONNX Runtime as a **second** inference call
     inside the same native library, so the JNI bridge still presents one `synthesize()` call to
     Kotlin even though internally it is (llama.cpp AR loop) → (ONNX Runtime decode). This reuses
     the ONNX Runtime the app already ships (`OnnxRuntime`) rather than requiring a from-scratch
     ggml decoder, at the cost of a second runtime dependency inside one native `.so`.
3. **The reference-audio encode step.** Cloning a voice needs `NeuCodec.encode_code()` on the
   reference `.wav` (16 kHz mono) to get the reference-code prefix (facts doc §3). This should run
   **once, at `openTtsSession()` time**, for every `(ref_audio, ref_text)` entry the manifest names
   (mirroring how `NativeTtsSession.voiceNames` reports whatever the native session baked -
   `NeuTtsEngine`'s own KDoc "VOICES / CLONING" and `voicesFrom()` already assume this), not
   per-`synthesize()` call - so the JNI's `nativeInit` is the natural place to run it, using either
   a ggml-ported NeuCodec **encoder** or (again) an ONNX Runtime call if `neucodec-onnx-decoder`'s
   sibling encoder export exists (not checked in this research pass - encoder ONNX export
   availability is **UNVERIFIED**, only the decoder export was confirmed).
4. **`neutts_jni.cpp`** implementing
   `Java_com_phonetts_app_runtime_NeuTtsNative_nativeInit`/`nativeSynthesize`/`nativeClose`,
   returning `0` on any failure (unknown backend, missing decoder, GGUF load failure) so
   `NativeNeuTtsRuntime.openTtsSession` turns that into a clear `check(handle != 0L)` failure,
   exactly `NativeGgmlTtsRuntime`'s posture.

**Guard**: exactly like `espeak`/`cosyvoice`/`ggmltts`, this must configure and link a **stub**
`libphonetts_neutts.so` when the llama.cpp checkout (or the chosen NeuCodec decoder path) is
absent, so a `-PwithNeuTts=true` build still assembles before the fetch script has been run.

## 5. `app/src/main/kotlin/com/phonetts/app/AppGraph.kt` - one more registration line

```kotlin
val runtimeRegistry =
    RuntimeRegistry().apply {
        register(OnnxRuntime())
        register(NativeCosyVoiceRuntime())
        register(NativeGgmlTtsRuntime())
        register(NativeNeuTtsRuntime()) // NeuTTS Nano's own llama.cpp + NeuCodec bridge
    }
```

Plus the import: `import com.phonetts.app.runtime.NativeNeuTtsRuntime`. Registration is
unconditional and harmless exactly like the other three - `isAvailable()` reports false until
`-PwithNeuTts=true` links a real `.so`, at which point `NeuTtsEngine.inspect()` starts claiming any
bundle carrying a recognized `<name>.gguf` + `<name>.gguf.json` manifest (see §7) and routing it
through this runtime.

## 6. Honest status: what a flag alone does NOT get you

**This is a strictly larger lift than `engines/ggmltts`'s equivalent section**, because that
ticket's native half was "link one more target inside an already-proven, already-vendored
library." This one is not:

1. There is **no existing vendored NeuTTS ggml/C++ implementation** in this repo or (as far as this
   research pass found - `docs/research/neutts-facts.md` §6, §8) confirmed to exist upstream at
   all. `llama.cpp` itself is proven and widely used for GGUF LLM inference generally, but the
   NeuCodec **decode** half (and the reference-audio **encode** half for cloning) has no confirmed
   ggml/C++ port - only a PyTorch implementation and a **decoder-only** ONNX export
   (`neuphonic/neucodec-onnx-decoder`) are confirmed to exist.
2. Even choosing the "reuse ONNX Runtime for the decoder" path (§4.2) still means writing and
   proving a NEW C++ orchestration: llama.cpp GGUF load → AR speech-token loop → speech-token-id
   extraction → ONNX Runtime decode call → PCM. None of this is written yet.
3. **The NDK cross-compile itself still has to succeed for arm64** - the same open item
   `docs/COSYVOICE2.md` and `engines/ggmltts/INTEGRATION.md` both flag for their own native halves
   (issue #46). A NeuTTS native bridge inherits that same unresolved risk on top of being novel
   native work, not proven-pattern work.
4. The `input_format` GGUF-metadata read (`docs/research/neutts-facts.md` §4, §8) that decides
   whether `NeuTtsEngine` phonemizes before calling `synthesize()` is itself something a bundle
   producer must discover and encode into the manifest (§7) - this ticket did not read a raw GGUF's
   metadata header to confirm the exact key/value spelling, only `neutts.py`'s Python-side logic
   for looking it up.

So the honest state after this ticket: the **Kotlin generalization is done and unit-tested**
(fingerprinting a backbone+manifest+voices bundle, descriptor-building, phonemizer routing by
discovered `input_format`, codec-decoder-path threading via `RuntimeOptions.extras` - all on a
plain JVM, `gradle -PskipApp=true :engines:neutts:test :engines:neutts:ktlintCheck
:engines:neutts:detekt`). **Every fact it relies on is cited in `docs/research/neutts-facts.md`.**
The **native half does not exist yet** - no `.so`, no CMake file, no JNI `.cpp`, no fetch script.
A future session doing §1-§5 above, then genuinely building and proving the llama.cpp + NeuCodec
decode path on desktop first (the same "prove on desktop before touching the NDK" order
`docs/COSYVOICE2.md` followed for CosyVoice3), is what turns this from "compiles everywhere,
offered nowhere" into "offered and working on-device."

## 7. The manifest shape `NeuTtsEngine` expects (for whoever produces/ships a bundle)

Alongside the backbone `<name>.gguf` (e.g. `neutts-nano-Q4_0.gguf`,
`docs/research/neutts-facts.md` §1), a `<name>.gguf.json` sidecar:

```json
{
  "sample_rate": 24000,
  "input_format": "phonemes",
  "codec_decoder": "neucodec-decoder.onnx",
  "voices": [
    {
      "id": "dave",
      "name": "Dave",
      "language": "en",
      "ref_audio": "dave.wav",
      "ref_text": "My name is Dave, and um, I'm from London."
    }
  ]
}
```

- `sample_rate`: positive integer, discovered per bundle - NeuCodec always decodes to 24 kHz in
  every source this research pass found (`docs/research/neutts-facts.md` §2), but the engine still
  reads it from the manifest rather than hardcoding it (CLAUDE.md rule 1).
- `input_format`: whatever the GGUF's own embedded `neuphonic.input_format` metadata says
  (`docs/research/neutts-facts.md` §4) - the literal value `"phonemes"`
  ([`NeuTtsEngine.PHONEME_INPUT_FORMAT`][engine]) routes each sentence through
  `EngineContext.phonemizer` before the native call; any other value passes text through unchanged.
- `codec_decoder`: the file name of the NeuCodec decoder asset, which MUST also be present in the
  bundle (`inspect()` refuses otherwise - no decoder, no audio path).
- `voices`: one entry per (reference-audio, reference-transcript) clone voice the bundle ships
  (`docs/research/neutts-facts.md` §3 - this is a cloning model, not a fixed-speaker one).
  `ref_audio` must name a `.wav` file present in the bundle; `ref_text` is its transcript. `name`/
  `language` are optional (default to a prettified id / `"en"`).

`inspect()` claims a bundle only when exactly one `<gguf, manifest>` pair in it is fully valid
(more than one is ambiguous and refused, same as zero); `forcedMatch()` picks the first valid one
instead of refusing on ambiguity, per its contract of never refusing an explicit user choice - see
`NeuTtsEngine`'s own KDoc "FINGERPRINT" for the exact validation rules.

[engine]: src/main/kotlin/com/phonetts/engines/neutts/NeuTtsEngine.kt

## 8. Why no `NativeTtsRequest` / `:core` change

`NativeTtsRequest` today carries only `text` + `voiceName`, and this ticket's scope/hard rules were
explicitly `:engines:neutts` plus this document, not a `:core` seam change. That shape is
sufficient for NeuTTS as designed here: the reference-voice encoding (§4.3) happens once at
`openTtsSession()` time (keyed by the manifest's `voices` list), not per `synthesize()` call, so a
per-call `voiceName` is enough to select which already-encoded reference prompt to reuse - no new
field needed. Similarly, no speed argument was added (`NeuTtsEngine` declares no tunable parameters
at all, honest-closed - see its own KDoc "PARAMETERS" and `docs/research/neutts-facts.md` §5): no
NeuTTS example or API this research pass found exposes one, so there is nothing to route yet.
