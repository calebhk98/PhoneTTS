# CMake build for the CosyVoice native ggml JNI bridge (spec §5.3 second runtime). See
# docs/COSYVOICE2.md for the full story; this file only carries the build logic. It is included from
# the parent app/src/main/cpp/CMakeLists.txt ONLY when Gradle is invoked with -PwithCosyVoice=true
# (which passes -DPHONETTS_BUILD_COSYVOICE=ON).
#
# The native engine is CrispStrobe/CrispASR's self-contained C++/ggml `cosyvoice3_tts` — the WHOLE
# CosyVoice3-0.5B pipeline (Qwen2 LLM + DiT-CFM flow + HiFi-GAN/iSTFT HiFT + native Qwen2 BPE) behind
# one C ABI, PROVEN end-to-end on desktop in scripts/model-verify/run_cosy_native.sh. CrispASR (a
# whisper.cpp fork, MIT) is NOT vendored in this repo -- like espeak-ng it is a large tree fetched
# once, pinned, with a helper script (documented here so the pin is visible without leaving this file):
#
#   scripts/fetch-cosyvoice-ggml.sh
#
# which clones CrispASR + its ggml submodule into:
#
#   app/src/main/cpp/cosyvoice/crispasr
#
# GUARD: if that checkout is missing, this file still configures and links successfully -- it
# produces a stub libphonetts_cosyvoice.so whose JNI entry points report "not built" instead of
# failing the Gradle/CMake configure step (same fail-closed-build policy as the espeak bridge).
#
# NATIVE STATUS: the SAME cosyvoice3_tts.cpp + crispasr-core + chatterbox_campplus + ggml targets
# linked below were built and run on desktop by run_cosy_native.sh (real 24 kHz audio out). The NDK
# cross-compile + final on-device link is the remaining unverified step (no NDK in this dev env),
# exactly like the espeak bridge -- hence the opt-in flag and the fail-closed stub.

set(CRISPASR_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/crispasr")

add_library(phonetts_cosyvoice SHARED cosyvoice_jni.cpp)
target_link_libraries(phonetts_cosyvoice PRIVATE log)

if(EXISTS "${CRISPASR_SRC_DIR}/src/cosyvoice3_tts.cpp")
    message(STATUS "phonetts: CrispASR source found at ${CRISPASR_SRC_DIR} -- building the real CosyVoice3 ggml backend")

    # CPU-only ggml on a budget ARM CPU (docs/research/cosyvoice2-mobile.md). No CUDA/Metal/Vulkan.
    set(GGML_CUDA OFF CACHE BOOL "" FORCE)
    set(GGML_METAL OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    # mel.cpp's cblas path needs a system BLAS header we don't ship to the NDK; the scalar mel
    # projection is bit-identical, just slightly slower (the flag run_cosy_native.sh also used).
    set(CRISPASR_MEL_BLAS OFF CACHE BOOL "" FORCE)
    # Don't build CrispASR's CLI / tests / server (would pull Catch2 + every ASR backend). These
    # already default OFF for a non-standalone (subdirectory) CrispASR, but pin them explicitly.
    set(CRISPASR_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(CRISPASR_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(CRISPASR_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(CRISPASR_CURL OFF CACHE BOOL "" FORCE)

    # EXCLUDE_FROM_ALL: only the targets phonetts_cosyvoice actually links (cosyvoice3_tts, chatterbox
    # and their transitive deps crispasr-core + ggml/ggml-base/ggml-cpu) get built -- not the 40+
    # other ASR/TTS backends CrispASR defines.
    add_subdirectory(${CRISPASR_SRC_DIR} crispasr-build EXCLUDE_FROM_ALL)

    target_include_directories(phonetts_cosyvoice PRIVATE "${CRISPASR_SRC_DIR}/src")
    # cosyvoice3_tts references chatterbox_campplus symbols (the shared CV2/CV3 speaker encoder),
    # so chatterbox must be linked alongside it -- matching the desktop proof's link line.
    target_link_libraries(phonetts_cosyvoice PRIVATE cosyvoice3_tts chatterbox)
    target_compile_definitions(phonetts_cosyvoice PRIVATE PHONETTS_HAVE_COSYVOICE=1)
else()
    message(WARNING
        "phonetts: CrispASR source NOT found at ${CRISPASR_SRC_DIR} -- building a STUB. "
        "CosyVoice will report isAvailable()=false at runtime and simply not be offered. "
        "Run scripts/fetch-cosyvoice-ggml.sh to fetch it, then rebuild with -PwithCosyVoice=true.")
endif()
