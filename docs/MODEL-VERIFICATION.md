# Model verification — do the models actually produce audio?

This proves the ONNX models PhoneTTS ships against actually generate valid speech **using the exact
tensor contracts the engines feed them** (the names/shapes validated in
[`onnx-io.md`](research/onnx-io.md)). Each model is given the same paragraph, run through ONNX
Runtime with the real phonemization, and the output audio is checked for: **> 1 KB** file size and
**> 2 seconds** duration, plus no NaNs and a sane peak amplitude.

Reproduce with the scripts in [`scripts/model-verify/`](../scripts/model-verify) (they self-download
the models; need `python3`, `onnxruntime`, `numpy`, and `espeak-ng` on PATH for phonemization).

## Results (paragraph of 3 sentences, speed 1.0)

| Model | Voice | Tokens | Duration | WAV size | Peak | NaNs | Verdict |
|---|---|---|---|---|---|---|---|
| **Piper** (en_US-lessac-medium) | lessac | 390 | **9.68 s** | **417 KB** | 0.639 | none | ✅ PASS |
| **Kokoro-82M** (fp32) | af_heart | 193 | **11.05 s** | **518 KB** | 0.519 | none | ✅ PASS (now one-tap) |
| **KittenTTS** (nano-0.1) | expr-voice-2-m | 194 | **10.25 s** | **480 KB** | 0.667 | none | ✅ PASS |
| MeloTTS (zh_mix_en export) | EN | 162 | 8.73 s | 752 KB | **0.000** | none | ⚠️ RUNS but **silent** — see below |
| CosyVoice2-0.5B | — | — | — | — | — | — | ⏸ deferred (LLM, ~1 GB, autoregressive) |

Every passing model clears the > 1 KB / > 2 s bar with large margins, confirming the validated
`input`/`input_lengths`/`scales`→`output` (Piper) and `input_ids`/`style`/`speed`→`waveform`
(Kokoro, Kitten) contracts produce real speech.

## Findings that affect the app

1. **Kokoro's q8f16 quantized export segfaults desktop ONNX Runtime** (mixed int8/fp16 ops). The
   **fp32** `onnx/model.onnx` runs cleanly. The one-tap download therefore pulls a known-good
   variant, and the in-app quantized-variant picker should treat q8f16 as risky. (onnxruntime-android
   may differ, but we default to the safe file.)
2. **Kokoro voice format — FIXED.** The real repo ships voices as `voices/*.bin` (`[510, 256]`
   float32, indexed by token count), not a `voices.json` table, and its `config.json` marks the
   family via `"model_type": "style_text_to_speech_2"` (no `family` field). `KokoroEngine` now
   reads `voices/<name>.bin` and selects the length-indexed style row (matching this test exactly),
   and detection accepts the real StyleTTS2 marker — so **Kokoro is now in the one-tap
   `BuiltInCatalog`** (fp32, 5 English voices).
3. **Phonemization** here uses system `espeak-ng` (same engine the app's `EspeakPhonemizer` wraps via
   NDK), so these token sequences match what the app produces once the espeak native build is on.

## MeloTTS — reworked to the right shape, but not yet audio-verified

The engine was reworked to the real 11-input contract (`x`/`tone`/`language`/`bert`/`ja_bert`/four
scale scalars, `length_scale = 1/speed`, positional output read) and its tests pass. But running the
only ONNX export on hand (`seasonstudio/melotts_zh_mix_en_onnx`) end-to-end surfaced a real,
**export-specific** problem that the same class as the tensor-name issue:

- **Symbol table size differs by export.** That export's phoneme embedding has **112 rows**; the
  engine's `MeloSymbolTable` is the **current 219-entry** upstream table. Feeding 219-table ids into
  the 112-row export throws `Gather ... idx=117 out of range [-112,111]` — i.e. **the app's hardcoded
  symbol table would crash on this export**, and vice-versa. MeloTTS symbol/tone/language ids must
  come from the *specific bundled model's* config, not one hardcoded table (SSOT — same lesson as
  tensor names).
- With ids clamped into range, the model runs to the right length but returns **pure silence
  (peak 0.0)** — expected, since clamped ids + English-IPA-that-falls-outside-this-export's-table +
  zeroed BERT give the duration/flow stack nothing meaningful to voice.

So MeloTTS is **shape-correct and test-green, but not proven to produce real speech.** Making it
sing needs an English MeloTTS export whose symbol/tone/language tables match the frontend (or a
config-driven table read at load time). It is therefore **not** in the one-tap `BuiltInCatalog`.
Tracked with the Kokoro voice-loader gap as the remaining engine work.

## What "verified" means and doesn't

- It verifies the **models + tensor contracts** produce valid audio off-device. It does **not** run
  the Android app itself (no emulator here) — on-device playback still depends on the espeak NDK
  build (`-PwithEspeak=true`) and onnxruntime-android.
- MeloTTS needs its multi-input frontend (tone/language ids + BERT) finished before it can be run
  the same way; CosyVoice2 is the deferred Tier-C LLM.
