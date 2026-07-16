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

## MeloTTS — `MiaoMint/MeloTTS-ONNX` (`onnx_exports/en_v2/model.onnx`) — RETARGETED, PROVEN

**The previous `seasonstudio/melotts_zh_mix_en_onnx` path is ABANDONED.** It ran shape-correctly
against an 11-input dual-session (acoustic + BERT) contract but produced pure silence: that
export's phoneme embedding has 112 rows while the engine's hardcoded 219-symbol table assumed a
different vocabulary (SSOT violation — see `docs/MODEL-VERIFICATION.md`'s prior findings, kept
below the fold for history). MeloTTS is now retargeted to the sherpa-onnx-style MiaoMint export,
which `scripts/model-verify/run_melo2.py` PROVED produces real, non-silent English speech (10.54s,
908 KB, peak 0.293) — see that script for the reference recipe this engine now follows exactly.

The model ships FOUR files, all of which are the actual source of truth (no hardcoded table):

- `model.onnx` — the single acoustic session (no separate BERT model at all).
- `tokens.txt` — `"<symbol> <id>"` per line (219 entries) — read by `MeloTokens`.
- `lexicon.txt` — `"<word> p1..pN t1..tN"` per line (~4.8 MB G2P dictionary) — read by `MeloLexicon`.
- `metadata.json` — `{"model_type":"melo-vits","language_code":"en","add_blank":1,"n_speakers":5,
  "sample_rate":44100,"speaker_id":0,"lang_id":2,"tone_start":7,...}` — read by `MeloMetadata`.

Acoustic graph (`model.onnx`), the REAL 7-input contract — no `bert`, no `ja_bert`, no `language`
input, no `sdp_ratio`:

| direction | name             | dtype | shape                | notes |
|-----------|------------------|-------|----------------------|-------|
| input     | `x`              | int64 | `[1, L]`              | symbol ids, `add_blank`-interspersed |
| input     | `x_lengths`      | int64 | `[1]`                 | `L` |
| input     | `tones`          | int64 | `[1, L]`              | per-symbol tone id, same interspersing |
| input     | `sid`            | int64 | `[1]`                 | speaker id |
| input     | `noise_scale`    | float | scalar (fixed `0.6`)  | |
| input     | `length_scale`   | float | scalar                | **the speed control (inverse: bigger = slower)** |
| input     | `noise_scale_w`  | float | scalar (fixed `0.8`)  | |
| output    | (auto-numbered)  | float | `[N, S, T]`            | **name is graph-generated — read positionally** |

`MeloEngine.sessionInputs` builds all 7 named inputs and reads the acoustic output **positionally**
(`Map<String, Tensor>.singleFloatsOrError`, `engines/common/TensorOutputs.kt`). Speed routes to
`length_scale` **inversely** (`1f / speed`, guarded `speed > 0`), exactly like Piper's `scales[1]`.

`MeloFrontend`'s G2P mirrors `run_melo2.py` exactly: tokenize lowercased text with
`[a-zA-Z']+|[.,!?;:]`; a word found in `lexicon.txt` contributes its phonemes+tones; a punctuation
token that is itself a known symbol contributes itself with tone 0; anything else falls back to
the `UNK` symbol (tone 0) rather than crashing (fail-closed OOV, spec rule 4). `add_blank`
interspersing (`[0, v0, 0, v1, ..., vN, 0]`) is applied to both the symbol and tone sequences. No
espeak/IPA approximation is involved anymore — pronunciation comes straight from the bundled
lexicon.

`inspect()` fingerprints a bundle by: a `.onnx` file present, `tokens.txt` present, `lexicon.txt`
present, and a `metadata.json` whose `model_type` is `"melo-vits"` (or whose `comment` mentions
"melo") — fails closed otherwise. `n_speakers`/`sample_rate`/`speaker_id` in `metadata.json` build
the descriptor's voice table and sample rate; nothing is hardcoded outside this engine (SSOT).

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
