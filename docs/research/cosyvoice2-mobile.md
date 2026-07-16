# Running CosyVoice2 (and LLM/autoregressive TTS) on mobile / on-device

Research note for PhoneTTS. Question: can we run **CosyVoice2-0.5B** on-device on a
budget Android phone (Samsung Galaxy A16, ~4 GB RAM, MediaTek Helio G99-class CPU, **no
NPU**), and if so, how.

**Date:** 2026-07-16. **Method:** web research only (no model execution).
**Sourcing convention below:** `[VERIFIED]` = read from a primary source (repo file,
issue, model card, paper); `[CLAIMED]` = asserted in a blog/secondary source or a
search-engine summary I could not open directly.

**One-line answer:** Yes, but only via a **llama.cpp/ggml GGUF port**, not ONNX Runtime
and not sherpa-onnx. A working, permissively-licensed CPU implementation already exists
(**CrispStrobe/CrispASR** + the `cstr/cosyvoice3-0.5b-2512-GGUF` weights). It is
architecturally identical to CosyVoice2 (Qwen2-0.5B AR head → FSQ speech tokens → DiT
flow-matching → HiFT vocoder). On a 4 GB no-NPU phone the realistic experience is
**file-export / near-real-time at best**, not guaranteed streaming real-time. This is a
**Large** integration effort for PhoneTTS because it requires a second, non-ONNX runtime.

---

## Q1 — Does any project run CosyVoice2 on a phone/edge device today?

### The only real on-device path: llama.cpp / ggml (CrispASR + GGUF weights)

**This is the key finding.** A third-party maintainer (CrispStrobe) has a **full native
C++ ggml implementation** of the CosyVoice3 pipeline, with **GGUF weights published on
Hugging Face**. CosyVoice3-0.5B is the same recipe as CosyVoice2-0.5B (both are Qwen2-0.5B
AR speech-token LM + FSQ tokenizer + chunk-aware DiT flow-matching + HiFT vocoder), so the
approach transfers directly to CosyVoice2.

`[VERIFIED]` from the model card `cstr/cosyvoice3-0.5b-2512-GGUF` (read in full):
- The whole pipeline is expressed as GGUF/ggml tensors:
  `text (Qwen2 BPE) → CosyVoice3LM (Qwen2-0.5B + speech-token AR head) → speech tokens ∈ [0,6561) → Flow (DiT + CausalConditionalCFM, 10-step Euler ODE) → mel @ 24 kHz → CausalHiFTGenerator (HiFi-GAN + NSF + iSTFT) → 24 kHz PCM`.
- **Every stage is ported to ggml**, including the front-end extractors that are normally
  ONNX-only: the **speech_tokenizer_v3** (12 FSMN/attention blocks + FSQ head, described as
  *"byte-exact vs the ONNX reference"*) and the **CAMPPlus** 192-D speaker encoder. So the
  runtime can clone from an arbitrary 16 kHz WAV **without** any Python/ONNX dependency.
- GGUF file sizes and quant levels (see Q2 table).
- Source: <https://huggingface.co/cstr/cosyvoice3-0.5b-2512-GGUF>

`[VERIFIED]` from the CrispASR repo (`cosyvoice3-tts` backend):
- Runs as a "fully native C++ ggml implementation," no Python.
- Platform coverage is *"all platforms where ggml compiles — CPU (x86, ARM), GPU (CUDA,
  Metal, Vulkan), and WebAssembly (4.3 MB)."* **ARM/mobile is explicitly in scope** via the
  single C++ binary.
- It is **file-export-oriented** (`--tts-output out.wav`) but also plumbs into an HTTP
  server for streamed audio.
- A `COSYVOICE3_FLOW_STEPS=5` env var halves the flow ODE steps (10→5) for faster synthesis
  — a direct latency lever.
- Source: <https://github.com/CrispStrobe/CrispASR> (and `COMPARISON.md` in the same repo).
- The same author ships **CrisperWeaver**, an on-device Flutter speech app built on CrispASR
  ggml — evidence the ggml runtime reaches phones — though that app is ASR-focused today:
  <https://github.com/CrispStrobe/CrisperWeaver>

`[VERIFIED]` upstream also gained a llama.cpp path: FunAudioLLM/CosyVoice **PR #1872** adds a
`llama-cpp-python` backend (`load_llama_cpp=True`, `gguf_model_path=...`) using
`Ferraronp/CosyVoice3-qwen2.5-0.5b-speech-gguf`. It keeps all existing inference methods
(`inference_zero_shot`, `inference_cross_lingual`, `inference_instruct2`) and skips loading
the PyTorch LLM weights to save memory. Status: **open PR** at time of research.
- <https://github.com/FunAudioLLM/CosyVoice/pull/1872>

