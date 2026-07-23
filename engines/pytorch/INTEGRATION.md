# `:engines:pytorch` - integration notes and feasibility verdict

## TL;DR

**Raw PyTorch checkpoints cannot run on-device in PhoneTTS, on any currently-considered
architecture.** This module ships as a deliberately inert placeholder: `PyTorchEngine.inspect()`
always returns `null` and `forcedMatch()` always throws. No runtime registration, no build
changes, no product flavor are needed or recommended. This file records *why*, for whoever next
looks at this model family, so the investigation isn't repeated.

## What was tried, in order

1. **Chaquopy + a `pytorch` product flavor**, bundling Python + `pip install("torch")` into a
   separate, opt-in APK variant. Rejected by the maintainer before implementation: *"a flag/flavor
   they can't install-and-test is useless"* - a build variant nobody but a CI job can exercise
   doesn't earn its complexity.
2. **On-demand provisioning**: download a Python interpreter + the model's Python dependencies into
   app-private storage at runtime (the same pattern this app already uses for model weights,
   CLAUDE.md rule 7), invoked through a small bridge interface so the mechanism could be swapped in
   without touching the engine. This is what a first pass of `PythonTtsRuntime`/`PythonBridge` in
   `:app` was built around. **Reverted** once a dedicated feasibility check came back negative - see
   below. (No trace of that code remains in `:app`; this module never depended on it directly.)

## Why on-demand provisioning is a dead end

Two independent blockers, either one sufficient on its own:

### 1. Android will not execute code a non-privileged app downloads into its own storage

