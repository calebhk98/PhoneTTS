# ONNX graph I/O validation (Blocker 2)

Each ONNX engine was written against **assumed** input/output tensor names because no confirmed
export was on hand. This document records the **real** signatures, inspected directly from the
model files with `onnx.load(...).graph`, and what each engine had to change. Inspected 2026-07.

> Why this matters: different exports of the *same* model use different tensor names (e.g. an
> older Kokoro export calls the token input `tokens`, the current onnx-community one calls it
> `input_ids`). A hardcoded name that doesn't match the bundled graph fails at the first
> `session.run(...)`. These are now facts, not guesses — but they are still tied to the specific
> exports listed below; a different bundled export must be re-checked (ideally at load time by
> reading `session.inputNames`/`outputNames`, see "Follow-up" at the bottom).

## Piper — `rhasspy/piper-voices` (`en_US-lessac-medium.onnx`)

| direction | name            | dtype | shape                        |
|-----------|-----------------|-------|------------------------------|
| input     | `input`         | int64 | `[batch, phonemes]`          |
| input     | `input_lengths` | int64 | `[batch]`                    |
| input     | `scales`        | float | `[3]` = noise, length, noise_w |
| output    | `output`        | float | `[batch, time, 1, ...]`      |

**Verdict: engine assumptions were CORRECT.** `PiperEngine` already uses `input`,
`input_lengths`, `scales`, `output`, and passes speed as `length_scale` inside the `scales`
vector (element 1). No change needed. Note the 4-D output shape — the engine flattens it.

## Kokoro — `onnx-community/Kokoro-82M-v1.0-ONNX` (`onnx/model_q8f16.onnx`)

| direction | name        | dtype | shape          |
|-----------|-------------|-------|----------------|
| input     | `input_ids` | int64 | `[1, seq]`     |
| input     | `style`     | float | `[1, 256]`     |
| input     | `speed`     | float | `[1]`          |
| output    | `waveform`  | float | `[1, samples]` |

**Verdict: two names were WRONG — FIXED.** The engine assumed `tokens`/`audio`; the real graph
uses `input_ids`/`waveform`. `style` and `speed` were already correct, and the engine's
per-voice 256-dim style-embedding design matches the real model. Fixed `TOKENS_INPUT` →
`input_ids` and `AUDIO_OUTPUT` → `waveform` in `KokoroEngine`, updated the synthesis test.

## KittenTTS — `KittenML/kitten-tts-nano-0.1` (`kitten_tts_nano_v0_1.onnx`)

| direction | name        | dtype | shape          |
|-----------|-------------|-------|----------------|
| input     | `input_ids` | int64 | `[1, seq]`     |
| input     | `style`     | float | `[1, 256]`     |
| input     | `speed`     | float | `[1]`          |
| output    | `waveform`  | float | `[samples]`    |
| output    | `duration`  | int64 | `[...]`        |

`voices.npz` is a zip of 8 `.npy` arrays, each `(1, 256)` float32 — one **style embedding per
named voice**: `expr-voice-2-m`, `expr-voice-2-f`, `expr-voice-3-m/f`, `4-m/f`, `5-m/f`.

**Verdict: `input_ids`/`speed`/`waveform` CORRECT, but the voice model is WRONG.** KittenTTS is
StyleTTS2, identical in shape to Kokoro: it takes a 256-dim `style` embedding, **not** the
integer `speaker_id` the engine currently sends. The fix (tracked, mirrors Kokoro): load the
per-voice embeddings from `voices.npz`, key voices by the `.npy` names, and feed the selected
voice's `[1,256]` row to the `style` input. The `.onnx`-only companion assumption also changes:
the real voice table is `voices.npz`, not a `voices.json` name array.

## MeloTTS — `seasonstudio/melotts_zh_mix_en_onnx` (`tts_model.onnx` + `bert_lml_model.onnx`)

Acoustic graph (`tts_model.onnx`):

| direction | name             | dtype | shape                | notes |
|-----------|------------------|-------|----------------------|-------|
| input     | `x`              | int64 | `[1, len]`           | phoneme ids |
| input     | `x_lengths`      | int64 | `[1]`                | |
| input     | `sid`            | int64 | `[1]`                | speaker id |
| input     | `tone`           | int64 | `[1, len]`           | per-phoneme tone id |
| input     | `language`       | int64 | `[1, len]`           | per-phoneme language id |
| input     | `bert`           | float | `[1, 1024, len]`     | zh BERT features |
| input     | `ja_bert`        | float | `[1, 768, len]`      | ja BERT features |
| input     | `noise_scale`    | float | scalar               | |
| input     | `length_scale`   | float | scalar               | **the speed control (inverse: bigger = slower)** |
| input     | `noise_scale_w`  | float | scalar               | |
| input     | `sdp_ratio`      | float | scalar               | |
| output    | (auto-numbered, e.g. `14035`) | float | `[T, 1, ...]` | **name is graph-generated — read `outputNames[0]`** |

