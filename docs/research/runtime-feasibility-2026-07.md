# Runtime feasibility - adding backends beyond ONNX (2026-07)

Revisits buckets B ("wrong format") and D ("impractical") of
[`model-triage-2026-07.md`](model-triage-2026-07.md) with the question the maintainer actually
asked: **not** "is this the wrong format," but "can PhoneTTS add a `Runtime` (spec §5.3,
`core/.../runtime/Runtime.kt`) to execute this format on-device, and is it worth it?" The app
already proves the seam is pluggable - `NativeTtsRuntime`
(`core/.../runtime/NativeTtsRuntime.kt`) is a second, non-ONNX backend registered in the same
`RuntimeRegistry` as `OnnxRuntime`, wired end-to-end for CosyVoice3 via a vendored native library
(CrispStrobe/CrispASR, see `docs/COSYVOICE2.md`). That precedent turns out to matter a lot below -
CrispASR is not a single-model port, it is a 34-backend ggml TTS mega-project, and the app already
pays its NDK integration cost.

**Headline correction going in:** the single highest-value move is not a brand-new runtime at all.
It's recognizing that the CrispASR checkout the app already fetches for CosyVoice3
(`scripts/fetch-cosyvoice-ggml.sh`, `app/src/main/cpp/cosyvoice/CMakeLists.txt`) *also* contains
production-ready **Piper**, **Kokoro**, **MeloTTS**, **F5-TTS**, and **Qwen3-TTS** ggml backends,
plus experimental **VoxCPM2**, **OmniVoice**, **VibeVoice**, and **Bark** ports - all behind the same
C ABI shape `NativeTtsRuntime` already models. Linking one more CrispASR target is a much smaller
marginal cost than standing up ExecuTorch, Chaquopy, or a QNN path from scratch, because the
fetch/CMake/JNI plumbing is already proven and merged.

---

## 1. Per-format table

