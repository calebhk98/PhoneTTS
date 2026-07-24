# Kyutai Pocket TTS - research findings

Model: `kyutai/pocket-tts` (Hugging Face). Researched 2026-07-24 for the `:engines:pockettts`
integration. Every fact below is sourced; nothing here is invented (CLAUDE.md rule 1/the
"NEVER fabricate" constraint on this ticket).

## Verdict

**No official Kyutai ONNX/ggml export exists.** A genuine, working, publicly downloadable
**community/unofficial** ONNX export exists and is real (not vaporware) - but it is a **5-graph,
stateful, autoregressive + flow-matching pipeline**, not the single-graph shape this app's other
ONNX engines (KittenTTS, Kokoro, Piper, MeloTTS) use. Building a correct, verified Kotlin port of
its driving algorithm was judged out of scope for a single session that cannot run the pipeline
against real weights to check the output (see "Why honest-closed, not real inference" below).
`engines/pockettts/` therefore ships the **honest-closed** posture: real `inspect()`/descriptor
facts, `synthesizeSentence` fails closed.

## Official model facts (sourced)

- **HF repo**: `kyutai/pocket-tts` - **gated** (access-request required; this session could not
  read its README/config directly via the HF MCP tool, which returned "Access to model
  kyutai/pocket-tts is restricted"). Its file listing (`hf://models/kyutai/pocket-tts`, readable
  even while gated) shows: `tts_b6369a24.safetensors` (weights, ~235 MB, LFS), `tokenizer.model`
  (LFS, SentencePiece), `embeddings/`, `embeddings_v2/`, `embeddings_v3/`, `languages/`
  directories (per-language voice embeddings), and a README.
