#!/usr/bin/env bash
# Prove the FULLY-NATIVE ggml CosyVoice3 pipeline produces real 24 kHz speech — the ggml analog of
# the PyTorch proof in run_cosy.py, but running the ACTUAL C++/ggml code the app ships (no PyTorch,
# no ONNX). It builds CrispStrobe/CrispASR's `cosyvoice3_tts` static lib (a whisper.cpp fork with a
# complete native CosyVoice3: Qwen2-0.5B LLM + DiT-CFM flow + HiFi-GAN/iSTFT HiFT + BPE tokenizer),
# downloads the Apache-2.0 GGUF stack, and synthesizes a sentence through the public C ABI.
#
# This is what docs/COSYVOICE2.md's "on-device" section is built on: the same cosyvoice3_tts.cpp
# sources are vendored into the app via scripts/fetch-cosyvoice-ggml.sh + app/src/main/cpp/cosyvoice.
#
# Requirements: git, cmake (>=3.16), a C++17 compiler, curl, ~1 GB disk for weights + build.
# Usage:  scripts/model-verify/run_cosy_native.sh [workdir]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${1:-$HERE/.cv3-native}"
mkdir -p "$WORK"
cd "$WORK"

CRISPASR_REPO="https://github.com/CrispStrobe/CrispASR.git"
GGUF_BASE="https://huggingface.co/cstr/cosyvoice3-0.5b-2512-GGUF/resolve/main"
# Minimal 745 MB combo the upstream README validates at 0% content WER.
GGUF_FILES=(cosyvoice3-llm-q4_k.gguf cosyvoice3-flow-q8_0.gguf cosyvoice3-hift-f16.gguf cosyvoice3-voices.gguf)

echo "== 1/4 clone CrispASR =="
[ -d CrispASR ] || git clone --depth 1 "$CRISPASR_REPO"
cd CrispASR
git submodule update --init --depth 1 ggml

echo "== 2/4 build cosyvoice3_tts (+ deps) — CPU only =="
cmake -B build -DCMAKE_BUILD_TYPE=Release -DGGML_CUDA=OFF -DGGML_METAL=OFF -DGGML_VULKAN=OFF -DCRISPASR_MEL_BLAS=OFF
cmake --build build --target cosyvoice3_tts chatterbox -j"$(nproc)"

echo "== 3/4 download GGUF weights =="
mkdir -p "$WORK/gguf"
for f in "${GGUF_FILES[@]}"; do
    [ -s "$WORK/gguf/$f" ] || curl -sSL -o "$WORK/gguf/$f" "$GGUF_BASE/$f"
done

echo "== 4/4 compile + run the driver =="
g++ -std=c++17 -O2 -I src -I include \
    "$HERE/cv3_native_driver.cpp" \
    build/src/libcosyvoice3_tts.a build/src/libchatterbox.a build/src/libcrispasr-core.a \
    -L build/ggml/src -lggml -lggml-base -lggml-cpu -fopenmp -lpthread -lm -ldl \
    -o "$WORK/cv3_driver"

export LD_LIBRARY_PATH="$PWD/build/ggml/src:${LD_LIBRARY_PATH:-}"
"$WORK/cv3_driver" "$WORK/gguf" "Hello, this is a test of on device text to speech." "fleurs-en" "$WORK/cv3_out.wav"
echo "wrote $WORK/cv3_out.wav"
