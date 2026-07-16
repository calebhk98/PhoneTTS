// JNI bridge between com.phonetts.app.runtime.LlamaCppNative (Kotlin) and the llama.cpp / ggml
// CosyVoice2 speech-token decoder. Three entry points, all blocking, serialized on the Kotlin side:
//
//   nativeInit(modelPath, threads)                          -> opaque model handle (>0), 0 on failure
//   nativeGenerate(handle, textIds, spkEmbed, promptIds, speed) -> speech token ids, or null on failure
//   nativeFree(handle)                                      -> release the handle
//
// PHONETTS_HAVE_COSYVOICE is defined by CMakeLists.txt ONLY when a llama.cpp checkout was present at
// configure time and actually linked in. When it is NOT defined, every function below compiles
// against no llama.cpp headers at all and simply reports "unavailable" -- this is what keeps the
// module buildable before scripts/fetch-cosyvoice-llama.sh has been run and lets the app assemble
// with -PwithCosyVoice off entirely (see docs/COSYVOICE2.md).
//
// NATIVE TODO (unverified on-device): even with llama.cpp linked, the real speech-token decode is
// NOT yet implemented here. It requires porting the CrispStrobe/`cstr` ggml recipe --
// cosyvoice3.speech_embd / cosyvoice3.speech_lm_head tensors, the KV-cache AR loop, and the
// sliding-window "repeat-aware" (RAS) sampler that avoids the documented silent-token loop
// (docs/research/cosyvoice2-mobile.md §Q2). The bodies below are honest stubs, NOT a working decode.
#include <jni.h>
#include <android/log.h>

#include <mutex>

#ifdef PHONETTS_HAVE_COSYVOICE
// ASSUMPTION (unverified here -- no NDK/llama.cpp checkout in this environment): llama.cpp's CMake
// target exports its public headers, so these resolve once CMakeLists.txt links against it. If the
// real header layout differs, this is where to fix it.
#include "llama.h"
#endif

namespace {

constexpr const char *kLogTag = "PhoneTTS/cosyvoice";

// Serializes native calls -- defense in depth on top of the Kotlin-side single loaded engine
// (SPEC rule 6). Never a hot path.
std::mutex g_cosyMutex;

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_phonetts_app_runtime_LlamaCppNative_nativeInit(
    JNIEnv *env,
    jclass /*clazz*/,
    jstring modelPath,
    jint threads
) {
#ifdef PHONETTS_HAVE_COSYVOICE
    std::lock_guard<std::mutex> lock(g_cosyMutex);
    (void)threads;
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return 0;
    // NATIVE TODO: llama_model_load_from_file(path, ...) + build a CosyVoice2 decode context and
    // return it as a handle. Reporting "unavailable" until that decode is implemented and verified.
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "nativeInit: CosyVoice2 ggml decode not yet implemented");
    env->ReleaseStringUTFChars(modelPath, path);
    return 0;
#else
    (void)env;
    (void)modelPath;
    (void)threads;
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "nativeInit: llama.cpp was not built in (stub library)");
    return 0;
#endif
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_phonetts_app_runtime_LlamaCppNative_nativeGenerate(
    JNIEnv *env,
    jclass /*clazz*/,
    jlong handle,
    jlongArray textTokenIds,
    jfloatArray speakerEmbedding,
    jlongArray promptSpeechTokens,
    jfloat speed
) {
    (void)handle;
    (void)textTokenIds;
    (void)speakerEmbedding;
    (void)promptSpeechTokens;
    (void)speed;
    // No handle can exist yet (nativeInit always returns 0), so this is unreachable in practice; it
    // returns null so the Kotlin side raises a clear "native failure" rather than crash.
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "nativeGenerate: CosyVoice2 ggml decode not yet implemented");
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_phonetts_app_runtime_LlamaCppNative_nativeFree(JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    (void)handle;
}
