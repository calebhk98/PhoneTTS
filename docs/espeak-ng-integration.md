# espeak-ng Integration (Blocker 1 - the shared Piper/KittenTTS/Kokoro text frontend)

This is the implementation doc for the espeak-ng phonemizer, following the exploratory work in
[`docs/research/espeak-ng.md`](research/espeak-ng.md). Read that first for background (license,
threading gotchas, decision matrix); this doc is the "how do I actually build and verify it"
companion.

## Status (issue #13 - verified with a real NDK + espeak-ng 1.52.0 checkout)

The assumptions below were originally written blind (no NDK/device available). They have now
been validated end-to-end in a build environment with the Android NDK (r27.0.12077973, now pinned
via `android.ndkVersion` in `app/build.gradle.kts`) and a real `espeak-ng` 1.52.0 checkout:
`gradle -PwithEspeak=true :app:assembleDebug` succeeds, `libphonetts_espeak.so` links for both
`arm64-v8a` and `armeabi-v7a` with the expected `Java_com_phonetts_app_text_EspeakNative_*` JNI
symbols resolved against real `espeak_Initialize`/`espeak_SetVoiceByName`/`espeak_TextToPhonemes`
(not the stub), and the `espeak-ng-data` asset packaging path (below) was exercised and produces a
real APK with `assets/espeak-ng-data/*` and both native libs present. **CI now builds this
automatically** - `.github/workflows/android.yml`'s `apk` job installs the NDK, fetches the
pinned source, builds the phoneme/dict data with a host-native CMake pass, and passes
`-PwithEspeak=true` to `assembleDebug`, so every published release APK includes real
phonemization, not the passthrough fallback. Two real build bugs turned up and were fixed (see
`app/src/main/cpp/CMakeLists.txt` and `app/build.gradle.kts` for the in-line explanations):

1. espeak-ng's own `src/CMakeLists.txt` links its `espeak-ng` target **PUBLIC** against an
   `espeak-include` interface library exposing both `include/` (the real public headers we need)
   and `include/compat/` (libc portability shims for espeak-ng's *own* `.c` files only). Because
   `-I` flags always win over the toolchain's system search paths, that second directory shadowed
   the NDK libc++'s own `<wctype.h>`/`<cwctype>` for our JNI `.cpp` file too (which only needs
   `<mutex>`/`<string>` plus `espeak-ng/speak_lib.h`), breaking the build with `'ucd/ucd.h' file
   not found`. Fixed by re-exposing only the real `include/` dir after `add_subdirectory(...)`.
2. Without restricting the CMake target list, Android Gradle Plugin asks ninja to build
   espeak-ng's **entire** `all` graph reachable from its subdirectory - its CLI binary, its unit
   tests, and a `data ALL` custom target that *executes* the just-cross-compiled arm64 binary on
   the host to generate phoneme/dictionary data, which fails outright on an x86_64 build machine.
   None of that is needed inside the Android cross-build: the data this app ships is produced
   separately by a **host-native** CMake pass (unchanged from step 2 below) and copied in as an
   asset. Fixed via `externalNativeBuild.cmake.targets(...)` restricting the build to only the
   JNI `.so`(s) actually packaged.

The include-path assumption and the `espeak_TextToPhonemes` clause-loop idiom noted below both
held up as originally written - no changes were needed there.

**Still unverified: real on-device phoneme output.** This environment has no Android device/
emulator, so "does it actually initialize and produce sane IPA on a phone" (the "Verifying phoneme
output" section below) remains to be checked on real hardware - everything up to a successful,
loaded `.so` + installed data has been confirmed; whether `espeak_Initialize` on-device (a real ARM
CPU, real filesystem paths under `/data/data/...`) succeeds and phonemization output actually fixes
Piper/Kokoro/KittenTTS/MeloTTS's garbled audio needs a phone or emulator to confirm.

## What shipped

| Piece | File |
|---|---|
| CMake build (guarded - builds a stub if espeak-ng source is absent) | `app/src/main/cpp/CMakeLists.txt` |
| JNI wrapper (init + text→IPA) | `app/src/main/cpp/espeak_jni.cpp` |
| Kotlin JNI declarations | `app/src/main/kotlin/com/phonetts/app/text/EspeakNative.kt` |
| Asset → app-private storage copy | `app/src/main/kotlin/com/phonetts/app/text/EspeakDataInstaller.kt` |
| Real `Phonemizer`, with built-in fallback | `app/src/main/kotlin/com/phonetts/app/text/EspeakPhonemizer.kt` |
| Fallback (labelled, used only on native failure) | `app/src/main/kotlin/com/phonetts/app/text/PassthroughPhonemizer.kt` |
| Pure-Kotlin IPA cleanup (tie bars, whitespace) | `core/src/main/kotlin/com/phonetts/core/text/EspeakIpaNormalizer.kt` |
| Fetch script for the pinned source | `scripts/fetch-espeak-ng.sh` |
| Gradle wiring (`externalNativeBuild { cmake { ... } }`) | `app/build.gradle.kts` |

Wired into `AppGraph`: `EspeakPhonemizer(appContext)` replaces the old direct
`PassthroughPhonemizer()` construction as the `Phonemizer` handed to every engine via
`EngineContext.phonemizer` (spec §5.2 - Piper/KittenTTS/Kokoro all consume it; MeloTTS and
CosyVoice2 have their own frontends and never touch this).

## Pinned version

**espeak-ng 1.52.0** - the tag matching "Current Version: 1.52 (released December 2024)" named
in the research doc, confirmed against the upstream repo's tag list. `scripts/fetch-espeak-ng.sh`
pins this exact tag; bump it deliberately (the CMake flags and JNI code were written against
this release specifically) rather than tracking `master`.

## Build steps (do this once, on a machine with network + the Android SDK/NDK - CI now does this
automatically for every published release, see the "Status" section above)

