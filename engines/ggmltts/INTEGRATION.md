# Wiring `:engines:ggmltts` into the app (parent-owned steps)

This module (`:engines:ggmltts`) and the two app-module Kotlin files it depends on
(`app/src/main/kotlin/com/phonetts/app/runtime/GgmlTtsNative.kt` and
`NativeGgmlTtsRuntime.kt`) are already written and COMPILE with no native library present —
exactly like `NativeCosyVoiceRuntime`/`CosyVoiceNative` do before `-PwithCosyVoice=true` is
passed. Per this ticket's hard rules, `app/build.gradle.kts`, `settings.gradle.kts`, and
`app/.../AppGraph.kt` were **not** touched (they're shared/parent-owned files). This document is
the exact diff the parent session needs to apply to finish the wiring. Nothing here is
speculative — every snippet mirrors the already-merged CosyVoice bridge line for line.

## What already exists (this session's output)

| File | Status |
|---|---|
| `engines/ggmltts/src/main/kotlin/.../GgmlTtsEngine.kt` + `GgmlTtsEngineProvider.kt` | Done, tested (28 passing seam tests) |
| `engines/ggmltts/src/main/resources/META-INF/services/com.phonetts.core.engine.EngineProvider` | Done |
| `app/src/main/kotlin/com/phonetts/app/runtime/GgmlTtsNative.kt` | Done — JNI declarations, compiles with no `.so` |
| `app/src/main/kotlin/com/phonetts/app/runtime/NativeGgmlTtsRuntime.kt` | Done — `NativeTtsRuntime` impl, compiles with no `.so` |
| `app/build.gradle.kts` (`:engines:ggmltts` on the classpath) | **Already present** — see line 203, `runtimeOnly(project(":engines:ggmltts"))`, added when the module was scaffolded. So the Kotlin engine is *already discovered* by `ServiceLoader` in any app build today; only the **native** half below is still pending. |
| `-PwithGgmlTts` Gradle flag, CMake target, `AppGraph` registration | **Not done** — this document specifies them |
| Native `.so` (NDK cross-compile) | **Not done** — see "What remains genuinely unproven" below |

## 1. `app/build.gradle.kts` — the new opt-in flag

Add a third native flag, following the exact `buildEspeak`/`buildCosyVoice` pattern (currently
lines 17–27):

```kotlin
// The generalized ggml TTS bridge (docs/research/runtime-feasibility-2026-07.md §2): CrispASR
// is a 34-backend project, and CosyVoice3 already proves the NativeTtsRuntime seam against it.
// This flag builds a SECOND, backend-parameterized native lib (libphonetts_ggmltts.so) that
// links whichever additional CrispASR backend(s) are configured (Piper/Kokoro/MeloTTS/... —
// see app/src/main/cpp/ggmltts/CMakeLists.txt), gated the same opt-in way as espeak/CosyVoice so
// the app still assembles everywhere. Run scripts/fetch-ggmltts-crispasr.sh first (§4 below).
val buildGgmlTts = (project.findProperty("withGgmlTts") as String?)?.toBooleanStrictOrNull() ?: false

// Any of the three native bridges pulls in the NDK + CMake externalNativeBuild.
val buildNative = buildEspeak || buildCosyVoice || buildGgmlTts
```

And extend the two `if (buildNative) { externalNativeBuild { cmake { ... } } }` blocks (lines
89–116 and 181–188) with one more define/target pair, exactly parallel to the existing
`buildCosyVoice` lines:

```kotlin
if (buildGgmlTts) arguments += "-DPHONETTS_BUILD_GGMLTTS=ON"
```
```kotlin
if (buildGgmlTts) nativeTargets += "phonetts_ggmltts"
```

No other line in `app/build.gradle.kts` changes. `:engines:ggmltts` is already on the
`runtimeOnly` classpath (line 203) — nothing to add there.

## 2. `app/src/main/cpp/CMakeLists.txt` — one more opt-in subdirectory

Mirror the existing `PHONETTS_BUILD_COSYVOICE` block at the bottom of the file:

```cmake
# --- Generalized ggml TTS JNI bridge (a second CrispASR backend beyond CosyVoice3). See
#     engines/ggmltts/INTEGRATION.md and docs/research/runtime-feasibility-2026-07.md §2.
if(PHONETTS_BUILD_GGMLTTS)
    add_subdirectory(ggmltts)
endif()
```

## 3. `app/src/main/cpp/ggmltts/CMakeLists.txt` — new file (parent writes this)

Mirror `app/src/main/cpp/cosyvoice/CMakeLists.txt`'s structure (stub-when-missing, real build
when the CrispASR checkout is present), with two differences from the CosyVoice version:

