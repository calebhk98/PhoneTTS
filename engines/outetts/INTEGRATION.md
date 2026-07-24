# Wiring `:engines:outetts` into the app (parent-owned steps)

This module (`:engines:outetts`) is already written and COMPILES with no native library present -
exactly like `NativeCosyVoiceRuntime`/`CosyVoiceNative` and `NativeGgmlTtsRuntime`/`GgmlTtsNative`
do before their respective `-Pwith*` flags are passed. Per this ticket's hard rules,
`app/build.gradle.kts`, `settings.gradle.kts`, and `app/.../AppGraph.kt` were **not** touched
(they're shared/parent-owned files), and no `app/src/main/kotlin/...` file was written either (see
§8 for why - unlike `engines/ggmltts`, this ticket did not scaffold the two app-module runtime
files, because a real bridge to write them against does not exist upstream yet). This document is
the exact diff the parent session needs to apply, plus an honest account of what is genuinely NOT
yet buildable and why.

## ⚠️ Read this before shipping a checkpoint in a paid app

**License varies per OuteTTS checkpoint, and the checkpoint with the best license is the one whose
native runtime does not exist yet.** Full sourcing in `docs/research/outetts-facts.md` §0/§5; the
one-line version:

- `OuteTTS-1.0-0.6B-GGUF` (smallest, newest, **Apache-2.0** - fully permissive, fine for a paid
  app) needs a **DAC** audio decoder. llama.cpp's native DAC decoder is an **unmerged draft PR**
  (`ggml-org/llama.cpp#12794`, confirmed still `Draft` when read live on 2026-07-24) - there is
  currently **no working native (non-Python) way to run this checkpoint at all**, on Android or
  anywhere else.
- `OuteTTS-0.2-500M-GGUF` and `OuteTTS-0.3-{500M,1B}-GGUF` use **WavTokenizer**, whose llama.cpp
  decoder IS merged and working today (`llama-tts`, `tools/tts/`) - but their licenses are
  **CC-BY-NC-4.0** (0.2, non-commercial), **CC-BY-SA-4.0** (0.3-500M, share-alike/copyleft), and
  **CC-BY-NC-SA-4.0** (0.3-1B, non-commercial) respectively. None of these three is a clean fit
  for a paid app either - 0.2 and 0.3-1B are outright non-commercial, and 0.3-500M's share-alike
  term needs a lawyer's read before bundling it for paying users.

**There is no OuteTTS checkpoint today that is both (a) fully permissively licensed and (b)
runnable through a native runtime that actually exists.** This is a genuinely harder situation
than `engines/ggmltts` (where the native *library* - CrispASR - exists and only Android NDK-
linking was unproven); here, the DAC decoder *itself* is not yet implemented anywhere outside
Python/PyTorch. Whoever decides which checkpoint ships needs to weigh that tradeoff explicitly,
not assume "smallest + Apache-2.0" is automatically the shippable choice.

## What already exists (this session's output)

| File | Status |
|---|---|
| `engines/outetts/src/main/kotlin/.../OuteTtsEngine.kt` + `OuteTtsEngineProvider.kt` | Done, tested (seam tests covering inspect fail-closed, license/decoder discovery, speed-lock, synthesize invariants) |
| `engines/outetts/src/main/resources/META-INF/services/com.phonetts.core.engine.EngineProvider` | Done |
| `docs/research/outetts-facts.md` | Done - every checkpoint's license, decoder, sample rate, default speaker, and native-support status, each cited to a primary source |
| `app/src/main/kotlin/com/phonetts/app/runtime/OuteTtsNative.kt` (JNI declarations) | **Not done** - unlike `engines/ggmltts`'s session, this ticket did NOT scaffold this file (see §8) |
| `app/src/main/kotlin/com/phonetts/app/runtime/NativeOuteTtsRuntime.kt` (`NativeTtsRuntime` impl) | **Not done** - same reason, §8 |
| `app/build.gradle.kts` (`:engines:outetts` on the classpath) | **Not done** - §1 below |
| `-PwithOuteTts` Gradle flag, CMake target, `AppGraph` registration | **Not done** - §1-§5 below |
| Native `.so` (NDK cross-compile against a real llama.cpp OuteTTS+decoder build) | **Not done, and cannot be done yet** - the upstream decoder code this would link against does not exist (see the caveat above and §5) |

## 1. `app/build.gradle.kts` - add the module to the classpath and a new opt-in flag

Add the module dependency alongside the other engine modules (current block, `app/build.gradle.kts`
around line 196-206):

```kotlin
dependencies {
    // ...
    runtimeOnly(project(":engines:outetts"))
}
```

Then, following the exact `buildEspeak`/`buildCosyVoice` pattern (currently lines 17-27):