| Format | Android runtime library | Maturity (2026-07) | APK / NDK cost | Fits `Runtime` seam? | Effort | Models unlocked |
|---|---|---|---|---|---|---|
| **GGUF/ggml - additional CrispASR backends** | CrispStrobe/CrispASR (already vendored) | The exact library the app ships for CosyVoice3; Piper/Kokoro/MeloTTS/F5-TTS/Qwen3-TTS backends are marked "production-ready" in its own docs | **Marginal** - same checkout, same CMake pattern, one more `add_subdirectory` target + JNI entry point; no new NDK dependency | Yes - literally the same `NativeTtsRuntime` interface, one more `openTtsSession()` implementation registered by id | **S per backend** (once, for the JNI generalization) then **S each** per additional backend | Piper-GGUF voices (2 repos), Kokoro-voices-GGUF, and - with a conversion step - VoxCPM2/1.5/0.5B, Qwen3-TTS-{0.6B,1.7B}, F5-TTS, k2-fsa-style OmniVoice, bark-small, VibeVoice, neuphonic/neutts GGUF |
| **ExecuTorch (`.pte`)** | `org.pytorch:executorch-android` (Maven Central) | **1.0 released, out of beta** - Meta calls it production-ready; XNNPACK CPU backend is the default path, Vulkan/QNN backends also ship as Maven variants | New AAR dependency (~10s of MB per ABI, unmeasured precisely - no published number found), no custom NDK build needed if the prebuilt AAR is used | Yes - a straightforward `Runtime`/`InferenceSession` implementation over the JNI `Module`/`EValue` API; closer to the ONNX shape than to `NativeTtsRuntime` | **M** - new runtime class, new frontend work per model (ExecuTorch has no shared "TextFrontend" convention) | `software-mansion/react-native-executorch-kokoro` **today**, confirmed by direct inspection (see §2) - a second, independent Kokoro path |
| **PyTorch Mobile / libtorch-android** | `org.pytorch:pytorch_android` | **Deprecated.** PyTorch's own docs state "PyTorch Mobile is no longer actively supported" and point to ExecuTorch | N/A - do not adopt | N/A | - | None recommended |
| **Chaquopy (embedded CPython + PyTorch)** | `com.chaquo.python` Gradle plugin | Mature *as a Python-on-Android bridge* (used in production apps for years); **`torch` itself is an immature, fragile addition** to Chaquopy's package repo (added ~v7.0.2) with open GitHub issues about missing libraries and version-pinned breakage on recent Python levels | Python interpreter itself: several MB per included ABI; a full `torch` wheel for arm64 adds **hundreds of MB** per ABI (unquantized fp32 torch + a TTS model's weights bundled raw) - the least APK-size-disciplined option here by a wide margin | Awkward - `synthesize()` would shell out to embedded Python per call; doesn't map cleanly onto `InferenceSession`'s tensor-in/tensor-out contract, more like a third, `Process`-shaped seam | **L** - and higher long-term maintenance risk (an interpreter + a fragile ML-package build, not a single native `.so`) | Any raw-PyTorch checkpoint in bucket B, in principle - but at real cost |
| **TFLite / LiteRT** | `com.google.ai.edge.litert` (renamed from TF Lite, Sept 2024; same runtime/format) | Mature, actively developed, Google's primary Android on-device ML runtime; has its own QNN NPU delegate path | Comparable to ONNX Runtime Mobile (10s of MB); already a "boring, safe" choice | Yes - same `InferenceSession` shape as `OnnxRuntime` | **S-M** if a `.tflite` model is ever downloaded - **but see verdict below: none currently are** | None found in the triaged 125-model set |
| **MLX (`safetensors` for Apple Silicon)** | - | **No Android build exists or is planned.** MLX runs only on macOS with Apple Silicon (confirmed: "No iOS, no Android, no Linux, no Windows") | N/A | N/A | N/A | **Zero** - genuinely Apple-only |
| **CoreML (`.mlpackage`/`.mlmodelc`)** | - | **iOS/macOS-only by design**; no Android runtime, official or unofficial, loads these containers | N/A | N/A | N/A | **Zero** - genuinely Apple-only |
| **Qualcomm QNN (`.bin` context binaries)** | `com.microsoft.onnxruntime:onnxruntime-android-qnn` (ORT's QNN execution provider) **or** raw `com.qualcomm.qti:qnn-runtime` | ORT-QNN is a real, small (6.5 MB AAR at 1.22.0, vs. 28.8 MB for the CPU-only `onnxruntime-android` the app already ships) Maven artifact; genuinely usable | Requires the Qualcomm AI Engine Direct SDK for anything beyond the prebuilt EP; **only functions on Snapdragon SoCs with the matching HTP version** - the A16 (typically Helio, MediaTek/Unisoc-class in that price tier) almost certainly has none | Partially - if the downloaded `.bin` is a QNN *context binary* (pre-compiled for one exact chip+SDK version), it isn't a portable "model" at all, it's a device-specific artifact; doesn't fit the "download once, run on any registered device" assumption the rest of the app makes | **M**, and narrow | Only the QNN-specific repos, only on matching Snapdragon phones |
| **Mobilint (`.mxq`) / Axera (`.axmodel`)** | Vendor SDKs, not on Maven, not public in the way QNN is | These are **embedded-SoC accelerator chips for cameras/dev boards** (Axera ships on SBCs like the Sipeed M4/M4N; Mobilint targets edge-AI boxes), **not a chip family found in any Android phone** | N/A - there's no phone to target | N/A | N/A | **Zero on phones** - not a "narrow" NPU case like QNN, a categorically wrong hardware family |

---

## 2. GGUF/ggml - how much more it unlocks (the big finding)

`docs/COSYVOICE2.md` documents CrispASR (CrispStrobe/CrispASR, MIT, a whisper.cpp fork) purely as
"the CosyVoice3 native runtime." Reading its own `docs/tts.md`, that undersells it: **CrispASR ships
34 TTS backends** behind one C ABI, and several are architectures already in the bucket-B/D triage:

- **Production-ready in CrispASR's own words:** Kokoro, **Piper**, Qwen3-TTS, MeloTTS, F5-TTS.
- **Specialized/experimental but present:** Chatterbox, IndexTTS-1.5, VibeVoice, Bark, and (per its
  `models/` conversion-script directory) **`convert-omnivoice-to-gguf.py`** /
  `convert-omnivoice-tokenizer-to-gguf.py`, plus a listed `voxcpm2` backend.

Concretely, `cstr/piper-voices-GGUF`'s own README (inspected directly) states it was produced by
CrispASR's `models/convert-piper-to-gguf.py`, targets `arch=piper` in the **same** runtime the app
already vendors, and is invoked with `crispasr --backend piper -m <voice>.gguf --tts "..."`. That is
not a hypothetical "someone could write a Piper-ggml runtime" - **the runtime already exists, in the
tree PhoneTTS already fetches**, and the conversion script that produced two of the triaged repos
(`cstr/piper-voices-GGUF`, `cstr/piper-en_US-lessac-medium-GGUF`) is public in that same repo.

`cstr/kokoro-voices-GGUF` is very likely the analogous `convert-kokoro-to-gguf.py` output for the
same reason (not independently re-fetched here, but the pattern matches).

**What this means for effort:** the app's `-PwithCosyVoice` build already does the hard part - fetch
the CrispASR checkout, cross-compile ggml for arm64 with the NDK, JNI-bridge a `synthesize(text,
voice) → PCM` call. Adding a second CrispASR backend (say, `piper`) is:

1. One more `add_subdirectory`/`target_link_libraries` line in the existing CMakeLists (link the
   `piper` ggml target instead of, or alongside, `cosyvoice3_tts`).
2. One more JNI entry point (or a `backend` parameter on the existing one).
3. A thin `PiperGgmlEngine` on the Kotlin side that `inspect()`s the GGUF `arch=piper` tag and hands
   off to the already-defined `NativeTtsRuntime`/`NativeTtsSession` contract - no new `:core`
   interface needed at all.

That is genuinely **S** effort per additional backend once the JNI is generalized past a single
hardcoded "cosyvoice" id (itself an **S** refactor) - smaller than any other row in the table above,
because it reuses proven plumbing instead of adding a new one.

**Caveats, to stay honest:**
- CrispASR's backend list does not by itself prove every one of *the maintainer's specific*
  downloaded files converts cleanly - `voxcpm2`/`omnivoice`/`bark` are labeled "specialized /
  experimental," not "production-ready," and no converter run was verified here (unlike the Piper
  one, whose README and companion script were read directly). Treat those as "a real path exists,
  worth prototyping," not "proven," pending an actual conversion + `run_cosy_native.sh`-style verify
  script for that backend.
