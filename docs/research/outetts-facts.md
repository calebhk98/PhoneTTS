# OuteTTS - researched facts (2026-07-24)

Every fact below was read directly from the Hugging Face Hub (via the `hf_fs` MCP tool), the
upstream GitHub repository (`edwko/OuteTTS`, fetched via `jsdelivr`'s GitHub mirror and
`raw.githubusercontent.com` - `api.github.com` itself is blocked for un-added repos in this
session), or the `ggml-org/llama.cpp` repository (same raw-content route), with the exact source
cited inline. Nothing here is inferred beyond what is explicitly stated in a model card, a source
file, or a live pull-request page. Anything that could not be directly verified is marked
**UNVERIFIED**; the engine and its `INTEGRATION.md` treat that as not-yet-proven, never assumed.

## 0. Headline: license is genuinely per-checkpoint, and the *cheapest* checkpoint is the
   *least* license-encumbered one - but its native runtime isn't merged upstream yet

This is the load-bearing fact for a paid app, so it goes first:

| Checkpoint | License (from HF `README.md` YAML front matter, read verbatim) | Decoder | Native llama.cpp support today |
|---|---|---|---|
| `OuteAI/OuteTTS-0.1-350M-GGUF` | **CC-BY-4.0** (permissive, commercial OK w/ attribution) | WavTokenizer | **UNVERIFIED** - merged `tts.cpp` only special-cases the 0.2/0.3 prompt format (see §5) |
| `OuteAI/OuteTTS-0.2-500M-GGUF` | **CC-BY-NC-4.0** (**non-commercial**) | WavTokenizer | **Yes** - this is the exact checkpoint the merged `tools/tts/README.md` documents |
| `OuteAI/OuteTTS-0.3-500M-GGUF` | **CC-BY-SA-4.0** (commercial OK, but share-alike/copyleft on the TTS-model components) | WavTokenizer | **Yes** - `tts.cpp`'s `OUTETTS_V0_3` branch |
| `OuteAI/OuteTTS-0.3-1B-GGUF` | **CC-BY-NC-SA-4.0** (**non-commercial**) | WavTokenizer | Yes (same `OUTETTS_V0_3` branch) |
| `OuteAI/OuteTTS-1.0-0.6B-GGUF` | **Apache-2.0** (fully permissive) | DAC | **No** - DAC decoder support is an **unmerged draft PR** (`ggml-org/llama.cpp#12794`), see §5 |
| `OuteAI/Llama-OuteTTS-1.0-1B-GGUF` | **CC-BY-NC-SA-4.0** (**non-commercial**) + base-model components under the **Llama 3.2 Community License** | DAC | Same unmerged-draft blocker as the 0.6B model |

**The tension for a paid app:** the only Apache-2.0 (no NC, no SA) checkpoint - `OuteTTS-1.0-0.6B` -
is also the smallest and newest, exactly what a 4 GB phone wants. But llama.cpp's native decoder
for its DAC-based audio codec is not merged; the PR that would add it is explicitly a work item
with "The decoder layers from DAC need to be implemented" still open (§5). Every checkpoint whose
native decoder (WavTokenizer) *is* merged today is either non-commercial (0.2, 0.3-1B) or
share-alike (0.3-500M). There is no checkpoint that is simultaneously (a) fully permissive and (b)
runnable through today's merged llama.cpp. See `engines/outetts/INTEGRATION.md` for how this
shapes the recommendation to whoever ships a bundle.

## 1. Repo ids (verified via `hf_fs search`/`ls` under `hf://models/OuteAI`)

All are public, non-gated `text-to-speech` repos:

