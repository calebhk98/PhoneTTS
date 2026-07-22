# ExecuTorch integration — exact wiring for the parent

This module (`:engines:executorch`) and `app/src/main/kotlin/com/phonetts/app/runtime/ExecuTorchRuntime.kt`
are both already written. Per the session's hard rules, this agent did **not** touch
`app/build.gradle.kts`, `settings.gradle.kts`, or `AppGraph.kt` — the three edits below are
exactly what the parent needs to make ExecuTorch ship in the main APK.

## 1. `app/build.gradle.kts` — add the AAR dependency

```kotlin
dependencies {
    // ...
    implementation(libs.executorch.android) // or the literal coordinate below
}
```

or, without a version-catalog entry:

```kotlin
dependencies {
    implementation("org.pytorch:executorch-android:1.3.1")
}
```

**Verified coordinate + version** (not guessed): `org.pytorch:executorch-android`, latest release
**`1.3.1`**. Confirmed directly against Maven Central's authoritative metadata —

```
$ curl -sS https://repo1.maven.org/maven2/org/pytorch/executorch-android/maven-metadata.xml
  <latest>1.3.1</latest>
  <release>1.3.1</release>
```

— and the artifact directory listing (`.aar`, `.pom`, `.module`, sources/javadoc jars, all present
and signed) at `repo1.maven.org/maven2/org/pytorch/executorch-android/1.3.1/`. The `.aar` itself is
`executorch-android-1.3.1.aar`, **7,177,348 bytes** (~6.8 MiB), license BSD-3-Clause, published by
the `pytorch` org (`https://github.com/pytorch/executorch/`).

The published Gradle module metadata (`.module`) declares three transitive runtime deps that
Gradle resolves automatically — no extra lines needed unless the app wants to pin versions
explicitly:

```
org.jetbrains.kotlin:kotlin-stdlib:1.9.23   (compile)
com.facebook.fbjni:fbjni:0.7.0               (runtime)
com.facebook.soloader:nativeloader:0.10.5    (runtime)
androidx.core:core-ktx:1.13.1                (runtime)
```

If a version catalog entry is preferred, add to `gradle/libs.versions.toml` next to the existing
`onnxruntime` entry:

```toml
[versions]
executorch = "1.3.1"

[libraries]
executorch-android = { module = "org.pytorch:executorch-android", version.ref = "executorch" }
```

### APK size impact

The AAR is ~6.8 MiB compressed, bundling `libexecutorch.so` for **two ABIs**
(`arm64-v8a` + `x86_64` — VERIFIED from the official docs page, "Using ExecuTorch on Android").
That is the runtime + XNNPACK CPU backend + portable/optimized/quantized kernels + LLaMA-specific
custom ops, all in one shared library — comparable in shape to what `onnxruntime-android` already
adds. No `armeabi-v7a`/`x86` slices exist for this artifact, so if the app ships those ABIs,
ExecuTorch simply won't be available there (`Module`'s static `NativeLoader.loadLibrary` throws,
`ExecuTorchRuntime.isAvailable()` catches it and returns `false` — fails safe per the ticket).

### NDK / native build

**None needed.** Unlike the opt-in `-PwithEspeak`/`-PwithCosyVoice` bridges, this is a **prebuilt**
AAR — no CMake/NDK cross-compilation, no `externalNativeBuild` gating. It ships unconditionally,
same as `onnxruntime-android`.

## 2. `AppGraph.kt` — register the runtime

```kotlin
import com.phonetts.app.runtime.ExecuTorchRuntime
// ...
val runtimeRegistry =
    RuntimeRegistry().apply {
        register(OnnxRuntime())
        register(NativeCosyVoiceRuntime())
        register(ExecuTorchRuntime()) // <-- add this line
    }
```

Runtime id: **`"executorch"`** (`ExecuTorchRuntime.ID`). Engines ask for it by that string via
`context.runtimes.get("executorch")` — `ExecuTorchKokoroEngine` (this module) already does.

`ExecuTorchRuntime.isAvailable()` is always-on (no build flag) but fails safe: it forces
`org.pytorch.executorch.Module`'s static initializer (which loads `libexecutorch.so`) via an
explicit `Class.forName(..., initialize = true, ...)` and catches any `LinkageError` —
registration is therefore harmless even on an ABI without the native lib.

## 3. Nothing else

No `settings.gradle.kts` change is needed — `include(":engines:executorch")` and the
`runtimeOnly(project(":engines:executorch"))` line in `app/build.gradle.kts` are already present
(this module was pre-scaffolded). Once step 1 adds the AAR dependency and step 2 registers the
runtime, `ExecuTorchKokoroEngineProvider` is discovered automatically via
`META-INF/services/com.phonetts.core.engine.EngineProvider` — no registry edit, per spec rule 5.

---

## What was verified vs. assumed — read before trusting this on a device

### Verified (cited, not guessed)

- **AAR coordinate + version**: `org.pytorch:executorch-android:1.3.1`, via Maven Central's
  `maven-metadata.xml` and the artifact directory listing (see §1).
- **Java API shape**: `Module.load(path, loadMode, numThreads)`, `Module.execute(methodName,
  EValue...)` (positional args, returns `EValue[]`), `Tensor.fromBlob(data, shape)` /
  `Tensor.getDataAs*Array()` / `Tensor.dtype()` / `Tensor.shape()`, `EValue.from(Tensor)` /
  `EValue.toTensor()` — read directly from
  `extension/android/executorch_android/src/main/java/org/pytorch/executorch/{Module,Tensor,
  EValue,DType}.java` at git tag `v1.3.1` on `github.com/pytorch/executorch`.