1. It links whichever **additional** CrispASR backend targets are wanted (`piper`, `kokoro`,
   `melotts`, ... — check CrispASR's own `CMakeLists.txt`/`docs/tts.md` for each backend's real
   target name; the research doc's claim that these are "production-ready" is CrispASR's own
   label, not independently re-verified by this ticket — see "Caveats" in
   `docs/research/runtime-feasibility-2026-07.md` §2).
2. Its JNI file (`ggmltts_jni.cpp`, also new) implements `Java_com_phonetts_app_runtime_GgmlTtsNative_nativeInit`
   etc. taking a `backend` `jstring` first argument and dispatching to the matching linked target
   — this is the literal "generalize past a hardcoded id" step the ticket asked for. A minimal
   dispatch shape:

   ```cpp
   // pseudocode — real signatures come from crispasr's per-backend headers
   JNIEXPORT jlong JNICALL
   Java_com_phonetts_app_runtime_GgmlTtsNative_nativeInit(
       JNIEnv* env, jclass, jstring jbackend, jstring jmodelDir,
       jint threads, jfloat temperature, jlong seed) {
     std::string backend = jstringToUtf8(env, jbackend);
     if (backend == "cosyvoice") { /* NOT expected here — CosyVoice3 keeps its own bridge */ }
     if (backend == "piper")    return piper_tts_init(...);
     if (backend == "kokoro")   return kokoro_tts_init(...);
     if (backend == "melotts")  return melotts_tts_init(...);
     return 0; // unknown/unlinked backend -> fail closed, matches NativeGgmlTtsRuntime's check()
   }
   ```

   Only link the backend(s) actually configured (mirror the CosyVoice CMakeLists'
   `EXCLUDE_FROM_ALL` + explicit `target_link_libraries` trick, so the other ~30 CrispASR
   backends are never even compiled).

   `EXCLUDE_FROM_ALL` on the shared `add_subdirectory(crispasr ...)` call means this can safely
   coexist with `cosyvoice/CMakeLists.txt`'s own `add_subdirectory` of the *same* CrispASR
   checkout without double-building it — CMake dedupes by target name across both, since both
   consumers can point `CRISPASR_SRC_DIR` at the SAME fetched tree (§4).

**Guard**: exactly like `espeak`/`cosyvoice`, this file must configure and link a **stub**
`libphonetts_ggmltts.so` when the CrispASR checkout is absent, so a `-PwithGgmlTts=true` build
still assembles even before `scripts/fetch-ggmltts-crispasr.sh` has been run. The stub's
`nativeInit` returns 0 for every backend — `NativeGgmlTtsRuntime.openTtsSession` already turns
that into a clear `check(handle != 0L)` failure, and `isAvailable()` is governed by
`System.loadLibrary` succeeding at all, not by which backends the stub answers for.

## 4. Fetch script — reuse, don't duplicate

CosyVoice3 and every other CrispASR backend live in the **same upstream repository**
(CrispStrobe/CrispASR). `scripts/fetch-cosyvoice-ggml.sh` already clones it to
`app/src/main/cpp/cosyvoice/crispasr`. The cleanest option is a thin second script,
`scripts/fetch-ggmltts-crispasr.sh`, that clones (or symlinks) the SAME checkout to
`app/src/main/cpp/ggmltts/crispasr` — copy `fetch-cosyvoice-ggml.sh` almost verbatim, changing
only `DEST`. (A shared checkout with two CMake consumers pointed at it is also viable and saves
disk, but keeping them independent matches the existing one-bridge-one-fetch-script convention
and avoids coupling the CosyVoice and ggmltts opt-in flags together.)

## 5. `app/src/main/kotlin/com/phonetts/app/AppGraph.kt` — one more registration line

Alongside the existing runtime registry block (currently lines 80–84):

```kotlin
val runtimeRegistry =
    RuntimeRegistry().apply {
        register(OnnxRuntime())
        register(NativeCosyVoiceRuntime())
        register(NativeGgmlTtsRuntime()) // generalized ggml backends (Piper/Kokoro/MeloTTS/…)
    }
```

