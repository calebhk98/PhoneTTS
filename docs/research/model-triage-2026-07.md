# "No engine yet" model triage (2026-07)

Context: 125 models were downloaded to a test device; ~80 resolved to **"no engine yet"** and 7
Piper voices **failed at synthesis**. This triage classifies every unclaimed model so we know
which are quick code wins, which are just the wrong file format (a download-time warning, not an
engine), and which are genuinely new architectures worth a new engine. Produced by a fan-out of
subagents inspecting each Hugging Face repo's actual file listing + model card.

Buckets:
- **A — should-resolve:** genuine ONNX with the right companion files; an existing engine *ought*
  to claim it and fails only on a small, fixable gap. **Highest value.**
- **B — wrong-format:** right model family, but MLX / CoreML / GGUF / raw-PyTorch / NPU-vendor
  container the ONNX runtime can't load. No engine fix helps — needs a conversion, or (better) a
  **pre-download "not supported by this app" badge** (extends issue #89).
- **C — new-engine:** a genuinely different architecture that could become a PhoneTTS engine.
- **D — impractical/junk:** too heavy for a 4 GB no-NPU phone, or not a usable model.

The 7 **failed** Piper voices (VCTK, LibriTTS, LibriTTS-R, L2Arctic, Arctic, Aru, Semaine) are a
separate, already-fixed bug: they are multi-speaker VITS graphs needing a `sid` input — see the
multi-speaker Piper change on branch `claude/tts-models-benchmark-q9bnln`.

---

## Bucket A — quick code wins

| Model(s) | Engine | Gap | Fix |
|---|---|---|---|
| `onnx-community/KittenTTS-{Nano,Mini,Micro}-v0.8-ONNX` | Kitten | Marker `kitten_tts` lives in `kitten_config.json`; `inspect()` only reads `config.json` (which here says `style_text_to_speech_2`). ONNX graph + `voices.npz` + I/O names (`input_ids`/`style`/`speed`) all already match. | `inspect()`/`forcedMatch()` also accept `kitten_config.json` as the marker/config file. **Caveat:** Mini/Micro are int8-quantized — sanity-check they load on ORT-mobile (Nano is fp32). |
| `speaches-ai/piper-*` (6), `ufozone/piper-de_DE-jarvis-{high,medium}`, `Lucasllfs/Razo-piper-voice` | Piper | Ship a **valid** Piper config (has `audio.sample_rate` + `phoneme_id_map`) but named `config.json`, not `<voice>.onnx.json`, so the sidecar pairing fails. | Let Piper `inspect()` fall back to `config.json` as the sidecar **when a bundle has exactly one `.onnx`** (content check still gates fail-closed). Unlocks ~9 voices. |
| `crazygiscool/ahsoka-piper-voice` | Piper | Already ships correct `ahsoka-final.onnx` + `ahsoka-final.onnx.json`. Likely the **downloader/file-selector** grabs a decoy (`config.json`, `ahsoka-final.json`, `.ckpt`) instead of the matched pair. | Investigate the download/bundle-assembly file selection, not `inspect()`. |
| `Xenova/mms-tts-ara` (and the `Xenova/mms-tts-*` family) | (new small VITS/MMS) | Genuine VITS ONNX (`onnx/model_quantized.onnx`, 38 MB) + `tokenizer.json`/`vocab.json`. Same runtime call as Piper; only the frontend differs (raw grapheme vocab, not espeak). | Small "MMS/VITS" engine or a Piper-adjacent fingerprint + a grapheme frontend. Claims every `Xenova/mms-tts-<lang>` repo for near-zero marginal cost. Borders C. |

**Caveat on `ayousanz/piper-plus-*`:** these ship a valid Piper-shaped `config.json` (same rename
gap) **but** are MB-iSTFT-VITS2 "piper-plus" graphs with extra `language_id`/`prosody` inputs. The
rename would let `inspect()` *identify* them, but synthesis likely needs engine-level changes — do
**not** fold them into the plain `config.json` fallback without handling the extra inputs.

---

## Bucket B — wrong format (download-time warning, not an engine)

All of these are real Kokoro / Kitten / MeloTTS / etc. models the app **already supports in ONNX**,
re-published in a container the ONNX runtime can't load. The fix is a pre-download badge (#89), not
code per model.

- **MLX (Apple `safetensors`):** all `mlx-community/kitten-tts-*` (~18), `mlx-community/Kokoro-82M-bf16`,
  `mlx-community/MeloTTS-English-{MLX,v3-MLX}`, `mlx-community/Qwen3-TTS-12Hz-0.6B-Base-4bit`.
- **CoreML (`.mlpackage`/`.mlmodelc`):** `kensora/kokoro-coreml`, `aufklarer/Kokoro-82M-CoreML`,
  `aufklarer/Supertonic-3-CoreML`.
- **GGUF (voice/weight packs for a ggml runtime, not ONNX):** `cstr/piper-*-GGUF` (2),
  `cstr/kokoro-voices-GGUF`.
- **ExecuTorch `.pte`:** `software-mansion/react-native-executorch-kokoro` (its `voices/*.bin` are
  already the exact `[510,256]` fp32 layout our Kokoro engine wants — reusable if paired with a real ONNX graph).
- **NPU-vendor binaries:** `mobilint/MeloTTS-English-v3` (`.mxq`), `runanywhere/melotts_en_HNPU`
  (Qualcomm QNN; ironically ships the `tokens.txt`/`lexicon.txt` we want), `AXERA-TECH/MeloTTS`
  (ONNX *encoder* only, Axera `.axmodel` decoder).
- **Raw PyTorch only:** `hexgrad/Kokoro-82M` + `Nextcloud-AI/Kokoro-82M` (canonical upstream, `.pth`),
  `myshell-ai/MeloTTS-English{,-v2}`, `dron3flyv3r/MeloTTS-GLaDOS`, `facebook/mms-tts-eng`
  (ONNX exists at `Xenova/mms-tts-eng`), `microsoft/speecht5_tts` (ONNX at `Xenova/speecht5_tts`),
  `RiricOFF/piper-checkpoints-*` (mid-training `.ckpt`), `ayousanz/piper-plus-base` (fine-tune base `.ckpt`),
  `neuphonic/neutts-nano` (fp32 safetensors; GGUF siblings exist).

---

## Bucket C — genuinely new engines (ranked by feasibility)

| Rank | Model | Arch | Runtime path | Effort | Notes |
|---|---|---|---|---|---|
| 1 | `SWivid/F5-TTS` | Flow-matching DiT + vocoder, ~336M | **Existing ONNX port** `DakeQQ/F5-TTS-ONNX` targets this exact checkpoint | S–M | Non-AR; CPU-friendly. Strongest candidate. |
| 2 | `microsoft/VibeVoice-Realtime-0.5B` | Next-token-diffusion streaming TTS | **Existing ggml port** `vibevoice.cpp` + GGUFs → fits our `NativeTtsRuntime` (CosyVoice pattern) | M | Novel, streaming, quantized GGUF for this checkpoint. |
| 3 | `Supertone/supertonic-3` | 3-stage flow-matching (enc → dur → vector-estimator → vocoder) | **Already ships ONNX** (4 graphs), publisher targets on-device CPU | M | Needs 4-graph orchestration + CFG denoise loop + unicode-indexer frontend. 31 langs. |
| 4 | `neuphonic/neutts-air-q{4,8}-gguf` | Qwen2-0.5B LM + NeuCodec | **GGUF** → ggml bridge like CosyVoice3 | M | Ship Q4 (527 MB) default, Q8 optional. Verify NeuCodec decode path. |
| 5 | `YatharthS/LuxTTS` | ZipVoice-style flow-matching + Vocos | ONNX text-enc + fm-decoder (int8 ~130 MB); **Vocos ships as `.bin`** (needs ONNX export) | M | Voice-cloning UX (needs reference audio). |
| 6 | `ResembleAI/chatterbox` | Llama-0.5B AR + S3Gen flow decoder | **ONNX port** `onnx-community/chatterbox-ONNX` (~1.5 GB q4) | M–L | Heavier; multi-part pipeline. |
| 7 | `OpenMOSS-Team/MOSS-TTS-Nano-100M` | AR LLM + causal audio codec | ONNX fork `Jonas0066/MOSS-TTS-Nano-100M-ONNX` exists | M | Small footprint, 20 langs, claims real-time CPU. |
| 8 | `SWivid/E2-TTS` | Flow-matching (F5 sibling) | Rides F5's exporter (unconfirmed for this ckpt) | M | Do after F5. |
| 9 | `projecte-aina/matxa-tts-cat-multiaccent` | Matcha-TTS + WaveNext | Ships ONNX (incl. e2e) **but** needs a Catalan espeak-ng fork | S–M | Single-language (Catalan) — narrow value. |
| 10 | `kyutai/tts-0.75b-en-public` | Hierarchical transformer over Mimi codec | Custom candle/ggml bridge (sibling precedent) | L | No ready port for this checkpoint. |

---

## Bucket D — impractical for a 4 GB no-NPU phone / junk

- **Too heavy:** `openbmb/VoxCPM2` (~5 GB), `openbmb/VoxCPM1.5` (~2 GB), `openbmb/VoxCPM-0.5B`
  (~1.6 GB), `nineninesix/gepard-1.0` (1.1 GB, exotic hybrid + NanoCodec), all 5 `Qwen/Qwen3-TTS-*`
  (0.6–1.7B, **no ONNX/GGUF port anywhere**), `coqui/XTTS-v2` (no working on-device port),
  `k2-fsa/OmniVoice` (no ONNX yet), `suno/bark-small` (slow multi-stage AR).
- **Not a usable standalone model:** `speechbrain/tts-hifigan-libritts-22050Hz` (a vocoder only),
  the `models` entry (0 B, empty dir).

---

## Recommended follow-up issues

1. **Kitten: recognize `kitten_config.json`** → unlocks 3 ONNX Kitten repos (A).
2. **Piper: `config.json` sidecar fallback** for single-`.onnx` bundles → unlocks ~9 Piper voices (A).
3. **Investigate `ahsoka-piper-voice` download file-selection** (decoy `.json`/`.ckpt`) (A).
4. **Pre-download "not supported by this app" badge** for MLX/CoreML/GGUF/`.pth`/NPU repos (extends #89) — covers the entire bucket B (~40 repos) so users see it *before* a wasted download.
5. **New engine: F5-TTS** (via `DakeQQ/F5-TTS-ONNX`) — highest-value new architecture (C).
6. **New engine: VibeVoice-Realtime** (via `vibevoice.cpp`, reuses `NativeTtsRuntime`) (C).
7. **New MMS/VITS engine** claiming the `Xenova/mms-tts-*` family (A/C-small) — one engine, many languages.
