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

**Verdict: the assumed acoustic contract is COMPREHENSIVELY WRONG — flagged for rework, not
name-patched.** The engine assumed `token_ids`/`bert_features`/`speaker_id`/`speed`/`audio`; the
real graph uses `x`/`bert`(+`ja_bert`)/`sid`/`length_scale` and an auto-numbered output, and it
additionally requires `x_lengths`, `tone`, `language`, and three more scale scalars that the
engine does not supply at all. Speed must route to `length_scale` **inversely** (like Piper), not
a `speed` arg. Correcting only the names would still not run, so `MeloEngine` is left as-is (its
seam tests still pass against its own contract) with an in-code pointer here; the full rework —
tone/language id extraction in the frontend, dual BERT, four scale scalars, inverse length_scale,
and runtime output-name resolution — is tracked as a separate ticket and needs on-device
verification. The auto-numbered output name is the strongest argument for the load-time
name-resolution follow-up below.

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