### Alibaba MNN — NOT a CosyVoice path (despite being first-party-adjacent)

MNN was the most plausible first-party mobile route (CosyVoice and MNN are both Alibaba /
FunAudioLLM-adjacent), but I found **no CosyVoice example in MNN**:
- `[VERIFIED]` MNN's `transformers/README.md` (LLM/`llmexport` docs) mentions **no TTS or
  audio-generation model at all** — only *audio input* for multimodal LLMs
  (`-DLLM_SUPPORT_AUDIO=true`). No CosyVoice, no CosyVoice2.
  <https://github.com/alibaba/MNN/blob/master/transformers/README.md>
- `[CLAIMED]` MNN's on-device TTS work is with **BertVITS2** and **Supertonic**
  (`supertonic-tts-mnn`), and its **TaoAvatar** offline avatar bundles an (unnamed,
  non-CosyVoice) TTS. These are non-autoregressive TTS, not the CosyVoice LLM path.
- `[VERIFIED]` MNN *does* provide `llmexport`, which exports LLMs to ONNX then to MNN
  format — so in principle the Qwen2-0.5B CosyVoice LM could be pushed through it, but
  **nobody has published that**, and it would not handle the custom speech-token head,
  flow, or vocoder. Source: MNN transformers README (above).

**Conclusion:** MNN is a capable mobile LLM engine but there is **no existing CosyVoice/
CosyVoice2 MNN example**. Using MNN would mean doing the port yourself, with no reference.

### sherpa-onnx — explicitly out of scope for autoregressive LLM-TTS

- `[VERIFIED]` sherpa-onnx (k2-fsa) supports **only non-autoregressive TTS**: VITS, Piper,
  Matcha(-TTS), Kokoro. Its TTS model list confirms this:
  <https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/index.html>