- A GGUF conversion of an unquantized checkpoint (e.g. `openbmb/VoxCPM2`) still needs to be
  *produced* (F16 or a quantization level) - this is the honest "adding a runtime that can execute
  this format" story the task asked for, not a "download a different, already-quantized repo"
  shortcut, which the maintainer explicitly ruled out.
- `neuphonic/neutts-air-q{4,8}-gguf` (already bucket C rank 4) and VibeVoice's own GGUF ports
  (`vibevoice.cpp`, MIT, LocalAI org) are separate, already-GGUF projects, not part of CrispASR -
  they'd each need their own small `NativeTtsRuntime` implementation (or, if they also expose a
  CrispASR-style unified ABI, could ride the same bridge). `vibevoice.cpp` is real, active, and
  ggml-based - a second good ggml-runtime candidate independent of CrispASR.

---

## 3. ExecuTorch - verified concretely runnable today

Fetched `software-mansion/react-native-executorch-kokoro`'s file listing directly. It contains:

- `xnnpack/{standard,german,polish}/{duration_predictor,synthesizer}_*.pte` - the actual Kokoro
  graph split into two static-shape ExecuTorch programs (~62 MB + ~272 MB each), exported for the
  XNNPACK CPU backend, ExecuTorch v1.0.0.
- `phonemizer/<lang>/phonemizer_<lang>.pte` (~7 MB each) - a second ExecuTorch program for G2P.
- `voices/*.bin`, each **exactly 522,240 bytes** = `510 × 256 × 4` bytes - confirmed to match the
  `[510, 256]` fp32 voice-embedding layout PhoneTTS's own Kokoro engine already expects
  (`engines/kokoro`), per the earlier triage note. These voice files are directly reusable.