```kotlin
// The OuteTTS native bridge (docs/research/outetts-facts.md): llama.cpp's `tools/tts` runs OuteTTS
// 0.2/0.3 (WavTokenizer decoder) today; a DAC-decoder build for OuteTTS 1.0 depends on an unmerged
// upstream PR (ggml-org/llama.cpp#12794) - see engines/outetts/INTEGRATION.md's license caveat
// before picking which checkpoint this flag is meant to build against.
val buildOuteTts = (project.findProperty("withOuteTts") as String?)?.toBooleanStrictOrNull() ?: false

// Any native bridge pulls in the NDK + CMake externalNativeBuild.
val buildNative = buildEspeak || buildCosyVoice || buildOuteTts // (extend whatever this line already is)
```

And extend the two `if (buildNative) { externalNativeBuild { cmake { ... } } }` blocks (mirroring
the existing `buildCosyVoice`/`buildGgmlTts`-style lines) with:

```kotlin
if (buildOuteTts) arguments += "-DPHONETTS_BUILD_OUTETTS=ON"
```
```kotlin
if (buildOuteTts) nativeTargets += "phonetts_outetts"
```

## 2. `app/src/main/cpp/CMakeLists.txt` - one more opt-in subdirectory

Mirror the existing `PHONETTS_BUILD_COSYVOICE`/`PHONETTS_BUILD_GGMLTTS` blocks:

```cmake
# --- OuteTTS JNI bridge. See engines/outetts/INTEGRATION.md and docs/research/outetts-facts.md.
if(PHONETTS_BUILD_OUTETTS)
    add_subdirectory(outetts)
endif()
```

## 3. `app/src/main/cpp/outetts/CMakeLists.txt` - new file (parent writes this, and only once
   there is something real to link)

Unlike `engines/ggmltts`'s CrispASR bridge (which links an already-built upstream library), this
one genuinely has **no working upstream target to point at yet** for the Apache-2.0 checkpoint:

- **WavTokenizer path (0.2/0.3 checkpoints)**: llama.cpp's merged `tools/tts/tts.cpp` is a real,
  working `llama-tts` CLI today. A JNI wrapper around it is buildable NOW, against a vendored
  llama.cpp checkout (mirror `cosyvoice`'s `EXCLUDE_FROM_ALL` + explicit
  `target_link_libraries` pattern, fetched the same way `scripts/fetch-cosyvoice-ggml.sh` fetches
  CrispASR). This is real, available work a future session can do - but it only runs the
  non-commercial (0.2) or share-alike (0.3-500M) / non-commercial (0.3-1B) checkpoints (see the
  license caveat above).
- **DAC path (1.0 checkpoints, Apache-2.0)**: **genuinely blocked upstream.** `ggml-org/llama.cpp
  #12794` is the only known attempt at a native DAC decoder for llama.cpp and it is an unmerged
  draft whose own description says "the decoder layers from DAC need to be implemented." Options
  once that lands (or is otherwise supplied): (a) wait for/help finish #12794 and vendor it the
  same way as CosyVoice3/CrispASR, or (b) port a DAC decoder from a working non-llama.cpp ggml
  implementation (a PR comment on #12794 points at `foldl/chatllm.cpp` as having one - not
  independently verified by this ticket). Neither is a small task; treat it as its own future
  ticket, not a "just flip the flag" item.

**Guard**: whichever path is built first, mirror `espeak`/`cosyvoice`/`ggmltts`: configure and
link a **stub** `libphonetts_outetts.so` when the vendored source is absent, so
`-PwithOuteTts=true` still assembles before the fetch script has run. The stub's `nativeInit`
returns 0 - `NativeOuteTtsRuntime.openTtsSession` should turn that into a `check(handle != 0L)`
failure exactly like `NativeGgmlTtsRuntime` does, and `isAvailable()` is governed by
`System.loadLibrary` succeeding, not by which decoder the stub answers for.

## 4. `app/src/main/kotlin/com/phonetts/app/runtime/OuteTtsNative.kt` +
   `NativeOuteTtsRuntime.kt` - new files (parent writes these)