| Repo id | Model release | GGUF files present |
|---|---|---|
| `OuteAI/OuteTTS-0.1-350M-GGUF` | v0.1, 350M, LLaMA-arch, English only | `OuteTTS-0.1-350M-{FP16,Q2_K..Q8_0}.gguf` |
| `OuteAI/OuteTTS-0.2-500M-GGUF` | v0.2, 500M, Qwen2.5-0.5B base | `OuteTTS-0.2-500M-{FP16,Q2_K..Q8_0}.gguf` |
| `OuteAI/OuteTTS-0.3-500M-GGUF` | v0.3, 500M, Qwen2.5-0.5B base (Apache-2.0) | same quant ladder |
| `OuteAI/OuteTTS-0.3-1B-GGUF` | v0.3, 1B, OLMo-1B base (Apache-2.0) | same quant ladder |
| `OuteAI/OuteTTS-1.0-0.6B-GGUF` | v1.0, 0.6B, **Qwen3-0.6B-Base** (Apache-2.0) | `OuteTTS-1.0-0.6B-{FP16,Q2_K,Q3_K_L,Q3_K_M,Q3_K_S,Q4_0,Q4_1,Q4_K_M,Q4_K_S,Q5_0,Q5_1,Q5_K_M,Q5_K_S,Q6_K,Q8_0}.gguf`; sizes span 301,275,264 B (Q2_K) to 1,210,254,976 B (FP16) - `Q4_K_M` is 401,741,952 B (~383 MiB) |
| `OuteAI/Llama-OuteTTS-1.0-1B-GGUF` | v1.0, 1B, **Llama-3.2-1B** base | `Llama-OuteTTS-1.0-1B-{FP16,Q2_K..Q8_0}.gguf`; sizes span 591,348,704 B (Q2_K) to 2,504,913,888 B (FP16) |

Source: `hf_fs ls hf://models/OuteAI/<repo> -R` for each row (file lists with byte sizes captured
directly from the Hub's LFS metadata, not estimated).

No separate "voices" or "decoder" GGUF ships **inside** any of these repos - the decoder is a
second, independently-hosted model (§3).

## 2. Text frontend: none of this engine's own - the LLM tokenizer + the native tool's own
   normalization does it all

OuteTTS has no separate G2P/phonemizer stage (unlike Piper/Kokoro/KittenTTS). Per the HF model
cards (`OuteAI/Llama-OuteTTS-1.0-1B-GGUF/README.md`, "What's New" §1) and confirmed by reading
`ggml-org/llama.cpp`'s merged `tools/tts/tts.cpp` (`process_text`, `prepare_guide_tokens`,
`raw.githubusercontent.com/ggml-org/llama.cpp/master/tools/tts/tts.cpp`, lines ~384-518):

- The **text is fed to the LLM's own BPE tokenizer** after a light, built-in normalization/word-
  alignment pass (punctuation → special tokens like `<|period|>`, word-boundary markers). This
  happens *inside* the model's prompt construction, not via an external phonemizer library.
- Automatic word alignment / numerical support is trained into the model (v1.0 README, "What's
  New" §1 and §4) - "no pre-processing required."
- `tts.cpp`'s own text chunking (10-30 word boundaries) and normalization mirror the Python
  `outetts` package's `prompt_processor.py` (comment at `tts.cpp:384` cites the exact upstream
  commit URL).

This matches the same "no separate Kotlin `TextFrontend`" shape CLAUDE.md documents for CosyVoice3
and `GgmlTtsEngine` (`engines/ggmltts/`): the native runtime's LLM tokenizer + guide-token logic
does the whole text→token job internally, so `OuteTtsEngine` declares no `TextFrontend` either.

## 3. Audio decoder / vocoder - **two different families across the version line, both external
   to the LLM GGUF**

