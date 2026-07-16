# CosyVoice2-0.5B — feasibility and the on-device path

This is the spec's **Tier-C** model (§3.1): LLM-based, **autoregressive**, multi-component, ~1 GB,
and explicitly flagged as the one that "may be too slow to be pleasant on the A16 — that's fine, it
goes to file export. Its real job here is to prove the runtime layer is pluggable." This document
records what I found when trying to get it producing real audio, and what an on-device
implementation actually requires. **UPDATE: it is now PROVEN to produce real speech in PyTorch —
see "PyTorch proof" below.** The remaining question is purely the on-device path.

> **UPDATE (2026-07-16): the on-device path is now PROVEN in native ggml and wired into the app.**
> CrispASR's `cosyvoice3_tts` (the whole pipeline in C++/ggml) synthesizes real 24 kHz audio on
> desktop — the same code the app vendors behind `-PwithCosyVoice=true`. See
> [On-device implementation](#on-device-implementation--proven-native-ggml-wired-into-the-app) at the
> bottom for the proof, the architecture that landed, and the one remaining step (the NDK link). The
> "four ONNX components / missing LLM" analysis below is the *earlier* investigation — the native
> ggml port made the ONNX-flow/HiFT hybrid unnecessary.

## Architecture (why it's not one ONNX graph)

CosyVoice2 text→speech is a pipeline of four stages:

1. **Text tokenizer** — a Qwen2 BPE tokenizer turns text into text tokens.
2. **LLM (autoregressive)** — a **Qwen2-0.5B**-based decoder generates *speech tokens* one at a
   time from the text tokens (+ a speaker prompt). This is the slow, non-deterministic core.
3. **Flow-matching decoder** — turns speech tokens into a mel-spectrogram (an ODE solve).
4. **HiFT vocoder** — turns the mel into a waveform.

Zero-shot voice cloning additionally uses **campplus** (speaker-embedding extractor) and
**speech_tokenizer_v2** (reference-audio → speech tokens) on a reference wav.

## What IS available as ONNX (and what is NOT)

Inspected on the Hub (2026-07):

| Component | ONNX available? | Where |
|---|---|---|
| Flow-matching decoder | ✅ | `razesystems/CosyVoice2-0.5B-FP16-ONNX` (`flow.decoder.estimator.fp16.onnx`), `Lourdle/CosyVoice2-0.5B_ONNX` (`flow_*.onnx`), `yuekai/cosyvoice2_flow_onnx` |
| HiFT vocoder | ✅ | `Lourdle/…` (`hift.onnx`), combined `flow_hift_combined_*.onnx` |
| campplus (speaker embed) | ✅ | `razesystems/…` (`campplus.fp16.onnx`) |
| speech_tokenizer_v2 (audio→tokens) | ✅ | `razesystems/…` (`speech_tokenizer_v2.fp16.onnx`) |
| **Text→speech-token LLM (Qwen2-0.5B)** | ❌ **NONE** | not published as ONNX anywhere |

**This is the blocker.** Every export covers only the *downstream, deterministic* half (speech
tokens → audio) plus the *reference-audio* path. The **autoregressive LLM that turns text into
speech tokens is not exported to ONNX by anyone** — because autoregressive decoders are awkward to
export (they need a KV-cache loop) and are usually run via PyTorch/vLLM. Without stage 2, there is
**no text→audio path from ONNX alone.**

## The honest blocker hit here

The only way to *prove* CosyVoice2 produces real speech right now is to install and run the
official **CosyVoice PyTorch package** (`github.com/FunAudioLLM/CosyVoice`) with the ~1 GB
`iic/CosyVoice2-0.5B` checkpoint. That means **executing arbitrary third-party code**, which the
sandbox correctly refused without explicit user authorization (unlike the Piper/Kokoro/Kitten/Melo
verifications, which only ran ONNX Runtime over downloaded model *data*). So this stays unproven
here pending a decision to run that stack. (The model is widely used and does work upstream; the
open question for this app is on-device, below — not whether the model itself is real.)

## What an on-device implementation would take

The app is already shaped for this (the `Runtime` seam, spec §5.3, exists precisely so a
non-ONNX runtime can be added, and there's a `CosyVoice2Engine` skeleton). The missing work is:

1. **Obtain the LLM as an on-device runtime.** Two realistic options, both **Large**:
   - Export Qwen2-0.5B to **ONNX with an explicit KV-cache decode loop** + implement the
     autoregressive sampling loop as the "second runtime." Non-trivial export; large model.
   - Or run the LLM via a **llama.cpp / GGUF** quantized path and keep flow+vocoder on ONNX — a
     genuinely different runtime to integrate and ship (native lib), but faster/smaller on CPU.
2. **Wire the downstream ONNX stages** (flow → hift), which already exist as exports — the
   tractable part.
3. **Ship a built-in speaker embedding.** Standalone use shouldn't require the user to provide a
   reference wav, so bundle a precomputed campplus embedding (or a small set) as the "voice(s)."
4. **Accept the performance reality.** A 0.5B autoregressive decoder on a 4 GB phone CPU produces
   speech tokens slowly; realistically this is **file-export-only, likely minutes per paragraph**,
   and **non-deterministic** — so its tests must be invariants (length in range, bounded samples,
   no NaNs), never golden-hash (spec §9).

## Verdict

- **Effort: L (large).** The blocker is not app wiring — it's that CosyVoice2's autoregressive
  LLM isn't available as a ready ONNX export, so it needs either a custom LLM export or a second
  (llama.cpp-style) runtime, plus a bundled speaker embedding. This is a project in its own right,
  exactly the Tier-C cost the spec priced in ("slow to first sound … prove the runtime is
  pluggable").
- **Recommended sequencing:** do it *after* the four other engines are producing audio on-device
  (Piper/Kitten/Kokoro proven; Melo now wired to a working export). It is the right model to prove
  the pluggable-runtime story, but it is the highest-effort, lowest-daily-value one on a budget
  phone, so it should not block the rest.
- **To proceed with a proof now**, someone needs to authorize running the official CosyVoice
  PyTorch stack (third-party code) in a sandbox to produce a reference wav and validate the
  flow→hift ONNX path against it. That's the next concrete step if CosyVoice2 is a priority.

## PyTorch proof (done — it works)

Ran CosyVoice2-0.5B end-to-end via the official FunAudioLLM/CosyVoice PyTorch stack (user-authorized),
CPU-only, on 4 cores. Zero-shot mode with the repo's bundled `asset/zero_shot_prompt.wav` as the
voice prompt, synthesizing an English sentence ("Text to speech turns written words into natural
sounding audio.").

**Result: PASS.** `samples=149760, sr=24000, duration=6.24s, size=293KB, peak=0.527, no NaNs.` Real,
non-silent speech — the model genuinely works. Script: `scripts/model-verify/run_cosy.py` (requires
the CosyVoice repo checkout + the ~4GB `FunAudioLLM/CosyVoice2-0.5B` model, unlike the other verify
scripts which self-download a single ONNX file).

Setup notes (for reproducing): the environment's Debian-patched setuptools breaks source builds
(`AttributeError: install_layout`); fixed by force-installing a clean `setuptools==75.8.0` to
`/usr/local` so it takes precedence, then `--no-build-isolation` for `antlr4-python3-runtime`,
`openai-whisper`, `wget`. Also needed (dropped from the CUDA/server-heavy requirements): torch-cpu
2.3.1, transformers 4.51.3, diffusers, conformer, HyperPyYAML, librosa, pyworld, pyarrow, wetext
(the text frontend — it downloads its EN normalization FSTs from ModelScope at first run).

So the model is real and produces good audio. What it does NOT change: **it ran on a desktop CPU via
PyTorch**, which is not the app's runtime. Getting this into PhoneTTS needs the on-device path
below — now **also proven**, on the actual ggml code the app ships.

## On-device implementation — PROVEN native ggml, wired into the app

The mobile research (`docs/research/cosyvoice2-mobile.md`) pointed at a GGUF/ggml port as the only
real on-device path. That path turned out to be much cleaner than a llama.cpp-LLM + ONNX-flow/HiFT
hybrid: **CrispStrobe/CrispASR** (a whisper.cpp fork, MIT) contains a self-contained C++/ggml
implementation of the *entire* CosyVoice3 pipeline — Qwen2-0.5B LLM (speech-token head) → DiT-CFM
flow → HiFi-GAN/iSTFT HiFT, **plus a native Qwen2 BPE tokenizer and a baked voice bank** — behind one
C ABI. The deployable model is **CosyVoice3-0.5B-2512** (Apache-2.0, GGUF-native — the sibling of the
CosyVoice2 model proven in PyTorch above).

### The ggml proof (done — real audio, native code path)

`scripts/model-verify/run_cosy_native.sh` (+ `cv3_native_driver.cpp`) builds CrispASR's
`cosyvoice3_tts` static lib and runs it against the Apache-2.0 GGUF stack
(`cstr/cosyvoice3-0.5b-2512-GGUF`, minimal 745 MB combo = Q4_K LLM + Q8_0 flow + F16 HiFT + voices):

> **Result: PASS.** "Hello, this is a test of on device text to speech." → **147 speech tokens →
> 5.88 s of 24 kHz audio, peak 0.829, rms 0.111, no NaNs.** A second sentence → 87 tokens → 3.48 s,
> peak 0.891. **No PyTorch, no ONNX** — this is the same `cosyvoice3_tts.cpp` the app vendors for its
> `-PwithCosyVoice` build. (Greedy decode loops on CV3's documented "silent_tokens"; the RAS sampler
> needs `temperature > 0` — 0.8, seed 42 — which the driver and the app runtime both set.)

### The architecture that landed (text → audio, one native call)

```
text ──(NativeTtsRuntime / cosyvoice3_tts, id "cosyvoice", NOT ONNX)──▶ 24 kHz waveform
        │  inside the native lib: Qwen2 BPE tokenize → Qwen2-0.5B AR speech-token loop (RAS)
        │  → DiT-CFM flow ODE → HiFi-GAN/iSTFT HiFT
        └─ voice = one of the baked voices in the voices GGUF (zero_shot, fleurs-en/de/…)
```

Because the native lib does everything, there is **no** Kotlin `TextFrontend`, speech-token stage, or
ONNX flow/HiFT graph for this engine — the earlier skeleton's `CosyVoice2Frontend` (a fabricated
`char.code % vocab` placeholder), `CosyVoice2Graphs` (ONNX flow/HiFT) and `CosyVoice2SpeakerPrompt`
are **deleted**. Speed: the CrispASR synth C ABI exposes no speed knob, and CLAUDE.md rule 2 forbids
resampling to fake one (it shifts pitch), so the descriptor advertises a **locked speed of 1.0**
(honest-closed) until the native synth routes a native token-rate parameter.

### The new seam (`:core`)

`com.phonetts.core.runtime.NativeTtsRuntime` (+ `NativeTtsSession`, `NativeTtsRequest`) is the
"pluggable second runtime" the spec priced in (§5.3). It is a `Runtime` (so it registers in the same
`RuntimeRegistry` alongside `OnnxRuntime` and an engine looks it up by id the same way), but its
`openTtsSession(modelDir)` returns a `NativeTtsSession` whose `synthesize(request): FloatArray` runs
the **entire** text→audio pipeline and returns finished PCM — it is emphatically **not** the
tensor-in/tensor-out ONNX `InferenceSession`. Its inherited `createSession()` fails closed to make
that boundary explicit. `voiceNames`/`sampleRate` are read from the loaded model (SSOT). Seam-tested
purely on the JVM with a `FakeNativeTtsRuntime`/`FakeNativeTtsSession`
(`core/.../runtime/NativeTtsRuntimeTest.kt`).

### The engine (`:engines:cosyvoice2`)

`CosyVoice2Engine` is now a thin delegate over the native session:
- **`inspect()`** fingerprints the four-GGUF cstr stack by name — `cosyvoice3-{llm,flow,hift,voices}-*.gguf`.
  That four-file set is the whole signature; anything less returns null (fail-closed, spec §9.1).
- **`load()`** opens one `NativeTtsSession` over the model directory; the native runtime discovers the
  four siblings itself.
- **`voices()`** are the baked voice names the native session read from the voices GGUF (SSOT — the
  engine hardcodes no voice list).
- **`synthesizeSentence()`** calls the native synth; one `FloatArray` chunk per sentence (spec §8).
- Display name is **CosyVoice3-0.5B**; the internal engine/package id stays `cosyvoice2` (the spec's
  build-order name for this Tier-C slot).

### The `-PwithCosyVoice` flag (`:app`)

`NativeCosyVoiceRuntime` implements the `:core` seam via JNI (`CosyVoiceNative`) over CrispASR's
`cosyvoice3_tts` C ABI, registered in `AppGraph`'s `RuntimeRegistry` **alongside** `OnnxRuntime`. The
native build mirrors the espeak-ng pattern exactly:

- Opt in with `-PwithCosyVoice=true` (adds `buildCosyVoice` in `app/build.gradle.kts`, passes
  `-DPHONETTS_BUILD_COSYVOICE=ON` to CMake). `app/src/main/cpp/cosyvoice/CMakeLists.txt`
  `add_subdirectory`s the CrispASR checkout (fetched by `scripts/fetch-cosyvoice-ggml.sh`) and links
  its `cosyvoice3_tts` + `chatterbox` (campplus) + `ggml` targets — the *exact* targets
  `run_cosy_native.sh` built and ran. Like the espeak stub, it **still configures and links a
  fail-closed stub `.so` if the checkout is absent**, so the app always assembles even without the NDK.
- **When off:** `libphonetts_cosyvoice.so` isn't built, `CosyVoiceNative.isLibraryLoaded` is false,
  `NativeCosyVoiceRuntime.isAvailable()` returns false, and the engine's `load()` fails with a clear
  "build with `-PwithCosyVoice=true`" message — so CosyVoice simply isn't offered. Registration itself
  is unconditional and harmless.

### What is proven vs. what remains

**Proven & green:** the native `cosyvoice3_tts` code path produces real 24 kHz audio on desktop
(`run_cosy_native.sh`); the `:core` seam + engine + `:app` Kotlin/JNI compile, and the seam/engine
tests pass on the JVM (`test ktlintCheck detekt` all green). The `cosyvoice_jni.cpp` bridge is a
**real** implementation over the proven C ABI (discovers the four GGUFs, inits all stages, `synth →
jfloatArray`), not a stub.

**Also proven — the arm64 (Android) native build:** with the Android NDK (r27c), CrispASR's
`cosyvoice3_tts` + `chatterbox` + `ggml` cross-compile for `arm64-v8a` (verified ELF `ARM aarch64`),
and the app's own `cosyvoice_jni.cpp` links against them into a real
`libphonetts_cosyvoice.so` (`arm64-v8a`, ~3.9 MB) that exports all five JNI entry points
(`Java_com_phonetts_app_runtime_CosyVoiceNative_native{Init,SampleRate,VoiceNames,Synthesize,Free}`).
So the native bridge compiles and links for Android, not just desktop — same sources, same C ABI.

**Remaining (opt-in, behind `-PwithCosyVoice`):**
- **Assembling the full `:app` APK** (AGP + SDK) with `-PwithCosyVoice=true` and running on a device.
  The native `.so` is proven to link for arm64 (above); packaging it into the APK via the app's
  `externalNativeBuild` + measuring real behaviour on-device is the last step. Pin
  `scripts/fetch-cosyvoice-ggml.sh` to a known-good CrispASR revision when that build resolves one.
- **On-device RTF** on the A16: a 0.5B AR decoder + flow ODE + vocoder on a 4 GB phone CPU is
  file-export-first, not guaranteed streaming (`docs/research/cosyvoice2-mobile.md` §Q3).

### Why there is no `BuiltInCatalog` entry yet

`BuiltInCatalog` is deliberately "only models proven to produce valid audio AND handled end-to-end by
inspect()/load() **on-device**". CosyVoice3's audio + code path are proven, but the one-tap entry
waits on the on-device NDK build being verified — a one-tap download must not land a model that then
can't load because the native lib wasn't built into that APK. The `inspect()` fingerprint already
matches the `cstr/cosyvoice3-0.5b-2512-GGUF` stack as-is (drop its four GGUFs in a folder and sideload
it), so no PhoneTTS-specific packaging step is needed — only the `-PwithCosyVoice` build. Until that
build is verified on a device, CosyVoice3 is reachable by sideloading the GGUF folder (fail-closed
`inspect()`, or an explicit user `forcedMatch`) into a `-PwithCosyVoice` APK.
