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
| **Kokoro-82M** (fp32) | af_heart | 193 | **11.05 s** | **518 KB** | 0.519 | none | ✅ PASS |
| **KittenTTS** (nano-0.1) | expr-voice-2-m | 194 | **10.25 s** | **480 KB** | 0.667 | none | ✅ PASS |
| MeloTTS | — | — | — | — | — | — | ⏳ pending frontend rework (tone/lang/BERT) |
| CosyVoice2-0.5B | — | — | — | — | — | — | ⏸ deferred (LLM, ~1 GB, autoregressive) |

Every passing model clears the > 1 KB / > 2 s bar with large margins, confirming the validated
`input`/`input_lengths`/`scales`→`output` (Piper) and `input_ids`/`style`/`speed`→`waveform`
(Kokoro, Kitten) contracts produce real speech.

## Findings that affect the app

1. **Kokoro's q8f16 quantized export segfaults desktop ONNX Runtime** (mixed int8/fp16 ops). The
   **fp32** `onnx/model.onnx` runs cleanly. The one-tap download therefore pulls a known-good
   variant, and the in-app quantized-variant picker should treat q8f16 as risky. (onnxruntime-android
   may differ, but we default to the safe file.)
2. **Kokoro voice format:** the real repo ships voices as `voices/*.bin` (`[510, 256]` float32,
   indexed by token count), **not** the `voices.json` name→embedding table `KokoroEngine` currently
   expects. The model is proven working (above); the engine's voice loader needs to read `*.bin`
   before Kokoro is one-tap-ready in-app. Tracked as a follow-up.
3. **Phonemization** here uses system `espeak-ng` (same engine the app's `EspeakPhonemizer` wraps via
   NDK), so these token sequences match what the app produces once the espeak native build is on.

## What "verified" means and doesn't

- It verifies the **models + tensor contracts** produce valid audio off-device. It does **not** run
  the Android app itself (no emulator here) — on-device playback still depends on the espeak NDK
  build (`-PwithEspeak=true`) and onnxruntime-android.
- MeloTTS needs its multi-input frontend (tone/language ids + BERT) finished before it can be run
  the same way; CosyVoice2 is the deferred Tier-C LLM.
