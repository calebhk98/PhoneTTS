# Supertonic (Supertone Inc) - research facts

Compiled 2026-07-24 for the `:engines:supertonic` module. Every fact below is cited to a source
fetched directly during this research session (Hugging Face Hub file listings/content via the
`hf_fs` tool, and raw source files from GitHub via `raw.githubusercontent.com`, both fetched live
- not recalled from training data). Anything not independently confirmed is marked **UNVERIFIED**
or **ASSUMPTION** rather than stated as fact, per this ticket's hard "never fabricate" rule.

## 1. Identity, license, repo status

- **Publisher**: Supertone Inc (a Korean voice-AI company).
- **Hugging Face repos** (confirmed via `hf://models` search, 2026-07-24):
  - [`Supertone/supertonic`](https://huggingface.co/Supertone/supertonic) - v1, English-only.
  - [`Supertone/supertonic-2`](https://huggingface.co/Supertone/supertonic-2) - v2, 5 languages
    (`en, ko, es, pt, fr` per `supertonic-py`'s docstring, see §5).
  - **[`Supertone/supertonic-3`](https://huggingface.co/Supertone/supertonic-3)** - v3, current/
    latest, **31 languages** - this is the repo `:engines:supertonic` targets (matches the task's
    "v3 has 31 languages/voices" framing) and the `DEFAULT_MODEL` in the official Python SDK
    (`supertonic-py/supertonic/config.py`, see §5).
  - Third-party ONNX re-exports also exist, e.g.
    [`onnx-community/Supertonic-TTS-ONNX`](https://huggingface.co/onnx-community/Supertonic-TTS-ONNX)
    and `onnx-community/Supertonic-TTS-2-ONNX`. **These ship a DIFFERENT graph shape** - 3 files
    (`latent_denoiser.onnx`, `text_encoder.onnx`, `voice_decoder.onnx`) plus a fast `tokenizer.json`
    and single-vector `voices/<name>.bin` files (51712 bytes each), not the official 4-graph
    `{duration_predictor,text_encoder,vector_estimator,vocoder}.onnx` + split `{style_ttl,
    style_dp}` voice-style JSON layout the official repos and this engine target. **NOT
    implemented by this engine** - noted so nobody assumes the two layouts are interchangeable.
  - GitHub: [`supertone-inc/supertonic`](https://github.com/supertone-inc/supertonic) (main repo,
    multi-language reference implementations under `java/`, `cpp/`, `web/`, `nodejs/`, `csharp/`,
    `go/`, `swift/`, `ios/`, `rust/`, `flutter/`) and
    [`supertone-inc/supertonic-py`](https://github.com/supertone-inc/supertonic-py) (the official
    Python SDK/package, `pip install supertonic`).
- **License** (from `Supertone/supertonic-3`'s README frontmatter and `LICENSE` file, fetched
  2026-07-24): **the sample/inference CODE is MIT**; **the model WEIGHTS are OpenRAIL-M**, not MIT
  as the task brief assumed - correcting that assumption rather than repeating it. Quote: *"This
  project's sample code is released under the MIT License... The accompanying model is released
  under the OpenRAIL-M License."* Both licenses permit local/offline inference; OpenRAIL-M carries
  standard use-restriction clauses worth a maintainer's own read of the full `LICENSE` file before
  shipping.
- **Repo status - IMPORTANT, time-sensitive**: `supertone-inc/supertonic`'s README (fetched
  2026-07-24, i.e. the day before "today" in this session's clock) carries a **live archive
  notice**: *"⚠️ Service and Repository Notice (July 23, 2026) - This repository will be archived,
  and there will be no further development or official support for the open-source Supertonic
  models. Voice Builder will no longer be accessible after August 31, 2026."* The already-published
  weights/code remain usable (nothing here depends on the live service), but do not expect upstream
  fixes, new releases, or continued HF-repo updates going forward. Worth flagging to whoever
  triages future Supertonic issues.

## 2. Model size, sample rate, architecture

- **~99M parameters** across the public ONNX assets (`Supertone/supertonic-3` README: *"At about
  99M parameters across the public ONNX assets, Supertonic 3 is much smaller than 0.7B to 2B class
  open TTS systems."*).
- **Sample rate: 44100 Hz** - `onnx/tts.json`'s `"ae": {"sample_rate": 44100, ...}` (downloaded
  directly from `Supertone/supertonic-3`, 2026-07-24). Cross-checked in two independent
  implementations reading the SAME field: `supertonic-py/supertonic/core.py`
  (`self.sample_rate = cfgs["ae"]["sample_rate"]`) and the official Java example's
  `Helper.loadCfgs` (`config.ae.sampleRate = root.get("ae").get("sample_rate").asInt()`).
- **Architecture** (from `onnx/tts.json`'s structure and the model card): a latent-space
  flow-matching TTS - a speech autoencoder (`ae`, 44.1 kHz mel -> 24-dim latent, `chunk_size=512`)
  feeds a duration predictor (`dp`) and a text-to-latent flow-matching model (`ttl`, RoPE
  cross-attention, style-token conditioning) whose output latent a vocoder decodes back to audio.
  The exported ONNX graphs are `duration_predictor.onnx`, `text_encoder.onnx`,
  `vector_estimator.onnx` (the flow-matching "vector field" step, run in a loop), and
  `vocoder.onnx`.
- **On-disk weight sizes** (`Supertone/supertonic-3/onnx/`, `hf_fs ls`, 2026-07-24):

  | File | Size |
  |---|---|
  | `duration_predictor.onnx` | 3,700,147 bytes (~3.5 MiB) |
  | `text_encoder.onnx` | 36,416,150 bytes (~34.7 MiB) |
  | `vector_estimator.onnx` | 256,534,781 bytes (~244.6 MiB) |
  | `vocoder.onnx` | 101,424,195 bytes (~96.7 MiB) |
  | `tts.json` (config) | 8,253 bytes |
  | `unicode_indexer.json` | 277,676 bytes |

  Total onnx/ weights ≈ 398 MiB on disk.

## 3. Languages (31 + "na" fallback)

`Supertone/supertonic-3`'s README frontmatter `language:` list AND
`supertonic-py/supertonic/config.py`'s `SUPPORTED_LANGUAGES` constant (downloaded 2026-07-24,
byte-identical to the official Java `Helper.java`'s `Languages.AVAILABLE` list) all agree on this
exact 31-code set, in this order:

```
en, ko, ja, ar, bg, cs, da, de, el, es, et, fi, fr, hi, hr, hu, id, it, lt, lv, nl, pl, pt, ro,
ru, sk, sl, sv, tr, uk, vi
```

Plus a 32nd special code, **`na`** (`UNKNOWN_LANGUAGE` in `config.py`) - Supertonic 3's own
"language-agnostic" fallback: wraps text as `<na>...</na>` instead of a real language tag, for text
whose language is unknown or unsupported. `supertonic-py`'s `TTS.synthesize` defaults to `lang="na"`
for the multilingual models (v2/v3) specifically so unrecognized-language text still works.

**Design note**: a Supertonic voice STYLE (§4) and a Supertonic LANGUAGE are orthogonal - neither
reference implementation ties a style to one language; the same `M1` style can speak any of the 31
languages. `:engines:supertonic` models this as its own CHOICE `ModelParameter`
(`SupertonicEngine.LANGUAGE_CHOICES`), not folded into `Voice.language` - see the engine's own KDoc
for the reasoning.

## 4. Voices ("voice styles")

**10 built-in voice styles**, confirmed by directory listing `Supertone/supertonic-3/voice_styles/`
(`hf_fs ls`, 2026-07-24) and by `TTS.get_voice_style`'s own docstring in
`supertonic-py/supertonic/pipeline.py` (*"Supertonic-3 ships with 10 built-in voices:
`'M1'..'M5'` and `'F1'..'F5'`"*):

```
F1, F2, F3, F4, F5, M1, M2, M3, M4, M5
```

(`M`/`F` read naturally as male/female timbre, per the model card's own naming, though the repo
itself doesn't spell that out explicitly - noted as the obvious reading, not an independently
confirmed claim.)

Each `voice_styles/<name>.json` file (downloaded `M1.json` directly, 2026-07-24) has the shape:

```json
{
  "style_ttl": {"dims": [1, 50, 256], "data": [[[ ... 12800 floats ... ]]]},
  "style_dp":  {"dims": [1, 8, 16],   "data": [[[ ... 128 floats ... ]]]}
}
```

- `style_ttl` feeds `text_encoder.onnx` and `vector_estimator.onnx` (the flow-matching/text-to-
  latent side).
- `style_dp` feeds `duration_predictor.onnx`.
- `[1, 50, 256]` matches `tts.json`'s `ttl.style_encoder.style_token_layer` config
  (`n_style: 50, style_value_dim: 256`); `[1, 8, 16]` matches `tts.json`'s
  `dp.style_encoder.style_token_layer` (`n_style: 8, style_value_dim: 16`) - i.e. the shapes are
  internally self-consistent with the model's own declared config, not a guess.
- 12800 + 128 = 12928 floats total per voice, which **independently cross-checks** against the
  third-party `onnx-community/Supertonic-TTS-ONNX` re-export's single-vector `voices/<name>.bin`
  files: 51712 bytes / 4 bytes-per-float32 = 12928 floats - the same total voice-style payload
  size, reached two independent ways.

Parsing code confirming this shape in both official reference implementations: `supertonic-py/
supertonic/loader.py`'s `load_voice_style_from_json_file`/`_load_style_from_json`
(`np.array(data, dtype=np.float32).reshape(*dims)`) and the official Java example's
`Helper.loadVoiceStyle`/`VoiceStyleData` (both downloaded from `supertone-inc/supertonic`,
2026-07-24).

## 5. Text frontend - character-level, NOT phoneme-based

Supertonic tokenizes **raw Unicode characters directly** - there is no phonemizer/g2p step. The
tokenizer is `onnx/unicode_indexer.json`: a flat JSON array of exactly **65536 entries** (one per
Unicode code point in the Basic Multilingual Plane, `0x0000`-`0xFFFF`), each either `-1`
(unsupported by the model) or a non-negative model-internal character-embedding index. Confirmed by
downloading the file directly from `Supertone/supertonic-3` and inspecting it with a small Python
script (2026-07-24): length 65536, values range `-1..8320` (8321 supported entries),
`ord('A') -> 33`, `ord('a') -> 60`.

Confirmed against BOTH official reference implementations reading this exact file the same way:

- `supertonic-py/supertonic/core.py`'s `UnicodeProcessor` class - loads the file as a flat list,
  builds `ord(char) -> self.indexer[val]`.
- The official Java example's `UnicodeProcessor`/`Helper.loadJsonLongArray` in
  `supertone-inc/supertonic`'s `java/Helper.java` - identical shape.

Preprocessing pipeline (`UnicodeProcessor._preprocess_text` in Python /
`UnicodeProcessor.preprocessText` in Java, both read 2026-07-24), applied before indexing:

1. Unicode **NFKD** normalization.
2. Emoji removal (explicit Unicode range stripping).
3. Punctuation/symbol normalization (smart quotes -> straight quotes, en/em dash -> `-`, etc.) and
   a small set of literal abbreviation expansions (`e.g.,` -> `for example, `, `i.e.,` -> `that is,
   `, `@` -> ` at `).
4. Whitespace cleanup, duplicate-quote collapsing.
5. **Append a trailing `.` if the text doesn't already end in sentence-final punctuation** (a fairly
   large explicit character class including several CJK closing marks).
6. For the multilingual models (v2/v3): **wrap the whole string in a language tag**,
   `<lang>text</lang>` (or `<na>...</na>`, §3) - literally embedded as characters the same
   `unicode_indexer.json` table tokenizes, not a separate special-token mechanism.

`:engines:supertonic`'s `SupertonicFrontend` implements steps 1, 5, 6, and the indexing itself
faithfully; it deliberately does NOT reproduce steps 2-4 (explicit emoji-range stripping, dash/
quote normalization, abbreviation expansion) - a documented text-preprocessing completeness gap,
not an identification failure, since `unicode_indexer.json`'s own `-1` sentinel already drops any
character the model doesn't support regardless (see the frontend's own KDoc).

## 6. ONNX pipeline - exact tensor I/O (VALIDATED, with one noted gap)

Both official reference implementations - `supertonic-py/supertonic/core.py`'s
`Supertonic.__call__` (Python) and `supertone-inc/supertonic`'s `java/Helper.java`
`TextToSpeech._infer` (Java) - were downloaded directly (2026-07-24) and agree, tensor-name-for-
tensor-name, on this exact 4-stage pipeline:

### Stage 1 - `duration_predictor.onnx`

- Inputs: `text_ids` (int64, `[batch, seq_len]`), `style_dp` (float32, `[batch, 8, 16]`),
  `text_mask` (float32, `[batch, 1, seq_len]`, 1.0 for real tokens / 0.0 for padding).
- Output: a duration array in **seconds**, one value per batch item. Read positionally (see the gap
  note below), then **`duration = duration / speed`** - this is where Supertonic's native speed
  knob lives; both reference implementations perform this exact division right after this call,
  before generation continues. This is the model's OWN duration-scaling mechanism, not a resample
  of any audio (satisfies CLAUDE.md rule 2).

### Stage 2 - `text_encoder.onnx`

- Inputs: `text_ids` (same as above), `style_ttl` (float32, `[batch, 50, 256]`), `text_mask` (same).
- Output: `text_emb` - a text embedding tensor consumed unchanged by every iteration of stage 3.

### Stage 3 - `vector_estimator.onnx` (the flow-matching denoising loop)

A noisy latent is first sampled: shape `[batch, latent_dim * chunk_compress_factor, latent_frames]`
= `[batch, 24*6, latent_frames]` = `[batch, 144, latent_frames]`, where
`latent_frames = ceil(duration_seconds * sample_rate / (base_chunk_size * chunk_compress_factor))`
= `ceil(duration_seconds * 44100 / (512*6))` = `ceil(duration_seconds * 44100 / 3072)` (both
reference implementations compute this identically - `core.py`'s `sample_noisy_latent` / the Java
`TextToSpeech.sampleNoisyLatent`). A `latent_mask` (float32, all-ones for a single-item batch with
no padding) is built the same way.

The graph then runs in a loop, `total_step` times (default **8**, see §7), each call:

- Inputs: `noisy_latent` (float32, `[batch,144,latent_frames]`), `text_emb` (from stage 2),
  `style_ttl` (same tensor as stage 2), `text_mask`, `latent_mask`, `current_step` (float32,
  `[batch]`, the loop index `0..total_step-1`), `total_step` (float32, `[batch]`, constant).
- Output: the denoised latent for this step, which becomes next iteration's `noisy_latent` input
  (an ODE/flow-matching integration loop). Read positionally (gap note below).

### Stage 4 - `vocoder.onnx`

- Input: `latent` (the final denoised latent from stage 3).
- Output: `wav` - raw audio samples. **Both reference implementations trim this to
  `round(duration_seconds * sample_rate)` samples** before use (the raw vocoder output can be
  longer than the requested duration) - `Helper.java`'s `int actualLen = (int)(sampleRate * dur);
  System.arraycopy(...)`.

### The one genuine gap: output tensor NAMES

Neither official reference implementation ever reads a graph's output BY NAME - Python calls
`session.run(None, {...})` (which returns every output in graph-declared order) and destructures
positionally: `value, *_ = session.run(...)`; the Java example calls
`OrtSession.Result result = session.run(inputs); result.get(0).getValue()` - also positional. So
this research could confirm every **input** tensor name with certainty (both implementations name
them explicitly as dict/map keys) but **could not independently verify what ONNX actually named any
output tensor** - that information genuinely isn't surfaced by either official example. Per this
ticket's "mark UNVERIFIED rather than invent" rule: `:engines:supertonic` reads every stage's
single output **positionally** (via the existing `singleTensorOrError`/`singleFloatsOrError` helpers
already used for exactly this situation by `com.phonetts.engines.melotts.MeloEngine`'s own
auto-numbered acoustic-graph output), and additionally assumes each graph reports **exactly one**
output - if a real loaded graph turns out to report more than one, that assumption needs revisiting
against the actual graph (there was no way to open the real `.onnx` files and inspect their
`graph.output` list in this research environment).

## 7. Speed and step-count parameters

- **Speed**: `supertonic-py/supertonic/config.py`: `MIN_SPEED = 0.7`, `MAX_SPEED = 2.0`,
  `DEFAULT_SPEED = 1.05`. Routes to the duration predictor's output as described in §6 Stage 1 -
  genuinely native, never a resample of the vocoder's output.
- **Denoising steps** (`total_step`): `supertonic-py/supertonic/config.py`:
  `DEFAULT_TOTAL_STEPS = 8`, `MIN_TOTAL_STEPS = 1`, `MAX_TOTAL_STEPS = 100`. This IS a genuine
  runtime graph input (unlike, say, F5-TTS's NFE count, which this codebase's own
  `com.phonetts.engines.f5tts.F5TtsEngine` documents as baked into its exported graph at conversion
  time) - a future ticket could reasonably expose it as a "quality/speed tradeoff" CHOICE or
  CONTINUOUS `ModelParameter`. This ticket's scope named only the SPEED knob, so
  `:engines:supertonic` keeps `DEFAULT_TOTAL_STEPS = 8` as an internal constant rather than exposing
  it - a deliberate scope decision, documented in the engine's own KDoc, not an oversight.
- **Expression tags**: the `Supertonic/supertonic-3` README advertises support for simple inline
  tags like `<laugh>`, `<breath>`, `<sigh>`. Because the tokenizer is plain character-level (§5),
  these need no special handling in code - they pass through `unicode_indexer.json` as ordinary
  ASCII characters, exactly like any other text, and the MODEL was apparently trained to interpret
  that literal character sequence specially. Not implemented as an app-visible feature by this
  ticket (no UI/`ModelParameter` for it), but nothing prevents a user from typing them into the text
  box today.

## 8. What `:engines:supertonic` implements vs. does not

| Fact | Status |
|---|---|
| Repo id (`Supertone/supertonic-3`) | VALIDATED |
| Sample rate (44100 Hz) | VALIDATED |
| 4 onnx graph file names | VALIDATED |
| `tts.json`/`unicode_indexer.json` file names | VALIDATED |
| Input tensor names, all 4 stages | VALIDATED (both official reference impls agree) |
| Output tensor names | **ASSUMPTION** - read positionally, real names unconfirmed (§6) |
| `latent_frames`/noisy-latent-shape math | VALIDATED (both official reference impls agree) |
| Speed routing (`duration / speed`) | VALIDATED |
| Speed range/default | VALIDATED |
| 10 voice style names (`M1-M5`,`F1-F5`) | VALIDATED |
| Voice style JSON shape (`style_ttl`/`style_dp`, dims) | VALIDATED |
| 31 languages + `na` | VALIDATED |
| Text frontend: NFKD + trailing period + lang tag + indexer | VALIDATED (steps implemented) |
| Text frontend: emoji-range/dash/quote/abbrev normalization | Documented gap, NOT implemented |
| License (code MIT / weights OpenRAIL-M) | VALIDATED |
| SHA-256 of any weight file | **NOT obtained** - not fetched in this research pass; the
  manifest/download pipeline (`:app`, out of this ticket's scope) is where a real SHA-256 pin would
  be added when the weights are actually mirrored for download |
| Repo archive/EOL notice | VALIDATED (live as of 2026-07-24, see §1) |

## Sources (fetched live during this research session, 2026-07-24)

- <https://huggingface.co/Supertone/supertonic-3> (README, `config.json`, `onnx/tts.json`, file
  listing)
- <https://huggingface.co/Supertone/supertonic-3/resolve/main/onnx/unicode_indexer.json>
- <https://huggingface.co/Supertone/supertonic-3/resolve/main/voice_styles/M1.json>
- <https://huggingface.co/Supertone/supertonic> (v1), <https://huggingface.co/Supertone/supertonic-2> (v2)
- <https://huggingface.co/onnx-community/Supertonic-TTS-ONNX> (third-party re-export, different graph shape)
- <https://github.com/supertone-inc/supertonic> (README/archive notice, `java/ExampleONNX.java`, `java/Helper.java`)
- <https://github.com/supertone-inc/supertonic-py> (`supertonic/core.py`, `config.py`, `loader.py`,
  `pipeline.py`, `utils.py`)
- <https://github.com/chantysothy/supertonic-pytorch>, <https://github.com/Topping1/Supertonic-Voice-Mixer>
  (found via web search, not fetched in depth - unofficial reconstructions, not used as a source
  for any fact above)

Not used as a source (explicitly ruled out): the task brief's suggested
`SUP3RMASS1VE/SuperTonic-TTS-Andriod` GitHub repo. This session's `add_repo` tool could not attach a
GitHub repo outside the session's already-configured account/owner tier (`cross-tier adds are not
supported`), and no fetchable public mirror of it was found via `raw.githubusercontent.com` in the
time available. Everything above was instead validated against the OFFICIAL `supertone-inc`
repos directly, which turned out to carry an equally-authoritative (arguably more authoritative)
Java ONNX reference implementation - so no fact in this document depends on that unavailable repo.