Plus the import: `import com.phonetts.app.runtime.NativeGgmlTtsRuntime`. Registration is
unconditional and harmless exactly like `NativeCosyVoiceRuntime()` — `isAvailable()` reports
false until `-PwithGgmlTts=true` links a real `.so`, at which point `GgmlTtsEngine.inspect()`
starts claiming any bundle carrying a recognized `<name>.gguf` + `<name>.gguf.json` manifest
pair (see the engine's own KDoc for the manifest shape) and routing it through this runtime.

## 6. Honest status: what a flag alone does NOT get you

**Passing `-PwithGgmlTts=true` does not make this device-testable in the released APK by
itself.** Three things still have to happen, in order, none of them done by this ticket:

1. `scripts/fetch-ggmltts-crispasr.sh` must actually be run (§4) so the CMake `EXISTS` guard finds
   real source instead of building the stub.
2. `app/src/main/cpp/ggmltts/CMakeLists.txt` + `ggmltts_jni.cpp` (§2–3) must be written and must
   **successfully link** against whichever CrispASR backend target(s) are chosen — this is
   genuinely new native code, not yet written or built by this ticket (`:engines:ggmltts`'s scope
   was the Kotlin generalization, per the hard rules above).
3. The **NDK cross-compile itself has to succeed for arm64**. This is exactly
   `docs/COSYVOICE2.md`'s own open item: CosyVoice3's native build is "proven on desktop
   (`run_cosy_native.sh`)" but the Android NDK link is separately flagged as the "remaining
   unverified step" (issue #46 in the ticket's own words). Adding a second CrispASR backend
   inherits that SAME unresolved risk — it does not remove it. Until #46 is closed for CosyVoice,
   there is no reason to expect a *second* CrispASR native target links cleanly for arm64 either;
   it should be treated as equally unproven, not smaller because "it's just one more target."

So the honest state after this ticket: the **Kotlin generalization is done and unit-tested**
(fingerprinting, descriptor-building, backend routing via `RuntimeOptions.extras` — 28 JVM tests,
all passing, `gradle -PskipApp=true :engines:ggmltts:test :engines:ggmltts:ktlintCheck
:engines:ggmltts:detekt`). The **native half is scaffolded and documented but not built** — no
`.so`, no CMake file, no JNI `.cpp` yet exist for `ggmltts`. A future session doing §1–§5 above,
then resolving #46's NDK link for at least one real backend (Piper is the best-evidenced one —
`cstr/piper-voices-GGUF`'s own README confirms `crispasr --backend piper` is a real, working CLI
invocation against that exact GGUF conversion), is what turns this from "compiles everywhere,
offered nowhere" into "offered and working on-device."

## 7. The manifest shape `GgmlTtsEngine` expects (for whoever produces/ships a bundle)

Per-voice, alongside `<name>.gguf`, a `<name>.gguf.json` sidecar:

```json
{ "backend": "piper", "sample_rate": 22050, "language": "en" }
```

`backend` must be a real CrispASR `--backend` id whatever the JNI dispatch (§3) links against;
`sample_rate` is a positive integer (never fabricated by the engine — CLAUDE.md rule 1, and
genuinely varies per Piper voice: 16 kHz vs 22.05 kHz). A bundle with multiple voices must have
every sidecar agree on `backend`/`sample_rate` for `inspect()` to auto-claim it (ambiguous
otherwise); `forcedMatch()` is more permissive and takes the first entry, per its contract of
never refusing an explicit user choice.

## 8. Why no `NativeTtsRequest` / `:core` change

A generalized backend like Piper genuinely HAS a native speed knob (`length_scale`), unlike
CosyVoice3. This ticket did not add one to `NativeTtsRequest` (which today carries only `text` +
`voiceName`) because that is a shared `:core` type every `NativeTtsRuntime` consumer — including
the already-shipped CosyVoice bridge — depends on, and this ticket's scope/hard-rules were
explicitly the `:engines:ggmltts` module plus the two new app-runtime files, not a `:core` seam
change. `GgmlTtsEngine` therefore declares **no** tunable parameters for now (honest-closed,
exactly CosyVoice3's own posture) rather than silently ignoring a speed value or resampling
output to fake it (which CLAUDE.md rule 2 forbids outright). Threading a native speed argument
through `NativeTtsRequest` (and from there into `GgmlTtsNative.nativeInit`/a new
`nativeSynthesizeWithSpeed`) is real, valuable follow-up work, but it is a `:core` decision for
the parent/maintainer to make deliberately, not a side effect of this module.