BERT graph (`bert_lml_model.onnx`): inputs `input_ids`, `token_type_ids`, `attention_mask` (all
int64 `[1, len]`); output auto-numbered (e.g. `1467`) float `[1, len, 768]`.

**Verdict: REWORKED against the real 11-input contract — runs the real graph shape-correctly, with
one honestly-documented gap (BERT/prosody), not stubbed.** `MeloEngine.sessionInputs` now builds
all 11 named inputs (`x`, `x_lengths`, `sid`, `tone`, `language`, `bert`, `ja_bert`, `noise_scale`,
`length_scale`, `noise_scale_w`, `sdp_ratio`) and reads the acoustic output **positionally**
(`Map<String, Tensor>.singleTensorOrError`, `engines/common/TensorOutputs.kt`) instead of by a
hardcoded name — no `InferenceSession` interface change was needed for this, since `run()` already
returns whatever key the runtime reports; taking the map's sole value works for a single-output
graph without widening the seam. Speed routes to `length_scale` **inversely**
(`1f / speed`, guarded `speed > 0`), exactly like Piper's `scales[1]`.

`MeloFrontend` (English only) now builds `x`/`tone`/`language` from MeloTTS's REAL, verbatim
symbol/tone/language-id table (`MeloSymbolTable`, copied index-for-index from upstream
`melo/text/symbols.py`, `language_id_map`, `language_tone_start_map` — myshell-ai/MeloTTS, checked
2026-07), including the real VITS "blank"/`add_blank` interspersing (`commons.intersperse`
upstream: `[0, v0, 0, v1, ..., vN, 0]`). What's approximated, and why:

  - **G2P**: real MeloTTS uses `g2p_en` (CMUdict + neural fallback) emitting ARPAbet. This module
    has neither in Kotlin, so per this ticket it routes through the shared espeak-ng
    [`Phonemizer`](../../core/src/main/kotlin/com/phonetts/core/text/Phonemizer.kt) instead and
    maps IPA codepoints to the closest English symbol one-at-a-time
    (`MeloEnglishPhonemeMap`) — valid, in-vocabulary ids every time, not upstream-identical
    pronunciation (diphthongs/affricates split into two phonemes; stress marks are honoured for
    tone but only approximately, since ARPAbet-exact stress needs CMUdict).
  - **`bert` (1024-dim) is fed ZEROS — this is NOT a shortcut, it is what real MeloTTS does.**
    `melo/utils.py::get_text_for_tts_infer` zeros the 1024-dim `bert` tensor for every
    non-Chinese language and puts the actual BERT hidden states in `ja_bert` (768-dim) instead —
    confirmed straight from upstream source. So no 768→1024 "padding/projection" was ever needed;
    the original ticket's premise about that was based on an incomplete read of the pipeline.
  - **`ja_bert` (768-dim) IS real content** — the `bert_lml_model.onnx` session is run (with
    `input_ids`/`token_type_ids`/`attention_mask`) and its `[1, L, 768]` output is
    nearest-neighbour resampled along the sequence axis to the acoustic model's phoneme count `T`
    (`MeloFrontend.resampleToPhoneCount`). Real MeloTTS instead does an EXACT `word2ph` (word ->
    phoneme count) alignment from its own tokenizer; this frontend does not implement that, so
    prosody conditioning is present but not word-aligned — a documented, not hidden, gap.
  - **BERT tokenization** is still the placeholder hash-based tokenizer (real MeloTTS loads
    `bert-base-uncased`'s WordPiece vocab, which ships with the model bundle, not this jar).

Net effect: MeloTTS now runs end-to-end against the real graph and produces audio (perfect
prosody remains out of scope, per this ticket's own bar). The auto-numbered output name is still
the strongest argument for the load-time name-resolution follow-up below, for engines that DO have
more than one output.

## CosyVoice2 — NOT single-file validatable

LLM backbone + flow-matching decoder + vocoder, ~1 GB, autoregressive (spec §3.1 Tier C). It is
multi-graph and has no plain single-file ONNX signature to validate the same way; its I/O must be
established when the second (LLM-style) runtime is wired in Phase 2.1.

## Follow-up (durable fix beyond this validation)

Because exports of one model disagree on names, the robust long-term design is to resolve logical
inputs to real names **at load time**: expose `inputNames`/`outputNames` on `InferenceSession`
(they come straight from `OrtSession`), and have each engine map its logical inputs
(tokens/style/speed) via a short alias list + positional fallback, failing closed if it cannot map
with confidence. That turns "wrong hardcoded name" from a runtime crash into the same fail-closed
refusal the resolver already uses. Tracked as a separate follow-up.
