# Model verification - do the models actually produce audio?

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
| MeloTTS (MiaoMint en_v2) | speaker 0 | - | **10.54 s** | **908 KB** | 0.293 | none | ✅ PASS (now one-tap) |
| **CosyVoice3-0.5B** (native ggml) | fleurs-en | 147 | **5.88 s** | **276 KB** | 0.829 | none | ✅ PASS (on-device path) |

Every passing model clears the > 1 KB / > 2 s bar with large margins, confirming the validated
`input`/`input_lengths`/`scales`→`output` (Piper) and `input_ids`/`style`/`speed`→`waveform`
(Kokoro, Kitten) contracts produce real speech.

## Findings that affect the app

1. **Kokoro's q8f16 quantized export segfaults desktop ONNX Runtime** (mixed int8/fp16 ops). The
   **fp32** `onnx/model.onnx` runs cleanly. The one-tap download therefore pulls a known-good
   variant, and the in-app quantized-variant picker should treat q8f16 as risky. (onnxruntime-android
   may differ, but we default to the safe file.)
2. **Kokoro voice format - FIXED.** The real repo ships voices as `voices/*.bin` (`[510, 256]`
   float32, indexed by token count), not a `voices.json` table, and its `config.json` marks the
   family via `"model_type": "style_text_to_speech_2"` (no `family` field). `KokoroEngine` now
   reads `voices/<name>.bin` and selects the length-indexed style row (matching this test exactly),
   and detection accepts the real StyleTTS2 marker - so **Kokoro is now in the one-tap
   `BuiltInCatalog`** (fp32, 5 English voices).
3. **Phonemization** here uses system `espeak-ng` (same engine the app's `EspeakPhonemizer` wraps via
   NDK), so these token sequences match what the app produces once the espeak native build is on.

## MeloTTS - retargeted to the MiaoMint export, now audio-verified

The original ONNX export on hand (`seasonstudio/melotts_zh_mix_en_onnx`) surfaced a real,
export-specific SSOT problem: its phoneme embedding has **112 rows** while the engine's old
hardcoded `MeloSymbolTable` was the **219-entry** upstream table, so feeding its ids into that
export threw `Gather ... idx=117 out of range [-112,111]`; even clamped into range, it produced
**pure silence (peak 0.0)**, since clamped ids + an IPA approximation that doesn't match that
export's vocabulary + zeroed BERT gave the duration/flow stack nothing meaningful to voice. That
export is now **abandoned**.

MeloTTS is retargeted to `MiaoMint/MeloTTS-ONNX`'s `onnx_exports/en_v2` - a sherpa-onnx-style
export that ships its own symbol table (`tokens.txt`) and G2P dictionary (`lexicon.txt`) instead of
requiring one hardcoded in the app (the SSOT fix). `scripts/model-verify/run_melo2.py` runs this
export end-to-end with the real lexicon-based G2P and PROVES it produces real, non-silent speech:
**10.54s duration, 908 KB, peak 0.293, no NaNs - PASS.** `MeloEngine`/`MeloFrontend` were rewritten
to this exact 7-input contract (`x`/`x_lengths`/`tones`/`sid`/`noise_scale`/`length_scale`/
`noise_scale_w` - no BERT session, no language input, no `sdp_ratio`), and MeloTTS now has a
one-tap `BuiltInCatalog` entry (`MELO_EN`, repo `MiaoMint/MeloTTS-ONNX`).

## CosyVoice - two independent proofs (PyTorch, then native ggml)

CosyVoice is the Tier-C autoregressive LLM model, and it does **not** fit the single-ONNX contract
the other four validate through - its LLM stage samples token-by-token and has no fixed ONNX graph.
It was proven in **two** stages instead:

1. **PyTorch reference** (`scripts/model-verify/run_cosy.py`, docs/COSYVOICE2.md): the upstream
   `FunAudioLLM/CosyVoice` stack running **CosyVoice2-0.5B** produced clean audio in **6.24 s**. This
   proved the *model* is real and worth shipping - but PyTorch is not an on-device runtime.
2. **Native ggml** (`scripts/model-verify/run_cosy_native.sh` + `cv3_native_driver.cpp`): the ACTUAL
   on-device code path. CrispStrobe/CrispASR's `cosyvoice3_tts` is a self-contained C++/ggml
   implementation of the whole pipeline - Qwen2-0.5B LLM + DiT-CFM flow + HiFi-GAN/iSTFT HiFT + BPE
   tokenizer - with a clean C ABI (`cosyvoice3_tts_synth(text, voice) → 24 kHz PCM`). Run against the
   Apache-2.0 GGUF stack (`cstr/cosyvoice3-0.5b-2512-GGUF`, minimal 745 MB combo = Q4_K LLM + Q8_0
   flow + F16 HiFT + voices), it synthesized **"Hello, this is a test of on device text to speech."**
   into **147 speech tokens → 5.88 s of 24 kHz audio, peak 0.829, rms 0.111, no NaNs - PASS**, and a
   second sentence ("the quick brown fox…") into 87 tokens → 3.48 s, peak 0.891. **No PyTorch, no
   ONNX** - this is the same `cosyvoice3_tts.cpp` the app vendors for its `-PwithCosyVoice` native
   build. The on-device engine is therefore **CosyVoice3-0.5B** (Apache-2.0, GGUF-native), the
   deployable sibling of the CosyVoice2 model proven in PyTorch.

Greedy decode (`temperature 0`) falls into CV3's documented "silent_tokens" loop; the RAS sampler
needs `temperature > 0` (0.8, seed 42 here) to produce well-formed speech - the driver and the app
runtime both set this.

## What "verified" means and doesn't

- It verifies the **models + tensor contracts** (and, for CosyVoice3, the **native ggml runtime**)
  produce valid audio off-device. It does **not** run the Android app itself (no emulator here) -
  on-device playback still depends on the espeak NDK build (`-PwithEspeak=true`), onnxruntime-android,
  and (for CosyVoice3) the `-PwithCosyVoice=true` ggml native build.
