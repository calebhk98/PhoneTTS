# eSpeak NG for Android: NDK Integration & Phoneme Frontend Research

**Date:** July 2026  
**Scope:** Building espeak-ng with Android NDK and wrapping it as a shared phoneme frontend for on-device neural TTS (Piper/KittenTTS/Kokoro)

> **Implementation status:** this research has been acted on. The CMake build, JNI wrapper,
> `EspeakPhonemizer`, and data-installer described below are implemented — see
> [`docs/espeak-ng-integration.md`](../espeak-ng-integration.md) for the concrete file list,
> pinned version (1.52.0), exact build steps, and the specific places this research doc's
> pseudocode diverged from what shipped (notably the `espeak_TextToPhonemes` clause-loop in
> §4.1, which needed a real multi-clause loop, not a single call). This document is kept as-is
> below as the original research log.

---

## 1. eSpeak NG Source, Version, and License

### Repository
- **Official Source:** https://github.com/espeak-ng/espeak-ng
- **Current Version:** 1.52 (released December 2024); also track 1.51.x
- **Language Support:** 100+ languages and accents
- **Build System:** CMake

### License Analysis
**PRIMARY LICENSE: GPLv3 or later**

- eSpeak NG core is released under **GPL v3 or later**.
- Additional compatibility licenses: BSD 2-clause (for some compat code), Apache 2.0 (select components).
- **Implication for Personal App:** A GPLv3 requirement means:
  - If you link espeak-ng statically or as a JNI shared library into your Android app, the entire app must be GPL v3 compatible or you must dynamically load it as a separate plugin.
  - The safer Android approach: compile `libespeak-ng.so` as a separate shared library and load it via JNI at runtime, treating it as an external GPL component.
  - **Disclosure requirement:** Any distribution of your app must acknowledge the GPLv3 license and provide source code availability.

**Alternative: piper-phonemize (Archived)**
- The Rhasspy project's `piper-phonemize` (https://github.com/rhasspy/piper-phonemize) is now archived; phonemization is embedded directly in Piper's Python wheel.
- For a pure C++ Android native implementation, espeak-ng is the canonical choice.
- **Recommended citation:** "This app includes espeak-ng (https://github.com/espeak-ng/espeak-ng) licensed under GPLv3."

---

## 2. Android NDK + CMake Cross-Compilation Setup

### 2.1 Overview
eSpeak NG's CMake build supports cross-compilation via the Android NDK. The NDK provides a toolchain file (`$NDK/build/cmake/android.toolchain.cmake`) that handles all architecture-specific flags.

### 2.2 Key CMake Variables for Android

```cmake
# Essential NDK + CMake variables
-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake
-DANDROID_ABI=arm64-v8a           # arm64-v8a, armeabi-v7a, x86_64, x86
-DANDROID_PLATFORM=android-21     # Minimum API level
-DANDROID_NDK=$ANDROID_NDK        # Path to NDK root
-DCMAKE_ANDROID_API=21            # Alternative to ANDROID_PLATFORM
-DCMAKE_ANDROID_ARCH_ABI=arm64-v8  # Alternative to ANDROID_ABI
```

**Gradle Integration (externalNativeBuild):**

In `build.gradle`:
```groovy
android {
  defaultConfig {
    ndk {
      abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64'
    }
  }
  externalNativeBuild {
    cmake {
      path 'src/main/cpp/CMakeLists.txt'
      version '3.22.1'  // Match espeak-ng's minimum version
    }
  }
}
```

### 2.3 Cross-Compilation Strategy
The key gotcha: eSpeak NG's build system generates data files (phonemes, language data) using a native `espeak-ng-mbrolatarget` binary. Cross-compilation requires a two-stage build:

**Stage 1: Native build (your build machine)**
```bash
cmake -Bbuild-native \
  -DCMAKE_INSTALL_PREFIX=`pwd`/install-native
cmake --build build-native --target data
```