**Maturity:** ExecuTorch reached **1.0 in 2026**, explicitly described by PyTorch as the official
exit from beta; `org.pytorch:executorch-android` is on Maven Central, no source NDK build required
for the common CPU (XNNPACK) path. This is a real, current, maintained Android runtime - not a
research toy. PyTorch Mobile, its predecessor, is explicitly deprecated in PyTorch's own docs
("no longer actively supported... check out ExecuTorch").

**Verdict:** worth adding as a third `Runtime` if the maintainer wants a second, independent Kokoro
path (or other `.pte`-shipped models) - Effort **M**: new `Runtime`/`InferenceSession`-shaped
wrapper over ExecuTorch's JNI `Module`/`EValue` API, plus reusing the existing Kokoro
`TextFrontend`/voice-loading code where the tensor shapes line up. Static input shapes
(32/64/128 tokens) mean the engine needs simple text chunking/padding logic, not present in the
ONNX Kokoro path.

## Raw PyTorch, more broadly - libtorch and Chaquopy

- **`org.pytorch:pytorch_android` (libtorch-android / TorchScript)**: dead end. PyTorch's own
  documentation states PyTorch Mobile is no longer actively supported and directs users to
  ExecuTorch. Do not build on it.
- **Chaquopy-embedded Python + PyTorch**: technically possible, honestly assessed as the **worst
  cost/benefit** option here. Chaquopy itself (the Python-on-Android bridge) is mature and has run
  in production apps for years. The problem is specifically **`torch` as a Chaquopy package**:
  it was added to Chaquopy's curated repo around v7.0.2, but public GitHub issues report missing
  libraries and breakage tied to specific Python levels - it reads as "recently made possible," not
  "battle-tested." Cost: the Python interpreter itself adds several MB per ABI even empty; a real
  `torch` wheel plus a raw fp32/bf16 checkpoint (the exact files in bucket B) would add **hundreds of
  MB per ABI** with no quantization discipline unless the maintainer hand-rolls one, and Chaquopy's
  `synthesize()` call would be a Python subprocess-shaped call, not the tensor-in/tensor-out
  `InferenceSession` contract - it would need its own new seam abstraction, not a clean
  `Runtime` implementation, arguably breaking the "no second synthesis path" spirit of rule 3 unless
  wrapped very carefully. **Assessment: real, but Large effort, real APK-size and RAM cost, and the
  library-maturity story for `torch`-in-Chaquopy specifically is genuinely shaky** - not a case of
  underclaiming feasibility to avoid work; it is feasible, just the worst-value entry in this table.

## 4. TFLite / LiteRT

Google renamed TensorFlow Lite to **LiteRT** in September 2024 (same runtime, same `.tflite` file
format, new brand + broader multi-framework ambitions, including a dedicated Qualcomm NPU delegate
path). It's mature and well-maintained. **However: none of the 125 triaged models ship a `.tflite`
file.** There is no reclassification to make here - the honest verdict is simply "a good runtime,
zero models in the current download set need it," so it is **not** recommended as a near-term add;
revisit only if the maintainer downloads something that actually ships `.tflite`.

## 5. MLX - definitive verdict

**MLX does not run on Android, full stop, and will not in any near-term future.** It is an
Apple-Silicon-only array framework (macOS + Apple GPUs/Neural Engine via Metal); there is no Linux,
Windows, or Android backend, and none is on its roadmap. Every `mlx-community/*` repo in bucket B
(the ~18 `kitten-tts-*` variants, `Kokoro-82M-bf16`, `MeloTTS-English-{MLX,v3-MLX}`,
`Qwen3-TTS-12Hz-0.6B-Base-4bit`) is **permanently unsupportable by PhoneTTS as downloaded** - not a
"hard effort," a **zero-path** format for this app. (Every one of these architectures already has, or
is getting, a real ONNX/GGUF sibling elsewhere in the triage - that's the actual path, not this
container.)

## 6. CoreML - definitive verdict

