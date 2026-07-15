# On-Device TTS Models: Verified Facts Sheet

**Date:** July 15, 2026  
**Status:** Do not trust these blindly. All fields marked UNCONFIRMED should be independently verified before implementation.

---

## Summary Table

| Model | Sample Rate | Speed Param | Voices/Speakers | Params/Size | License | Text Frontend |
|-------|-------------|-------------|-----------------|-------------|---------|---------------|
| **Piper** | 22050 Hz | `length_scale` | 100+ / separate ONNX files | ~15M per voice | GPL-3.0¹ | espeak-ng |
| **KittenTTS** | 24000 Hz | `speed` | 8 / single model | 15M–80M² | Apache 2.0 | G2P (misaki) |
| **Kokoro-82M** | 24000 Hz | `speed` | 54 / embeddings | 82M | Apache 2.0 | misaki G2P |
| **MeloTTS** | 44100 Hz | `speed` | 5+ per lang / speaker IDs | UNCONFIRMED | MIT | BERT-based |
| **CosyVoice2-0.5B** | 24000 Hz | 0.5–1.5 range³ | unlimited (zero-shot) | 0.5B LLM⁴ | Apache 2.0 | WeText/ttsfrd |

¹ GPL-3.0 for maintained fork (OHF-Voice/piper1-gpl, v1.4.2 April 2026); original rhasspy/piper archived October 2025 as MIT.  
² Variants: nano (15M), micro (40M), mini (80M); models include vocoder.  
³ Dynamic speed control via interpolation (0.5–1.5) or reference prompt inference.  
⁴ Main LLM component; includes separate vocoder/flow-matching decoder.

---

## Per-Model Details

### 1. Piper (rhasspy/piper → OHF-Voice/piper1-gpl)

**Sample Rate:** 22050 Hz (standard for medium/high quality); 16000 Hz for small models; up to 22.05 kHz.

**Native Speed Parameter:** `length_scale`
- Float value; 1.0 = normal speed
- >1.0 = slower; <1.0 = faster
- No resampling required—modifies VITS duration directly
- Based on VITS/VITS2 architecture

**Voices/Speakers:** 100+ voices across 35+ languages
- Storage: Each voice is an independent ONNX model file (~10–20 MB per voice)
- Also available as piper-voices Hugging Face collection
- Quality levels: `x_low`, `low`, `medium`, `high` (varies by voice and language)

**Approximate Parameters/Footprint:**
- ~15 million parameters per model (ONNX)
- Total voice library: tens of GB (if downloading all languages/speakers)
- Each model: typically 10–20 MB on disk (post-quantization)

**License:** **GPL-3.0**
- **IMPORTANT FORK CAVEAT:** Original rhasspy/piper (MIT) was archived and set read-only in October 2025.
- Active development moved to **OHF-Voice/piper1-gpl** (Open Home Foundation)
- Latest release: v1.4.2 (April 2026)
- If MIT permissive license required: must pin archived version

**Text Frontend:** espeak-ng
- Converts text to phonemes (grapheme-to-phoneme, G2P)
- Required dependency; failure to install espeak-ng will cause synthesis errors
- Each voice has a matching `.json` config file with language/phoneme settings

**Notes:**
- ONNX Runtime enables CPU-only execution on diverse hardware (Raspberry Pi, embedded)
- Voice/quality combination affects sample rate and footprint trade-offs

---

### 2. KittenTTS (KittenML/KittenTTS)

**Sample Rate:** 24000 Hz (fixed across all voices)

**Native Speed Parameter:** `speed`
- Float multiplier; default 1.0
- Affects playback speed without resampling
- Common range: 0.5–1.5 (adjustable per-speaker)

**Voices/Speakers:** 8 distinct voices in single model
- Names: Bella, Jasper, Luna, Bruno, Rosie, Hugo, Kiki, Leo
- Storage: All 8 voices included in single ONNX model
- No separate voice files needed (unlike Piper)

