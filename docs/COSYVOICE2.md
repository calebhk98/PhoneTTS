# CosyVoice2-0.5B — feasibility and the on-device path

This is the spec's **Tier-C** model (§3.1): LLM-based, **autoregressive**, multi-component, ~1 GB,
and explicitly flagged as the one that "may be too slow to be pleasant on the A16 — that's fine, it
goes to file export. Its real job here is to prove the runtime layer is pluggable." This document
records what I found when trying to get it producing real audio, and what an on-device
implementation actually requires. **UPDATE: it is now PROVEN to produce real speech in PyTorch —
see "PyTorch proof" below.** The remaining question is purely the on-device path.

> **UPDATE (2026-07-16): the on-device pipeline is now BUILT and WIRED (compiles, seam-tested),
> gated behind `-PwithCosyVoice=true`, but the native GGUF decode is NOT yet verified on a phone.**
> See [On-device implementation (in progress)](#on-device-implementation-in-progress) at the bottom
> for the architecture that landed, the real flow/HiFT tensor names, what compiles vs. what is a
> documented native TODO, and an honest statement of what remains.

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
PyTorch**, which is not the app's runtime. Getting this into PhoneTTS still requires the on-device
path below — and the mobile research (`docs/research/cosyvoice2-mobile.md`) concludes that path is
**GGUF + llama.cpp/ggml** (there is an Apache-2.0 GGUF port that runs the same recipe on ARM at
745MB with an 8-voice pre-baked bank), a **Large** second-runtime effort, file-export-oriented, with
no streaming-realtime promise on a 4GB phone.

## On-device implementation (in progress)

The full app-side structure for the GGUF/llama.cpp path is now built, wired, and seam-tested. It
**compiles** end-to-end (`:core`, `:engines:cosyvoice2`, and `:app:compileDebugKotlin`), and its
plumbing is proven with fakes. It is **not** a working "it speaks on a phone" claim: the native
speech-token decode is opt-in and unverified (see [What remains](#what-remains-before-it-runs-on-a-phone)).

### The architecture that landed (text → audio)

```
text ──(CosyVoice2Frontend)──▶ Qwen2 BPE token ids
     ──(SpeechTokenRuntime: GGUF Qwen2-0.5B AR loop, id "cosyvoice-llm", NOT ONNX)──▶ speech token ids
     ──(flow.onnx via the ONNX Runtime, id "onnx")──▶ mel
     ──(hift.onnx via the ONNX Runtime)──▶ 24 kHz waveform
```

Speaker prompt = a bundled **pre-baked voice** (`SpeakerPrompt`: CAMPPlus embedding + prompt speech
tokens + prompt mel), *no reference wav in v1*. Speed routes to the LLM's native **token-rate**
parameter (`SpeechTokenRequest.speed`) and is never used to resample output audio (CLAUDE.md rule 2).

### The new seam (`:core`)

`com.phonetts.core.runtime.SpeechTokenRuntime` (+ `SpeechTokenSession`, `SpeechTokenRequest`) is the
"pluggable second runtime" the spec priced in (§5.3). It is a `Runtime` (so it registers in the same
`RuntimeRegistry` alongside `OnnxRuntime` and an engine looks it up by id the same way), but its
`openSpeechSession(...)` returns a `SpeechTokenSession` whose `generate(request): LongArray` runs the
**entire** autoregressive decode and returns speech token ids — it is emphatically **not** the
tensor-in/tensor-out ONNX `InferenceSession`. Its inherited `createSession()` fails closed to make
that boundary explicit. This directly fixes the first skeleton's bug (it *wrongly* drove the LLM as
an ONNX `InferenceSession` in a per-token `run()` loop). Seam-tested purely on the JVM with a
`FakeSpeechTokenRuntime`/`FakeSpeechTokenSession` (`core/.../runtime/SpeechTokenRuntimeTest.kt`).

### Real flow/HiFT ONNX tensor names (inspected, not guessed)

Read with `python3` + `onnx.load(...).graph` over the published exports on 2026-07-16 (the model
files are **not** committed — CLAUDE.md). Encoded as constants in `CosyVoice2Graphs`:

- **Flow-matching decoder** — `Lourdle/CosyVoice2-0.5B_ONNX/flow_fp32.onnx` (tokens → mel):
  - inputs: `token` INT64[B,L], `prompt_token` INT32[B,PL], `prompt_feat` FLOAT[B,ML,80],
    `embedding` FLOAT[B,E]
  - output: `tts_mel` FLOAT[B,80,mel_len]
- **HiFT vocoder** — `Lourdle/CosyVoice2-0.5B_ONNX/hift.onnx` (mel → waveform):
  - input: `speech_feat` FLOAT[1,80,L]
  - output: `generated_speech` FLOAT[1,N]
- The same repo's `flow_hift_combined_fp32.onnx` fuses both and adds a scalar `speed` FLOAT[] input —
  independent evidence that speed is a native flow-side token-rate knob (razesystems'
  `flow.decoder.estimator.fp16.onnx` is only the inner DiT ODE-step estimator: `x`,`mask`,`mu`,`t`,
  `spks`,`cond` → `dphi_dt`, i.e. one Euler step, not the whole tokens→mel graph — so this engine
  uses the Lourdle full-flow export, not that estimator).

### The `-PwithCosyVoice` flag (`:app`)

`LlamaCppSpeechTokenRuntime` implements the `:core` seam via JNI (`LlamaCppNative`) to llama.cpp,
registered in `AppGraph`'s `RuntimeRegistry` **alongside** `OnnxRuntime`. The native build mirrors
the espeak-ng pattern exactly:

- Opt in with `-PwithCosyVoice=true` (adds `buildCosyVoice` in `app/build.gradle.kts`, passes
  `-DPHONETTS_BUILD_COSYVOICE=ON` to CMake). `app/src/main/cpp/cosyvoice/CMakeLists.txt` builds
  llama.cpp (git-clone-at-build via `scripts/fetch-cosyvoice-llama.sh`), and — like the espeak stub —
  **still configures and links a fail-closed stub `.so` if the checkout is absent**, so the app
  always assembles even without the NDK.
- **When off:** `libphonetts_cosyvoice.so` isn't built, `LlamaCppNative.isLibraryLoaded` is false,
  `LlamaCppSpeechTokenRuntime.isAvailable()` returns false, and the engine's `load()` fails with a
  clear "build with `-PwithCosyVoice=true`" message — so CosyVoice2 simply isn't offered. Registration
  itself is unconditional and harmless.

### What compiles vs. what is a documented native TODO

**Compiles & is seam-tested (green):** the `:core` seam + its fake; `CosyVoice2Frontend`/`Engine`/
`Graphs`/`SpeakerPrompt` reworked to the real three-stage pipeline (tokens flow LLM→flow→hift, one
`FloatArray` chunk per sentence, fail-closed `inspect()`, speed→native param, baked-voice parse +
fail-closed reader); the `:app` Kotlin/JNI declarations; the CMake + gradle gating; `AppGraph` wiring.

**Native TODO (opt-in, unverified — honest stub today):**
- `app/src/main/cpp/cosyvoice/cosyvoice_jni.cpp` currently returns "not built"/`0`/`null`. Integrating
  the actual ggml **speech-token decode + the sliding-window repeat-aware (RAS) sampler** from the
  CrispStrobe/`cstr` recipe is still to do (naive greedy decode falls into a documented silent-token
  loop; `docs/research/cosyvoice2-mobile.md` §Q2).
- **Obtaining/converting the GGUF:** the Qwen2-0.5B speech LLM with the CosyVoice-specific
  `cosyvoice3.speech_embd` / `cosyvoice3.speech_lm_head` tensors (Q4_K ≈ 384 MB) — download into
  app-private storage, never bundle in the APK (rule 7).
- **The voice bank:** an offline baking script must produce `voices.bin` in the layout
  `CosyVoice2SpeakerPrompt` reads (embedding + prompt tokens + prompt mel). Not committed (weights are
  never committed).
- **INT32 gap:** the flow graph's `prompt_token` is INT32, but the app's `Tensor`/`OnnxRuntime` seam
  only carries INT64/FLOAT; it's fed as INT64 today. Feeding the real graph correctly needs an INT32
  path in `OnnxRuntime` — invisible to the current fake-session plumbing tests.
- The llama.cpp tag in `scripts/fetch-cosyvoice-llama.sh` and `cosyvoice/CMakeLists.txt` is a
  **placeholder** pin, to be confirmed against a real on-device build.

### Why there is no `BuiltInCatalog` entry yet

`BuiltInCatalog` is deliberately "only models proven to produce valid audio AND handled end-to-end
by inspect()/load()". CosyVoice2 is neither yet: (1) the native decode is unverified, and (2) no
single upstream repo/manifest yields a bundle matching this engine's `inspect()` fingerprint — the
LLM GGUF (`cstr/…`) and the flow/HiFT ONNX (`Lourdle/…`) live in **different** repos, and both the
`cosyvoice2.yaml` signature file and the baked `voices.bin` are **PhoneTTS's own** artifacts that an
offline packaging step must produce. A one-tap download must not land a model that then fails to
load, so adding a catalog entry waits until the native path is verified and a single packaged bundle
exists. Until then CosyVoice2 is reachable only by sideloading such a bundle (fail-closed `inspect()`,
or an explicit user `forcedMatch`).

### What remains before it runs on a phone

In priority order: (1) implement + verify the ggml speech-token decode & RAS sampler in the JNI
bridge against a real GGUF; (2) obtain/convert the Q4_K LLM GGUF and bake a `voices.bin`; (3) add the
INT32 `prompt_token` path to `OnnxRuntime`; (4) package a single `inspect()`-recognizable bundle
(gguf + `flow.onnx` + `hift.onnx` + `voices.bin` + signed `cosyvoice2.yaml`) and measure RTF on the
A16 (expect file-export-first, not guaranteed streaming — `docs/research/cosyvoice2-mobile.md` §Q3).
None of (1)–(4) touches the seams proven here; they are native/packaging work behind the
`-PwithCosyVoice` flag.