Same shape of answer: **CoreML (`.mlpackage`/`.mlmodelc`) is iOS/macOS-only by Apple's own design.**
No Android runtime exists, official or third-party, that loads these containers. `kensora/kokoro-coreml`,
`aufklarer/Kokoro-82M-CoreML`, `aufklarer/Supertonic-3-CoreML` are **zero-path** on Android for the
same reason as MLX - again, each has a real ONNX-format sibling that is the actual path forward.

## 7. NPU-vendor formats

Three genuinely different situations were bucketed together in the prior triage as "NPU-vendor" -
they deserve different verdicts:

- **Qualcomm QNN (`runanywhere/melotts_en_HNPU`)**: the *only* one of the three where "add a runtime"
  is even coherent, because Snapdragon phones are common and ONNX Runtime already ships a QNN
  execution-provider AAR (`onnxruntime-android-qnn`, 6.5 MB - smaller than the CPU-only build the app
  already carries). But two caveats keep this narrow: (1) if the downloaded `.bin` is a **precompiled
  QNN context binary**, it is baked for one exact chip + QNN SDK version, not a portable model the
  resolver can hand to any registered device the way ONNX/GGUF files are - that breaks a soft
  assumption the rest of the app makes (one file works on every device once downloaded); (2) the A16
  (this app's baseline target) is not a Snapdragon phone with a matching HTP, so this would need to
  be an opportunistic accelerator path, off by default, benefiting only a subset of higher-end
  Snapdragon users - not a baseline feature. **Verdict: worth a follow-up spike (Effort M, narrow
  payoff) if/when a Snapdragon device is the target, not a priority now.**
- **Axera (`AXERA-TECH/MeloTTS`, `.axmodel`)**: Axera's NPUs ship in embedded camera/SoC boards
  (e.g. the Sipeed M4/M4N), **not in any Android phone**. This is not a "the A16 lacks the chip"
  device-tier problem - it's a categorically wrong hardware family. **Verdict: zero-path for a phone
  app**, full stop, independent of which phone.
- **Mobilint (`mobilint/MeloTTS-English-v3`, `.mxq`)**: same verdict as Axera - Mobilint targets
  edge-AI accelerator boxes, not phone SoCs. **Zero-path.**

---

## 8. RAM-tier reclassification of bucket D (and the large bucket-B stragglers)

CLAUDE.md's own framing: a 4 GB phone leaves **~2-2.5 GB usable** for an app after the OS. Peak RAM
was estimated from the actual on-disk (unquantized) weight size using two calibration points already
in the codebase's own `ResourceCost` estimates: **non-AR single-graph ONNX models run ~1.3-1.5×**
their weight size at peak (F5-TTS: ~650 MB-1.3 GB weights → `PEAK_RAM_MIB = 1400`), while
**multi-stage AR/LLM-style pipelines run ~1.8-2.4×** (CosyVoice3's four-GGUF combo: 745 MB on disk →
`PEAK_RAM_MIB = 1800`, a 2.4× multiplier, from KV-cache + multiple loaded stages). File sizes below
are directly measured from each repo's Hugging Face listing (not the prior doc's rounded figures).

All sizes below are decimal GB (10⁹ bytes), measured directly from each repo's file listing (sum of
weight files only - tokenizer/vocab tables are excluded as immaterial to peak RAM). "AR-ish
multiplier" (1.8-2.4×) is applied to multi-stage/autoregressive architectures; "single-graph
multiplier" (1.3-1.5×) is not used below because every model in this table is multi-stage/AR.

