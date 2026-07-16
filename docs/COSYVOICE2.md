# CosyVoice2-0.5B — feasibility and the on-device path

This is the spec's **Tier-C** model (§3.1): LLM-based, **autoregressive**, multi-component, ~1 GB,
and explicitly flagged as the one that "may be too slow to be pleasant on the A16 — that's fine, it
goes to file export. Its real job here is to prove the runtime layer is pluggable." This document
records what I found when trying to get it producing real audio, and what an on-device
implementation actually requires. **UPDATE: it is now PROVEN to produce real speech in PyTorch —
see "PyTorch proof" below.** The remaining question is purely the on-device path.

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