Since Android 10 (API 29), apps targeting API ≥ 29 are restricted by a W^X (write-XOR-execute)
policy: **by default they can `dlopen()` a file from their own writable storage but not `exec()`
it**, and this is enforced independent of root/sideloading status - it is not something the user
can opt out of by installing the APK a particular way.
[developer.android.com/about/versions/10/behavior-changes-10](https://developer.android.com/about/versions/10/behavior-changes-10)
confirms this restriction is part of the standard API-29+ behavior changes; the mechanism is
documented in more depth at
[agnostic-apollo/Android-Docs: app-data-file-execute-restrictions.md](https://github.com/agnostic-apollo/Android-Docs/blob/master/site/pages/en/projects/docs/apps/processes/app-data-file-execute-restrictions.md)
and the underlying `PROT_EXEC`/W^X mechanics at
[Bionic's android-changes-for-ndk-developers.md](https://android.googlesource.com/platform/bionic/+/master/android-changes-for-ndk-developers.md).
A full CPython interpreter is not one shared object - running it (and PyTorch's own native
extensions) means repeatedly `exec()`-ing a `python` binary or loading many independent native
libraries the way a desktop package manager would, which is exactly the pattern this restriction
targets. Termux and similar projects work around comparable limits with `proot`/loader tricks that
assume a level of process control an ordinary sideloaded, non-rooted third-party app does not have.
`:engines:pytorch`'s own PhoneTTS target device (a stock, non-rooted Galaxy A16, see CLAUDE.md) has
no such escape hatch.

### 2. Even if execution were allowed, there is no working `torch` build for this target

- PyTorch's official wheels are `manylinux` (glibc-linked, x86/ARM Linux); Android's userland is
  Bionic libc, an ABI PyTorch's upstream build does not target at all - there is no official
  `arm64-android` wheel to install in the first place.
- Chaquopy (the one project that tries to bridge this gap) ships an **unofficial, community-built**
  `torch` package, and its own issue tracker documents it as broken across recent versions: missing
  native libraries after a Python version bump
  ([chaquo/chaquopy#1273](https://github.com/chaquo/chaquopy/issues/1273)), a missing
  `torch_shm_manager` binary that prevents `torch` from loading at all
  ([chaquo/chaquopy#1215](https://github.com/chaquo/chaquopy/issues/1215)), pip-install failures
  building it in the first place ([chaquo/chaquopy#246](https://github.com/chaquo/chaquopy/issues/246),
  [#247](https://github.com/chaquo/chaquopy/issues/247)), and outright native crashes at runtime
  (`SIGSEGV` inside the `torch` library,
  [chaquo/chaquopy#1376](https://github.com/chaquo/chaquopy/issues/1376)). This is not "fragile but
  usable" - it is not a supported path today.

Both blockers are structural (a platform security policy, and an upstream packaging gap), not
implementation bugs this repo could work around with more engineering.

## What `:engines:pytorch` actually ships

- **`RawPyTorchBundle.looksLikeRawPyTorch(fileNames: Set<String>): Boolean`** - a pure, unit-tested
  shape detector: true when a bundle has a `.pth`/`.ckpt`/`.bin` weight file plus a `config.json`
  and no `.onnx` graph. It answers *"does this look like a raw PyTorch export"*, never *"can this
  engine run it"* - those are deliberately different questions (see `PyTorchEngine`'s kdoc). Nothing
  in the app calls this today; it exists so a **future** call site (most naturally
  `com.phonetts.core.resolver.DetectionFailureExplainer`'s failure-path narration, or a Browse-screen
  hint) has one tested place to ask the question and produce a message like *"this looks like a raw
  PyTorch checkpoint, which PhoneTTS can't run on-device - convert it to ONNX or ExecuTorch first"*
  instead of a bare "no engine recognized this model." Wiring that in is a UI/resolver change, out
  of this module's scope (and would touch `AppGraph.kt`/the resolver, which this session was not
  to edit).
- **`PyTorchEngine`** (`internal class`, `id = "pytorch"`) - implements `VoiceEngine` directly (not
  `AbstractVoiceEngine`, since there is no `Runtime`/`NativeTtsRuntime` to delegate to).
  `inspect()` always returns `null`; `forcedMatch()` always throws
  `UnsupportedOperationException` with a message that names the actual reason (using
  `RawPyTorchBundle` only to make that message more specific) and points at the real path forward.
  `voices()` returns an honest empty list. `load()`/`synthesize()` throw defensively (unreachable in
  practice, since nothing can ever produce an `EngineMatch` for this engine).
- **`PyTorchEngineProvider`** + `META-INF/services/com.phonetts.core.engine.EngineProvider` - the
  usual ServiceLoader registration (CLAUDE.md rule 5: this is the only thing that adds the engine to
  the app; deleting the whole module removes it with no other change). Registering a
  never-matches-anything provider is intentional and harmless: it documents the model family's
  status in the running app's registry rather than in silence, and costs nothing (no runtime is
  looked up, no weights are ever loaded).

## No build/wiring changes needed

Unlike a real engine, this one needs **no runtime registration**. `app/build.gradle.kts` already
carries `runtimeOnly(project(":engines:pytorch"))` (pre-existing scaffolding - untouched by this
session), which is sufficient: `EngineLoader.discoverProviders()` in `AppGraph.kt` finds
`PyTorchEngineProvider` via `ServiceLoader` the same way it finds every other engine, with zero
engine-specific code in `AppGraph.kt`. There is nothing else to wire - no `Runtime`/`NativeTtsRuntime`
implementation, no product flavor, no Chaquopy/Python dependency anywhere in `:app`.

## The actual path forward for a model like this

Convert offline, before the model ever reaches this app:

- **ONNX** - the community has already done this for MeloTTS specifically, via
  [`k2-fsa/sherpa-onnx`](https://github.com/k2-fsa/sherpa-onnx), which exports/hosts ONNX builds of
  MeloTTS-family models. `:engines:melotts` in this repo already targets that packaging - a
  converted MeloTTS bundle is a `:engines:melotts` bundle, not a `:engines:pytorch` one.
- **ExecuTorch** - `:engines:executorch` is this repo's other path for models that don't fit the
  ONNX Runtime seam; a `.pte`-exported model belongs there instead.

## Verification run in this session

```
gradle -PskipApp=true :engines:pytorch:test :engines:pytorch:ktlintCheck :engines:pytorch:detekt
```

(see the session's final report for the actual result). `:app` was never built or compiled in this
session, per the task's hard rules.