- **GitHub**: [kyutai-labs/pocket-tts](https://github.com/kyutai-labs/pocket-tts) - the official
  Python package (`pocket-tts` on PyPI), MIT licensed
  ([LICENSE](https://raw.githubusercontent.com/kyutai-labs/pocket-tts/main/LICENSE) - full MIT
  text, no additional restriction in the code license itself; see "License nuance" below for the
  weights).
- **Size**: 100M parameters, CPU-only inference (`torch>=2.5.0`, no GPU required); the README
  claims "~6x real-time on a CPU of MacBook Air M4" and "~200ms" time-to-first-audio-chunk on 2
  CPU cores.
- **Sample rate: 24000 Hz.** Not stated in the official README's prose, but confirmed directly
  from real exported metadata: `KevinAHM/pocket-tts-onnx/onnx/english_2026-04/bundle.json` (public
  on HF, fetched in this session) has `"sample_rate": 24000` and `"samples_per_frame": 1920`
  (24000/1920 = 12.5 Hz latent frame rate, matching Mimi's well-known 12.5 Hz continuous-latent
  configuration and the exporter's own comment "200Hz transformer / 12.5Hz latent = 16"). The
  export script also traces the Mimi encoder with `torch.randn(1, 1, 24000)` "1 second" of dummy
  audio (`scripts/export_mimi_and_conditioner.py`), independently confirming 24 kHz. Multiple
  third-party write-ups (aimodels.fyi, a Termo.ai skill page) independently state 24 kHz mono
  16-bit PCM, consistent with the above.
- **Voices**: the official README (`kyutai-labs/pocket-tts` `README.md`, fetched via
  `raw.githubusercontent.com`) lists **27 named preset voices** across **6 languages** - English,
  French, German, Portuguese, Italian, Spanish (`--language english|french|german|portuguese|
  italian|spanish`, with bigger "24-layer" variants for the non-English languages selectable as
  e.g. `--language italian_24l`). Voice cloning from an arbitrary `.wav`/`.mp3` reference is also
  supported (zero-shot).
- **Per-bundle voice subsets are real and vary.** The `english_2026-04` bundle's own
  `bundle.json` lists only 8 of those names as its `predefined_voices`: `alba, azelma, cosette,
  eponine, fantine, javert, jean, marius` - the full 27-name list is the union across all
  language/variant bundles, not what any single bundle ships. This engine reads
  `predefined_voices` per-bundle rather than hardcoding the 27-name README list (SSOT).
- **License nuance**: the **code** (`kyutai-labs/pocket-tts` and the exporter) is MIT. The
  **model weights** are CC BY 4.0 (`KevinAHM/pocket-tts-onnx`'s own README states this explicitly:
  "Models: CC BY 4.0 (inherited from kyutai/pocket-tts)"; per-voice licenses are individually
  documented at [kyutai/tts-voices](https://huggingface.co/kyutai/tts-voices)). The task prompt
  said "MIT" - that is correct for the *code*, but the *weights* carry the stricter CC BY 4.0
  attribution requirement, which matters for anything this app ships/redistributes.
- **Text frontend**: a SentencePiece tokenizer (`tokenizer.model`, per-bundle/per-language file).
  There is no separate phonemizer - `pocket_tts_onnx.py`'s `_tokenize`/`_prepare_text_prompt`
  do light text normalization (capitalize, ensure trailing punctuation, `;` -> `,` if
  `remove_semicolons` is set) then SentencePiece-encode directly to token ids. This codebase has
  no SentencePiece reader (Kitten/Kokoro/Piper all use their own simpler
  `com.phonetts.core.engine.TextFrontend` implementations, none of them SentencePiece-based).
- **Architecture** (from the export scripts' own naming/comments, `kyutai-labs/pocket-tts`'s
  vendored source read via the export repo): an AR "FlowLM" transformer that emits a per-frame
  1024-dim `conditioning` vector and an `eos_logit`, driving a **flow-matching** ("LSD"/Euler
  integration) sampler that turns that conditioning + noise into a continuous latent, decoded to
  audio by a **Mimi**-derived codec decoder operating on continuous latents (not discrete codes -
  the class-level comment in the exporter explicitly notes "Mimi... uses continuous latents,
  regularized to follow a normal distribution" as the Pocket-TTS-specific variant of Mimi).

## The community ONNX export (real, but not "one simple graph")

- **Exporter**: [KevinAHM/pocket-tts-onnx-export](https://github.com/KevinAHM/pocket-tts-onnx-export)
  (GitHub, unofficial/community, by a third party - not Kyutai). Requires the **gated**
  `kyutai/pocket-tts` checkpoint to run. License: exporter code MIT (derived from
  `kyutai-labs/pocket-tts`); exported artifacts subject to upstream model/dataset terms.
- **Published weights + runtime**: [KevinAHM/pocket-tts-onnx](https://huggingface.co/KevinAHM/pocket-tts-onnx)
  on Hugging Face - **publicly listable and downloadable** (confirmed: `hf_fs ls` on this repo
  worked with no gating error, unlike the base `kyutai/pocket-tts` repo). Ships one bundle
  directory per language variant (`onnx/english_2026-04/`, `onnx/french_24l/`, `onnx/german/`,
  `onnx/german_24l/`, `onnx/italian/`, `onnx/italian_24l/`, `onnx/portuguese/`,
  `onnx/portuguese_24l/`, `onnx/spanish/`, `onnx/spanish_24l/`), each self-contained: `bundle.json`,
  `tokenizer.model`, `bos_before_voice.npy`, and the 5 ONNX graphs below (plus legacy root-level
  copies for backward compatibility with an older single-bundle layout - this engine only
  recognizes the per-language bundle-directory layout).
- **The 5 ONNX graphs per bundle** (file names, from the exporter's own README and confirmed
  present in the published HF repo's file listing):
  - `text_conditioner.onnx` - inputs `["token_ids"]` -> outputs `["embeddings"]`. FP32 only.
  - `mimi_encoder.onnx` - inputs `["audio"]` -> outputs `["latents"]`. FP32 only (used for
    zero-shot voice cloning from a reference wav).
  - `flow_lm_main.onnx` - the AR transformer backbone + per-layer state update. Inputs
    `["sequence", "text_embeddings", state_0..N]`, outputs
    `["conditioning", "eos_logit", out_state_0..N]`. Optional `_int8.onnx` sibling.
  - `flow_lm_flow.onnx` - the stateless flow-matching (Euler integration) step. Inputs
    `["c", "s", "t", "x"]` -> output `["flow_dir"]`. Optional `_int8.onnx` sibling.
  - `mimi_decoder.onnx` - the codec decoder. Inputs `["latent", state_0..N]`, outputs
    `["audio_frame", out_state_0..N]`. Optional `_int8.onnx` sibling.

  (All five fixed input/output *names* above are read directly from the exporter's actual
  `torch.onnx.export(..., input_names=..., output_names=...)` calls in
  `scripts/export_flow_lm.py` and `scripts/export_mimi_and_conditioner.py`, fetched from
  `raw.githubusercontent.com` in this session - not guessed.)

- **The state tensors are dynamic, per-bundle, and numerous - not a fixed contract.** `state_0..N`
  / `out_state_0..N` are generated by `onnx_export/bundle_metadata.py`'s
  `build_state_manifest()`, which walks whatever KV-cache/counter tensors the actual PyTorch
  module tree has and numbers them in sorted-path order. This session fetched the REAL manifest
  for the published `english_2026-04` bundle (a 6-layer variant):
  `flow_lm_state_manifest` has **18 entries** (`cache`/`current_end`/`step` x 6 transformer
  layers, e.g. layer 0's attention cache is `float32` shape `[2, 1, 1000, 16, 64]`), and
  `mimi_state_manifest` has **56 entries** (per-layer attention `cache`/`end_offset`/`offset` plus
  an upsample-conv `partial` state, shape `[1, 512, 16]`). A 24-layer language variant would have
  a proportionally larger flow_lm state set. **These counts/shapes are read from each bundle's own
  `bundle.json` at load time by the reference Python driver - they are not a fixed, hardcodable
  constant**, which is exactly why `PocketTtsEngine.inspect()` fingerprints the *presence* of the
  `flow_lm_state_manifest`/`mimi_state_manifest` arrays (proving the schema) without hardcoding
  their contents anywhere.
- **The reference driver's full algorithm is real and was read in this session** (not
  truncated): `pocket_tts_onnx.py` in `KevinAHM/pocket-tts-onnx` implements text chunking by
  sentence/clause boundary (`_split_into_best_sentences`), a per-chunk `flow_lm_main` conditioning
  call, then a per-frame loop that (a) checks `eos_logit > -4.0` for end-of-speech, (b) draws
  temperature-scaled Gaussian noise, (c) runs `flow_lm_flow` `lsd_steps` times doing simple Euler
  integration (`x = x + flow * dt`) to produce one latent frame, and (d) periodically batches
  accumulated latents through `mimi_decoder` (with its own separately-threaded state) to produce
  audio - with a background-thread pipeline for offline generation and an adaptive-chunk-size
  variant for streaming.
- **The reference driver is NOT fully offline by default.** `prepare_voice_state()` fetches a
  built-in voice's precomputed conditioning state via `hf_hub_download(repo_id="kyutai/pocket-tts",
  filename=f"languages/{language}/embeddings/{voice}.safetensors")` **at first use**, unless the
  caller supplies a local `.safetensors` file or precomputed embedding array instead. That is a
  live network call at inference time for the common "just use a built-in voice" path - directly
  in tension with this app's "fully-offline... no network calls at inference time" requirement.
  A correct on-device integration would need every built-in voice's embedding bundled/downloaded
  up front as part of the model manifest (CLAUDE.md rule 7), not fetched lazily.

## Why honest-closed, not real inference, in this session

`engines/pockettts/` mirrors `engines/ggmltts/`'s "compiles everywhere, real half pending" posture
rather than KittenTTS's "single `InferenceSession.run()` call" posture, because:

1. It is **5 ONNX graphs**, not 1, with **manifest-driven, per-bundle-sized state** (18-56+
   dynamically named tensors observed above) that must be threaded by hand across many session
   calls per generated frame - a materially different order of integration work than any existing
   engine in this codebase.
2. Correctly reproducing it requires porting a **numerical sampling algorithm** (temperature
   noise -> Euler-integrated flow matching -> EOS thresholding -> adaptive frame chunking) from
   Python to Kotlin, plus a **SentencePiece decoder** this codebase does not have. That is
   substantial novel algorithmic work, not "call the exemplar's `InferenceSession.run()` with a
   different tensor name."
3. This session has **no way to run the ported code against the real (24-1200+ MB per graph)
   weights and listen to the output** to confirm correctness - the base checkpoint is gated, and
   there is no on-device/emulator audio verification available here. Shipping a plausible-looking
   but unverified port of a multi-step numerical algorithm is exactly the fabrication CLAUDE.md
   rule 1 forbids ("if you cannot source the real I/O contract, DO NOT invent one" - here the
   *shape* of the I/O is sourced, but the *behavior* of driving it correctly is not verifiable).
4. The reference implementation's default reliance on a live Hugging Face fetch for built-in
   voices (point above) means a naive port would also violate this app's fully-offline
   requirement unless voice embeddings are separately bundled - another design decision better
   made deliberately by a session that can also touch the download-manifest side, which is out of
   this ticket's `engines/pockettts/`-only scope.

What **is** real and shipped: `inspect()`/`forcedMatch()` fingerprint the exact bundle layout
above (fail-closed per spec §9.1 - see `PocketTtsInspectTest`), and the `ModelDescriptor` it
builds carries only verified, per-bundle-discovered facts (`sample_rate` from `bundle.json`,
voices from `predefined_voices`, real asset paths for all 5 graphs + config + tokenizer, correctly
preferring whichever precision variant - FP32 or `_int8` - the bundle actually ships). No speed
parameter is advertised (none was found in the reference API/CLI - `temperature`/`lsd_steps`
control generation diversity/quality, not speech rate, so advertising either as "Speed" would
violate CLAUDE.md rule 2).

## Sources

- <https://huggingface.co/kyutai/pocket-tts> (gated; file listing readable, README/config not)
- <https://github.com/kyutai-labs/pocket-tts> / `README.md`, `LICENSE`, `pyproject.toml` (fetched via
  `raw.githubusercontent.com`)
- <https://kyutai-labs.github.io/pocket-tts/> (docs site)
- <https://github.com/KevinAHM/pocket-tts-onnx-export> (`README.md`,
  `onnx_export/wrappers.py`, `onnx_export/bundle_metadata.py`, `scripts/export_flow_lm.py`,
  `scripts/export_mimi_and_conditioner.py`, `pocket_tts/default_parameters.py`,
  `pocket_tts/models/mimi.py` - all fetched via `raw.githubusercontent.com`)
- <https://huggingface.co/KevinAHM/pocket-tts-onnx> (`README.md`, `pocket_tts_onnx.py`,
  `onnx/english_2026-04/bundle.json` - fetched via the Hugging Face MCP filesystem tool; publicly
  readable, not gated)
- <https://github.com/VolgaGerm/PocketTTS.cpp> (a second, C++/single-file community runtime;
  noted for completeness, not independently verified in this session)
- Third-party corroboration for sample rate/voice count (not authoritative on their own, used only
  to cross-check the primary sources above): aimodels.fyi's `pocket-tts-kyutai` model page, a
  Termo.ai "Pocket TTS" skill summary page.