### 1. Fetch the espeak-ng source

```bash
sh scripts/fetch-espeak-ng.sh
# clones --depth 1 --branch 1.52.0 https://github.com/espeak-ng/espeak-ng.git
#   into app/src/main/cpp/espeak-ng/
```

Until this has been run, `:app` still assembles - `app/src/main/cpp/CMakeLists.txt` detects the
missing `espeak-ng/CMakeLists.txt` and builds a **stub** `libphonetts_espeak.so` instead of
failing configure. At runtime, `EspeakPhonemizer` detects the stub can't initialize and falls
back to `PassthroughPhonemizer`, logging a warning (see "Failure modes" below) - the app never
crashes for lack of the native source.

### 2. Produce `espeak-ng-data/` and place it under `assets/`

espeak-ng needs its compiled phoneme/language tables at a real filesystem path at runtime - they
can't be read out of the APK's compressed asset store (research doc §3.3, §6.5), so they're
copied out to app-private storage on first launch by `EspeakDataInstaller`. That means the raw
`espeak-ng-data/` directory itself must ship *in* the APK, as an asset:

```bash
cd app/src/main/cpp/espeak-ng
cmake -Bbuild-native -DCMAKE_INSTALL_PREFIX="$(pwd)/install-native"
cmake --build build-native --target data
# produces build-native/espeak-ng-data/ (or similar - check the exact output path this
# espeak-ng version uses; it has moved between releases)

mkdir -p ../../../assets
cp -r build-native/espeak-ng-data ../../../assets/espeak-ng-data
```

To keep the APK small, trim `assets/espeak-ng-data/` down to the languages the shipped voices
actually need (at minimum `en` for the Piper/KittenTTS/Kokoro voices named in
`docs/research/model-facts.md`) before committing - measured full data (all languages, dicts,
`phondata`, `intonations`, `lang/`, `voices/!v`) is ~19 MB uncompressed, not the ~50 MB originally
estimated here; CI currently ships the full set rather than trimming (simpler, still small enough
not to matter). Both `app/src/main/cpp/espeak-ng/` and `app/src/main/assets/espeak-ng-data/` are
git-ignored (see `.gitignore`) - this is generated/vendored content, never committed, consistent
with the "weights are never bundled/committed" rule (CLAUDE.md rule 7 - this isn't weights but the
same "large generated binary blob" logic applies).

### 3. Build normally

```bash
./gradlew :app:assembleDebug -PwithEspeak=true
```

(Omit `-PwithEspeak=true` and the module still assembles - see "Do NOT break no-NDK dev builds"
below - but without it you get the stub `.so` + `PassthroughPhonemizer` fallback, not real
phonemization.)

`app/build.gradle.kts` wires `externalNativeBuild { cmake { path = "src/main/cpp/CMakeLists.txt";
version = "3.22.1" } }` at the `android {}` level, `-DANDROID_STL=c++_shared` in
`defaultConfig.externalNativeBuild.cmake.arguments`, and a `targets(...)` restriction so only the
JNI `.so`(s) actually packaged get built (not espeak-ng's own CLI/tests/data-generation targets -
see the "Status" section above for why). `android.ndkVersion` is now pinned to `27.0.12077973`,
the version a real build confirmed working end-to-end (r23+ needed for CMake 3.22.1
compatibility; bump deliberately if a future native bridge needs newer).

## Verifying phoneme output (on-device / emulator - still cannot be done in this environment;
no Android device/emulator is available here even though the native build itself is now verified)

1. Confirm `libphonetts_espeak.so` loaded: `adb logcat -s EspeakNative` should show no
   `"failed to load"` warning at app start.
2. Confirm espeak-ng initialized: `adb logcat -s EspeakPhonemizer` should show no
   `"espeak_Initialize failed"` / `"assets missing"` warnings.
3. Add a temporary log line (or a debug-only screen) that calls
   `EspeakPhonemizer(context).phonemize("hello world", "en")` and prints the result. Expected
   shape: an IPA string with no tie-bar characters (`EspeakIpaNormalizer` strips `͡`/`͜`) and
   single-space word separators, e.g. something like `hɛloʊ wɜːld` (exact symbols depend on the
   installed voice's phoneme inventory - do not hardcode this string as a golden value anywhere,
   per the SSOT rule; it is illustrative only).
