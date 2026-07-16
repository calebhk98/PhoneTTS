# Top-level CMake for PhoneTTS's native JNI bridges. There are two independent, opt-in bridges,
# each built only when Gradle passes the matching define (see app/build.gradle.kts):
#
#   -DPHONETTS_BUILD_ESPEAK=ON     (from -PwithEspeak=true)     -> the espeak-ng phonemizer, below
#   -DPHONETTS_BUILD_COSYVOICE=ON  (from -PwithCosyVoice=true)  -> the llama.cpp CosyVoice2 LLM
#                                                                  bridge, in cosyvoice/CMakeLists.txt
#
# Neither native tree is vendored in git; each is fetched, pinned, by its own helper script and each
# builds a fail-closed STUB .so when its source is absent, so the app always assembles.
#
# --- espeak-ng phonemization JNI bridge (Blocker 1 — the shared text frontend for
#     Piper/KittenTTS/Kokoro, spec §5.2). See docs/espeak-ng-integration.md for the full story.
#
# espeak-ng's own source (GPLv3) is NOT vendored in this repo — it's a multi-hundred-MB tree with
# generated data files, and shipping it in git would fight the "weights/generated artifacts are
# never committed" rule (CLAUDE.md). Fetch it once, pinned to the exact tag this JNI wrapper was
# written against, with:
#
#   scripts/fetch-espeak-ng.sh
#
# which runs (documented here too, so the pin is visible without leaving this file):
#
#   git clone --branch 1.52.0 --depth 1 \
#       https://github.com/espeak-ng/espeak-ng.git \
#       app/src/main/cpp/espeak-ng
#
# GUARD: if that checkout is missing, this file still configures and links successfully — it
# produces a stub libphonetts_espeak.so whose JNI entry points return a clear "not built" error
# instead of failing the Gradle/CMake configure step (spec: fail closed, never a build break —
# rule 4's spirit applied to the build system, not just inspect()).
cmake_minimum_required(VERSION 3.22.1)
project(phonetts_native_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ESPEAK_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/espeak-ng")

if(PHONETTS_BUILD_ESPEAK)
add_library(phonetts_espeak SHARED espeak_jni.cpp)
target_link_libraries(phonetts_espeak PRIVATE log)

if(EXISTS "${ESPEAK_SRC_DIR}/CMakeLists.txt")
    message(STATUS "phonetts: espeak-ng source found at ${ESPEAK_SRC_DIR} -- building the real engine")

    # Android-recommended flags (docs/research/espeak-ng.md §2.4):
    #  - USE_ASYNC OFF: espeak-ng's async mode is pthread-based and meant for its own event-driven
    #    audio playback, which this wrapper never uses (phoneme-only, synchronous JNI calls,
    #    serialized on the Kotlin side by EspeakPhonemizer's single-threaded dispatcher). Avoids a
    #    class of Android-specific threading gotchas for no loss of functionality here.
    #  - USE_KLATT / USE_MBROLA OFF: alternate synthesis backends we never call (phonemes only,
    #    no audio out of espeak-ng itself) — smaller .so.
    #  - USE_LIBSONIC / USE_LIBPCAUDIO OFF: audio playback/speed-change helpers, unused for the
    #    same reason.
    set(USE_ASYNC OFF CACHE BOOL "" FORCE)
    set(USE_KLATT OFF CACHE BOOL "" FORCE)
    set(USE_MBROLA OFF CACHE BOOL "" FORCE)
    set(USE_LIBSONIC OFF CACHE BOOL "" FORCE)
    set(USE_LIBPCAUDIO OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

    add_subdirectory(${ESPEAK_SRC_DIR} espeak-ng-build)

    target_link_libraries(phonetts_espeak PRIVATE espeak-ng)
    target_compile_definitions(phonetts_espeak PRIVATE PHONETTS_HAVE_ESPEAK=1)
else()
    message(WARNING
        "phonetts: espeak-ng source NOT found at ${ESPEAK_SRC_DIR} -- building a STUB. "
        "Real phonemization will be unavailable at runtime; EspeakPhonemizer falls back to "
        "PassthroughPhonemizer and logs a warning. Run scripts/fetch-espeak-ng.sh to fix this.")
endif()
endif() # PHONETTS_BUILD_ESPEAK

# --- CosyVoice2 speech-token LLM JNI bridge (spec §5.3 second, LLM-style runtime). Its own
#     CMakeLists builds llama.cpp (or a fail-closed stub). See docs/COSYVOICE2.md.
if(PHONETTS_BUILD_COSYVOICE)
    add_subdirectory(cosyvoice)
endif()
