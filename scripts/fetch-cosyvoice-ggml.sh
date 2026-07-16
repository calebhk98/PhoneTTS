#!/bin/sh
#
# Fetches the CrispStrobe/CrispASR source tree the CosyVoice native bridge
# (app/src/main/cpp/cosyvoice/CMakeLists.txt) compiles against — the self-contained C++/ggml
# `cosyvoice3_tts` engine that runs the WHOLE CosyVoice3-0.5B pipeline on-device (Qwen2 LLM +
# DiT-CFM flow + HiFi-GAN/iSTFT HiFT + native Qwen2 BPE). Spec §5.3 second runtime; see
# docs/COSYVOICE2.md and docs/research/cosyvoice2-mobile.md.
#
# The exact `cosyvoice3_tts` + `crispasr-core` + `chatterbox` + `ggml` targets this fetches were
# built and RUN on desktop by scripts/model-verify/run_cosy_native.sh (real 24 kHz audio out), so
# the code path is proven; only the NDK cross-compile is done here. Not a git submodule and not run
# automatically by any Gradle task, because the automated dev environment can't reach the network
# during a build — run this by hand once, on a machine with network + the Android SDK/NDK.
#
# Pin: CrispASR moves fast; pin COMMIT to the exact revision cosyvoice_jni.cpp's C-ABI usage was
# written against once a real on-device build resolves a known-good one. HEAD is used until then.
#
# Usage:
#   sh scripts/fetch-cosyvoice-ggml.sh
#
set -e

CRISPASR_REPO="https://github.com/CrispStrobe/CrispASR.git"
# COMMIT="" # set to a pinned revision once verified on-device; empty = default branch HEAD
REPO_ROOT=$(git rev-parse --show-toplevel)
DEST="$REPO_ROOT/app/src/main/cpp/cosyvoice/crispasr"

if [ -d "$DEST/.git" ]; then
    echo "fetch-cosyvoice-ggml: $DEST already exists -- remove it first to re-fetch."
    exit 0
fi

echo "fetch-cosyvoice-ggml: cloning CrispASR into $DEST ..."
git clone --depth 1 "$CRISPASR_REPO" "$DEST"

echo "fetch-cosyvoice-ggml: initialising the ggml submodule (the CrispStrobe/ggml fork) ..."
git -C "$DEST" submodule update --init --depth 1 ggml

echo "fetch-cosyvoice-ggml: done. Build with -PwithCosyVoice=true to link cosyvoice3_tts (previously"
echo "a stub was built -- see app/src/main/cpp/cosyvoice/CMakeLists.txt's guard). The four GGUF"
echo "stages (llm/flow/hift/voices) are separate downloads into app-private storage (never bundled"
echo "in the APK, CLAUDE.md rule 7) -- see docs/COSYVOICE2.md and scripts/model-verify/run_cosy_native.sh."
