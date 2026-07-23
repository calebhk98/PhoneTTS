# Research: On-demand Python + PyTorch for a sideloaded Android app (2026-07)

**Question:** Can PhoneTTS, a sideloaded (non-Play-Store) offline Android app, download Python and
PyTorch *at runtime* into app-private storage and use that to run a raw `.pth` checkpoint (e.g.
`myshell-ai/MeloTTS-English`) directly, instead of converting the model to ONNX/ggml/ExecuTorch?

**Verdict up front: no - not realistically, for the standard reason people assume it "must already
exist."** Two independent, hard blockers stack on top of each other: (1) Android will not let a
normal app execute or dlopen code it downloaded into its own writable storage, and (2) PyTorch
does not publish a wheel that would run on Android's C library even if you got past (1). Neither is
a maturity gap that will close with more searching - they are the platform's design. Details and
sources below.

---

## 1. Chaquopy: build-time only, and Play-Store-only for on-demand delivery

Chaquopy is the most mature "Python inside an Android app" SDK and the closest thing to what the
maintainer is picturing. It is **strictly a build-time mechanism**:

- Python packages are declared in the `pip { install "..." }` block of `app/build.gradle.kts` and
  are downloaded, cross-compiled (for packages with native code) and **baked into the APK** during
  `assemble`. There is no supported API for fetching a new package after the app is installed and
  running - the whole point of the Gradle plugin is that `pip` runs on the *build machine*, not the
  phone. [Chaquopy Android docs](https://chaquo.com/chaquopy/doc/current/android.html)
- Chaquopy maintains its **own private wheel repository** of Android-cross-compiled packages
  (`pypi.chaquo.com`) because ordinary PyPI wheels are built for glibc Linux/Windows/macOS and
  won't run on Android (see §3). A package only works if either it's pure Python, or Chaquopy has
  specifically cross-compiled and published it.
- **Torch on Chaquopy is present but unofficial-grade and flaky, not "supported."** Chaquopy's own
  repo does carry `torch`/`torchvision` build recipes
  ([`server/pypi/packages/torchvision/patches/chaquopy.patch`](https://github.com/chaquo/chaquopy/blob/master/server/pypi/packages/torchvision/patches/chaquopy.patch)),
  but open issues show it breaking across Python-version bumps and at runtime:
  - [#1215](https://github.com/chaquo/chaquopy/issues/1215) - no torch build for Python 3.9+, open/unresolved.
  - [#1273](https://github.com/chaquo/chaquopy/issues/1273) - "Missing torch libraries in python 3.12."
  - [#247](https://github.com/chaquo/chaquopy/issues/247) - `RuntimeError: Unable to find torch_shm_manager` at runtime.
  - [#1376](https://github.com/chaquo/chaquopy/issues/1376) - `SIGNAL 11` (segfault) inside the torch library on-device.

  This is the pattern you'd expect from a volunteer/small-team cross-compile of a huge, actively-changing
  native project: it works for some snapshot of (Python version, torch version, device ABI) and
  breaks on the next bump. Not something to build a "personal use, must keep working" app on.

- **Play Feature Delivery (the mechanism that would let a Chaquopy Python "module" be downloaded
  on demand instead of baked into the base APK) is a Google Play Store feature.** Dynamic feature
  modules are delivered by the Play Store's serving infrastructure; other stores/sideload paths
  (F-Droid-style APK install, Amazon Appstore, adb install, GloballyDynamic-style private
  servers) don't get this for free - "the core limitation is that dynamic feature modules
  fundamentally rely on Google Play Store's infrastructure to function, which prevents sideloading
  in the traditional sense." [Overview of Play Feature Delivery](https://developer.android.com/guide/playcore/feature-delivery),
  [discussion](https://proandroiddev.com/mastering-android-dynamic-feature-module-delivery-1-3-3cf08afd1e42)
  A sideloaded app could self-host the equivalent (an in-house "GloballyDynamic" clone that
  downloads a signed split APK and installs it via `PackageInstaller`), but that's a research
  project of its own, and it still bottoms out on Chaquopy's build-time pip model for *what's
  inside* that split - you'd have to pre-bake and host your own split builds, not `pip install`
  torch on the phone.

**Conclusion for Chaquopy:** it's build-time only; the closest thing to "on demand" (Play Feature
Delivery) is Play-Store-gated and unavailable to a sideloaded app; and even when torch is baked in
at build time it's an unofficial, frequently-broken cross-compile, not a package Chaquopy commits
to supporting.

## 2. Downloading a prebuilt CPython interpreter and running it via subprocess/embed

Setting Chaquopy aside, could PhoneTTS fetch a standalone CPython build (ELF binary + shared libs)
into app-private storage after install and just exec/dlopen it? Survey of what exists:

| Option | What it actually does | Runtime-downloadable? |
|---|---|---|
| **Termux / termux-packages** | Ships a full Bionic-libc-targeted Python via its own `pkg` (apt-like) package manager, `.deb`-style archives with Android-specific patches. | Yes, packages install after Termux is running - but Termux **is** the app being downloaded into; PhoneTTS can't just borrow its exec path (see W^X below), and would have to reimplement its whole execution workaround. |
| **python-for-android (Kivy toolchain)** | Cross-compiles CPython + deps and **bundles the interpreter inside the APK at build time**; it is a packager, not a runtime downloader. No documented runtime-fetch mode. |No |
| **BeeWare/Briefcase** | Same shape as p4a: bundles a Python runtime into the APK at packaging time. | No |
| **Chaquopy "standalone" builds** | Same build-time model as above (§1). | No |
| **Pydroid 3** | A full closed-source Python IDE *app* (not an embeddable SDK) that does let its own users `pip install` from a **prebuilt-wheel repository it hosts**, including torch, after the app is installed. This is real evidence the general idea "works" for someone - but it's not a library PhoneTTS could embed: it's a separate monolithic app, its wheel repo and native-lib loading trick are proprietary/undocumented, and it doesn't solve "ship this inside my own APK." |
| **A generic "cpython-android" prebuilt artifact** | No canonical, actively-maintained prebuilt CPython-for-Android binary distribution exists outside the above toolchains' own build pipelines; nothing analogous to `python.org`'s official installers exists for Android. | - |

### The real blocker: Android's W^X (write XOR execute) restriction

Even if PhoneTTS fetched a working Bionic-linked Python interpreter and native `.so` extension
modules, **Android will not let a normal, non-rooted app execute a file it downloaded into its own
writable storage**, once the app targets API 29 (Android 10) or higher:

> "Execution of files from the writable app home directory is a W^X violation... Untrusted apps
> that target Android 10 cannot invoke `execve()` directly on files within the app's home
> directory." - [Android 10 behavior changes, apps targeting API 29+](https://developer.android.com/about/versions/10/behavior-changes-10)

This is enforced by SELinux (`app_data_file` execute denial) and is **gated by the app's own
`targetSdkVersion`, not by whether it came from Play or was sideloaded** - it's an OS/kernel-level
policy, so sideloading doesn't exempt PhoneTTS from it. The same restriction extends in practice to
`dlopen()`-ing native `.so` shared libraries out of app-private storage (torch's actual weight is
almost entirely in `libtorch.so` and friends, not in `.py` files) - Google's own guidance is "apps
should load only the binary code that's embedded within an app's APK file."

Termux hit exactly this wall and had to build a real workaround: **`termux-exec`**, an
`LD_PRELOAD` shim that intercepts the `exec()` family and routes execution through the system
linker (`/system/bin/linker64`), which SELinux does permit, instead of calling `execve()` on the
app-data file directly. [Termux execution environment wiki](https://github.com/termux/termux-packages/wiki/Termux-execution-environment) -
this is a fragile, Termux-specific hack tied to their whole packaging pipeline, not a general
mechanism PhoneTTS could casually reuse for arbitrary downloaded native code, and it addresses
process *execution*, not the `dlopen()` case a Python C-extension-heavy stack like torch needs.

**The one theoretical dodge - and why it's not worth taking:** the restriction is gated by
`targetSdkVersion < 29`. Since PhoneTTS sideloads (skipping Play's target-API minimums), it could
in principle ship at a target SDK below 29 to keep the old (permissive) exec behavior, and current
Android versions still allow installing such an app (Android 14+ only blocks
`targetSdkVersion < 23`, and the next threshold floated is `< 24` -
[Android 14 targetSdk floor](https://www.androidpolice.com/android-14-may-not-let-you-install-outdated-apps-anymore/)).
But this would mean regressing the **entire app** - not just one engine - off a decade of Android
permission, storage, notification, and background-execution models to unlock exec for one
experimental feature. That's a project-wide architectural cost for a single engine's convenience,
squarely against the "budget hardware, but must run unmodified on better/worse phones" and
security-conscious posture this codebase already has. Not recommended.

## 3. The PyTorch wheel problem - the real crux

Independent of the exec restriction: **PyTorch does not publish, and has never published, an
Android wheel.** Checking the current PyPI release (`torch` 2.13.0) directly, the available wheel
platform tags are:

- `manylinux_2_28_x86_64` (Linux x86-64, glibc)
- `manylinux_2_28_aarch64` (Linux **server** ARM64, glibc)
- `macosx_14_0_arm64`
- `win_amd64`

No `android` tag exists, and none ever has. The `manylinux_..._aarch64` wheel that superficially
looks like "ARM64 torch" is built against **glibc** and Linux's syscall/dynamic-linker ABI; Android
uses **Bionic libc**, a different dynamic linker, different paths (no `/lib`, no `/usr`), and a
sandboxed process model. A glibc manylinux wheel simply will not load on Android - it's not a
"probably works" gray area, it's a different, incompatible platform underneath the same CPU
architecture. This is exactly why Chaquopy has to **cross-compile its own torch build from
source** for Android rather than just pip-installing the PyPI wheel (§1) - and why that build is
the fragile, frequently-broken one referenced above.

People who actually want torch running *inside* Termux on Android build it from source **on the
phone**, which is reported to take on the order of **a week of compile time on a comparable-era
flagship phone** ([xuancong84/install-PyTorch-on-Android](https://github.com/xuancong84/install-PyTorch-on-Android)) -
a non-starter on a Galaxy A16.

### What people actually use on Android instead of Python torch

- **PyTorch Mobile** (the old `.pt`/TorchScript + `libtorch` C++ runtime) - legacy, in maintenance
  mode, superseded by ExecuTorch.
- **ExecuTorch** - PyTorch's current on-device inference runtime (Meta/PyTorch Foundation). You
  **export** a `.pth`/`nn.Module` model *offline, at build time on a dev machine* into a `.pte`
  file, then ship that `.pte` plus the small `executorch-android` AAR (`org.pytorch:executorch-android`,
  published on Maven Central) inside the APK. This is a **conversion path**, not a way to run the
  checkpoint as-is, and requires implementing the model's Android inference logic against
  ExecuTorch's C++/Java bindings. [Using ExecuTorch on Android](https://docs.pytorch.org/executorch/stable/using-executorch-android.html),
  [ExecuTorch on GitHub](https://github.com/pytorch/executorch)
- **ONNX** - export the model with `torch.onnx.export` (or the model author's exporter) and run it
  through ONNX Runtime, which PhoneTTS's other engines already do. This is also a conversion path,
  but a much shallower one for many TTS architectures.

**MeloTTS specifically already has a done, working ONNX conversion in the wild** - `k2-fsa/sherpa-onnx`
ships a MeloTTS export script (`scripts/melo-tts`) and there are pre-converted ONNX exports on
Hugging Face (e.g. `seasonstudio/melotts_zh_mix_en_onnx`). This means "convert instead of running
raw PyTorch" is not a hypothetical extra engineering burden for this specific model - the
conversion has already been done by the community and can likely be adapted or reused directly.
[sherpa-onnx MeloTTS scripts](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html),
[seasonstudio/melotts_zh_mix_en_onnx](https://huggingface.co/seasonstudio/melotts_zh_mix_en_onnx)

## 4. Does any project genuinely let a shipped Android app fetch-and-run Python (with torch) on demand?

Short, plain answer: **no**, not for a sideloaded app that needs to run PyTorch.

- Chaquopy is the closest real SDK, but is build-time-baked, and its torch support is an
  unofficial, breakage-prone cross-compile (§1).
- Pydroid 3 proves the *idea* of "download prebuilt wheels including torch after install" works
  for a monolithic, closed-source, Play-distributed app talking to its own private wheel host -
  but it is not something PhoneTTS can embed or replicate without rebuilding Chaquopy's entire
  cross-compilation pipeline, hosting your own arm64-Bionic torch wheel repo, and separately
  solving the W^X exec/dlopen problem it currently dodges only because Pydroid ships those
  binaries as part of its own signed APK/native-lib layout, not by writing to arbitrary app
  storage post-install and loading from there in the way "download on demand" implies.
- No project surfaced that packages "fetch a Python runtime, then fetch torch, then run a raw
  `.pth`" as a turnkey capability for a third-party sideloaded Android app. This is not a gap in
  search effort - it runs into the same two structural walls (W^X, no Android torch wheel)
  everyone else hits, which is why the ecosystem's actual answer is "export the model" (ONNX /
  ExecuTorch / ggml), not "ship Python."

## Verdict

**Not realistically doable** for PhoneTTS as specified (sideloaded, on-device, download-Python-and-
torch-at-runtime, run the raw `.pth`). Two independent blockers, either one sufficient alone:

1. **Android's W^X policy blocks a non-rooted app from executing or `dlopen`-ing code it downloaded
   into its own app-private storage**, once targeting API 29+ (the OS enforces this regardless of
   sideloading; only Termux-style `LD_PRELOAD`/system-linker tricks work around it for plain
   process exec, not for loading shared-library extension modules, and PhoneTTS shouldn't be in
   the business of re-deriving that hack).
2. **PyTorch publishes no Android wheel and never has** - the Linux-aarch64 wheel that looks
   tempting is glibc-only and won't run on Bionic libc. The only people running torch on Android
   either (a) cross-compile it themselves for Bionic (Chaquopy's approach - unofficial, breaks
   across version bumps, per the open issues above), or (b) compile it from source on-device,
   which takes about a week.

Even setting those aside, a working `torch` + CPython install is commonly 800MB-1.5GB+ on disk -
heavy for a 4GB-RAM budget phone that's supposed to run "unmodified on better/worse phones," and a
poor match for "off the main thread, chunk text into sentences, stream" (rule 8) given Python's GIL
and torch's own threading model layered under Kotlin coroutines.

### Recommendation for PhoneTTS

Treat the MeloTTS engine like every other engine in this codebase: **convert, don't ship an
interpreter.** Concretely:

1. Export `MeloTTS-English`'s `checkpoint.pth` to **ONNX** - reuse or adapt the already-existing,
   community-maintained sherpa-onnx MeloTTS export script/pre-converted models rather than writing
   a conversion from scratch. This slots MeloTTS straight into the existing `Runtime`/`VoiceEngine`
   abstraction the same way other ONNX-backed engines already work - no new runtime, no new
   architecture, no violation of any of the CLAUDE.md rules.
2. If ONNX export turns out to be a poor fit for some component of the pipeline, ExecuTorch is the
   documented fallback conversion path (ship a `.pte` + the small `executorch-android` AAR) -
   still a conversion, but one PyTorch itself maintains and supports going forward as PyTorch
   Mobile's replacement.
3. Do **not** pursue on-demand Python/Chaquopy/torch-on-Android for this or future models. It is
   not a shortcut around conversion - it is strictly more engineering effort (build/maintain a
   Bionic torch cross-compile or work around W^X) for a *less* reliable, *less* supported result
   than converting the model once.

---

### Sources

- [Chaquopy - Gradle plugin docs (build-time `pip` block)](https://chaquo.com/chaquopy/doc/current/android.html)
- [chaquo/chaquopy issue #1215 - no torch build for Python 3.9+](https://github.com/chaquo/chaquopy/issues/1215)
- [chaquo/chaquopy issue #1273 - missing torch libs on Python 3.12](https://github.com/chaquo/chaquopy/issues/1273)
- [chaquo/chaquopy issue #247 - `torch_shm_manager` runtime error](https://github.com/chaquo/chaquopy/issues/247)
- [chaquo/chaquopy issue #1376 - SIGSEGV inside torch on-device](https://github.com/chaquo/chaquopy/issues/1376)
- [chaquo/chaquopy torchvision Chaquopy patch (custom cross-compile)](https://github.com/chaquo/chaquopy/blob/master/server/pypi/packages/torchvision/patches/chaquopy.patch)
- [Android Developers - Play Feature Delivery overview](https://developer.android.com/guide/playcore/feature-delivery)
- [ProAndroidDev - dynamic feature delivery relies on Play Store infrastructure](https://proandroiddev.com/mastering-android-dynamic-feature-module-delivery-1-3-3cf08afd1e42)
- [Android Developers - behavior changes for apps targeting API 29+ (W^X / execve restriction)](https://developer.android.com/about/versions/10/behavior-changes-10)
- [termux/termux-packages wiki - Termux execution environment (`termux-exec` / system-linker workaround)](https://github.com/termux/termux-packages/wiki/Termux-execution-environment)
- [Android Police - Android 14 targetSdk floor for install](https://www.androidpolice.com/android-14-may-not-let-you-install-outdated-apps-anymore/)
- [PyPI - `torch` package files/platform tags](https://pypi.org/project/torch/)
- [xuancong84/install-PyTorch-on-Android - building torch from source on-device (~1 week)](https://github.com/xuancong84/install-PyTorch-on-Android)
- [PyTorch - Using ExecuTorch on Android](https://docs.pytorch.org/executorch/stable/using-executorch-android.html)
- [pytorch/executorch on GitHub](https://github.com/pytorch/executorch)
- [sherpa-onnx - MeloTTS pretrained models / export scripts](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html)
- [seasonstudio/melotts_zh_mix_en_onnx on Hugging Face](https://huggingface.co/seasonstudio/melotts_zh_mix_en_onnx)
- [Pydroid 3 - prebuilt-wheel repository plugin (numpy/scipy/torch) for its own IDE app](https://medium.com/@thedataisaac/exploring-pydroid-3-the-ultimate-python-ide-for-android-20e9161b3655)