**Stage 2: Android cross-compile (pointing to native build)**
```bash
cmake -Bbuild-android \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-21 \
  -DNativeBuild_DIR=`pwd`/build-native/src \
  -DCMAKE_INSTALL_PREFIX=`pwd`/install-android
cmake --build build-android
```

### 2.4 espeak-ng CMake Feature Flags (Android Relevant)
- `-DUSE_KLATT=ON/OFF` – Klatt formant synthesis (default: ON; use OFF to reduce binary size)
- `-DUSE_MBROLA=ON/OFF` – MBROLA voice support (default: ON if available)
- `-DUSE_ASYNC=OFF` – Disable async mode (pthreads) for Android to avoid threading complications (see §5.1)
- `-DBUILD_ESPEAK_DATA=ON` – Build language data (needed; default: ON)
- `-DEXTRA_ru/cmn/yue=ON/OFF` – Extended dictionaries (large; consider disabling for app size)

**Recommended Android build:**
```bash
-DUSE_KLATT=OFF \
-DUSE_ASYNC=OFF \
-DBUILD_ESPEAK_DATA=ON
```

### 2.5 Resulting Artifacts
- **libespeak-ng.so** – Phoneme synthesis engine (~500 KB–1.2 MB depending on flags)
- **espeak-ng-data/** – Language/phoneme data directory (must be shipped separately; see §3)

---

## 3. eSpeak NG Data Files: Shipping and Runtime Location

### 3.1 The Data Directory Structure
eSpeak NG requires a data directory (`espeak-ng-data/`) containing:
- `voices/` – Voice definitions (phoneme sets, pitch contours)
- `phondata` – Compiled phoneme data
- `phontab` – Phoneme-to-IPA tables
- `intonation` – Intonation contours per language
- `mbrola/` – MBROLA voice files (if enabled)

### 3.2 Shipping Strategy: Assets vs. App-Private Storage

**Option A: Assets (Recommended for <30 MB)**
- Ship `espeak-ng-data/` inside the APK's `assets/` directory.
- **Pros:** Data bundled with app; no download or permissions needed; works offline immediately.
- **Cons:** Increases APK size; data is read-only.
- **Implementation:** Copy assets to app-private internal storage on first run.

**Option B: Download to App-Private External Storage (For larger datasets)**
- Download `espeak-ng-data.tar.bz2` (~3–5 MB compressed) to `context.getExternalFilesDir()` or `context.getFilesDir()` on first run.
- **Pros:** Smaller initial APK; user can skip download if offline TTS not needed.
- **Cons:** First-run overhead; requires network permission; data remains on disk (privacy consideration).

**Option C: Mixed (Recommended for production)**
- Ship essential voices (en_US, etc.) in assets; allow optional language packs as downloads.

### 3.3 Runtime Data Path Setup

eSpeak NG locates data via the `ESPEAK_DATA_PATH` environment variable or via the `espeak_Initialize()` call.

**Java/Android JNI side (set before first espeak call):**
```java
// Copy assets to internal storage
String dataDir = context.getFilesDir() + "/espeak-ng-data";
if (!new File(dataDir).exists()) {
  copyAssetsRecursive(context.getAssets(), "espeak-ng-data", dataDir);
}

// Set environment variable before loading native lib
System.setenv("ESPEAK_DATA_PATH", dataDir);
```

**C/C++ side (in JNI initialization):**
```c
#include <espeak-ng.h>
#include <cstring>

// Call after loading libespeak-ng.so
int initEspeak(const char* dataPath) {
  espeak_Initialize(
    AUDIO_OUTPUT_RETRIEVAL,  // Output mode: retrieve phoneme data
    0,                        // Buflength: we handle buffering in Java
    dataPath,                 // Path to espeak-ng-data
    NULL                      // Options (reserved)
  );
  
  // Set output to IPA phonemes (eSpeak phoneme codes)
  espeak_SetPhonemeTrace(1, NULL);  // Enable phoneme output
  
  return 0;
}
```

### 3.4 espeak-ng-data Distribution
For reference, sherpa-onnx distributes espeak-ng-data as a separate tarball:
- **Source:** https://github.com/k2-fsa/sherpa-onnx/releases (espeak-ng-data.tar.bz2)
- **Size:** ~3–5 MB compressed, ~50 MB uncompressed (full language set)
- For Piper/Kokoro: use the language-specific subset only.

---

## 4. JNI Surface: Text → IPA/eSpeak Phonemes

### 4.1 Core JNI Functions

**Initialization (once per app session):**
```c
JNIEXPORT jint JNICALL
Java_com_phonetts_EspeakNative_initialize(JNIEnv *env, jobject thiz, jstring dataPath) {
  const char *path = (*env)->GetStringUTFChars(env, dataPath, NULL);
  int result = espeak_Initialize(AUDIO_OUTPUT_RETRIEVAL, 0, path, NULL);
  (*env)->ReleaseStringUTFChars(env, dataPath, path);
  return result;
}
```

**Text → Phoneme conversion:**
```c
JNIEXPORT jstring JNICALL
Java_com_phonetts_EspeakNative_textToPhonemesIPA(JNIEnv *env, jobject thiz, 
                                                  jstring text, jstring lang) {
  const char *textUTF = (*env)->GetStringUTFChars(env, text, NULL);
  const char *langCode = (*env)->GetStringUTFChars(env, lang, NULL);
  
  // Set language
  espeak_SetVoiceByProperties(NULL);  // Use default; refine with lang selector if needed
  
  // Generate phonemes
  const char *phonemes = espeak_TextToPhonemes((const void **)&textUTF);
  
  jstring result = (*env)->NewStringUTF(env, phonemes);
  (*env)->ReleaseStringUTFChars(env, text, textUTF);
  (*env)->ReleaseStringUTFChars(env, lang, langCode);
  
  return result;
}
```

**Output modes** (set via espeak API):
- **IPA output:** Default; produces Unicode IPA symbols (e.g., `/pɪŋ/` for "ping")
- **eSpeak mnemonic output:** Use `-x` equivalent; produces phoneme codes (e.g., `p ɪ ŋ`)
- **SAMPA:** Use `espeak-ng-mbrola` flavor for SAMPA phonemes

### 4.2 Phoneme Output Format
eSpeak NG outputs phoneme sequences separated by spaces:
```
Input: "Hello"
Output: "hɛ loʊ"  (IPA mode)
       or "h E l oU" (mnemonic mode)
```

For Piper/Kokoro, the output is typically in eSpeak's phoneme mnemonic set (single-letter or digraph codes), not full IPA.

---

## 5. Phoneme → Per-Model ID Mapping

### 5.1 Why Per-Model?
Each TTS model (Piper, KittenTTS, Kokoro) has its own phoneme inventory and ID mapping. The neural network's embedding layer expects fixed integer IDs (0–N) that correspond to the model's training vocabulary.

### 5.2 Piper's Phoneme ID Map Approach
Piper's voice configs include a JSON mapping:

```json
{
  "phoneme_id_map": {
    "^": 0,         // Sentence boundary
    " ": 1,         // Space
    "ɑ": 2,         // English /ɑ/ vowel
    "b": 3,
    ...
  }
}
```

**Pipeline:**
1. Text → espeak-ng → phoneme sequence (IPA or mnemonic)
2. Phoneme sequence → split by separator → individual phonemes
3. For each phoneme, look up its ID in `phoneme_id_map`
4. Pass phoneme ID sequence to neural encoder

### 5.3 Mapping Implementation (Android/C++)
```c
// Load phoneme map from model config JSON
static GHashTable *phoneme_map = NULL;

void loadPhonemeMap(const char *mapJSON) {
  // Parse JSON and populate hash table
  // phoneme_map["ɑ"] = 2, etc.
}

int phonemeToId(const char *phoneme) {
  if (!phoneme_map) return -1;
  gpointer id = g_hash_table_lookup(phoneme_map, phoneme);
  return (int)(intptr_t)id;
}

jintArray Java_com_phonetts_TextFrontend_toModelInput(JNIEnv *env, jobject thiz,
                                                       jstring text, jstring lang,
                                                       jstring modelMapJSON) {
  // 1. Get phonemes from espeak
  const char *phonemes = espeak_TextToPhonemes(...);
  
  // 2. Load model phoneme map
  loadPhonemeMap((*env)->GetStringUTFChars(env, modelMapJSON, NULL));
  
  // 3. Convert to ID sequence
  char *phoneme_copy = strdup(phonemes);
  char *tok = strtok(phoneme_copy, " ");
  
  jintArray ids = (*env)->NewIntArray(env, num_phonemes);
  int idx = 0;
  while (tok) {
    int id = phonemeToId(tok);
    (*env)->SetIntArrayRegion(env, ids, idx++, 1, &id);
    tok = strtok(NULL, " ");
  }
  
  return ids;
}
```

**Key point:** The mapping is defined per-model in the model config JSON, not in espeak-ng itself.

---

## 6. Known Gotchas and Mitigations

### 6.1 Threading and Reentrancy
**Problem:** eSpeak NG maintains global state (current voice, synthesis context). Multiple threads calling espeak simultaneously can corrupt state.

**Evidence:** The Rust wrapper for espeak-ng wraps all calls in a global mutex lock (https://docs.rs/espeakng/latest/espeakng/); eSpeak-NG issue #495 discusses thread-safety concerns.

**Mitigation:**
- Use a single-threaded phonemization queue on the Android side.
- Wrap all espeak calls in a Mutex or synchronized block.
- Disable `-DUSE_ASYNC=ON` in CMake (already recommended above).
- **Best practice:** Initialize espeak-ng once on app startup, on a single thread, then only call text→phoneme from that thread or via a queue.

```java
// Java side: single-threaded queue
ExecutorService espeakExecutor = Executors.newSingleThreadExecutor();

public ListenableFuture<int[]> textToPhonemeIds(String text, String lang, 
                                               String modelMap) {
  return Futures.transformAsync(
    Futures.submit(() -> nativeTextToPhonemes(text, lang), espeakExecutor),
    phonemes -> phonemeMapToIds(phonemes, modelMap),
    espeakExecutor
  );
}
```

### 6.2 SSML Handling
**Problem:** eSpeak NG supports SSML (Speech Synthesis Markup Language) tags, but phoneme output mode may or may not preserve them correctly.

**Status:** eSpeak NG's SSML support is partial. Use `-m` flag (command-line) or check `espeak_SetSynthCallback()` for event notifications in JNI code.

**Mitigation:** For Piper/Kokoro (which don't use SSML at the TTS level), pre-process text to strip SSML before passing to espeak-ng, or handle SSML separately in the text-normalization layer (not espeak-ng's job).

### 6.3 Phoneme Set Differences
**Problem:** eSpeak NG defines ~100+ phonemes (IPA + eSpeak mnemonics). Each model may only use 50–80 of them. Unknown phonemes → model error.

**Mitigation:**
- Build a phoneme inventory check: before inference, verify all phonemes from espeak are in the model's map.
- Fallback strategy: map unknown phonemes to a default "unknown" ID or nearest match.
- **Log and warn:** Print phonemes that espeak generates but the model doesn't recognize.

```c
// Check coverage
for (int i = 0; i < espeak_phoneme_count; i++) {
  if (!g_hash_table_lookup(phoneme_map, espeak_phonemes[i])) {
    __android_log_print(ANDROID_LOG_WARN, "EspeakNative", 
      "Phoneme '%s' not in model map", espeak_phonemes[i]);
  }
}
```

### 6.4 Punctuation and Clause Handling
**Problem:** eSpeak NG can output clause/sentence boundaries (often marked with special tokens or silence gaps). Neural TTS models may require explicit boundary markers.

**Solution:** Use `espeak_TextToPhonemesWithTerminator()` (if available in your espeak fork) to detect sentence boundaries, then insert boundary tokens (e.g., `<PUNC>` or model-specific end-of-sentence ID).

**Piper's approach:** Inserts `^` (caret) as a sentence boundary marker at clause breaks; maps `^` → ID 0.

### 6.5 Data Path Issues on Android
**Problem:** `ESPEAK_DATA_PATH` environment variable not respected; espeak-ng still looks for data in wrong location.

**Mitigation:**
- Always call `espeak_Initialize(mode, buflength, dataPath, NULL)` with explicit dataPath parameter, not environment variable alone.
- Verify `espeak-ng-data/` directory exists and contains `phondata`, `voices/`, etc. before initializing.
- Use absolute paths (e.g., `/data/data/com.example.phonetts/files/espeak-ng-data`), not relative.

```c
// Verify data directory
const char *dataPath = "/data/data/com.example.phonetts/files/espeak-ng-data";
if (access(dataPath, F_OK) != 0) {
  __android_log_print(ANDROID_LOG_ERROR, "EspeakNative", 
    "Data path not found: %s", dataPath);
  return -1;
}
```

### 6.6 Language/Voice Selection
**Problem:** eSpeak NG supports 100+ languages; each language has different phoneme sets. Mismatched language can produce wrong phonemes.

**Mitigation:**
- Always call `espeak_SetVoiceByProperties()` or `espeak_SetVoiceByName()` before phonemization, passing the correct language code (e.g., `en-us`, `fr`, `de`).
- Cache the current voice name; don't re-set if unchanged (minor optimization).
- Validate that the model's phoneme map matches the espeak-ng language used.

---

## 7. Build vs. Reuse: Decision Matrix

| Approach | Pros | Cons | Recommendation |
|----------|------|------|---|
| **Build espeak-ng from source** | Full control; latest version; customize feature flags | 2-stage CMake build; slow CI; native build machine required | **Use for production app** |
| **Use sherpa-onnx prebuilts** | Pre-built `.so` files; tested; includes data | Tied to sherpa-onnx release cycle; may lag espeak-ng upstream | **Good for prototyping** |
| **Ship piper-phonemize** | Tested with Piper models | Archived; outdated; no Android prebuilts | **Not recommended** |
| **Delegate to system TTS** | Zero shipping cost; uses device voice | No phoneme control; cloud-dependent; poor for offline NN-TTS | **Not viable for this use case** |

**Recommendation for Phase 1:** Build espeak-ng from source. This gives you:
- Exact control over feature flags and library size.
- Confidence in GPL compliance (you know exactly what's shipped).
- Ability to patch espeak-ng if needed (e.g., phoneme boundary detection).
- Foundation for adding other phonemizers later (e.g., for non-Latin scripts).

---

## 8. Quick Start: Minimal Working Implementation

### 8.1 Directory Structure
```
android/
  jni/
    CMakeLists.txt           # espeak-ng build rules
    espeak-ng-jni.c          # JNI wrapper
  src/
    main/
      assets/
        espeak-ng-data/      # (copy from espeak-ng build)
      java/
        TextFrontend.java    # Java-side phoneme API
      cpp/
        CMakeLists.txt       # gradle externalNativeBuild entry point
```

### 8.2 CMakeLists.txt Outline (android/jni/CMakeLists.txt)
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(espeak-ng-jni C)

# 2-stage build setup
set(ESPEAK_SRC_PATH "${CMAKE_CURRENT_SOURCE_DIR}/../../espeak-ng-src")
set(NATIVE_BUILD_DIR "" CACHE STRING "Path to native espeak build (from 2-stage compile)")

if(NATIVE_BUILD_DIR)
  # Cross-compile using native build's data
  set(NativeBuild_DIR "${NATIVE_BUILD_DIR}")
  add_subdirectory("${ESPEAK_SRC_PATH}" espeak-ng)
else()
  # Assume espeak-ng is prebuilt or in NDK sysroot
  add_subdirectory("${ESPEAK_SRC_PATH}" espeak-ng)
endif()

add_library(espeak-ng-jni SHARED espeak-ng-jni.c)
target_link_libraries(espeak-ng-jni PRIVATE espeak-ng)
```

### 8.3 Building
```bash
# 1. Prepare espeak-ng source
git clone https://github.com/espeak-ng/espeak-ng.git android/espeak-ng-src

# 2. Native build (on your build machine)
cd android/espeak-ng-src
cmake -Bbuild-native
cmake --build build-native --target data

# 3. Android Gradle build
cd ../../
./gradlew build  # gradle automatically uses NDK + CMakeLists.txt
```

---

## 9. References and Further Reading

### Official Projects
- **eSpeak NG GitHub:** https://github.com/espeak-ng/espeak-ng
- **eSpeak NG Building Guide:** https://github.com/espeak-ng/espeak-ng/blob/master/docs/building.md
- **eSpeak NG Releases:** https://github.com/espeak-ng/espeak-ng/releases
- **Piper (OHF-Voice):** https://github.com/OHF-Voice/piper1-gpl
- **sherpa-onnx TTS:** https://k2-fsa.github.io/sherpa/onnx/tts/index.html
- **sherpa-onnx Piper Support:** https://k2-fsa.github.io/sherpa/onnx/tts/piper.html

### Android NDK + CMake
- **Android CMake Guide:** https://developer.android.com/ndk/guides/cmake
- **ExternalNativeBuild DSL:** https://google.github.io/android-gradle-dsl/

### Threading & Safety
- **Rust espeakng wrapper (thread-safety notes):** https://docs.rs/espeakng/latest/espeakng/
- **eSpeak-NG Issue #495 (thread-safety discussion):** https://github.com/espeak-ng/espeak-ng/issues/495

### Phonemization Context
- **Piper Text Processing:** https://deepwiki.com/rhasspy/piper/2.1-text-processing-and-phonemization
- **eSpeak NG Phoneme Docs:** https://github.com/espeak-ng/espeak-ng/blob/master/docs/phonemes.md
- **eSpeak NG XSAMPA Phonemes:** https://github.com/espeak-ng/espeak-ng/blob/master/docs/phonemes/xsampa.md

### Example Implementations
- **eSpeak-NG Android SpeechSynthesis.java:** https://github.com/espeak-ng/espeak-ng/blob/master/android/src/com/reecedunn/espeak/SpeechSynthesis.java
- **ncnn-android-piper (custom phonemizer):** https://github.com/nihui/ncnn-android-piper
- **OHF Piper1 GPL:** https://github.com/OHF-Voice/piper1-gpl

---

## 10. Summary Table: Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Source | espeak-ng official repo v1.52+ | Maintained; GPLv3 clear; 100+ languages |
| CMake flags | `-DUSE_KLATT=OFF -DUSE_ASYNC=OFF` | Smaller binary; thread-safety for Android |
| Data shipping | Assets + optional download | Offline support; flexible for large language sets |
| Threading | Single-threaded queue (Mutex) | espeak-ng has global state; proven pattern |
| Phoneme mode | IPA + mnemonic export | Flexible for different models |
| Phoneme→ID | Per-model JSON config mapping | Standard in Piper; supports multiple TTS engines |
| GPL handling | Dynamic JNI `.so` load; clear attribution | Compliant; app remains agnostic to GPL |

---

**Author's Note:** This research is current as of July 2026. Verify espeak-ng releases and sherpa-onnx compatibility before implementation. The two-stage CMake build is the critical gotcha; failing to pre-build native data will cause silent phoneme generation failures on Android.
