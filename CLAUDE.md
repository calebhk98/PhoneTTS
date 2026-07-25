# CLAUDE.md

Guidance for Claude Code (and any engineer) working in this repository.

## What this is

**PhoneTTS** - a standalone, **fully-offline** Android text-to-speech app for personal
use. It runs several neural TTS models entirely on-device (no network calls at inference
time), lets the user pick a model + voice, adjust speed, and either stream playback in real
time or export to a file. Target: budget hardware (developed against a Samsung Galaxy A16,
~4 GB RAM, no NPU), but it must run unmodified on better/worse phones.

The full engineering specification lives in [`docs/SPEC.md`](docs/SPEC.md). **Read it before
making architectural changes** - the abstractions there are *decided, not suggestions*.

## The one idea that governs everything

The app does **not** know about a fixed set of models. It knows about a **registry of
engines**, and each engine advertises what it can do via a **descriptor**. Everything the UI
shows is derived at runtime from the registry + descriptors.

Four abstractions (all in `:core`):
- **`VoiceEngine`** - loads weights, runs inference. Never references another engine.
- **`TextFrontend`** - turns text into model input (phonemes/tokens). Varies hardest between
  models, so it is deliberately **not** part of `VoiceEngine`; it lives *inside* each engine.
  (Not every engine has one: CosyVoice's native runtime tokenizes internally.)
- **`Runtime`** - pluggable inference backend behind an interface, so adding one touches nothing
  else. Several exist (see `app/src/main/kotlin/com/phonetts/app/runtime/`): the ONNX `Runtime`
  most engines use; the non-ONNX ggml `NativeTtsRuntime` family (one impl runs CosyVoice3's
  **entire** text→audio pipeline in one native call via CrispASR's `cosyvoice3_tts`, another is a
  generalized ggml backend); plus ExecuTorch and LiteRT backends. See docs/COSYVOICE2.md.
- **`ModelDescriptor`** - the single authority for every user-visible model fact, including the
  **dynamic list of tunable `ModelParameter`s** a model supports (see rule 1). The UI is derived
  entirely from it.

## The rules that must never be broken

These come straight from the spec and are the whole point of the design:

1. **SSOT - a model constant outside the resolver/descriptor layer is a bug.** No sample
   rate, voice name, speed bound, tunable-parameter, or display name may appear as a literal in
   the UI or an engine. Tunable knobs are **discovered, not assumed**: each engine inspects the
   model and declares the `ModelParameter`s it actually supports (speed, and anything a future
   model adds like emotion), so the UI renders a control per parameter with no app change. A model
   with no knob (CosyVoice3) declares none. If a fact is duplicated, the guarantee is broken.
2. **Speed always routes to the model's native parameter** (Piper `length_scale`, others a
   `speed`/duration arg), carried in the `SynthesisParams` bag. **Never** resample output audio to
   change speed - that shifts pitch. A model whose runtime exposes no speed knob advertises a
   locked range rather than faking one.
   **One explicit, flagged exception (issue #43):** a beyond-native, pitch-*preserving*
   time-stretch (`TempoStretch`, WSOLA - not resampling) is allowed **only** as a separately-named,
   **off-by-default** `AudioTransform` ("Extra tempo boost - post-processed, not native", 0.1x-10x)
   applied on the **playback path only** (via `TransformingSink`), completely separate from
   generation/export. It **never** touches the "Speed" control or the native speed `ModelParameter`,
   and it is **not** the resampling this rule forbids (that fakes speed by shifting pitch; WSOLA
   preserves pitch). The no-resampling-for-Speed rule above stands unchanged.
3. **One generation path.** `synthesize(text, voiceId, params: SynthesisParams)` returns
   `Flow<FloatArray>`. Real-time playback and file export are two *consumers* of that one flow. No
   second synthesis path.
4. **`inspect()` fails closed.** `null` means "not mine," never a guess. If a dropped-in model
   can't be identified with confidence, the app drops to the user-pick fallback rather than
   guessing. That refusal is a feature.
5. **No `when(modelType)` switches.** Removing a model = removing its engine registration,
   nothing else. Adding a model = registering it; the UI recomputes itself. The only
   built-in-vs-sideloaded distinction is the `Origin` field, used for **display only**.
6. **One engine loaded at a time.** `EngineManager` calls `unload()` on the previous engine
   before `load()` on the next. A 4 GB phone cannot hold them all.
7. **Model weights are never bundled in the APK.** Ship a manifest, download into app-private
   storage, verify SHA-256, then load.
8. **All inference off the main thread** (coroutines). **Chunk long text into sentences** and
   synthesize sequentially so output can start before the whole thing is generated.
9. **Never-nesting.** Guard clauses / early `return`/`continue`. No deep `if` pyramids.
   Enforced by detekt (`NestedBlockDepth`, `LongMethod`, `LargeClass`).

## Practices that would have prevented real bugs

Not generic advice - each rule below exists because its absence caused a specific, verified
defect in this repo. If you're tempted to skip one, re-read the defect it cites.

1. **Derive, don't hand-maintain, any list that must track reality.** Which engines exist, how
   many, which screens the app can reach - compute these from the source of truth (the
   filesystem, an enum, `Screen.entries`, `rootProject.subprojects`), never keep a second,
   hand-written copy. `settings.gradle.kts`/`app/build.gradle.kts` used to hand-list engine
   modules; `neutts`, `outetts`, `pockettts`, and `supertonic` existed and compiled but were
   missing from the app's list, so `ServiceLoader` never saw them and they silently shipped in no
   build at all. Both files now derive the engine set from `engines/*/build.gradle.kts` on disk
   (see *Adding an engine*).
   **Corollary: no magic counts in tests.** A test asserting "there are N of these" computes N
   from the same source of truth. `AllEnginesDiscoveryTest` used to hardcode
   `expectedEngineCount = 5` while 15 engine modules existed - the test meant to guard
   `ServiceLoader` discovery kept passing while blind to two-thirds of the engines. It now reads
   `phonetts.expectedEngineCount`, a Gradle-derived system property (`integration/build.gradle.kts`).
2. **No silent no-ops on a control path.** `controller?.pause() ?: Unit`, an empty `catch`, or a
   swallowed null on any user-triggered action (button, tile, notification, widget) is a bug, not
   a safe default - it used to mean a tapped pause button did nothing, with no log and no crash to
   find it by. If an action can fail, log it or surface it; never let the failure branch be a
   silent no-op.
3. **UI state is derived from its one owner, never mirrored into a second field.** (About
   *state*, distinct from rule 1 above the *"Practices"* header and rule 1 of the numbered rules,
   which is about model *facts*.) `fun play() = startPlaybackFrom(0)` ignored the resume point
   `DocumentPosition` already recorded - the data existed, nothing read it. Worse, that resume
   point was keyed to *generation* progress, not *playback* progress, so a skip discarded audio
   that had already been generated but never heard, while the screen kept showing "playing." On a
   real device that read as "audio kept going after the UI said it had stopped." If two fields can
   both answer "where are we" and disagree, delete one of them.
4. **Foreground services must be started, not merely bound.** `PlaybackService` was a declared
   foreground media service that the app only ever bound to, and the Activity detached its
   controller in `onStop()` - so lock-screen/notification controls went dead exactly while the
   Activity was backgrounded, which is the entire point of a foreground service. Start it
   (`ContextCompat.startForegroundService`) for anything that must stay controllable off-screen;
   binding alone couples its lifetime to the Activity's.
5. **Every user-visible choice survives process death.** Settings toggles, reader position,
   anything the user set and would notice reverting, gets persisted - before this was enforced, 8
   Settings toggles and the reader's text position silently reset on process death because no
   ViewModel used `SavedStateHandle`/`rememberSaveable` and nothing backed them. Persist through
   `:core`'s store seam (`DurableStore`/`JsonListStore`, `core/src/main/kotlin/com/phonetts/core/
   store/`) or a dedicated store like `SessionStateStore` - don't add a fourth hand-rolled
   `getSharedPreferences(...)` namespace next to `PlaybackStateStore`, `PrefsOverrideStore`, and
   `PrefsPreferenceStore` without a reason the existing seam can't cover.
6. **Wire the crash hook, don't just build the log it's supposed to feed.**
   `Thread.setDefaultUncaughtExceptionHandler` appeared nowhere, so the durable error log built to
   catch crashes recorded nothing on an actual crash - empty exactly when it mattered. A
   failure-capture path that isn't wired to the real crash hook (`CrashReporter`, installed at app
   start) is dead code wearing a safety net's clothes.
7. **A UI control, a persisted value, and a screen all need a test before they're "done."** `:app`
   shipped with zero tests for a long time; every bug above went out through a green build because
   nothing exercised the UI layer. `app/src/test/` now has a Robolectric source set:
   `NavigationReachabilityTest` walks `Screen.entries` against the drawer's `NAV_ITEMS` so a new
   screen that isn't wired into a reachable path fails the build, and `ScreenSmokeTest` renders
   every `Screen.entries` value and asserts it doesn't crash. Adding a screen means adding it to
   `Screen.entries` - the tests find it automatically; leaving one out of reachability/smoke
   coverage must be a **named, deliberate exception in the test** (see
   `NavigationReachabilityTest`'s `nonDrawerEntryPoints`), not a silent gap. The same bar applies
   to anything reachable from a control (rule 2 above) and anything that persists (rule 5 above).
8. **State what "verified" means - don't imply more than you checked.** "Compiles," "unit-tested,"
   and "confirmed on device" are different claims; an issue comment or commit message should say
   which one applies. Issues have been closed "implemented, pending on-device confirmation" and
   later turned out not to work on real hardware - closing as `completed` (see *Workflow
   conventions*, step 4) on reasoning alone, for anything device-observed (audio, playback,
   foreground services, lock-screen controls), is how that happened. If verification stopped short
   of running on a device, say so instead of letting the closure imply it didn't.

## Module layout

- **`:core`** - pure Kotlin/JVM. All deterministic "seam" logic: contracts, registry,
  resolver, descriptors, WAV encoder, streaming driver, manifest/SHA-256. **No Android
  dependencies**, so its unit tests (the seam tests, spec §9) run on any JVM with no SDK.
  This is where correctness is proven.
- **`:engines:*`** - one Gradle module per model engine (`:engines:kokoro`, `:engines:kittentts`,
  `:engines:cosyvoice2`, `:engines:melotts`, `:engines:ggmltts`, ...), plus `:engines:common` for
  shared, model-agnostic engine plumbing (`AbstractVoiceEngine` and friends). Each is pure
  Kotlin/JVM, depends only on `:core`'s seams, **names no other engine**, and is discovered at
  runtime via `ServiceLoader` (a `META-INF/services/com.phonetts.core.engine.EngineProvider`
  resource). See *Adding an engine* below.
- **`:integration`** - cross-module JVM integration tests: the one place all engines share a
  classpath.
- **`:app`** - the Android application (Compose UI, `AudioTrack` sink, ONNX/native runtime
  implementations, SharedPreferences-backed override store, downloader). Requires the
  Android SDK. `settings.gradle.kts` includes it **automatically when an SDK is present**
  (`ANDROID_HOME`/`ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`); on a core-only
  machine it is skipped so the JVM modules still build. Force-skip with `-PskipApp=true`. It has
  a real test source set (`app/src/test`, Robolectric + Compose UI testing on plain JVM, no
  emulator needed) including `NavigationReachabilityTest` and `ScreenSmokeTest`, which cover every
  `Screen.entries` value - see *Practices that would have prevented real bugs*, rule 7, and
  *Environment quirks* below for why CI doesn't run it yet.

Package root: `com.phonetts`. Core lives under `com.phonetts.core.{engine,model,runtime,registry,
resolver,audio,download,update,text,prefs,…}` (that list is not exhaustive - see the package tree for
the full set); each engine under `com.phonetts.engines.<name>`.

## Build & test

The pure-JVM modules are where correctness is proven - work against `:core` first:

```bash
./gradlew :core:test          # run the seam tests (this is the important one)
./gradlew :core:compileKotlin  # compile the main sources
./gradlew ktlintCheck detekt   # style + never-nesting / size rules
```

When an Android SDK is present, `:app` also builds/compiles (`gradle :app:compileDebugKotlin`);
on a core-only machine it is skipped. Don't assume the SDK/NDK is absent - check
(`local.properties` `sdk.dir`, `$ANDROID_HOME`) before claiming the app can't be built. The NDK
native bridges (espeak, CosyVoice) cross-compile for arm64 with the NDK; see docs/COSYVOICE2.md.

**No SDK? Download it - `dl.google.com` is reachable, so `:app` can be built here.** Grab Google's
command-line tools and install exactly what `app/build.gradle.kts` needs (`compileSdk`/`targetSdk`
35 → `platforms;android-35` + `build-tools;35.0.0`), then point the build at it:

```bash
SDK=/opt/android-sdk; mkdir -p "$SDK/cmdline-tools"
curl -sSL -o /tmp/cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q /tmp/cmdline-tools.zip -d "$SDK/cmdline-tools" && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=$SDK" > local.properties          # or just export ANDROID_SDK_ROOT
gradle :app:compileDebugKotlin --no-daemon      # now builds :app; :app:assembleDebug for the APK
```

(Bump the platform/build-tools versions to match if `compileSdk` changes. The NDK - `ndk;<ver>` - is
only needed for the opt-in `-PwithEspeak`/`-PwithCosyVoice` native bridges, not the baseline APK.)

(If the wrapper can't fetch its distribution behind a proxy, a system Gradle 8.14.3 also
works: `gradle :core:test`.)

`:core` compiles with the running JDK (21 here) but **emits JVM 17 bytecode** so the Android
`:app` module can consume it unchanged.

## Environment quirks (remote / Claude Code on the web)

These bite in the ephemeral cloud sessions; note them so you don't waste a loop rediscovering them.

- **`./gradlew` can't fetch its distribution behind the proxy** - the wrapper download 403s from
  `github.com`. **Use the system Gradle instead: `gradle …` (8.14.3 at `/opt/gradle/bin/gradle`,
  matches the wrapper version).** Same tasks, e.g. `gradle -PskipApp=true :core:test ktlintCheck detekt`.
  Don't hand-edit `gradle-wrapper.jar`/`.properties` to work around it - `gradle-wrapper.properties`
  sets `validateDistributionUrl=true` (Gradle checks the distribution download), but CI does **not**
  currently re-validate the jar itself.
- **No Android SDK preinstalled, but it's downloadable** - `dl.google.com` is reachable. See the
  block in *Build & test* to install it and build `:app`; don't claim the app can't be built here.
- **The pre-commit hook is NOT installed in a fresh clone** - there's no `.git/hooks/pre-commit`
  and no `core.hooksPath` until you run `scripts/install-hooks.sh`. So a commit runs **no** checks
  automatically here. Run them yourself before committing (`gradle -PskipApp=true :core:test
  ktlintCheck detekt`), and note the hook itself calls `./gradlew` - which fails per the first bullet.
- **CI is core-only for both lint and tests** - the `test` job runs `-PskipApp=true`, so `:app`
  Kotlin is **not** ktlint/detekt-checked, and `:app`'s Robolectric suite (`app/src/test`) is
  **not** run, by CI (the `apk` job only assembles the debug APK, it doesn't test it). If you
  touch `:app`, lint and test it locally with the SDK present
  (`gradle ktlintCheck detekt :app:testDebugUnitTest`), or regressions land unnoticed.
- **`JAVA_TOOL_OPTIONS` proxy/truststore noise** is printed on **every** JVM invocation
  (`Picked up JAVA_TOOL_OPTIONS: …`). It's harmless boilerplate, not an error - ignore it.
- **The container is ephemeral** - only what you commit and push survives. `local.properties`
  (the `sdk.dir` you write) is git-ignored and vanishes with the container; that's fine.
- **History can be rewritten between sessions** - the old `v0.1.0`/`v0.1.1` tags point at orphaned
  commits, which is what once stranded the version anchor. If a build's version looks wrong, check
  the merge count against `VERSION_BASE_MERGES` before assuming the logic is broken.

## TDD - test the plumbing, not the audio

Voice quality is the model's job, not this codebase's. Tests target the **deterministic
seams** (spec §9): `inspect()` fail-closed, `resolve()` + saved override, registry dynamism
(the SSOT guard - **do not skip**), speed routing, sample-rate routing, WAV round-trip, dual
consumer draining one flow. Write the failing test first, then the minimum code to pass it.

**No golden audio hashes for CosyVoice2** - it samples autoregressively and is
non-deterministic. Test invariants instead (length in range, samples bounded, no NaNs).

Shared test fixtures (`FakeEngine`, `testDescriptor`) live in
`core/src/testFixtures/kotlin/com/phonetts/core/testing/Fakes.kt` - reuse them for new seam tests.

## Subagent model policy

**Spawn subagents on Sonnet or Haiku, never Opus or Fable.** When delegating with the Agent/Task
tools, always pin the model explicitly: `model: "sonnet"` for research/exploration/analysis, and
`model: "haiku"` for cheap mechanical work (simple greps, file listings, boilerplate). Do NOT leave
the model unset - an unpinned subagent inherits the (expensive) parent model. Reserve Opus for the
main orchestrating loop only. This keeps fan-out cheap without hurting result quality on the seam-
and UI-level tasks this repo involves.

## Workflow conventions in this repo

**The issue is the source of truth for what to do - the prompt only gets you up to speed.** Work is
tracked as **GitHub issues** (one per ticket). The session prompt carries *generic* orientation
(which branch, how the repo is laid out, environment quirks) - the *actual task, scope, and
acceptance criteria live in the issue*. If the prompt and the issue seem to disagree about what to
build, the issue wins; ask before diverging from it.

So, when working an issue:

1. **Read the whole issue first - the body AND every comment.** Later comments often refine, narrow,
   or redirect the original ask (a decision, a gotcha, a "actually do X instead"). Don't act on the
   title alone.
2. **Do the work described there**, on the branch named in the session config (a fresh `claude/<slug>`
   per session). Keep the change scoped to what the issue asks.
3. **Leave a comment on the issue** summarizing what you did - what changed, why, anything the issue
   didn't anticipate, and what you verified (tests run, build result). This is the trail the human
   follows; keep it honest (if tests failed or a step was skipped, say so).
4. **Close it with `state_reason: completed`** once its tests/acceptance criteria are green. If it's
   blocked or ambiguous, comment with the question instead of guessing.

Other conventions:

- Branch: feature work lands on the `claude/<slug>` branch named in the session config.
- Commits: clear, descriptive messages. The pre-commit hook runs ktlint + detekt + `:core:test` and
  blocks on failure (see `scripts/`) - **but it isn't installed in a fresh clone** (run
  `scripts/install-hooks.sh`, or just run the checks by hand - see *Environment quirks*).
- Weights, `.onnx`/`.bin` files, and `models/` are git-ignored - never commit model data.

## Release, versioning, and self-update

- **CI + APK:** `.github/workflows/android.yml` runs the core seam tests on every push and builds
  the debug APK. It publishes the APK to a **GitHub Release** on **every push to `main`** (each merge
  bumps the patch, so each merge ships a release) and on any `v*` tag; a manual `workflow_dispatch`
  on another branch publishes a **prerelease**. Feature-branch pushes only upload the APK as an
  Actions artifact - they do **not** cut a release. The checked-in `gradle-wrapper.jar` should stay
  the official one - regenerate with the pinned Gradle, never hand-edit it (`validateDistributionUrl=true`
  in `gradle-wrapper.properties` guards the download, but CI does not currently re-validate the jar).
- **Auto-versioning (per merge, not per commit):** `app/build.gradle.kts` derives the version from
  the count of **merges to main** - `git rev-list --count --first-parent HEAD`, so a PR merge (or
  squash) adds exactly one and the branch's own commits never inflate it. `versionName` is
  `0.1.<merges − VERSION_BASE_MERGES>` (**every merge bumps the patch**, 0.1.2 → 0.1.3 → …);
  `versionCode` encodes the version (`major*1_000_000 + minor*1_000 + patch`) so it stays monotonic
  and above the old raw-count codes. Bump MAJOR/MINOR by editing `VERSION_MAJOR_MINOR` and
  re-anchoring `VERSION_BASE_MERGES` (keep `base=` in the workflow in sync). CI checks out full
  history (`fetch-depth: 0`) so the count is right. Use "merge commit" or "squash and merge", **not
  "rebase and merge"** (rebase replays each branch commit onto main's first-parent line and would
  count per-commit).
- **In-app update check (offer, never force):** `core/.../update/UpdateChecker` compares
  `BuildConfig.VERSION_NAME` to the latest GitHub Release and, if newer, the app shows a
  **dismissible** banner offering it. Fail-closed: a network hiccup shows nothing. The **Help screen**
  also has a manual **"Check for updates"** button (`HelpScreen` → `TtsViewModel.checkForUpdatesNow`)
  that re-runs the same check on demand and reports "Up to date" / a download offer / a can't-reach note.
- **The update download runs IN the app, not the browser.** Handing the release-asset URL to the
  browser is what used to break: GitHub 302s that URL to a **signed, ~1-hour** `release-assets.
  githubusercontent.com` link, so a browser download that queues/pauses/retries past the signature's
  expiry gets a 403 and parks forever ("never finishes"). `:app`'s `ApkUpdateDownloader` streams the
  APK itself into `cacheDir/updates` with visible progress, re-requesting the stable `github.com` URL
  on every attempt so each retry follows a **freshly signed** redirect; it then verifies the bytes
  against the release asset's `sha256` digest (fail-closed - a mismatch deletes the file and refuses).
  `ApkInstaller` hands the result to the system package installer via a FileProvider `content://` URI
  (`REQUEST_INSTALL_PACKAGES` + `res/xml/file_paths.xml`). Still offer-never-force: nothing downloads
  until Download is tapped, nothing installs until Install is tapped, and the OS's own confirm screen
  is the last gate. The pure decisions (`ApkDownloadState`, percent-or-null, `ApkIntegrity`) live in
  `:core` and are seam-tested; only the transport and the intent are in `:app`.

## Adding an engine

Adding a model is **adding a module**, not editing a shared list (rule 5). Create
`engines/<name>/` mirroring an existing engine of the same runtime family - an ONNX model like
`:engines:kittentts` or `:engines:kokoro`, a native-ggml model like `:engines:ggmltts` - with:

- an engine extending `AbstractVoiceEngine` (implementing `inspect`/`forcedMatch`/`doLoad`/
  `synthesizeSentence`), building its `ModelDescriptor` from facts it verified against the real
  model files (rule 1 SSOT; rule 4 fail-closed - never fabricate a sample rate, voice, or tensor
  contract you can't source);
- its `TextFrontend`, if the model needs one;
- an `EngineProvider` registered via `META-INF/services/com.phonetts.core.engine.EngineProvider`;
- seam tests (inspect fail-closed, descriptor facts, speed routing, synthesize invariants).

That's it - there is no `include()` line and no `runtimeOnly` line to add anymore.
`settings.gradle.kts` auto-discovers every directory under `engines/` that has its own
`build.gradle.kts` and includes it; `app/build.gradle.kts` and `integration/build.gradle.kts` each
derive their engine dependency list from `rootProject.subprojects` the same way. (This replaced a
hand-maintained include/runtimeOnly list that had already drifted - see *Practices that would have
prevented real bugs*, rule 1.) The registry and the entire UI recompute themselves from the new
descriptor - no `when(model)` switch, no shared list is touched. Removing an engine is deleting its
`engines/<name>/` directory, full stop - no other file to edit. A native-runtime engine
additionally documents its opt-in native wiring in an `INTEGRATION.md` (see
`engines/ggmltts/INTEGRATION.md`) rather than editing shared app/build files directly.

**Kotlin gotcha - block comments nest, so a literal `/*` inside a comment breaks the build.** Engine
KDoc references paths and globs constantly; writing one like `voice_styles/*.json` or `onnx/*.onnx`
in a comment puts a `/*` inside it, which *opens a nested comment* - the intended `*/` then closes
only the inner level and the compiler swallows everything to end-of-file (`Syntax error: Unclosed
comment`). Write such paths as `voice_styles/<name>.json`, or otherwise never let a literal `/*`
appear inside a comment or KDoc.