Mirror `GgmlTtsNative.kt`/`NativeGgmlTtsRuntime.kt` line-for-shape: a `NativeTtsRuntime` impl whose
`openTtsSession(modelDir, options)` calls a JNI `nativeInit(modelDir, decoderType, decoderFileName,
threads, temperature, seed)` - reading `decoderType`/`decoderFileName` from
`options.extras[OuteTtsEngine.DECODER_TYPE_OPTION_KEY]`/`[OuteTtsEngine.DECODER_FILE_OPTION_KEY]`
(see `OuteTtsEngine`'s own KDoc "DECODER ROUTING") - so the native side knows which decoder GGUF
inside `modelDir` to load alongside the LLM GGUF. `NativeTtsSession.voiceNames` should report
whichever `*.speaker.json` profiles the native side found in `modelDir` (mirrors
`GgmlTtsEngine.voicesFrom`'s "session voices are the SSOT once loaded" contract).

This ticket did not scaffold these two files (unlike `engines/ggmltts`'s own session, which wrote
its two app-module Kotlin files even before the native `.so` existed) because, per §3, there is
currently no real native target - not even a stub-worthy one with a known JNI signature - to write
Kotlin declarations against without guessing at an ABI that doesn't exist yet. Writing them now
would mean inventing a JNI contract with nothing upstream to verify it against, which is exactly
the kind of unverified fact CLAUDE.md rule 1 and this ticket's "never fabricate" instruction rule
out. A future session doing §3 first, then §4 against whatever real signature results, is the
correct order.

## 5. `app/src/main/kotlin/com/phonetts/app/AppGraph.kt` - one more registration line

Alongside the existing runtime registry block (currently `app/src/main/kotlin/com/phonetts/app/AppGraph.kt`
lines 86-101):

```kotlin
val runtimeRegistry =
    RuntimeRegistry().apply {
        register(OnnxRuntime())
        register(NativeCosyVoiceRuntime())
        register(ExecuTorchRuntime())
        register(NativeGgmlTtsRuntime())
        register(NativeOuteTtsRuntime()) // OuteTTS's own native GGUF bridge - see engines/outetts/INTEGRATION.md
        register(LiteRtRuntime { durableErrorLog.record(System.currentTimeMillis(), "litert", it) })
    }
```

Plus the import: `import com.phonetts.app.runtime.NativeOuteTtsRuntime`. Registration is
unconditional and harmless exactly like `NativeGgmlTtsRuntime()` - `isAvailable()` reports false
until `-PwithOuteTts=true` links a real `.so`, at which point `OuteTtsEngine.inspect()` starts
claiming any bundle carrying the manifest shape in §7 and routing it through this runtime. **This
line can only be added once §4 exists** (it references a class this ticket did not write).

## 6. Fetch script - once there's something to fetch

Once §3's WavTokenizer path (or, later, a DAC path) is real, add `scripts/fetch-outetts-*.sh`
following the exact convention `scripts/fetch-cosyvoice-ggml.sh`/`fetch-ggmltts-crispasr.sh`
already establish (clone the upstream checkout to `app/src/main/cpp/outetts/<vendor>`).

## 7. The bundle manifest shape `OuteTtsEngine` expects (for whoever produces/ships a bundle)

Per model, in one directory:

- `<llmId>.gguf` - the OuteTTS LLM backbone weights, plus a `<llmId>.gguf.json` sidecar:
  ```json
  { "decoder": "dac", "decoder_file": "dac-speech-v1.0.gguf", "sample_rate": 24000, "license": "Apache-2.0" }
  ```
  `decoder` is `"wavtokenizer"` or `"dac"` per the checkpoint (facts doc §3); `decoder_file` MUST
  name a `.gguf` file actually present in the same bundle (`OuteTtsEngine.inspect()` refuses the
  bundle otherwise - it will not guess); `sample_rate` is 24000 for every checkpoint verified so
  far (facts doc §3) but is read from the manifest, never assumed; `license` is the checkpoint's
  EXACT license string (facts doc §0) - this is what ends up in the picker's model name and in
  `assetPaths[OuteTtsEngine.LICENSE_ASSET_KEY]`, so get it right per checkpoint.
- `<decoderFileName>` - the decoder GGUF itself (WavTokenizer or DAC), named by `decoder_file`
  above. No `.gguf.json` sidecar of its own (that would make it look like a second LLM candidate
  and `inspect()` would refuse the bundle as ambiguous - keep exactly one manifest per bundle).
- One or more `<voiceId>.speaker.json` - OuteTTS speaker profiles (facts doc §4's exact schema:
  `{text, words[], global_features, interface_version}`), one per voice this bundle offers. A
  bundle with zero of these is refused by both `inspect()` and `forcedMatch()` - there is no voice
  to synthesize with.

A bundle whose LLM+manifest is ambiguous (zero or more than one qualifying `<name>.gguf` +
`<name>.gguf.json` pair) is refused by `inspect()`; `forcedMatch()` is more permissive and picks
the first candidate instead of refusing, per its contract of never refusing an explicit user
choice - but it still throws if there is no candidate at all, or no speaker profile at all, since
neither can be invented.

## 8. Why this session did NOT scaffold the app-module Kotlin files (unlike `engines/ggmltts`)

`engines/ggmltts`'s session could write `GgmlTtsNative.kt`/`NativeGgmlTtsRuntime.kt` compiling
against no `.so` because CrispASR - the thing those files' JNI declarations describe - is a real,
existing upstream library with a known (if not yet Android-linked) C ABI. OuteTTS 1.0's DAC
decoder has **no such reference implementation in any native (C/C++/ggml) codebase** to describe
(facts doc §5 - even the OuteTTS Python library's own DAC path calls PyTorch, not a ggml decoder).
Inventing JNI function signatures for a native module that doesn't exist anywhere, upstream or
otherwise, would be exactly the kind of unverified fabrication this ticket's hard rules forbid
("NEVER fabricate codec details ... Unverified → fail closed + mark UNVERIFIED"). The Kotlin engine
above is fully real and tested against the seam `:core` already defines
(`NativeTtsRuntime`/`NativeTtsSession`/`NativeTtsRequest`); the two app-module files are the
correct next step for a session that either finishes #12794 upstream first, or scopes down to the
WavTokenizer-only (0.2/0.3) path where a real binary to wrap already exists.
