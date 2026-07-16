#!/bin/sh
#
# Fetches the llama.cpp source tree the CosyVoice2 native bridge
# (app/src/main/cpp/cosyvoice/CMakeLists.txt) compiles against — the ggml runtime that decodes the
# Qwen2-0.5B speech-token LLM on-device (spec §5.3 second runtime; docs/COSYVOICE2.md,
# docs/research/cosyvoice2-mobile.md). Not run automatically by any Gradle task, and not a git
# submodule, because this environment cannot reach network during automated work — run this by hand
# once, on a machine with network access and the Android SDK/NDK installed.
#
# Pinned tag: PLACEHOLDER — the CosyVoice2 ggml speech-token decode + repeat-aware sampler have NOT
# been built/verified on-device yet (they still have to be ported from the CrispStrobe/`cstr`
# recipe). Confirm and pin an exact known-good llama.cpp tag when that native work is done; bump
# deliberately, since cosyvoice_jni.cpp's API usage is written against it.
#
# Usage:
#   sh scripts/fetch-cosyvoice-llama.sh
#
set -e

LLAMA_TAG="b4589"
REPO_ROOT=$(git rev-parse --show-toplevel)
DEST="$REPO_ROOT/app/src/main/cpp/cosyvoice/llama.cpp"

if [ -d "$DEST/.git" ]; then
    echo "fetch-cosyvoice-llama: $DEST already exists -- remove it first to re-fetch."
    exit 0
fi

echo "fetch-cosyvoice-llama: cloning llama.cpp @ $LLAMA_TAG into $DEST ..."
git clone --branch "$LLAMA_TAG" --depth 1 \
    https://github.com/ggml-org/llama.cpp.git \
    "$DEST"

echo "fetch-cosyvoice-llama: done. Build with -PwithCosyVoice=true to link it (previously a stub"
echo "was built — see app/src/main/cpp/cosyvoice/CMakeLists.txt's guard). The GGUF weights + the"
echo "8-voice pre-baked bank are separate downloads into app-private storage (never bundled in the"
echo "APK, CLAUDE.md rule 7) — see docs/COSYVOICE2.md."
