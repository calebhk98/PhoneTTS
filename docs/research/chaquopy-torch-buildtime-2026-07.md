# Research: Chaquopy build-time PyTorch for PhoneTTS (2026-07)

**Question:** Instead of running Piper/Kokoro/MeloTTS-style models through ONNX (or converting them
at all), could PhoneTTS bundle Chaquopy (Python + a cross-compiled `torch`) into the APK **at build
time** and run raw `.pth`/`.ckpt` checkpoints (e.g. `myshell-ai/MeloTTS-English`'s `checkpoint.pth`)
directly, for the maintainer's target model list (MeloTTS, F5-TTS, VoxCPM, Qwen3-TTS, Kokoro-82M)?

**Verdict up front:** The premise correction is real — build-time Chaquopy code is baked into
`lib/`, which W^X does **not** block, so `docs/research/on-demand-python-2026-07.md`'s W^X objection
does not apply here. But that correction buys almost nothing in practice, because a second,
independent wall stands in its place: **Chaquopy's cross-compiled `torch` has been stuck at version
1.8.1 (Python 3.8 only) since May 2021, and Chaquopy's own current plugin (17.0.0, Dec 2025) has
since dropped Python 3.8 entirely** — so the latest Chaquopy cannot even install the only torch build
that exists. Of the maintainer's five target models, at most **one (MeloTTS)** is plausible on
version-constraint grounds, and even that is unverified and sits on a build known to segfault
in-process as recently as May 2025. F5-TTS, VoxCPM, Qwen3-TTS, and Kokoro-82M all require a torch
version Chaquopy has never shipped for Android. Recommendation: **do not pursue this path** — go
straight to ONNX/ExecuTorch conversion, which the community has already done for four of the five
models.

---

## 1. Confirming the premise: build-time is a genuinely different question from on-demand

`docs/research/on-demand-python-2026-07.md` ruled out *downloading* Python/torch into app-private
storage after install, for two independent reasons: (a) Android's W^X policy blocks executing or
`dlopen()`-ing code from an app's own writable storage once `targetSdkVersion >= 29`
([Android 10 behavior changes](https://developer.android.com/about/versions/10/behavior-changes-10)),
and (b) PyPI's official `torch` wheels are `manylinux`/glibc-only and won't load on Bionic libc
regardless of W^X.

Chaquopy's **build-time** model sidesteps (a) entirely: the Gradle plugin's `pip { install "..." }`
block runs `pip` on the **build machine**, cross-compiles/fetches Android-targeted wheels from
Chaquopy's own repository, and packages the resulting `.so`/`.py` files into the APK's `lib/<abi>/`
and asset directories at `assemble` time
([Chaquopy Android docs](https://chaquo.com/chaquopy/doc/current/android.html)). Code shipped inside
the APK's `lib/` directory is signed, installed by the package manager, and is exactly the kind of
code W^X is designed to allow — this is how every native `.so` (ONNX Runtime, `libtorch.so` if you
get that far) already ships in PhoneTTS today. **So the distinction the maintainer drew is correct:
build-time bundling is not blocked by W^X, and the prior doc's verdict was scoped correctly (it says
so explicitly — "build-time only" — but is worth restating since it's easy to over-read as "Chaquopy
is dead end, full stop").**

That said, blocker (b) from the prior doc reappears in a different shape here: Chaquopy doesn't use
the PyPI wheel at all — it maintains its **own** cross-compiled Android build of torch. The question
this doc actually has to answer is: **is that specific, Chaquopy-maintained torch build current,
compatible with the target models, and reliable?** It is not.

## 2. Chaquopy's torch package: exact version, and it has not moved since 2021

Chaquopy's Android wheel repository moved to `https://chaquo.com/pypi-13.1/` in Chaquopy 15.0.1
(2023-12-24); the old `pypi-7.0` URL is retired
([changelog](https://chaquo.com/chaquopy/doc/current/changelog.html)). Fetching the live index for
`torch` directly:

```
torch-1.4.0-1-cp38-cp38-android_{16_armeabi_v7a,16_x86,21_arm64_v8a,21_x86_64}.whl   (2020-02-07)
torch-1.4.0-2-cp38-cp38-android_{16_armeabi_v7a,16_x86,21_arm64_v8a,21_x86_64}.whl   (2020-10-21)
torch-1.8.1-3-cp38-cp38-android_{16_armeabi_v7a,16_x86,21_arm64_v8a,21_x86_64}.whl   (2021-05-17)
```
(https://chaquo.com/pypi-13.1/torch/, fetched 2026-07)

**The newest torch Chaquopy has ever published for Android is 1.8.1, built for `cp38` (Python 3.8)
only, last updated 2021-05-17 — over five years stale.** There is no 1.13.1 fork as the maintainer
had heard, and nothing newer than 1.8.1 anywhere in Chaquopy's own repo. `torchvision` tops out at
0.9.1 (also cp38, May 2021) — https://chaquo.com/pypi-13.1/torchvision/. Supporting native packages
are similarly frozen at their 2020–2021 vintage: `numpy` 1.19.5, `scipy` 1.4.1, `tokenizers` 0.10.3,
`sentencepiece` 0.1.95 (all cp38-only, all last touched 2020–2021, verified against the live
`pypi-13.1` index). This is a five-plus-year-old, single-maintainer-effort snapshot of the PyTorch
ecosystem, not an actively tracked backport.

## 3. The current Chaquopy plugin can no longer even install this torch

This is the sharpest finding of this research and the one that makes the "just bundle it" idea much
less viable than it first sounds: **Chaquopy's own Python-version support moved past 3.8 while
torch stayed frozen at cp38, so the two no longer fit together.**

From Chaquopy's changelog (https://chaquo.com/chaquopy/doc/current/changelog.html):

- **17.0.0 (2025-12-01, current):** "Python 3.8 and 3.9 no longer supported; default is now 3.10."
  Supports Android Gradle Plugin 9.0–9.2; drops AGP 7.0–7.2.
- **16.1.0 (2025-05-07):** last release that still supports Python 3.8. Supports AGP 8.9–8.13.
- **16.0.0 (2024-10-15):** minimum API level raised to 24.

Since the only torch wheels ever published are `cp38`, **the current, actively-supported Chaquopy
plugin (17.0.0) cannot install torch at all** — there is no `cp310`/`cp311`/... torch wheel to
install. To get torch working you would have to deliberately pin the Gradle plugin to **16.1.0 or
older** (Python 3.8's last supported release), which caps you at AGP ≤8.13 and forgoes every
Chaquopy improvement since May 2025 — including the 16 KB memory-page work below.

### The 16 KB-page problem makes this worse on 2026-era hardware specifically

Chaquopy 17.0.0 added support for Android's 16 KB memory page size (required by newer devices/newer
Android versions), but with a direct caveat in its own changelog: **"many existing Android wheels
will still fail to load on 16 KB devices. For best compatibility with these devices, use Python 3.13
or later."** The one Python version with a torch wheel — 3.8 — is exactly the *oldest*, *least*
16 KB-compatible option Chaquopy offers. A phone with a 16 KB page-size kernel (an increasing share
of Android 15+ devices, part of the reason this "must run on better/worse phones" app cares about
staying current) is a plausible way for this whole approach to fail to even `dlopen()` `libtorch.so`,
independent of every other issue below.

## 4. Reliability: known-broken, and not from ancient history

The open/closed GitHub issues on `chaquo/chaquopy` for torch specifically:

- [#1215](https://github.com/chaquo/chaquopy/issues/1215) — no torch build exists for Python 3.9+;
  confirms the cp38 ceiling is a structural gap, not a temporary lag.
- [#1273](https://github.com/chaquo/chaquopy/issues/1273) — "Missing torch libraries in python 3.12."
- [#247](https://github.com/chaquo/chaquopy/issues/247) — `RuntimeError: Unable to find
  torch_shm_manager` at runtime (torch's shared-memory manager binary not found/executable in the
  Android package layout).
- [#1376](https://github.com/chaquo/chaquopy/issues/1376) — **opened 2025-05-28** (14 months before
  this research, not an old relic): a `Fatal SIGNAL 11` (SIGSEGV) crash inside the PyTorch library
  on-device, reported against **Chaquopy 16.1.0 + torch 1.8.1 on `arm64-v8a`** — i.e. exactly the
  plugin/torch/ABI combination this doc would have to recommend pinning to. The reporter's app also
  pulled in `transformers`/`sentence-transformers` alongside torch, the same class of dependency
  MeloTTS needs. No confirmed maintainer fix is recorded on the issue.

No search turned up a credible, documented case of someone shipping a working nontrivial neural
model (TTS or otherwise) through Chaquopy+torch in production. The closest hits were generic
"Chaquopy can run Python on Android" writeups and PyTorch's own **native** Android tooling (LibTorch
Mobile / ExecuTorch demo apps), which are unrelated to Chaquopy. One ML-focused writeup on using
Chaquopy for pipelines explicitly warns it's not the performance-appropriate choice for
heavy pre/post-processing versus native code. This is the pattern you'd expect from an
unofficial, under-resourced cross-compile of a huge, fast-moving native project: it doesn't get
fixed, it gets left behind.

## 5. APK size / packaging

- The `torch-1.8.1-3` wheel itself is **32 MB compressed for `arm64_v8a`** (36 MB for `x86_64`),
  confirmed from the live directory listing's file sizes. That's a floor, not the full on-disk
  contribution — torch's shared libraries are large uncompressed, and Chaquopy embeds native `.so`s
  into `lib/<abi>/`, which Android's package installer typically extracts.
- Add the rest of what MeloTTS-class code needs: `numpy` (cp38 wheel), `scipy`, `tokenizers`,
  `sentencepiece`, plus Chaquopy's own bundled Python 3.8 runtime and standard library (Chaquopy
  ships a full CPython build regardless of what packages you add). Real-world Chaquopy app-size
  reports commonly land at **150 MB+ once Python plus a handful of native packages are bundled**,
  with "more than 90% of the size... from Chaquopy and Python" per a maintainer discussion
  ([chaquo/chaquopy#618](https://github.com/chaquo/chaquopy/issues/618)). A torch-carrying build
  should be assumed to land well above that.
- **A separate arm64-only build variant is straightforward and already Chaquopy's own recommended
  practice**, not a novel idea this doc has to invent: Chaquopy requires you to declare `abiFilters`
  explicitly (`arm64-v8a`, `x86_64`, `armeabi-v7a`, `x86`), and its docs state plainly "each ABI will
  add several MB to the size of the app" — i.e., dropping to `arm64-v8a` only removes the other three
  ABIs' worth of torch/numpy/etc. wheels outright. This does genuinely limit the blast radius to one
  ABI, but does nothing about the underlying torch-version/reliability problems above.

## 6. Model compatibility — the actual decision table

| Model | Torch version the model needs | Chaquopy has | Verdict |
|---|---|---|---|
| **MeloTTS-English** (`checkpoint.pth`, VITS/VITS2 + BERT frontend) | Unpinned in `requirements.txt` (just `torch`/`torchaudio`); its pinned `transformers==4.27.4` dependency requires `torch>=1.7,!=1.12.0` ([transformers 4.27.4 setup.py](https://github.com/huggingface/transformers/blob/v4.27.4/setup.py)) — 1.8.1 satisfies that on paper. | 1.8.1 (cp38) | **Only plausible candidate**, and only on version-constraint grounds. VITS/VITS2 is 2019–2021-era architecture, so it's the one model here old enough to conceivably predate torch-1.8.1-incompatible ops. But no one has demonstrated it actually loading/running on Chaquopy's torch, and the closest real evidence (issue #1376) is a SIGSEGV on this exact plugin+torch+ABI combo. Call it "unverified, high execution risk," not "works." |
| **F5-TTS** (`SWivid/F5-TTS`) | `torch>=2.0.0` (pinned in [`pyproject.toml`](https://github.com/SWivid/F5-TTS/blob/main/pyproject.toml)) | 1.8.1 max | **Not viable.** Two major versions beyond what Chaquopy ships; no path to closing that gap without Chaquopy shipping a new torch build (which hasn't happened in 5 years). |
| **VoxCPM** (`openbmb/VoxCPM*`) | `torch>=2.5.0` for the current VoxCPM2 line ([VoxCPM install docs](https://voxcpm.readthedocs.io/en/latest/installation.html)) | 1.8.1 max | **Not viable.** Also independently flagged in `docs/research/model-triage-2026-07.md` Bucket D as too heavy (1.6–5 GB) with no ONNX/GGUF port at all — this model is out of reach for PhoneTTS by any path right now, Chaquopy included. |
| **Qwen3-TTS** (`Qwen/Qwen3-TTS*`) | No single official pin found; community forks/runners use `torch>=2.5.1`–`2.7.0` (CUDA-graph and Blackwell-GPU code paths require ≥2.5) | 1.8.1 max | **Not viable.** Also flagged in `model-triage-2026-07.md` Bucket D — "no ONNX/GGUF port anywhere." Same story as VoxCPM: nothing works today, not just Chaquopy. |
| **Kokoro-82M** (`hexgrad/Kokoro-82M`, `.pth`) | The `kokoro` package's own [`pyproject.toml`](https://github.com/hexgrad/kokoro/blob/main/pyproject.toml) requires **Python >=3.10** as install metadata — incompatible with Chaquopy's cp38-only torch by itself, before even asking what torch version the code needs. The model code (`istftnet.py`) does use the **old**, still-torch-1.8-compatible `torch.nn.utils.weight_norm` API rather than the newer `parametrizations.weight_norm` (added torch 2.1), so the actual tensor ops aren't obviously version-locked — but nobody has verified a run below torch ~2.x, and you'd have to hand-vendor the model code to dodge the Python-3.10 packaging requirement. | 1.8.1 max, cp38 only | **Not viable as shipped.** Blocked twice over: Chaquopy has no torch for Python ≥3.10 (what Kokoro's package declares it needs), and even bypassing that by vendoring code manually is unverified speculation, not a working path. |

**Bottom line: of five target models, zero are demonstrated to work, and at most one (MeloTTS) is
even plausible on paper** — and that one plausibility is undercut by a documented in-process crash
on the exact plugin/torch/ABI combination required.

## 7. The runtime shape even if torch loaded cleanly

Bundling torch is necessary but nowhere near sufficient. Each model still needs its Python
**inference code** — the `nn.Module` class definitions, checkpoint-loading logic, and any
pre/post-processing (MeloTTS: BERT tokenization + G2P + VITS decode; Kokoro: misaki G2P + StyleTTS2
decode; F5-TTS: flow-matching sampler loop) — copied into the app's Python source tree and invoked
from Kotlin via Chaquopy's JNI bridge (`com.chaquo.python.Python.getInstance().getModule(...)`).
This is **bespoke per model family**, not a generic loader: VITS-style, StyleTTS2-style, and
flow-matching-style models have different forward-pass shapes, different input preprocessing, and
different multi-stage pipelines (e.g., F5-TTS's DiT + separate vocoder). There is no "drop in any
`.pth` and go" shim — you'd be hand-porting each model's reference `inference.py` into the app and
maintaining it, on top of getting the interpreter and torch itself to load, for every future model.
This directly conflicts with this codebase's core design goal (CLAUDE.md rule 5: "no `when(modelType)`
switches... adding a model = registering it") — a Chaquopy-based engine would in practice need
bespoke Python glue **and** bespoke Kotlin bridging code per model family, the opposite of the
registry/descriptor pattern the rest of the app follows.

## 8. Honest comparison to conversion (ONNX/ExecuTorch)

For the same models, the "just convert once" alternative already has a head start, community-wide,
for most of them:

- **MeloTTS** — a working ONNX conversion already exists in the wild: `k2-fsa/sherpa-onnx` ships an
  export script, and pre-converted exports are published on Hugging Face (e.g.
  `seasonstudio/melotts_zh_mix_en_onnx`). This is the same finding the prior on-demand doc already
  cited. This slots into PhoneTTS's existing `Runtime`/`VoiceEngine` ONNX path with zero new
  abstractions.
- **Kokoro-82M** — an official, actively maintained ONNX export exists:
  `onnx-community/Kokoro-82M-v1.0-ONNX` (fp32/fp16/int8 variants, 88–310 MB), with multiple
  community ONNX-Runtime wrapper projects (`thewh1teagle/kokoro-onnx`, `taylorchu/kokoro-onnx`) —
  this is a mature, well-trodden path, not a one-off.
- **F5-TTS** — `docs/research/model-triage-2026-07.md` already identifies `DakeQQ/F5-TTS-ONNX` as an
  existing ONNX port of this exact checkpoint, ranked the **#1** highest-feasibility new-engine
  candidate in that triage — independent of this research, for reasons unrelated to Chaquopy.
- **VoxCPM / Qwen3-TTS** — no ONNX or GGUF port exists for either today (same triage doc, Bucket D).
  This is the one place where "conversion" isn't obviously easier than Chaquopy — but Chaquopy isn't
  easier either (§6): both paths are simply closed for these two models right now. Revisit if/when
  a community conversion appears; don't build custom infrastructure (Chaquopy or otherwise) to
  route around that gap alone.

Given that, an "old, frozen, occasionally-segfaulting torch 1.8.1 plus per-model hand-ported Python
inference code plus a 150 MB+ APK tax" is **worse on every axis** than a one-time ONNX export for
the three models (MeloTTS, Kokoro, F5-TTS) where that export already exists and is maintained by
someone else. For the two models where no conversion exists yet (VoxCPM, Qwen3-TTS), Chaquopy
doesn't help either, since their torch requirement (≥2.5) is unreachable on Chaquopy regardless.

## Verdict

**Chaquopy build-time bundling is a real, W^X-compliant mechanism — the maintainer's premise
correction against the prior on-demand doc is right.** But it doesn't unlock what it sounds like it
should, because the specific package it would need to bundle — Android-cross-compiled `torch` — has
been stuck at **1.8.1 for Python 3.8 only since 2021-05-17**, and Chaquopy's **own current plugin
(17.0.0) has since dropped Python 3.8 support**, so the latest, actively-maintained Chaquopy cannot
even install the torch it would need; you'd have to deliberately pin to a plugin version over a year
stale (≤16.1.0) to get there at all. On top of that: a SIGSEGV inside torch reported against exactly
that pinned combination as recently as May 2025 ([#1376](https://github.com/chaquo/chaquopy/issues/1376)),
a documented risk that this cp38-era build won't even `dlopen()` on newer 16 KB-page devices per
Chaquopy's own changelog, and a 150 MB+ APK tax even restricted to a single `arm64-v8a` variant.

Of the maintainer's five target models, only **MeloTTS-English** clears the torch-version bar on
paper (its dependencies tolerate torch 1.8.1); **F5-TTS, VoxCPM, and Qwen3-TTS all require
torch ≥2.0–2.5**, flatly beyond anything Chaquopy has ever shipped for Android; **Kokoro-82M** is
blocked by its own package's Python ≥3.10 requirement, which Chaquopy's torch (cp38-only) cannot
satisfy. Even the one plausible case (MeloTTS) is unverified in practice, sits on a build with a
recent, unresolved crash report, and would still require hand-porting MeloTTS's own inference code
into the app in Python (bespoke per model, contradicting this codebase's registry/descriptor
design). Meanwhile a working, community-maintained ONNX export already exists for MeloTTS, Kokoro,
**and** F5-TTS — three of the five models — making conversion strictly less work and more reliable
than standing up and maintaining a five-year-stale, crash-prone Python runtime inside the APK for
one, best-case, unverified model.

**Recommendation: do not pursue Chaquopy-bundled torch for any of these five models.** Use the
existing ONNX conversions for MeloTTS, Kokoro, and F5-TTS (all reachable through PhoneTTS's existing
`Runtime`/`VoiceEngine` abstraction with no new architecture). For VoxCPM and Qwen3-TTS, neither
Chaquopy nor conversion currently has a path — leave them in Bucket D (impractical for now) rather
than building bespoke infrastructure to force one uniquely fragile model through.

---

### Sources

- [Chaquopy — Gradle plugin docs (build-time `pip` block, ABI filters, APK size notes)](https://chaquo.com/chaquopy/doc/current/android.html)
- [Chaquopy — change log (Python version support history, AGP support, 16 KB page-size caveat)](https://chaquo.com/chaquopy/doc/current/changelog.html)
- [Chaquopy Android wheel index — `torch/`](https://chaquo.com/pypi-13.1/torch/) (torch 1.4.0, 1.8.1, cp38-only, last updated 2021-05-17)
- [Chaquopy Android wheel index — `torchvision/`](https://chaquo.com/pypi-13.1/torchvision/) (0.9.1 max, cp38-only)
- [Chaquopy Android wheel index — repository root `pypi-13.1/`](https://chaquo.com/pypi-13.1/)
- [chaquo/chaquopy issue #1215 — no torch build for Python 3.9+](https://github.com/chaquo/chaquopy/issues/1215)
- [chaquo/chaquopy issue #1273 — missing torch libs on Python 3.12](https://github.com/chaquo/chaquopy/issues/1273)
- [chaquo/chaquopy issue #247 — `torch_shm_manager` runtime error](https://github.com/chaquo/chaquopy/issues/247)
- [chaquo/chaquopy issue #1376 — Fatal SIGSEGV inside torch on-device (Chaquopy 16.1.0 + torch 1.8.1, arm64-v8a, opened 2025-05-28)](https://github.com/chaquo/chaquopy/issues/1376)
- [chaquo/chaquopy issue #618 — reported real-world app sizes (150 MB+, 90%+ from Chaquopy/Python)](https://github.com/chaquo/chaquopy/issues/618)
- [chaquo/chaquopy torchvision Chaquopy patch (custom cross-compile source)](https://github.com/chaquo/chaquopy/blob/master/server/pypi/packages/torchvision/patches/chaquopy.patch)
- [Android Developers — behavior changes for apps targeting API 29+ (W^X / execve restriction)](https://developer.android.com/about/versions/10/behavior-changes-10)
- [MeloTTS — `requirements.txt` (unpinned torch/torchaudio, pinned `transformers==4.27.4`)](https://github.com/voxos-ai/MeloTTS/blob/master/requirements.txt)
- [transformers v4.27.4 `setup.py` — `torch>=1.7,!=1.12.0`](https://github.com/huggingface/transformers/blob/v4.27.4/setup.py)
- [SWivid/F5-TTS `pyproject.toml` — `torch>=2.0.0`](https://github.com/SWivid/F5-TTS/blob/main/pyproject.toml)
- [VoxCPM installation docs — `torch>=2.5.0`](https://voxcpm.readthedocs.io/en/latest/installation.html)
- [hexgrad/kokoro `pyproject.toml` — `requires-python = ">=3.10, <3.14"`](https://github.com/hexgrad/kokoro/blob/main/pyproject.toml)
- [hexgrad/kokoro `istftnet.py` — uses legacy `torch.nn.utils.weight_norm`](https://github.com/hexgrad/kokoro/blob/main/kokoro/istftnet.py)
- [sherpa-onnx — MeloTTS pretrained models / export scripts](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html)
- [seasonstudio/melotts_zh_mix_en_onnx on Hugging Face](https://huggingface.co/seasonstudio/melotts_zh_mix_en_onnx)
- [onnx-community/Kokoro-82M-v1.0-ONNX on Hugging Face](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX)
- [thewh1teagle/kokoro-onnx on GitHub](https://github.com/thewh1teagle/kokoro-onnx)
- `docs/research/on-demand-python-2026-07.md` (prior research — on-demand/W^X case, confirmed still correct for that narrower question)
- `docs/research/model-triage-2026-07.md` (F5-TTS ONNX port ranked #1 new-engine candidate; VoxCPM/Qwen3-TTS classified Bucket D — no ONNX/GGUF port exists for either)
