# Models — what to download and how

PhoneTTS ships **no model weights** in the app. You add voices yourself, and the app figures out
which engine runs them automatically. This guide says exactly **which Hugging Face models work,
what files to grab, and what to expect** — including the gotchas we hit while verifying each one.

Every model listed here was run end-to-end and produces real audio (see
[`MODEL-VERIFICATION.md`](MODEL-VERIFICATION.md)); a random, un-curated model of any supported
family also auto-loads and speaks with **zero code changes** (proven in `RealModelAutoLoadTest`).

## Three ways to add a model

1. **Recommended (one-tap)** — open **Browse models**; the top section lists curated, known-good
   models. Tap one and it downloads the exact files and configures itself. Easiest path.
2. **Browse Hugging Face** — search in-app and tap Download on any result. If a repo offers several
   weight precisions, you're asked which to fetch.
3. **Sideload a folder** — already downloaded a model on a computer? Copy the folder to the phone
   and pick it with **Sideload folder**. Same auto-detection as a download.

All three funnel through the same detect → resolve → register pipeline. A model the app can't
identify with confidence isn't guessed at — it asks you which engine to use, and remembers.

## What works, per engine

> **Auto-detection needs the companion files.** Each engine recognizes a model by its files (see
> "needs" below). A bare `.onnx` with nothing else usually can't be identified — that's deliberate
> (it fails closed and asks you), because the sample rate / vocab / voices live in those side files.

### Piper — the easy, broad choice
- **Repo:** [`rhasspy/piper-voices`](https://huggingface.co/rhasspy/piper-voices) — hundreds of
  voices across 30+ languages.
- **Needs:** the voice's `<name>.onnx` **and** its `<name>.onnx.json` sidecar (same base name).
- **Size:** ~20–70 MB per voice. **Any** voice in that repo works.
- **Notes:** fast and clear; great default. Speed control is the model's own `length_scale`.

### KittenTTS — smallest
- **Repo:** [`KittenML/kitten-tts-nano-0.1`](https://huggingface.co/KittenML/kitten-tts-nano-0.1)
  (also `-nano-0.2`) — English, 8 expressive voices.
- **Needs:** the `<model>.onnx`, a `config.json` (its contents mention `kitten_tts`), and
  `voices.npz`.
- **Size:** ~24 MB. Smallest working model.

### Kokoro-82M — highest quality, multi-voice
- **Repo:** [`onnx-community/Kokoro-82M-v1.0-ONNX`](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX)
  — 54 voices, several languages.
- **Needs:** `onnx/model.onnx`, `config.json`, `tokenizer.json`, and one or more `voices/<name>.bin`.
- **Size:** ~325 MB (fp32). **Use the fp32 `onnx/model.onnx`** — the `q8f16` quantized export
  **crashes** ONNX Runtime, so the one-tap download deliberately pulls fp32.

### MeloTTS — multi-speaker, no espeak needed
- **Repo:** [`MiaoMint/MeloTTS-ONNX`](https://huggingface.co/MiaoMint/MeloTTS-ONNX) — per-language
  exports (`en_v2`, `en_newest`, `es`, `fr`, `ja`, `ko`, `zh`).
- **Needs:** from a language folder: `model.onnx`, `tokens.txt`, `lexicon.txt`, `metadata.json`.
- **Size:** ~175 MB. Ships its own pronunciation dictionary, so it doesn't need the espeak add-on.
- **Notes:** **use this "sherpa-onnx"-style export, not the raw `myshell-ai/MeloTTS` repo** — that
  one has no ONNX + its symbol table is version-specific and produced silence in testing.

### CosyVoice2-0.5B — not usable on-device yet
The model is real and excellent (proven in PyTorch), but running it on a phone needs a separate
GGUF/llama.cpp runtime that isn't finished. See [`COSYVOICE2.md`](COSYVOICE2.md). It is **not** in
the one-tap list and won't auto-load yet.

## The one thing to install for Piper/Kitten/Kokoro: espeak

Those three turn text into sound using **espeak-ng** phonemes. The app works without it (it falls
back to a no-op and the audio will be wrong), so for real speech build with the espeak add-on —
run `scripts/fetch-espeak-ng.sh`, then build with `-PwithEspeak=true` (see
[`BUILDING.md`](BUILDING.md)). **MeloTTS doesn't need it** (it carries its own dictionary).

## FAQ / troubleshooting

**I downloaded a model but there's no sound, or it's garbled.**
Most likely the espeak add-on isn't built (Piper/Kitten/Kokoro need it). Or you grabbed the wrong
files — check the "Needs" list above; a lone `.onnx` isn't enough.

**Which Kokoro file do I pick?** The fp32 `onnx/model.onnx`. The `q8f16` one segfaults ONNX Runtime.

**My MeloTTS download doesn't work.** Use `MiaoMint/MeloTTS-ONNX` (the sherpa export with
`tokens.txt`/`lexicon.txt`), not the original `myshell-ai/MeloTTS`.

**Can I use any Piper voice / a language other than English?** Yes — any voice from
`rhasspy/piper-voices`, any language it ships. Kokoro/Kitten/Melo are English-first today.

**I downloaded a model that isn't in your list — will it work?** If it's the same family and ships
the companion files above, yes — auto-detection handles it (we tested random, un-curated models of
each family). If the app can't identify it, it asks you which engine to use and remembers your
choice.

**The app asked me to pick an engine — why?** It couldn't identify the model with confidence
(missing/foreign companion files). Pick the matching engine from the "per engine" section; it's
saved so you're not asked again.

**Where do models live / how do I free space?** In app-private storage. Use **Manage models** to
see each one's size and delete it.
