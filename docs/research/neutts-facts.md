# NeuTTS Nano - researched facts (2026-07-24)

Every fact below was read directly from the Hugging Face Hub (via the `hf_fs` MCP tool) or from
the upstream GitHub repository (`neuphonic/neutts`, via `WebFetch`/`WebSearch`), with the exact
source cited inline. Nothing here is inferred beyond what's explicitly stated in a model card,
`config.json`, or upstream source file. Where a claim could not be directly verified, it is marked
**UNVERIFIED** and the engine/design treats it as not-yet-proven rather than assumed.

This document exists to satisfy CLAUDE.md rule 1 (no model fact as a literal outside the
descriptor/resolver layer) and rule 4 (`inspect()` fails closed): every number the engine reads
from a bundle manifest, and every number engineering decisions below are based on, traces back to
one of these citations.

## 0. Correction to the session brief

The task brief that kicked off this research assumed **"License = Apache-2.0 (confirm per
checkpoint)."** That is only half right:

| Checkpoint | License | Source |
|---|---|---|
| `neuphonic/neutts-nano` (+ its `-q4-gguf`/`-q8-gguf`/language siblings) | **"NeuTTS Open License v1.0"** - a Neuphonic-authored, non-standard license ("intended to allow free research use and limited commercial use, while requiring a paid license for large-revenue commercial deployments"), `license: other` in the HF metadata | `hf://models/neuphonic/neutts-nano/README.md` (`license: other`) and `LICENCE` file, read directly |
| `neuphonic/neutts-air` (+ its `-q4-gguf`/`-q8-gguf`/`-onnx` siblings) | **Apache-2.0** | `hf://models/neuphonic/neutts-air/README.md` (`license: apache-2.0`) |
| `neuphonic/neucodec` (the shared codec, both checkpoints use it) | **Apache-2.0** | `hf://models/neuphonic/neucodec/README.md` (`license: apache-2.0`, and "Commercial use permitted") |
| `neuphonic/neutts-2e` (emotional, fixed-speaker sibling, not this ticket's target) | "NeuTTS Open License v1.0" (`license: other`) | `hf://models/neuphonic/neutts-2e/README.md` |

**This ticket's target is NeuTTS Nano**, so the engine and its docs below say the license honestly:
**NeuTTS Open License v1.0**, not Apache-2.0. `docs/MODELS.md`/license-surfacing UI (parent-owned,
not touched by this ticket) should reflect this per-checkpoint, not assume Apache-2.0 app-wide.

## 1. Repo ids (verified via `hf_fs find hf://models/neuphonic`)

| Repo id | Contents | Size |
|---|---|---|
| `neuphonic/neutts-nano` | fp32 safetensors + tokenizer (English) | `model.safetensors` 914,843,656 bytes |
| `neuphonic/neutts-nano-q4-gguf` | `neutts-nano-Q4_0.gguf` | 194,600,640 bytes |
| `neuphonic/neutts-nano-q8-gguf` | `neutts-nano-Q8_0.gguf` | 252,744,384 bytes |
| `neuphonic/neutts-nano-{spanish,french,german}[,-q4-gguf,-q8-gguf]` | Same architecture, other languages (multilingual collection) | same shape |
| `neuphonic/neutts-air` | fp32 safetensors + `neutss-air-BF16.gguf` (sic - upstream's own typo'd filename) | safetensors 1,495,893,752 bytes; BF16 GGUF 1,503,776,000 bytes |
| `neuphonic/neutts-air-q4-gguf` | `neutts-air-Q4_0.gguf` | 527,054,304 bytes |
| `neuphonic/neutts-air-q8-gguf` | `neutts-air-Q8_0.gguf` | 802,658,528 bytes |
| `neuphonic/neucodec` | The shared neural audio codec (PyTorch) | - |
| `neuphonic/neucodec-onnx-decoder` / `-int8` | ONNX export of just the NeuCodec **decoder** half (codes → waveform) | 782,565,930 bytes (fp) |
| `neuphonic/neutts-2e[,-q4-gguf,-q8-gguf]` | Newer emotional sibling, **four fixed speakers** (`emily`/`paul`/`sophie`/`steven`), not voice-cloning | - |

All confirmed to exist and be non-gated/public via `hf_fs ls`/`find`.

## 2. Architecture

- **Two-stage pipeline, not one fused native call** (unlike CosyVoice3/CrispASR - see §6):
  1. A GGUF **LLM backbone** (loaded via `llama-cpp-python`'s `Llama.from_pretrained(..., filename="*.gguf")`
     per `neutts/neutts.py`, confirmed by `WebFetch` of that file) autoregressively generates a
     sequence of speech-token strings, `<|speech_0|>`, `<|speech_1|>`, ... The prompt embeds the
     text (or its phonemization, see §4) between `<|TEXT_PROMPT_START|>`/`<|TEXT_PROMPT_END|>`
     markers and a voice-cloning reference-code prefix; generation stops at
     `<|SPEECH_GENERATION_END|>`.
  2. The extracted integer speech-token ids are decoded to a 24 kHz waveform by **NeuCodec**'s
     decoder: `self.codec.decode_code(codes)` (`codes` shaped `(1, 1, T)`), either the ONNX decoder
     (`neuphonic/neucodec-onnx-decoder`) or the PyTorch one.
- **Backbone family**: `neutts-nano`'s `config.json` (read directly) is `architectures:
  ["LlamaForCausalLM"]`, `hidden_size: 576`, `num_hidden_layers: 24`, `num_attention_heads: 9`,
  `num_key_value_heads: 3`, `vocab_size: 194256` (194,256 - notably larger than a stock Llama/Qwen
  text vocab, consistent with the codec's speech-token ids sharing the same embedding table),
  `rope_scaling: {type: linear, factor: 32}`. **This is a compact Llama-architecture model, not
  Qwen** - the "Qwen-family backbone" claim in the session brief is true only of the larger sibling,
  **NeuTTS **Air**, which the README states is "built off Qwen 0.5B."** Nano's own README calls its
  backbone only "a compact LM backbone tuned for TTS token generation (Nano class)" and never names
  Qwen.
- **Parameter counts (Nano)**, from `neutts-nano/README.md`: active (backbone only) **~116.8M**,
  total (backbone + tied embeddings/head) **~228.7M**.
- **Audio codec - NeuCodec** (`neuphonic/neucodec/README.md`): Finite Scalar Quantisation (FSQ),
  **single codebook**, **0.8 kbps**, **50 tokens/sec**, **16 kHz input → 24 kHz output** via an
  upsampling decoder, extends X-Codec2.0, Apache-2.0, paper arXiv:2509.09550.
- **Output sample rate: 24,000 Hz.** Confirmed twice: NeuCodec's own README ("Upsamples from
  16kHz → 24kHz") and every NeuTTS example script's `sf.write("test.wav", wav, 24000)` /
  `neutts/neutts.py`'s `self.sample_rate = 24_000` (via `WebFetch` of that file).

## 3. Voices - cloning-first, not a fixed voice bank

NeuTTS Nano (and Air) are **voice-cloning models**, not fixed-speaker models (that's NeuTTS-2E, a
different, newer checkpoint - see §1). Confirmed directly from both READMEs: *"NeuTTS Nano requires
two inputs: 1. A reference audio sample (.wav file) 2. A text string ... This is what enables
NeuTTS Nano's instant voice cloning capability."* `encode_reference()` runs the audio through
NeuCodec's **encoder** once to get a fixed reference-code prefix, which is then reused as the
"speaker prompt" for every synthesis call - so a "voice," for this model, **is** a
(reference-audio, reference-transcript) pair, not a discrete speaker id baked into the weights.

Reference-audio guidelines (both READMEs, verbatim): mono, 16-44 kHz, 3-15 seconds, `.wav`, clean
(minimal background noise), natural continuous speech.

**Example reference clips that ship in the upstream GitHub repo's `samples/` folder** (confirmed
via `WebFetch` of `github.com/neuphonic/neutts`, `tree/main` listing): English - `dave.wav`,
`jo.wav`, `emily.wav`, `paul.wav`, `sophie.wav`, `steven.wav`; Spanish - `mateo.wav`; German -
`greta.wav`; French - `juliette.wav` (each with an accompanying `.txt` transcript). **These are
cloning *examples* shipped with the pip package/repo, not a HF-hosted "voices" bundle** - PhoneTTS
never bundles them (rule 7: weights/data are never shipped in the APK), so the descriptor's
"preset voices" are honestly whichever (ref-audio, ref-text) pairs a bundle producer chooses to
ship alongside the GGUF backbone (see §5's manifest shape) - this reflects the cloning nature
rather than papering over it with a fake fixed voice list.

**NeuTTS-2E's four fixed speakers (`emily`/`paul`/`sophie`/`steven`) belong to a *different*
checkpoint** (`neuphonic/neutts-2e`, "NeuTTS Open License v1.0", emotional-only, English-only, not
requested by this ticket) and must not be conflated with Nano's cloning-only voice model.

## 4. Text frontend - phonemization is genuinely model-dependent, discovered per-checkpoint

`neutts/neutts.py` (`WebFetch`'d directly) determines `input_format` **per loaded GGUF**, not
per model family:

- For a quantized (GGUF) backbone: `self.input_format = self.backbone.metadata.get('neuphonic.input_format')`,
  falling back (if that GGUF metadata key is absent) to sniffing the chat template: phoneme-mode
  prompts contain `"Convert the text to speech:<|TEXT_PROMPT_START|>...`, BPE-mode prompts use a
  different marker combination.
- For an unquantized (safetensors) backbone: `neuphonic_cfg.get('input_format', 'phonemes')`
  (defaults to phonemes).
- **When `input_format == "phonemes"`**, the Python client calls a phonemizer
  (`self.phonemizer.phonemize([text])`, an espeak-ng-backed phonemizer selected by a per-language
  eSpeak code) **before** the LLM call. `neutts-nano/README.md` independently confirms this as a
  **required system dependency**: *"Install System Dependencies (required): `espeak-ng`."*
- **`BACKBONE_LANGUAGE_MAP`** in `neutts.py` maps repo ids (including `neuphonic/neutts-air`,
  `neuphonic/neutts-nano`, and the language variants) to a phonemizer language - but per the same
  source, this only selects *which* eSpeak language code to phonemize with, it does **not** by
  itself determine whether phonemization happens at all (that's the GGUF-embedded
  `input_format`, above).

**Design consequence for this engine** (see `NeuTtsEngine` KDoc): whether a given GGUF wants
phonemes or raw/BPE text is a **discovered, per-bundle fact**, exactly like CLAUDE.md rule 1
requires - never hardcoded here. It must ride in the bundle's manifest sidecar (§5) as
`"input_format"`, sourced by whoever produces the bundle from the GGUF's own embedded
`neuphonic.input_format` metadata key (a native GGUF-metadata read, not something this JVM module
parses). PhoneTTS already has exactly the seam this needs -
`com.phonetts.core.text.Phonemizer`, espeak-ng-backed (`docs/espeak-ng-integration.md`, not
touched by this ticket) - injected via `EngineContext.phonemizer`, so `NeuTtsEngine` phonemizes in
Kotlin before building the native request precisely when the manifest says to, and passes text
straight through otherwise. This is different from `GgmlTtsEngine`/`NativeCosyVoiceRuntime`, whose
native pipeline tokenizes everything internally with no Kotlin-side frontend at all - NeuTTS
genuinely needs one.

## 5. No native speed knob

Every example call (`tts.infer(input_text, ref_codes, ref_text)`) takes no speed/duration argument
anywhere in the READMEs or the `neutts.py` fetch above. Per CLAUDE.md rule 2, the engine therefore
declares **no** tunable speed parameter (honest-closed, the same posture `GgmlTtsEngine` and
`CosyVoice3`/`NativeCosyVoiceRuntime` take) rather than resampling output to fake one.

## 6. Runtime: needs its OWN `NativeTtsRuntime`, not `GgmlTtsEngine`'s "ggml" backend

`docs/research/runtime-feasibility-2026-07.md` (already in this repo, written before this ticket)
independently reaches the same conclusion the task brief anticipated - quoting it directly (§2,
"Caveats"): *"`neuphonic/neutts-air-q{4,8}-gguf` (already bucket C rank 4) ... are separate,
already-GGUF projects, **not part of CrispASR** - they'd each need their own small
`NativeTtsRuntime` implementation."* Cross-checked directly against CrispASR's own
`docs/tts.md` (`WebFetch`'d from `CrispStrobe/CrispASR`): its 34-backend list does **not** mention
NeuTTS, Neuphonic, or NeuCodec by name anywhere. So unlike `engines/ggmltts` (a thin Kotlin
generalization over CrispASR's *already-shipped* multi-backend native library), a NeuTTS native
bridge is **new native work**: it has to link `llama.cpp` (for the GGUF LLM backbone - the same
library CrispASR itself forks from, per its README, but NeuTTS is not one of CrispASR's backends)
*and* a NeuCodec decoder (either a ported ggml/C++ ISTFT+FSQ decoder, or embed the existing
`neuphonic/neucodec-onnx-decoder` behind ONNX Runtime as a second inference call inside the same
native library). **Neither half of that native bridge exists in this repo today** - see
`engines/neutts/INTEGRATION.md` §6 for the honest pending-work breakdown.

## 7. Sources (every URL fetched during this research pass)

- `hf://models/neuphonic` (find, `-r`) - repo enumeration
- `hf://models/neuphonic/neutts-nano/README.md`, `config.json`, `LICENCE` (first 3000 bytes)
- `hf://models/neuphonic/neutts-nano-q4-gguf/README.md`, file listing
- `hf://models/neuphonic/neutts-nano-q8-gguf` file listing
- `hf://models/neuphonic/neutts-air/README.md`, file listing
- `hf://models/neuphonic/neutts-air-q4-gguf`, `-q8-gguf` file listings + `meta.yaml`
- `hf://models/neuphonic/neucodec/README.md`
- `hf://models/neuphonic/neucodec-onnx-decoder/README.md`, file listing
- `hf://models/neuphonic/neutts-2e/README.md`
- `https://github.com/neuphonic/neutts` (tree listing, via WebFetch)
- `https://raw.githubusercontent.com/neuphonic/neutts/main/README.md` (via WebFetch)
- `https://github.com/neuphonic/neutts/tree/main/neutts` (via WebFetch)
- `https://raw.githubusercontent.com/neuphonic/neutts/main/neutts/neutts.py` (via WebFetch, twice -
  synthesis pipeline, then `input_format` determination logic)
- `https://raw.githubusercontent.com/CrispStrobe/CrispASR/main/docs/tts.md` (via WebFetch - checked
  NeuTTS is NOT one of CrispASR's backends)
- This repo's own `docs/research/runtime-feasibility-2026-07.md` and `model-triage-2026-07.md`
  (pre-existing, cross-checked, not written by this ticket)

## 8. Open / UNVERIFIED items (fail-closed, not guessed at)

- The **exact GGUF metadata key format** for `neuphonic.input_format` (string enum values, exact
  spelling) is described by `neutts.py`'s Python code but this ticket did not load an actual GGUF's
  header to confirm the literal string values (`"phonemes"` vs `"bpe"` are inferred from context,
  not read from a raw GGUF byte-for-byte). The manifest shape in §5 of `INTEGRATION.md` treats this
  as a value the bundle producer must supply and the engine just reads back - **UNVERIFIED** exact
  enum spelling, so `NeuTtsEngine` only special-cases the literal `"phonemes"` and passes text
  through unchanged for every other value (fail-open on the safe/simpler path, never silently wrong).
- Whether a ggml/C++ port of the NeuCodec decoder exists anywhere is **UNVERIFIED** - not found in
  this research pass. `engines/neutts/INTEGRATION.md` treats the whole native bridge as unbuilt.
- Peak RAM / resource cost for NeuTTS Nano at Q4/Q8 was not benchmarked here - the descriptor
  reports `ResourceCost.UNKNOWN` rather than a fabricated number (CLAUDE.md rule 1).