| Model | Measured weight size | Architecture shape | Est. peak RAM (×1.8-2.4) | Tier verdict | Real blocker |
|---|---|---|---|---|---|
| `nineninesix/gepard-1.0` | **1.11 GB** (`model.safetensors`) | "exotic hybrid" LM backbone + NanoCodec (AR-ish) | **~1.6-2.1 GB** (lower multiplier used, 1.4-1.9×, given its own docs call it lighter-weight than a full LLM stack) | **Fits the 4 GB tier, tight** - CORRECTED from the prior doc's blanket "too heavy" call, which lumped it in with the 5 GB VoxCPM2. It is not too big. | No ONNX/GGUF port exists anywhere; unproven, exotic architecture. This is an *arch/no-port* problem, not a RAM problem. |
| `openbmb/VoxCPM-0.5B` | **1.61 GB** (`pytorch_model.bin` + `audiovae.pth`) | LM backbone + audio codec (AR-ish) | **~2.9-3.9 GB** | **Needs 6 GB tier** (tight there, comfortable at 8 GB+) | No ONNX/GGUF port confirmed at S/M effort; CrispASR lists a `voxcpm2`-family backend (experimental, unverified - see §2) as the closest real lead |
| `openbmb/VoxCPM1.5` | **1.95 GB** (`model.safetensors` + `audiovae.pth`) | same shape, larger | **~3.5-4.7 GB** | **Needs 6-8 GB tier** | Same as above |
| `openbmb/VoxCPM2` | **4.96 GB** (`model.safetensors` + `audiovae.pth`) | same shape, largest | **~8.9-11.9 GB** | **Needs 8-12 GB tier** (flagship-class phones only) | Same as above - CORRECTED to a precise number rather than the prior rounded "~5 GB"; still genuinely a flagship-only model regardless of port status |
| `Qwen/Qwen3-TTS-12Hz-0.6B-Base` | **1.83 GB** (`model.safetensors`) | Speech-LLM (AR transformer + codec) | **~3.3-4.4 GB** | **Needs 6-8 GB tier** | No ONNX/GGUF port at S/M effort was found independently; CrispASR's `Qwen3-TTS` backend is marked *production-ready* in its own docs (see §2) - this is the strongest "worth converting" candidate in this table |
| `Qwen/Qwen3-TTS-12Hz-1.7B-*` | **3.86 GB** (`model.safetensors`) | same, larger | **~6.9-9.3 GB** | **Needs 8-12 GB tier** | Same lead as above, but the size makes it a poor fit for the app's stated budget-hardware focus regardless |
| `k2-fsa/OmniVoice` | **2.45 GB** (`model.safetensors`) | AR LM + audio codec | **~4.4-5.9 GB** | **Needs 6-8 GB tier** | No confirmed ONNX port; CrispASR's `convert-omnivoice-to-gguf.py` is a real lead (unverified whether it targets this exact checkpoint - see §2 caveats) |
| `coqui/XTTS-v2` | **2.08 GB** (`model.pth` + `dvae.pth`) | GPT-2-style AR + HiFi-GAN | **~3.7-5.0 GB** | **Needs 6-8 GB tier** | No maintained on-device (ONNX/GGUF) port found; XTTS's GPT-AR core is the same category of export problem CosyVoice2's LLM stage was |
| `suno/bark-small` | **1.68 GB** (`pytorch_model.bin`) | 3-stage AR (semantic→coarse→fine) | **~2.4-3.2 GB** (lower multiplier, 1.4-1.9×: three separate small AR stages run sequentially rather than one stage with a large combined KV cache) | **Fits 4 GB tier RAM-wise (tight) to 6 GB comfortably** - CORRECTED: this is smaller than the prior "too heavy" framing implied | RAM is *not* actually the blocker here - CrispASR lists a `bark` backend, but Bark's own 3-stage autoregressive decode is notoriously slow even on desktop GPUs; on a 4 GB phone CPU this is a **latency** problem (likely minutes per sentence), not a memory one. Worth stating precisely: "too slow," not "too big." |