4. Feed that output through `PiperFrontend`/`KittenFrontend`/`KokoroFrontend.toModelInput()` and
   confirm `tokenIds` is non-empty and (for Piper) that most characters resolve in the voice's
   `phonemeIdMap` rather than being silently dropped - a mostly-empty id sequence usually means a
   phoneme-set mismatch between what espeak-ng emitted and what the voice's map expects (research
   doc §6.3).

## Phoneme representation per engine

All three consumers (`engines/piper/.../PiperFrontend.kt`, `engines/kittentts/.../
KittenFrontend.kt`, `engines/kokoro/.../KokoroFrontend.kt`) call `Phonemizer.phonemize(text,
language)` and then walk the returned `String` **one Kotlin `Char` at a time**, looking each one
up in their own per-model phoneme→id table (spec §5.2: that table is per-model, never shared).
`EspeakPhonemizer` therefore must hand back a string where each meaningful phoneme is exactly one
UTF-16 code unit - that's what `EspeakIpaNormalizer` (in `:core`, unit-tested, see
`core/src/test/kotlin/com/phonetts/core/text/EspeakIpaNormalizerTest.kt`) is for: it strips
espeak-ng's combining tie-bar characters (which would otherwise glue two phonemes' codepoints
together in a way the per-char walk doesn't expect) and collapses whitespace runs from clause
joining into single word-separator spaces. Stress marks (`ˈ`, `ˌ`) and length marks (`ː`) are
deliberately left alone - they're real entries in Piper-family phoneme maps, not decorations.

Nothing about the per-engine id mapping changed in this ticket - `PiperFrontend`'s BOS/PAD/EOS
wrapping, `KittenFrontend`'s code-point-as-id scaffold, and `KokoroFrontend`'s ASCII-vocab
placeholder are all pre-existing and out of scope here (owned by the engine-tensor validation
work, not the phonemizer).

## Failure modes and what happens (all handled, none crash the app)

| Failure | Detected in | Result |
|---|---|---|
| `espeak-ng/` source not fetched | CMake configure (`CMakeLists.txt`) | Stub `.so` builds; `EspeakNative.nativeInit` returns -1 at runtime |
| `libphonetts_espeak.so` fails to load (missing ABI slice, etc.) | `EspeakNative.isLibraryLoaded` (class-init) | `EspeakPhonemizer` falls back immediately, logs a warning |
| `espeak-ng-data/` missing from `assets/` | `EspeakDataInstaller.install()` returns null | `EspeakPhonemizer` falls back, logs a warning |
| `espeak_Initialize` returns an error code | `EspeakPhonemizer.runInit` | Falls back, logs the error code |
| A native call throws (e.g. `UnsatisfiedLinkError` from a signature mismatch) | `runCatching` around every native call in `EspeakPhonemizer` | Falls back per-call, logs the exception |

In every case the delegate is `PassthroughPhonemizer` (text returned unchanged), which is
explicitly documented as "used only as a fallback" (see its kdoc) so it's never mistaken for the
real implementation in code review.

## Assumptions verified by a real NDK + espeak-ng 1.52.0 build (issue #13), vs. still open

- **espeak-ng header include path** (`espeak_jni.cpp`): `#include <espeak-ng/speak_lib.h>`
  resolves - **confirmed**, with a caveat: upstream doesn't actually mark this `PUBLIC` on the
  `espeak-ng` target directly (it's `PUBLIC` via a separate `espeak-include` interface library),
  and that same public exposure also leaked `include/compat` onto the include path, which broke
  the build for an unrelated reason (see "Status" above) - fixed in `CMakeLists.txt`.
- **`espeak_TextToPhonemes` clause-loop idiom**: compiles and links cleanly as written - the
  `while (textPtr != nullptr)` loop bounded by `kMaxClauseIterations`. Compiling doesn't prove the
  runtime behavior (does it correctly walk multi-clause input without hanging or truncating?) -
  that still needs a real device/emulator call, per "Verifying phoneme output" below.
- **Language → voice mapping** (`EspeakPhonemizer.toEspeakVoice`): only `"en"` is explicitly
  mapped to `"en-us"`; everything else passes through to espeak-ng's own language-code
  resolution unchanged. Still unverified against real voice language tags - this is Kotlin logic
  the native build couldn't exercise; revisit once real voice language tags from shipped models
  are known.
- **`espeak-ng-data` output path** from the `data` CMake target - **confirmed** for 1.52.0: the
  host-native `cmake --build ... --target data` pass produces `espeak-ng-data/` directly under
  the build directory (`<build-native>/espeak-ng-data/`), containing `phondata*`, `phonindex`,
  `phontab`, `intonations`, `lang/`, `voices/`, and one `<lang>_dict` per compiled language -
  matching what this doc's step 2 already assumed.

None of the above required changing `core/src/main/kotlin/com/phonetts/core/text/Phonemizer.kt`
or any engine's `synthesize`/tensor code - the `Phonemizer` interface and every engine's
`TextFrontend` signature are unchanged.