| OuteTTS releases | Decoder | Decoder repo | Decoder license | Sample rate |
|---|---|---|---|---|
| v0.1, v0.2, v0.3 | **WavTokenizer** (75 tokens/sec, 320x downsample) | `novateur/WavTokenizer-large-speech-75token` (`.ckpt`, converted to GGUF via `tools/tts/convert_pt_to_hf.py` + `convert_hf_to_gguf.py`) | **MIT** (`hf://models/novateur/WavTokenizer-large-speech-75token/README.md`, `license: mit`) | **24,000 Hz** (75 tok/s × 320 downsample = 24,000) |
| v1.0 (both 0.6B and 1B) | **DAC** (Descript Audio Codec, IBM's speech-tuned checkpoint) | `ibm-research/DAC.speech.v1.0` (PyTorch `.pth` weights; `base_model: descript/dac_24khz`) | **CDLA-Permissive-2.0** (`hf://models/ibm-research/DAC.speech.v1.0/README.md`, `license: cdla-permissive-2.0`) | **24,000 Hz** (model card table: `weights_24khz_3.0kbps_v1.0.pth` / `weights_24khz_1.5kbps_v1.0.pth`, both `24kHz`) |

So **every** OuteTTS checkpoint's output sample rate is **24,000 Hz** - confirmed independently for
both decoder families, not assumed from one and copied to the other. `OuteAI/DAC-speech-v1.0-ONNX`
(an ONNX export of the DAC codec) exists on the Hub, but **no DAC-decoder GGUF exists yet** as of
this research (its own repo has an `onnx/` directory, no `.gguf`) - consistent with §5's finding
that llama.cpp's native DAC decoder is unmerged.

`novateur/WavTokenizer-large-speech-75token`'s only file is
`wavtokenizer_large_speech_320_v2.ckpt` (1,754,880,893 B) - the PyTorch checkpoint that
`tools/tts/README.md` walks through converting to `wavtokenizer-large-75-f16.gguf`.

## 4. Voices: "speaker profile" JSON, not baked discrete voice ids

OuteTTS has no fixed voice roster like Piper/Kokoro. Per every model card's Quick Start and
`docs/interface_usage.md` (`edwko/OuteTTS`, fetched via `jsdelivr`):

- A **speaker profile** is a small JSON file produced by `interface.create_speaker("audio.wav")`
  from ~10-15 s of reference audio (auto-transcribed by Whisper if no transcript is supplied),
  then `interface.save_speaker(speaker, "speaker.json")` / `load_speaker("speaker.json")`.
  Voice cloning, not a discrete speaker-id table.
- A small number of **default speakers** ship with the library so a build has *something* to
  demonstrate with zero user setup. Directly listing `edwko/OuteTTS`'s current `main` branch tree
  (via `data.jsdelivr.com`'s GitHub-mirror package API, since `api.github.com` itself 404s/denies
  for repos not attached to this session) shows **exactly one** shipped default-speaker file for
  the v1.0/"interface v3" line:
  `outetts/version/v3/default_speakers/json/en-female-1-neutral.json` - matching the
  `OuteTTS-1.0-0.6B-GGUF` README's own quick-start call,
  `interface.load_default_speaker("EN-FEMALE-1-NEUTRAL")`. **This is the only OuteTTS 1.0 default
  speaker confirmed to exist in the current upstream repo** - the older v0.2/v0.3 READMEs mention
  names like `male_1`/`en_male_1`, but no `default_speakers` directory for the library's internal
  "v1"/"v2" prompt-processor generations is present in the tree today, so those older names are
  marked **UNVERIFIED** (may have been removed/relocated upstream since those docs were written).
- The shipped `en-female-1-neutral.json` was fetched directly
  (`cdn.jsdelivr.net/gh/edwko/OuteTTS@main/outetts/version/v3/default_speakers/json/en-female-1-neutral.json`,
  25,295 bytes) and parsed. Its **exact top-level shape**:
  ```json
  {
    "text": "The cat watched from the windowsill, tail flicking with quiet curiosity ...",
    "words": [
      { "word": "The", "duration": 0.0, "c1": [...], "c2": [...], "features": {...} },
      "... one entry per word (26 words in this file) ..."
    ],
    "global_features": { "energy": 0.0, "spectral_centroid": 0.0, "pitch": 0.0 },
    "interface_version": 3
  }
  ```
  `c1`/`c2` are the two DAC codebooks' token-id sequences for that word (the "New tokens for c1
  (codebook 1) and c2 (codebook 2)" the v1.0 README's "What's New" describes). No raw audio is
  stored in the profile - it is already tokenized. `"interface_version": 3` is the **Python
  library's own internal prompt-processor generation number** (`outetts/version/v1`, `v2`, `v3` in
  the repo tree), **not** the "OuteTTS 1.0" model-release version - the two numbering schemes are
  easy to conflate and this document deliberately keeps them separate.

