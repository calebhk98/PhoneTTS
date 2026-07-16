# CMake build for the CosyVoice2 speech-token LLM JNI bridge (spec §5.3 second runtime). See
# docs/COSYVOICE2.md for the full story; this file only carries the build logic. It is included
# from the parent app/src/main/cpp/CMakeLists.txt ONLY when Gradle is invoked with
# -PwithCosyVoice=true (which passes -DPHONETTS_BUILD_COSYVOICE=ON).
#
# llama.cpp (MIT) is NOT vendored in this repo -- like espeak-ng it is a large tree fetched once,
# pinned, with a helper script (documented here so the pin is visible without leaving this file):
#
#   scripts/fetch-cosyvoice-llama.sh
#
# which runs:
#
#   git clone --branch b4589 --depth 1 \
#       https://github.com/ggml-org/llama.cpp.git \
#       app/src/main/cpp/cosyvoice/llama.cpp
#
# (Pin the exact tag once a real on-device build has resolved a known-good one -- b4589 is a
# placeholder recorded so the intent is explicit; this has NOT been built here.)
#
# GUARD: if that checkout is missing, this file still configures and links successfully -- it
# produces a stub libphonetts_cosyvoice.so whose JNI entry points report "not built" instead of
# failing the Gradle/CMake configure step (same fail-closed-build policy as the espeak bridge).

set(LLAMA_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

add_library(phonetts_cosyvoice SHARED cosyvoice_jni.cpp)
target_link_libraries(phonetts_cosyvoice PRIVATE log)

if(EXISTS "${LLAMA_SRC_DIR}/CMakeLists.txt")
    message(STATUS "phonetts: llama.cpp source found at ${LLAMA_SRC_DIR} -- building the real CosyVoice2 LLM backend")

    # CPU-only, no examples/tests/tools -- we only need the core library for a 0.5B GGUF decode on
    # a budget ARM CPU (docs/research/cosyvoice2-mobile.md). Keeps the .so small and the build fast.
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

    add_subdirectory(${LLAMA_SRC_DIR} llama-cpp-build)

    target_include_directories(phonetts_cosyvoice PRIVATE "${LLAMA_SRC_DIR}/include")
    target_link_libraries(phonetts_cosyvoice PRIVATE llama)
    target_compile_definitions(phonetts_cosyvoice PRIVATE PHONETTS_HAVE_COSYVOICE=1)
else()
    message(WARNING
        "phonetts: llama.cpp source NOT found at ${LLAMA_SRC_DIR} -- building a STUB. "
        "CosyVoice2 will report isAvailable()=false at runtime and simply not be offered. "
        "Run scripts/fetch-cosyvoice-llama.sh to fetch llama.cpp, then rebuild with -PwithCosyVoice=true.")
endif()