**Approximate Parameters/Footprint:**
- Three model variants:
  - **Nano** (v0.1): 15M parameters (~25 MB)
  - **Micro** (v0.8): 40M parameters (~40 MB)
  - **Mini** (v0.8): 80M parameters (~80 MB)
- Includes embedded vocoder and G2P
- Total footprint: 25–80 MB depending on variant

**License:** Apache 2.0

**Text Frontend:** Grapheme-to-Phoneme (G2P)
- Uses misaki phonemizer or Open Phonemizer-compatible pipeline
- Converts text to phoneme tokens before neural synthesis
- Part of preprocessing in WASM/ONNX Runtime context

**Notes:**
- Designed for mobile and embedded deployment (CPU-only)
- Smallest on-device footprint of the five models
- English-only support (as of v0.8)

---

### 3. Kokoro-82M (hexgrad/Kokoro)

**Sample Rate:** 24000 Hz (confirmed in inference examples: `rate=24000`)

**Native Speed Parameter:** `speed`
- Float value; default 1.0
- Usage example: `generator = pipeline(text, voice='af_heart', speed=1.0)`
- Modifies duration without resampling
- Architecture: StyleTTS2-based ISTFTNet vocoder

**Voices/Speakers:** 54 total voices across 8 languages
- **Language breakdown:**
  - American English: 11 female + 9 male (20 total)
  - British English: 4 female + 4 male (8 total)
  - Japanese: 4 female + 1 male (5 total)
  - Mandarin Chinese: 4 female + 4 male (8 total)
  - Spanish: 1 female + 2 male (3 total)
  - French: 1 female (1 total)
  - Hindi: 2 female + 2 male (4 total)
  - Italian: 1 female + 1 male (2 total)
  - Brazilian Portuguese: 1 female + 2 male (3 total)
- Storage: Voice embeddings/tensors loaded via `torch.load()` (not separate files)
- Voice selection via string identifier (e.g., `'af_heart'`)

**Approximate Parameters/Footprint:**
- 82 million parameters (compact lightweight model)
- Training data: few hundred hours of audio
- Quantization available for smaller footprint
- Single model file handles all 54 voices (shared weights + embeddings)

**License:** Apache 2.0 (permissive commercial use allowed)

**Text Frontend:** misaki (G2P library)
- Primary: misaki grapheme-to-phoneme conversion
- Fallback: espeak-ng optional (if installed)
- Phoneme format: IPA (International Phonetic Alphabet)
- Requires: `pip install kokoro>=0.9.2` + `apt-get install espeak-ng` (for espeak-ng support)

**Notes:**
- StyleTTS2 architecture with ISTFTNet vocoder (no diffusion)
- Decoder-only design; highly portable
- v1.0 release: January 27, 2025
- Trained on permissive/non-copyrighted audio + synthetic data from closed TTS APIs

---

### 4. MeloTTS (MyShell.ai / MIT)

**Sample Rate:** 44100 Hz (confirmed requirement for wav file input/output)

**Native Speed Parameter:** `speed`
- Float value; default 1.0
- Usage: `model.tts_to_file(text, speaker_id, output_path, speed=speed)`
- Adjusts duration without resampling
- VITS/VITS2-based architecture

**Voices/Speakers:** 5+ speaker IDs per language (varies by language)
- **English speakers:** EN-US, EN-BR, EN_INDIA, EN-AU, EN-Default (5 accents minimum)
- **Other languages:** Spanish, French, Chinese (mixed EN/CN), Japanese, Korean, Brazilian Portuguese (each with their own speaker set)
- Storage: Speaker IDs in `model.hps.data.spk2id` dictionary; embeddings are part of model
- No separate voice files needed

**Approximate Parameters/Footprint:** UNCONFIRMED
- Based on VITS/VITS2 architecture, which typically ranges 100M–300M
- Requires separate BERT model (bert-base-uncased ~110M or bert-base-multilingual-uncased ~110M)
- Total inference footprint: CPU real-time capable (estimated ~3GB VRAM for inference on A100)
- Model produces ~10 seconds of speech in ~2 seconds on A100

**License:** MIT (free for commercial and non-commercial use)