- `[VERIFIED]` CosyVoice support is an **open feature request, not implemented**: issue
  **#3568** "[FEATURE] Add support for FunAudioLLM/Fun-CosyVoice3-0.5B-2512"
  (<https://github.com/k2-fsa/sherpa-onnx/issues/3568>). CosyVoice's autoregressive Qwen2 LM
  does not fit sherpa-onnx's fixed non-AR TTS graph model — an AR token loop with KV-cache
  is a different execution shape than what sherpa-onnx's TTS API assumes.

**Conclusion:** sherpa-onnx will not run CosyVoice2, and there is no signal it's coming.
It is, however, the reference implementation for the *alternative* models (Q4).

### Other runtimes

- **vLLM / TensorRT-LLM:** server-side only. CosyVoice2/3 has vLLM support (issue
  `vllm-project/vllm-omni#1552`) and TensorRT-LLM gives ~4x over HF transformers `[CLAIMED]`
  — irrelevant to a phone but confirms the LLM is a standard-enough Qwen2 to run on
  mainstream LLM engines.
- **executorch / MLC-LLM / ncnn:** no CosyVoice2 port found for any of these.

---

## Q2 — How is the autoregressive 0.5B LLM run on-device?

The practitioner answer is **GGUF + llama.cpp/ggml**, and the speech-token head *is*
handled — this is the part people worried would break, and it was solved by adding
CosyVoice-specific tensors alongside the standard llama.cpp ones.

`[VERIFIED]` tensor layout from the GGUF model card:
- Standard llama.cpp Qwen2 tensors (`token_embd`, `blk.K.{attn,ffn}_*`, `output_norm`,
  `output`) **plus** two CosyVoice-specific tensors:
  `cosyvoice3.speech_embd.weight` (input embedding, **vocab 6761**) and
  `cosyvoice3.speech_lm_head.weight` (the **speech-token output head**). So the extra
  speech vocabulary/head is carried in the GGUF, not bolted on at runtime.
- A **sliding-window / RAS (repeat-aware) sampler** is required: greedy decode falls into a
  documented "silent_tokens" loop within ~5 steps, so the backend forces `temperature=0.8`.
  PR #1872 similarly adds a *"sliding-window repeat penalty for speech token sampling."*
  This is a real gotcha: naive sampling produces garbage.

`[VERIFIED]` GGUF files / quant levels / sizes (from the model card):

| Component | Quant | Size | Notes |
|---|---|---|---|
| LLM | F16 | 1.29 GB | reference |
| LLM | Q4_K | **384 MB** | Q4_0 fallback on 896-wide rows; head + embeddings stay F16 |
| Flow (DiT-CFM) | F16 | 665 MB | |
| Flow | Q8_0 | **361 MB** | input_embd + spk_affine stay F16; "perceptually indistinguishable from F16" |
| HiFT vocoder | F16 | 42 MB | too small to quantize |
| Voices bank | F32 | 665 KB | 8 baked speaker voices |
| speech_tokenizer_v3 | F16 | 462 MB | only needed for runtime WAV cloning |
| speech_tokenizer_v3 | Q4_K | 139 MB | ~0.6% token drift |
| CAMPPlus | F16 | 13 MB | only needed for runtime WAV cloning |

- **Smallest viable synthesis combo (baked voice):** `llm-q4_k + flow-q8_0 + hift-f16 +
  voices` = **745 MB on disk**. F16 reference = 1.96 GB.
- If you drop runtime WAV-cloning (ship only baked voices), you **do not need** the 462 MB
  s3tokenizer or the 13 MB campplus at all — they are cloning-time front-end only.

`[VERIFIED]` quality vs quantization (the model card's own ASR-roundtrip WER test,
`parakeet` transcribing the TTS output):

| Combo | Size | WER |
|---|---|---|
| llm-f16 + flow-f16 | 1.96 GB | 0% |
| llm-f16 + flow-q8_0 | 1.66 GB | 0% |
| llm-q4_k + flow-f16 | 1.05 GB | 0% (punctuation-intonation drift only) |
| **llm-q4_k + flow-q8_0** | **745 MB** | **0% (punctuation-intonation drift only)** |

Takeaway: **Q4_K LLM + Q8_0 flow keeps content intact** (0% WER, minor prosody/punctuation
drift). Q8_0 flow is called perceptually identical to F16. So int4 LLM / int8 flow is a
**safe** on-device precision target.

**ONNX-with-KV-cache path:** the flow, vocoder, campplus, and speech_tokenizer are already
ONNX (as established), and the LLM *could* be exported to ONNX with a KV-cache decode loop,
but **nobody has published a CosyVoice2 LLM ONNX** — CosyVoice issue #192 ("How to export
llm.pt to onnx?") reflects that this is unresolved upstream
(<https://github.com/FunAudioLLM/CosyVoice/issues/192>). The community solved the LLM on
GGUF instead precisely because llama.cpp already handles the AR decode loop + KV-cache +
ARM-optimized int4 kernels that you'd otherwise have to build yourself in ONNX Runtime.

---

## Q3 — Real performance numbers on phones / budget ARM CPUs

**Honest finding: there are NO published CosyVoice2/3 real-time-factor numbers on a
budget ARM phone.** The only hard numbers are GPU/desktop. What we can do is bound it.

`[VERIFIED]` published numbers (all non-phone):
- CosyVoice3 llama-cpp-python F16 GGUF on an **NVIDIA T4 GPU**: ~**0.45 RTF** vs ~1.17 RTF
  for PyTorch fp16 — *"~2.6x faster"* (PR #1872). GPU, not CPU.
- The GGUF card's WER tests are correctness, not timing.

`[VERIFIED]` the arithmetic that matters:
- CosyVoice2's speech tokenizer runs at **25 Hz — 25 speech tokens per second of audio**
  (CosyVoice2 paper, <https://arxiv.org/html/2412.10117v2>). So to synthesize 1 s of audio
  the **LLM must decode ≥25 tokens**. Real-time on the LLM stage requires **≥25 tok/s**
  decode of the 0.5B model.
- `[CLAIMED]` llama.cpp reference point: an **8B** Q4 model does ~15–25 tok/s on a
  Snapdragon X Elite; Q4_0 has hand-written ARM GEMV kernels. A **0.5B** model is ~16x
  smaller, so on a strong ARM core it should do **well over 100 tok/s**. Even a budget
  Helio G99 (A16) core should comfortably clear 25 tok/s for a 0.5B Q4 — **the LLM stage
  alone is plausibly real-time on the A16.** (Extrapolated, not measured — treat as an
  estimate.)
- **The real cost is the flow + vocoder, not the LLM.** The DiT flow-matching decoder runs a
  **10-step Euler ODE** (each step a full DiT forward) and the HiFT vocoder does NSF+iSTFT
  synthesis — these are dense conv/attention compute with **no llama.cpp-style ARM kernel
  tuning**, and no published ARM timings exist. The `COSYVOICE3_FLOW_STEPS=5` fast-mode knob
  exists precisely because flow steps dominate latency.

**Memory:** the 745 MB smallest combo loads into RAM during synthesis. On a 4 GB phone
(with ~2–2.5 GB usable to an app after OS/other apps) this **fits but is tight**, especially
alongside PhoneTTS's own working buffers. This is well within PhoneTTS's "one engine loaded
at a time / call unload() first" rule (SPEC rule 6) — you could not co-resident this with
another engine.

**Verdict on mode:** plan for **file-export / near-real-time**, not guaranteed streaming.
The LLM is likely fast enough; the flow+vocoder on a budget no-NPU CPU are the unknown that
would push RTF over 1.0. Sentence-chunked synthesis (SPEC rule 8) is essential so the first
sentence's audio starts while later sentences generate.

---

## Q4 — What practitioners actually pick for on-device TTS instead

**Practitioner consensus: for on-device mobile TTS, people use non-autoregressive models
(Piper / Kokoro / Matcha / VITS), not CosyVoice.** The mature, battle-tested mobile TTS
stack is sherpa-onnx, and it deliberately supports only those:
- `[VERIFIED]` sherpa-onnx ships Android/iOS/HarmonyOS/embedded builds and pretrained
  **Piper, VITS, Matcha, Kokoro** — all single-pass, deterministic, no AR token loop, easily
  real-time on CPU. <https://github.com/k2-fsa/sherpa-onnx>
- `[CLAIMED]` **Supertonic** (`supertonic-tts-mnn`, ONNX/MNN, fp32/fp16/int8) is a newer
  "lightning-fast on-device multilingual TTS" that people run natively on phones — again
  non-autoregressive. <https://github.com/supertone-inc/supertonic>
- These map exactly onto PhoneTTS's other planned engines (Piper, Kokoro, Melo, Kitten),
  which are all non-AR and are the "safe" real-time-on-CPU choices.

Why CosyVoice2 is the odd one out on mobile: it's the only one of PhoneTTS's targets that
needs an **autoregressive LLM decode loop**, which is a fundamentally different (and
heavier, and less deterministic) runtime than a single ONNX forward pass. That's exactly why
it can't ride the sherpa-onnx/ONNX-Runtime rail the others use.

**So: is CosyVoice2 "the right choice for mobile"?** For raw practicality, **no** — Kokoro/
Melo/Piper give real-time CPU TTS with a single ONNX graph and no sampler tuning. CosyVoice2
earns its place only for its distinctive capability: **zero-shot voice cloning + expressive/
instruct control** that the non-AR models don't offer. If that capability is the point, the
GGUF/llama.cpp path is the way; if it isn't, the non-AR engines are strictly easier on a
4 GB phone.

---

## Q5 — The speaker/voice problem on-device

**Finding: the standard on-device approach is to ship a fixed bank of pre-computed speaker
voices, so no reference WAV is needed at runtime.** Runtime WAV cloning is *supported* but
optional and heavier.

`[VERIFIED]` from the GGUF model card:
- Ships **`cosyvoice3-voices.gguf`** — a **665 KB bank of 8 baked voices** (a zero-shot
  Mandarin default + en/de/zh/ja/fr/es/ko from Google FLEURS, CC BY 4.0). You synthesize
  with `--voice zero_shot` / `--voice fleurs-en` etc. **No reference recording required.**
- Baking a voice = precomputing the speaker representation (CAMPPlus 192-D embedding +
  prompt speech tokens) offline with a converter script and storing it in the bank. Each
  manifest entry is `{name, wav, prompt_text}`.
- **Runtime cloning from an arbitrary 16 kHz WAV** is also supported natively (ggml-ported
  campplus + speech_tokenizer_v3), but that costs the extra **462 MB s3tok + 13 MB
  campplus** and requires the user to supply a reference clip **plus its exact transcript**
  (`--ref-text`).

**For PhoneTTS this is the clean answer:** ship a small **pre-baked voice bank** (a handful
of embeddings, ~KB each), expose them as the model's voices in the descriptor, and **skip
runtime WAV cloning entirely for v1** (drops 475 MB and the record-a-reference UX). If
cloning is wanted later, it's an additive feature, not a re-architecture. Note this maps
onto PhoneTTS's SSOT rule: the voice list comes from the shipped bank via the
`ModelDescriptor`, not hardcoded.

---

## Recommendation for PhoneTTS

**Most viable on-device path: llama.cpp / ggml GGUF port (the CrispASR / `cstr` recipe),
NOT ONNX Runtime and NOT MNN.**

Rationale:
1. It is the **only path with a working, permissively-licensed (Apache-2.0 weights),
   fully-CPU, ARM-capable implementation that already exists.** ONNX-Runtime can't run the
   AR LLM (no published ONNX, and you'd have to hand-build the KV-cache decode loop +
   int4 ARM kernels + speech-token sampler that llama.cpp already has). MNN has no CosyVoice
   reference. sherpa-onnx explicitly won't do it.
2. Quantization is a **solved, validated** problem: **llm-Q4_K + flow-Q8_0 + hift-F16 +
   voices = 745 MB, 0% content WER**. Fits (tightly) in a 4 GB phone under PhoneTTS's
   one-engine-at-a-time rule.
3. The speaker problem is solved by a **pre-baked voice bank** (KB-scale), so no reference
   recording UX and no 475 MB of extra front-end models for v1.

**Cost to PhoneTTS's architecture:** this violates the app's "ONNX for most" default — it
introduces a **second, non-ONNX `Runtime`** (a ggml/llama.cpp JNI backend). SPEC already
anticipates exactly this ("a second, LLM-style [runtime] for CosyVoice2… behind an interface
so adding one touches nothing else"), so it's *designed-for*, but it's still real work:
building/bundling a llama.cpp+ggml native lib for arm64, JNI bindings, the CosyVoice
speech-token sampler, and wiring the flow/HiFT ggml stages. Everything downstream
(`synthesize() → Flow<FloatArray>`, WAV export, speed via native param) is unaffected.

**Effort: Large (L).** Not because the algorithm is unknown — it's fully worked out
upstream — but because it's a whole native runtime + JNI + build-system addition distinct
from the ONNX path the other four models share.

**Expected on-device experience on the A16 (4 GB, no NPU):**
- **File-export: yes**, this will work (sentence-chunked).
- **Real-time streaming: not guaranteed.** The 0.5B LLM decode is likely fast enough
  (≥25 tok/s needed; a 0.5B Q4 should clear that on the G99), but the 10-step DiT flow +
  HiFT vocoder have no ARM-optimized kernels and no published budget-phone timings — expect
  RTF around or above 1.0 until tuned (use `FLOW_STEPS=5` and chunking to claw it back).
- **Memory: tight but feasible** at 745 MB, only as the single loaded engine.

**Honest bottom line:** If the goal is "expressive/voice-cloning TTS on-device," CosyVoice2
via GGUF/llama.cpp is the right and only real choice, at **L effort, file-export-first**. If
the goal is just "good offline TTS on a budget phone," the **practitioner consensus is to
prefer Kokoro / Melo / Piper** (single ONNX graph, real-time on CPU, already on the
sherpa-onnx rail PhoneTTS's other engines use). Recommendation: **keep CosyVoice2 as the
build-order-first "hard model" it already is in the SPEC** (it forces the second-runtime
abstraction to be right), target it as **file-export with a pre-baked voice bank**, and do
not promise streaming real-time on the A16 until measured on-device.

---

## Sources

Primary (read directly):
- CosyVoice3 GGUF weights + quant/WER/voice-bank details — `cstr/cosyvoice3-0.5b-2512-GGUF`: <https://huggingface.co/cstr/cosyvoice3-0.5b-2512-GGUF>
- CrispASR ggml runtime (`cosyvoice3-tts` backend): <https://github.com/CrispStrobe/CrispASR>
- CrisperWeaver on-device Flutter/ggml app: <https://github.com/CrispStrobe/CrisperWeaver>
- FunAudioLLM/CosyVoice PR #1872 (llama-cpp-python backend, T4 RTF numbers): <https://github.com/FunAudioLLM/CosyVoice/pull/1872>
- sherpa-onnx CosyVoice feature request #3568 (unsupported): <https://github.com/k2-fsa/sherpa-onnx/issues/3568>
- sherpa-onnx supported TTS list (Piper/VITS/Matcha/Kokoro): <https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/index.html>
- sherpa-onnx repo (mobile platform support): <https://github.com/k2-fsa/sherpa-onnx>
- MNN transformers/llmexport README (no CosyVoice/TTS): <https://github.com/alibaba/MNN/blob/master/transformers/README.md>
- CosyVoice llm.pt→ONNX export issue #192 (unresolved): <https://github.com/FunAudioLLM/CosyVoice/issues/192>
- CosyVoice2 paper (25 Hz token rate, architecture): <https://arxiv.org/html/2412.10117v2>

Secondary / claimed (search summaries, not opened directly):
- Ferraronp GGUF weights referenced by PR #1872 (HF 504'd on fetch): `Ferraronp/CosyVoice3-qwen2.5-0.5b-speech-gguf`
- vLLM-omni CosyVoice2/3 issue #1552 (server-side): <https://github.com/vllm-project/vllm-omni/issues/1552>
- Supertonic on-device TTS (ONNX/MNN, non-AR alternative): <https://github.com/supertone-inc/supertonic>
- llama.cpp ARM/Snapdragon throughput reference points (Discussions #8273/#8336): <https://github.com/ggml-org/llama.cpp/discussions/8273>