**Bundle design consequence** (see `engines/outetts/OuteTtsEngine.kt` KDoc): a voice is a
`<voiceId>.speaker.json` side file in the model bundle, not a row in a fixed table this engine
could hardcode - so `OuteTtsEngine.inspect()` discovers voices from whichever speaker-profile files
the bundle actually ships, exactly the same "engine reads the bundle, never assumes a roster"
posture as every other engine in this codebase.

## 5. Native inference path: llama.cpp `tools/tts` - **merged for WavTokenizer, still a draft PR
   for DAC/OuteTTS 1.0**

- **Merged, working today** (read directly from
  `raw.githubusercontent.com/ggml-org/llama.cpp/master/tools/tts/README.md` and `tts.cpp`):
  the `llama-tts` binary (`tools/tts/tts.cpp`, historically `examples/tts`) runs OuteTTS 0.2/0.3
  end-to-end - `build/bin/llama-tts -m <outetts-llm>.gguf -mv <wavtokenizer>.gguf -p "Hello world"`,
  or the one-flag quick start `--tts-oute-default` (auto-downloads both GGUFs). `tts.cpp` defines
  `enum outetts_version { OUTETTS_V0_2, OUTETTS_V0_3 }` (source line 25) and branches its prompt/
  text-separator format on whichever the loaded speaker JSON's `"version"` field says (`get_tts_
  version`, source lines 478-498; defaults to `OUTETTS_V0_2` if unspecified). **There is no
  `OUTETTS_V0_1` case** - v0.1's older prompt format is not special-cased by the merged code, so
  whether `OuteTTS-0.1-350M-GGUF` actually works against `llama-tts` today is **UNVERIFIED** (it
  is plausible v0.1 shares enough of v0.2's format to work, since both are pre-DAC WavTokenizer
  releases, but this was not proven by reading the source, and CLAUDE.md rule 1 forbids reporting
  an unread guess as fact).
- **Not merged - OuteTTS 1.0 / DAC**: `ggml-org/llama.cpp#12794`, "Support for OuteTTS 1.0" by
  the OuteTTS author (`edwko`). Read live from the PR page itself
  (`github.com/ggml-org/llama.cpp/pull/12794`, fetched 2026-07-24): the PR is marked **Draft**
  ("edwko marked this pull request as draft, April 7, 2025"). The PR's own description says **"The
  decoder layers from DAC need to be implemented"** and asks maintainers for help; the most recent
  substantive comment found (May 19, 2025) points at a *different* project
  (`foldl/chatllm.cpp`) as having a working DAC decoder implementation that could be ported. No
  merge event was found. This PR would add a second binary (`llama-tts-outetts-v1` per its
  description), a new JSON speaker format (matching §4's shape), `--tts-speaker-file`, and
  `--tts-no-text-chunking` flags - all **not available in any released llama.cpp today**.
- Consistent independent corroboration: the Python reference implementation's own DAC path
  (`outetts/dac/interface.py`, fetched from the same GitHub mirror) calls the **PyTorch** `dac`
  package directly (`import dac`, `dac.DAC.load(...)`), not a ggml/GGUF decoder - there is no
  native (non-Python) DAC decode path anywhere in the ecosystem yet, upstream OuteTTS included.

**Conclusion for this engine's runtime choice:** exactly the same shape as `NativeTtsRuntime` /
`GgmlTtsEngine` already establish in this codebase - `OuteTtsEngine` is a thin Kotlin delegate over
a **native, full text→audio pipeline** runtime (one call in, finished PCM out; no Kotlin
`TextFrontend`). Unlike `GgmlTtsEngine`'s CrispASR backend (already built, just not NDK-linked into
this APK), an OuteTTS-1.0/DAC-capable native binary **does not exist upstream yet** - so this is a
harder-than-ggmltts "native half pending" story, spelled out plainly in
`engines/outetts/INTEGRATION.md`.