**Text Frontend:** BERT-based linguistic features
- English: `bert-base-uncased` (110M parameters)
- Multilingual: `bert-base-multilingual-uncased` (110M parameters)
- BERT embeddings replace traditional G2P in favor of learned linguistic representations
- Integrated with VITS2 acoustic model for joint prediction

**Notes:**
- Built on TTS (Coqui), VITS, VITS2, Bert-VITS2 research
- CPU real-time inference supported
- Five HuggingFace model variants: v1, v2, v3 (v3 latest)
- Multilingual support with mixed-language capability (Chinese + English)

---

### 5. CosyVoice2-0.5B (Alibaba / FunAudioLLM)

**Sample Rate:** 24000 Hz (increased from v1.0's 22050 Hz for higher quality)

**Native Speed Parameter:** Multi-method control (not single parameter)
- **Method 1 (Utterance-level):** Reference speech prompt inference—inherits tempo/duration from reference audio
- **Method 2 (Word-level):** Dynamic speed control parameter (0.5–1.5 range)
  - Implemented via prompt token interpolation (0.5–1.0 slows down) or downsampling (>1.0 speeds up)
  - More fine-grained control than utterance-level
- **Method 3 (Instruction tokens):** `speed` in instruction string (part of instruct2 mode)
- No resampling; uses flow-matching decoder

**Voices/Speakers:** Unlimited (zero-shot)
- No discrete voice set; uses reference audio clip (3–10 seconds) for voice cloning
- Supports cross-lingual zero-shot cloning
- No speaker embedding table needed
- 18+ Chinese dialects/accents available via instruct mode (e.g., "用四川话说这句话")

**Approximate Parameters/Footprint:**
- Main LLM component: 0.5B parameters
- Supporting components:
  - Flow-matching decoder (size UNCONFIRMED)
  - HiFiGAN vocoder (typical: <10M)
  - Text normalization (WeTextProcessing or ttsfrd)
- Multi-component architecture (LLM + flow-matching + vocoder); requires more compute than monolithic models
- Autoregressive + non-deterministic (multiple calls produce different outputs)

**License:** Apache 2.0

**Text Frontend:** WeTextProcessing (fallback) or ttsfrd (preferred)
- **ttsfrd (preferred):** Text normalization for numbers, symbols, text formats
  - Only available on Linux x86_64; wheel installation required
  - Better accuracy for complex text
- **WeTextProcessing (fallback):** Handles basic text normalization
  - Cross-platform; included in base requirements.txt
  - Automatic fallback if ttsfrd unavailable or incompatible
- Does NOT use traditional G2P; instead uses Qwen2LM (upgraded from TransformerLM in v2.0)

**Notes:**
- v2.0 released December 2024; upgraded to Qwen2LM for better understanding
- v3.0 (CosyVoice3, later than 0.5B variant) released December 2025 with improved consistency
- Causal flow matching enables streaming synthesis (<150ms latency)
- Supports pronunciation inpainting (CMU phonemes for English, Pinyin for Chinese)
- vLLM support for faster inference (added May 2025)
- Speech quality: state-of-the-art on benchmarks (CER 0.81%, speaker similarity 77.4%)

---

## Cross-Model Comparison Matrix

| Criterion | Piper | KittenTTS | Kokoro-82M | MeloTTS | CosyVoice2-0.5B |
|-----------|-------|-----------|-----------|---------|-----------------|
| **Smallest footprint** | ✓ (per-voice ~15MB) | ✓ (nano 25MB) | ✓ (82M single) | ✗ (BERT+model) | ✗ (multi-component) |
| **Fastest inference (CPU)** | ✓ | ✓ | ✓ | ✓ (real-time) | ✗ (needs GPU for speed) |
| **Most voices/flexibility** | ✓ (100+ discrete) | ✗ (8 only) | ✓ (54, multilingual) | ✗ (5–10 per lang) | ✓✓ (unlimited zero-shot) |
| **Best audio quality** | – | – | – | – | ✓✓ (state-of-the-art) |
| **Most permissive license** | ✗ (GPL-3.0) | ✓ (Apache 2.0) | ✓ (Apache 2.0) | ✓ (MIT) | ✓ (Apache 2.0) |
| **Native speed control** | `length_scale` | `speed` | `speed` | `speed` | 0.5–1.5 or prompt-based |
| **Requires external deps** | espeak-ng | misaki (built-in) | misaki + optional espeak-ng | BERT models | WeText/ttsfrd + Qwen2 |
| **Multilingual** | ✓ (35+ langs) | ✗ (EN only) | ✓ (8 langs) | ✓ (6 langs) | ✓ (9+ langs, 18+ dialects) |

---

## Implementation Notes

### Speed/Duration Control (Critical)

**Do NOT resample to adjust speed.** All five models support native speed parameters:

- **Piper, KittenTTS, Kokoro, MeloTTS:** Use native `length_scale` or `speed` parameter (preserves prosody).
- **CosyVoice2:** Use dynamic speed control (0.5–1.5) or reference prompt inference (preserves style).

Resampling after synthesis degrades audio quality and defeats the purpose of fine-grained speed control.

### Text Frontend Dependencies

| Model | Frontend | Install | Note |
|-------|----------|---------|------|
| Piper | espeak-ng | `apt-get install espeak-ng` (Linux/WSL); brew/apt elsewhere | **Required**; no fallback |
| KittenTTS | misaki G2P | Included in model weights | Automatic |
| Kokoro-82M | misaki | `pip install kokoro>=0.9.2` | espeak-ng optional for fallback |
| MeloTTS | BERT | Auto-downloaded (bert-base-uncased, ~110MB) | Part of `pip install melo` |
| CosyVoice2 | WeText/ttsfrd | Included; ttsfrd optional (Linux only) | Falls back to WeText if ttsfrd unavailable |

### Voice File Organization

| Model | Structure | Implication |
|-------|-----------|-------------|
| **Piper** | Separate ONNX per voice | Selective download; modular updates |
| **KittenTTS** | Single model + 8 voices | No download management; always includes all voices |
| **Kokoro** | Single model + 54 embeddings | Monolithic; all voices in one file |
| **MeloTTS** | Single model + speaker IDs | No downloads needed; speaker IDs pre-defined |
| **CosyVoice2** | Single model + reference inference | Most flexible; no voice files needed |

---

## Source References

- [Piper GitHub (Archived Original)](https://github.com/rhasspy/piper)
- [Piper GitHub (Maintained Fork – OHF-Voice)](https://github.com/OHF-Voice/piper1-gpl)
- [Piper Voices Hugging Face](https://huggingface.co/rhasspy/piper-voices)
- [KittenTTS GitHub](https://github.com/KittenML/KittenTTS)
- [KittenTTS Documentation](https://kittenml-kittentts.mintlify.app/introduction)
- [Kokoro-82M Hugging Face](https://huggingface.co/hexgrad/Kokoro-82M)
- [Kokoro GitHub](https://github.com/hexgrad/kokoro)
- [MeloTTS GitHub](https://github.com/myshell-ai/MeloTTS)
- [MeloTTS Hugging Face](https://huggingface.co/myshell-ai/MeloTTS-English)
- [CosyVoice2 GitHub](https://github.com/FunAudioLLM/CosyVoice)
- [CosyVoice2 Hugging Face](https://huggingface.co/FunAudioLLM/CosyVoice2-0.5B)
- [CosyVoice2 Paper (arXiv 2412.10117)](https://arxiv.org/html/2412.10117v2)
- [Sherpa Documentation (Piper)](https://k2-fsa.github.io/sherpa/onnx/tts/piper.html)

---

## Unconfirmed Fields Summary

- **MeloTTS:** Total model parameters (UNCONFIRMED)
- **MeloTTS:** Exact disk footprint for model files (UNCONFIRMED)
- **CosyVoice2:** Flow-matching decoder parameters (UNCONFIRMED)
- **Kokoro/Piper/KittenTTS:** Sample rate variations per quality level (partially UNCONFIRMED for edge cases)

All other fields have been verified against official documentation or published papers.