- **Kokoro-on-ExecuTorch bundle shape**: Hugging Face
  `software-mansion/react-native-executorch-kokoro` — `voices/<name>.bin` (522,240 bytes each =
  `510 × 256 × 4`, the **same** `[510, 256]` fp32 raw layout `:engines:kokoro`'s ONNX export
  already uses), `config.json` = `{"modelName": "kokoro"}`, and
  `xnnpack/{standard,german,polish}/{duration_predictor,synthesizer}_<suffix>.pte` (two separate
  graphs, ~62 MB / ~272 MB respectively for the "standard" variant).
- **Two-graph tensor I/O**: read directly from `NorbertKlockiewicz/kokoro-export`'s
  `demo/inference_example.py` (the script the HF repo's README points to as the reference runner):
  duration predictor inputs `(input_tokens int64 [1,L], text_mask bool [1,L], v_style float32
  [1,128], speed float32 [1])` via method `forward_128`, outputs `(pred_dur, d)`; synthesizer
  inputs `(input_tokens, text_mask, indices int64, d, voice_vec float32 [1,256])` via method
  `forward`, output `audio` float32. The token pad-wrap recipe (`[0, *tokens, 0]`, cap 128) and the
  voice-row split (`voice_vec[:, :128]` unused / `voice_vec[:, 128:]` = `v_style`) are copied
  verbatim from that script.

### Assumed / flagged gaps — validate against a real device before shipping audio

1. **`text_mask` dtype.** The reference feeds a `bool` tensor. ExecuTorch's public Java `Tensor`
   API (v1.3.1) has factories for byte/short/int/long/half/float/double but **no bool factory**,
   even though `DType.BOOL` exists as an output tag. `ExecuTorchKokoroEngine` feeds an all-true
   **INT64** tensor instead — this is UNVALIDATED and the real `.pte`'s dtype checker may reject
   it. Fixing this for real needs either (a) confirming XNNPACK's bool-input handling silently
   upcasts, or (b) a native-side workaround (e.g. re-export the graph with an int8/int64 mask
   input instead of bool). See `ExecuTorchRuntime.kt`'s kdoc and
   `ExecuTorchKokoroEngine.kt`'s class kdoc item 1.
2. **`MAX_DURATION = 296`** and the `forward_128` method name are copied from the demo script, not
   independently reverified against this specific `.pte` export's metadata (no tool in this
   environment could introspect a `.pte` file's declared methods/bounds without the real
   ExecuTorch Python package + the downloaded weights).
3. **Silence trimming** (`find_voice_bound`, used by the reference to crop the synthesizer's raw
   output) is **not** replicated — this engine returns the synthesizer's raw audio for the
   sentence chunk. Audio-quality post-processing, not plumbing; out of scope per CLAUDE.md's TDD
   note ("test the plumbing, not the audio").
4. **`vocab.json` does not exist in the real upstream repo.** VERIFIED: HF
   `software-mansion/react-native-executorch-kokoro` ships no phoneme-vocabulary file at all — the
   real pipeline's `MODEL_VOCAB` table is hardcoded inside the `kokoro-export` demo script itself.
   SSOT (CLAUDE.md rule 1) forbids this engine hardcoding that table the way the script does, so
   `ExecuTorchKokoroEngine` invents its own companion-file convention (`vocab.json`, a flat
   `{"phoneme-char": id, ...}` object) and **fails closed** (`inspect()` returns `null`) without
   it. **Packaging a real bundle for PhoneTTS therefore requires adding a `vocab.json` file**
   alongside the downloaded `.pte`/`voices/` assets — extract `MODEL_VOCAB` from
   `kokoro-export/demo/inference_example.py` and serialize it as JSON. This is a manifest/packaging
   step, not a code change.
5. **`d`'s channel width and whether `forward_128` truly runs at the real token count vs. always
   at bound 128** are read generically from the tensor's own reported shape at runtime (never
   hardcoded) and truncated via `DurationExpansion.truncateMiddleDim` — so the code is shape-safe
   either way, but which behavior the real graph exhibits is unconfirmed.

### The `"method"` extras contract

`ExecuTorchRuntime.createSession(path, options)` reads `options.extras["method"]` to pick which
`.pte` method to invoke (default `"forward"`). `ExecuTorchKokoroEngine` sets this to `"forward_128"`
when opening the duration-predictor session. Both sides hardcode the literal string `"method"`
(`ExecuTorchRuntime.MODULE_METHOD_EXTRA` / a private constant in `ExecuTorchKokoroEngine`) because
`:app` cannot depend on an engine module and an engine module cannot depend on `:app` — keep both
literals in sync if either changes.

### Device testing checklist (for whoever has a real device + the real weights)

1. Add the AAR dependency + registration above, build `:app:assembleDebug` with a real Android SDK.
2. Download a `react-native-executorch-kokoro` bundle (`xnnpack/standard/*.pte`, `voices/*.bin`,
   `config.json`) and hand-write the missing `vocab.json` (gap 4 above).
3. Sideload it, force-match to `executorch-kokoro`, and synthesize a short sentence.
4. If it throws a dtype-mismatch error on `text_mask`, that is gap 1 above — expected, not a
   surprise.
5. If durations/audio look wrong but no exception is thrown, that's most likely the `d`-shape
   assumption (gap 5) or the un-replicated silence trim (gap 3), not a tensor-plumbing bug — check
   `DurationExpansion`'s unit tests still pass in isolation before suspecting them.