## 6. Languages (from each repo's own README YAML front matter, `language:` list)

- `OuteTTS-1.0-0.6B-GGUF` (14 tags): `en, zh, nl, fr, ka, de, hu, it, ja, ko, lv, pl, ru, es`.
- `Llama-OuteTTS-1.0-1B-GGUF` (23 tags): `en, ar, zh, nl, fr, de, it, ja, ko, lt, ru, es, pt, be,
  bn, ka, hu, lv, fa, pl, sw, ta, uk`.
- Both READMEs additionally state the model "can generate speech in untrained languages with
  varying success" (best-effort, not a guarantee) - not surfaced as a supported-language claim
  here.

## 7. What this research recommends to whoever ships a bundle (non-binding - the engine itself
   hardcodes none of this, see `OuteTtsEngine.kt` KDoc)

For a **paid** app, `OuteTTS-1.0-0.6B-GGUF` (Apache-2.0, smallest, best per-checkpoint license) is
the right *target*, but shipping it today means shipping ahead of upstream llama.cpp DAC support -
exactly the `-PwithOuteTts` "native half pending" situation `engines/outetts/INTEGRATION.md`
documents, mirroring `engines/ggmltts/INTEGRATION.md`'s own honesty pattern one level further out
(there, the native *library* exists and only NDK-linking is unproven; here, the native *decoder
code itself* is an unmerged draft). `OuteTTS-0.3-500M-GGUF` (CC-BY-SA-4.0, WavTokenizer, natively
supported today) is the fallback that actually runs against merged llama.cpp right now, at the cost
of a share-alike license the app's counsel should review before bundling for paying users.
`OuteTTS-0.2-500M-GGUF`, despite being the checkpoint the upstream README itself walks through, is
CC-BY-NC-4.0 and therefore off the table for a commercial app outright.

## Sources

- `hf://models/OuteAI/*` model cards and file listings (`hf_fs cat`/`ls`/`search`), fetched directly.
- `hf://models/novateur/WavTokenizer-large-speech-75token/README.md`
- `hf://models/ibm-research/DAC.speech.v1.0/README.md`
- `hf://models/OuteAI/DAC-speech-v1.0-ONNX` (file listing only, confirms no GGUF present)
- `https://raw.githubusercontent.com/ggml-org/llama.cpp/master/tools/tts/README.md`
- `https://raw.githubusercontent.com/ggml-org/llama.cpp/master/tools/tts/tts.cpp`
- `https://github.com/ggml-org/llama.cpp/pull/12794` ("Support for OuteTTS 1.0", draft status
  confirmed live)
- `https://github.com/ggml-org/llama.cpp/pull/10784` ("tts: add OuteTTS support", the original
  merged v0.2 PR)
- `edwko/OuteTTS` GitHub repository tree + `docs/interface_usage.md` +
  `outetts/version/v3/default_speakers/json/en-female-1-neutral.json` +
  `outetts/dac/interface.py`, all fetched via `data.jsdelivr.com`'s GitHub-mirror package API and
  `cdn.jsdelivr.net` raw-content mirror (used because `api.github.com` is blocked for repositories
  not attached to this session; `raw.githubusercontent.com`/`cdn.jsdelivr.net` are not).