**Framing correction for the UI, concretely:** the app's existing per-model RAM hint
(`ModelManagementScreen.kt`'s `ramHint()`) already does the right, honest thing - it compares a
model's `ResourceCost.approxPeakRamBytes` against *this device's* actual free RAM
(`DeviceInfo.availableRamBytes`) and shows a non-blocking `"Est. RAM: ~X · may not fit - you can
still try"` rather than a hardcoded tier cutoff. That per-device comparison is the right long-term
mechanism and should stay. What's missing is a **pre-download, no-device-yet** version of the same
honesty for the catalog/browse screen (extending issue #89's planned badge) - since there's no
"this device" to compare against before a model is even downloaded, that surface should show the
tier-framed message the task asked for: **"Needs ~3.2 GB RAM - comfortable on 6 GB+ phones, tight on
4 GB."** Same underlying `ResourceCost` field, just a second, catalog-appropriate rendering - not a
new fact, so it doesn't violate the SSOT rule.

---

## 9. Reclassification summary (bucket B / D → corrected buckets)

**(i) Supportable by adding a runtime, ranked by effort:**

| Models | Runtime to add | Effort |
|---|---|---|
| `cstr/piper-voices-GGUF`, `cstr/piper-en_US-lessac-medium-GGUF` | CrispASR's already-vendored `piper` ggml backend (generalize the existing JNI bridge past a hardcoded "cosyvoice" id) | **S** |
| `cstr/kokoro-voices-GGUF` | Same CrispASR `kokoro` backend, same generalization | **S** |
| `software-mansion/react-native-executorch-kokoro` | ExecuTorch (`org.pytorch:executorch-android`, Maven Central, 1.0/production) | **M** |
| `Qwen/Qwen3-TTS-12Hz-{0.6B,1.7B}-*` (raw safetensors) | CrispASR's `Qwen3-TTS` backend (production-ready per its docs) - needs a GGUF conversion run + RAM-tier gating (6-12 GB, see §8) | **M** |
| `openbmb/VoxCPM-{0.5B,1.5,2}` | CrispASR's experimental `voxcpm2`-family backend - unverified, worth a spike; RAM-tier gates 0.5B at "tight-4GB" up to 2 at "8-12GB only" | **M**, unverified |
| `k2-fsa/OmniVoice` | CrispASR's `convert-omnivoice-to-gguf.py` - unverified against this exact checkpoint | **M**, unverified |
| `suno/bark-small` | CrispASR's experimental `bark` backend - RAM fits 4-6 GB, but expect minutes-per-sentence latency; file-export-only, same posture as CosyVoice2/3 | **M**, unverified, likely low daily value |
| `microsoft/VibeVoice-Realtime-0.5B`, VibeVoice `.gguf` siblings | Already bucket C rank 2 - `vibevoice.cpp` (independent of CrispASR, also ggml/MIT, active) | **M** (already tracked) |
| `runanywhere/melotts_en_HNPU` (QNN) | `onnxruntime-android-qnn` execution provider - only if the model is portable ONNX+QNN-EP rather than a precompiled context binary, and only benefits Snapdragon devices | **M**, narrow payoff |

**(ii) Genuinely Apple-only / no Android path (drop the "wrong format, might still be fixable"
framing - these are permanent zero-paths, not effort-ranked at all):**

All `mlx-community/*` repos (MLX, ~18 kitten-tts variants + Kokoro-82M-bf16 + MeloTTS-English-{MLX,
v3-MLX} + Qwen3-TTS-12Hz-0.6B-Base-4bit) and all CoreML repos (`kensora/kokoro-coreml`,
`aufklarer/Kokoro-82M-CoreML`, `aufklarer/Supertonic-3-CoreML`). Every one of these architectures
already has (or is getting) a real ONNX/GGUF sibling elsewhere in the triage - that sibling, not a
new runtime, is the actual path to supporting these models in PhoneTTS.

**(iii) Too large for a given device tier (RAM number + phone tier needed, per §8):**

- **Fits 4 GB tier (tight), blocker is arch/no-port not RAM:** `nineninesix/gepard-1.0` (~1.6-2.1 GB
  est. peak) - this is the direct correction the maintainer asked to see: the prior doc's blanket
  "too heavy" dismissal was wrong on the RAM axis for this specific model.
- **Fits 4-6 GB tier RAM-wise, blocker is latency not RAM:** `suno/bark-small` (~2.4-3.2 GB est.
  peak, but 3-stage AR decode is slow enough that file-export-only, minutes-per-sentence is the
  realistic ceiling regardless of RAM headroom).
- **Needs 6-8 GB tier:** `openbmb/VoxCPM-{0.5B,1.5}` (~2.9-4.7 GB), `Qwen/Qwen3-TTS-12Hz-0.6B-Base`
  (~3.3-4.4 GB), `k2-fsa/OmniVoice` (~4.4-5.9 GB), `coqui/XTTS-v2` (~3.7-5.0 GB).
- **Needs 8-12 GB tier (flagship-class only):** `openbmb/VoxCPM2` (~8.9-11.9 GB),
  `Qwen/Qwen3-TTS-12Hz-1.7B-*` (~6.9-9.3 GB).

Also unchanged from the prior triage (no new evidence found to revise): `RiricOFF/piper-checkpoints-*`
and `ayousanz/piper-plus-base` remain mid-training `.ckpt` snapshots, not finished inference-ready
voices - a training/export problem, not a runtime problem, regardless of format. Raw-PyTorch
siblings of architectures the app already runs in ONNX (`hexgrad/Kokoro-82M`,
`myshell-ai/MeloTTS-English{,-v2}`, `facebook/mms-tts-eng`, `microsoft/speecht5_tts`,
`neuphonic/neutts-nano`) still don't need a new runtime - the ONNX sibling already claimed elsewhere
in the triage is strictly the cheaper path.

---

## 10. Ranked recommendation

1. **Generalize the CrispASR JNI bridge to support multiple backends, starting with `piper`.**
   Highest value-per-effort in this entire report: reuses NDK plumbing the app already ships and
   maintains for CosyVoice3, no new external dependency, no new APK-size line item beyond what
   `-PwithCosyVoice` already costs. Unlocks the two `cstr/piper-*-GGUF` repos immediately (Effort S)
   and opens a credible, low-marginal-cost path to `kokoro-voices-GGUF`, and - pending a verification
   spike analogous to `run_cosy_native.sh` - Qwen3-TTS, VoxCPM2, OmniVoice, and Bark. **Tradeoff:**
   all of this is still gated behind the opt-in `-PwithCosyVoice` NDK build; it does nothing for
   users who don't build with the NDK, and every new backend needs its own verify script before it's
   trusted (CrispASR's own "production-ready" label isn't proof for this app's specific use).

2. **Add ExecuTorch as a third `Runtime`.** It is the one format in this report that is (a) verified
   runnable via a public, current, Maven-distributed Android library, (b) production-grade per its
   own 1.0 release, and (c) has a concrete model ready today
   (`react-native-executorch-kokoro`, voice `.bin` layout confirmed byte-for-byte compatible with the
   app's own Kokoro voice format). **Tradeoff:** a genuinely new native dependency (new AAR, new JNI
   surface, no shared code with `OnnxRuntime`), and static-shape `.pte` graphs (32/64/128 tokens)
   mean the engine needs its own text-chunking logic. Worth doing specifically because it's a second,
   independent Kokoro implementation with no NDK cross-compilation of the app's own code required
   (unlike CrispASR, which the app builds from source).

3. **Do not add Chaquopy/PyTorch-in-Python, MLX, CoreML, Mobilint, or Axera.** The first is
   technically possible but is this report's worst cost/benefit entry (Large effort, hundreds of MB
   per ABI, a fragile `torch` package, and an awkward non-tensor seam that strains rule 3's "one
   generation path"). The other four are not "hard," they are **zero-path on any Android phone** -
   spending any engineering time chasing them would be pure waste. **QNN is a narrow, defer-able
   fourth option**: real and Maven-available, but only benefits Snapdragon-class phones the baseline
   A16 target doesn't have, and only if the downloaded artifact turns out to be portable (not a
   device-pinned context binary) - worth a spike only once a Snapdragon device is an actual target,
   not now.

**Net effect on the triage:** roughly a third of bucket B (the GGUF and ExecuTorch repos) moves from
"wrong format, no fix" to "supportable, S-M effort." The MLX/CoreML two-thirds of bucket B is
correctly a permanent zero-path - but every one of those models already has a real path via its
ONNX/GGUF sibling elsewhere in the catalog, so no user-visible capability is actually lost. Bucket D
splits cleanly into "genuinely too large for any but a flagship phone" (VoxCPM2, Qwen3-TTS-1.7B) and
"the earlier 'too heavy' call was wrong on the RAM axis" (gepard-1.0, bark-small) - the real blockers
for the corrected models are architecture/port availability and, for Bark specifically, latency, not
memory.
