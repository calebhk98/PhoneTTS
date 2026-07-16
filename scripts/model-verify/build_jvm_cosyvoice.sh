#!/usr/bin/env bash
# Builds the DESKTOP JVM binding of CrispASR's cosyvoice3_tts (integration/src/test/cpp/
# jvm_cosyvoice_jni.cpp) so RealCosyVoiceAutoLoadTest can drive PhoneTTS's OWN Kotlin pipeline
# against a real GGUF stack — the CosyVoice analog of the desktop onnxruntime jar the ONNX engines
# test through. Depends on the CrispASR desktop static libs already built by run_cosy_native.sh
# (its build/ dir). Prints the -D flags to hand gradle.
#
# Usage:  scripts/model-verify/build_jvm_cosyvoice.sh [crispasr_build_dir] [out_dir]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CRISPASR_BUILD="${1:-$HERE/.cv3-native/CrispASR}"     # the tree run_cosy_native.sh built
OUT="${2:-$HERE/.cv3-native/jvmnative}"
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"

if [ ! -f "$CRISPASR_BUILD/build/src/libcosyvoice3_tts.a" ]; then
    echo "error: $CRISPASR_BUILD/build/src/libcosyvoice3_tts.a not found."
    echo "Run scripts/model-verify/run_cosy_native.sh first (it builds the CrispASR desktop libs)."
    exit 1
fi

mkdir -p "$OUT"
echo "== compiling libjvmcosyvoice.so =="
g++ -std=c++17 -O2 -fPIC -shared \
    -I "$JH/include" -I "$JH/include/linux" -I "$CRISPASR_BUILD/src" -I "$CRISPASR_BUILD/include" \
    "$HERE/../../integration/src/test/cpp/jvm_cosyvoice_jni.cpp" \
    "$CRISPASR_BUILD/build/src/libcosyvoice3_tts.a" \
    "$CRISPASR_BUILD/build/src/libchatterbox.a" \
    "$CRISPASR_BUILD/build/src/libcrispasr-core.a" \
    -L"$CRISPASR_BUILD/build/ggml/src" -lggml -lggml-base -lggml-cpu -fopenmp -lpthread -lm -ldl \
    -Wl,-rpath,'$ORIGIN' \
    -o "$OUT/libjvmcosyvoice.so"
# ggml shared libs must sit next to the JNI lib ($ORIGIN rpath).
cp "$CRISPASR_BUILD"/build/ggml/src/libggml*.so* "$OUT/"

echo "== done =="
echo "Run the real-model test with:"
echo "  gradle :integration:test --tests '*RealCosyVoiceAutoLoadTest*' \\"
echo "    -DrunRealModel=true -Dcosyvoice.nativeLib=$OUT/libjvmcosyvoice.so \\"
echo "    -Dcosyvoice.modelDir=<dir with the 4 cosyvoice3-*.gguf files>"
