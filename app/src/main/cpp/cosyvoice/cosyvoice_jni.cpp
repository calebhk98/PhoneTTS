// JNI bridge between com.phonetts.app.runtime.CosyVoiceNative (Kotlin) and CrispStrobe/CrispASR's
// native ggml `cosyvoice3_tts` C ABI — the self-contained CosyVoice3-0.5B pipeline (Qwen2 LLM +
// DiT-CFM flow + HiFi-GAN/iSTFT HiFT + native Qwen2 BPE tokenizer). That C ABI was proven end-to-end
// on desktop in scripts/model-verify/run_cosy_native.sh (147 tokens -> 5.88 s of real 24 kHz audio),
// and the SAME cosyvoice3_tts.cpp sources are what this bridge links on the NDK build.
//
// Five entry points, all blocking, serialized on the Kotlin side (one engine loaded at a time):
//
//   nativeInit(modelDir, threads, temperature, seed) -> opaque ctx handle (>0), 0 on failure
//   nativeSampleRate(handle)                          -> output sample rate (24000), 0 on failure
//   nativeVoiceNames(handle)                          -> String[] of baked voice names, null on failure
//   nativeSynthesize(handle, text, voiceName)         -> float[] 24 kHz mono PCM, null on failure
//   nativeFree(handle)                                -> release the ctx
//
// PHONETTS_HAVE_COSYVOICE is defined by CMakeLists.txt ONLY when a CrispASR cosyvoice3 source
// checkout was present at configure time and actually linked in. When it is NOT defined, every
// function below compiles against no cosyvoice3 headers at all and simply reports "unavailable" --
// this keeps the module buildable before scripts/fetch-cosyvoice-ggml.sh has been run and lets the
// app assemble with -PwithCosyVoice off entirely (see docs/COSYVOICE2.md).
#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>
#include <vector>

#ifdef PHONETTS_HAVE_COSYVOICE
#include "cosyvoice3_tts.h"

#include <cstdlib>
#include <cstring>
#include <dirent.h>
#endif

namespace {

constexpr const char *kLogTag = "PhoneTTS/cosyvoice";

// Serializes native calls -- defense in depth on top of the Kotlin-side single loaded engine
// (SPEC rule 6). Never a hot path.
std::mutex g_cosyMutex;

// CosyVoice3 always emits 24 kHz mono; the C ABI has no sample-rate getter, so mirror the constant
// the CrispASR backend itself hardcodes (examples/cli/crispasr_backend_cosyvoice3.cpp).
constexpr jint kSampleRate = 24000;

#ifdef PHONETTS_HAVE_COSYVOICE
// Find the first file in `dir` whose name starts with `prefix` and ends with ".gguf" (the four
// CosyVoice3 stages carry a varying quant suffix: cosyvoice3-llm-q4_k.gguf, ...-flow-q8_0.gguf, ...).
// Returns the full path, or empty string if not found.
std::string discover_gguf(const std::string &dir, const std::string &prefix) {
    DIR *d = opendir(dir.c_str());
    if (d == nullptr) return "";
    std::string found;
    for (struct dirent *ent = readdir(d); ent != nullptr; ent = readdir(d)) {
        const std::string name = ent->d_name;
        const bool has_prefix = name.rfind(prefix, 0) == 0;
        const bool has_suffix = name.size() >= 5 && name.compare(name.size() - 5, 5, ".gguf") == 0;
        if (has_prefix && has_suffix) {
            found = dir + "/" + name;
            break;
        }
    }
    closedir(d);
    return found;
}
#endif

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_phonetts_app_runtime_CosyVoiceNative_nativeInit(
    JNIEnv *env,
    jclass /*clazz*/,
    jstring modelDir,
    jint threads,
    jfloat temperature,
    jlong seed
) {
#ifdef PHONETTS_HAVE_COSYVOICE
    std::lock_guard<std::mutex> lock(g_cosyMutex);
    const char *dir_c = env->GetStringUTFChars(modelDir, nullptr);
    if (dir_c == nullptr) return 0;
    const std::string dir = dir_c;
    env->ReleaseStringUTFChars(modelDir, dir_c);

    const std::string llm = discover_gguf(dir, "cosyvoice3-llm");
    const std::string flow = discover_gguf(dir, "cosyvoice3-flow");
    const std::string hift = discover_gguf(dir, "cosyvoice3-hift");
    const std::string voices = discover_gguf(dir, "cosyvoice3-voices");
    if (llm.empty() || flow.empty() || hift.empty() || voices.empty()) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "nativeInit: missing a CosyVoice3 GGUF stage in %s", dir.c_str());
        return 0;
    }

    cosyvoice3_tts_context_params cp = cosyvoice3_tts_context_default_params();
    cp.n_threads = threads > 0 ? threads : 4;
    cp.verbosity = 0;
    cp.use_gpu = false; // ggml CPU backend on-device (docs/research/cosyvoice2-mobile.md)
    cp.temperature = temperature; // > 0 engages the RAS sampler (greedy loops on silent tokens)
    cp.seed = static_cast<uint64_t>(seed);

    cosyvoice3_tts_context *ctx = cosyvoice3_tts_init_from_file(llm.c_str(), cp);
    if (ctx == nullptr) return 0;

    // The four stages compose for full end-to-end synth; any failure tears the ctx back down so a
    // half-open pipeline never leaks on a 4 GB phone.
    const bool ok =
        cosyvoice3_tts_init_flow_from_file(ctx, flow.c_str()) == 0 &&
        cosyvoice3_tts_init_hift_from_file(ctx, hift.c_str()) == 0 &&
        cosyvoice3_tts_init_voices_from_file(ctx, voices.c_str()) == 0;
    if (!ok) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "nativeInit: a CosyVoice3 stage failed to load");
        cosyvoice3_tts_free(ctx);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
#else
    (void)env;
    (void)modelDir;
    (void)threads;
    (void)temperature;
    (void)seed;
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "nativeInit: cosyvoice3_tts was not built in (stub library)");
    return 0;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_phonetts_app_runtime_CosyVoiceNative_nativeSampleRate(JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
#ifdef PHONETTS_HAVE_COSYVOICE
    return handle != 0 ? kSampleRate : 0;
#else
    (void)handle;
    return 0;
#endif
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_phonetts_app_runtime_CosyVoiceNative_nativeVoiceNames(JNIEnv *env, jclass /*clazz*/, jlong handle) {
#ifdef PHONETTS_HAVE_COSYVOICE
    std::lock_guard<std::mutex> lock(g_cosyMutex);
    auto *ctx = reinterpret_cast<cosyvoice3_tts_context *>(handle);
    if (ctx == nullptr) return nullptr;
    const int n = cosyvoice3_tts_n_voices(ctx);
    if (n <= 0) return nullptr;
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(n, stringClass, nullptr);
    for (int i = 0; i < n; i++) {
        const char *name = cosyvoice3_tts_voice_name(ctx, i);
        env->SetObjectArrayElement(out, i, env->NewStringUTF(name != nullptr ? name : ""));
    }
    return out;
#else
    (void)env;
    (void)handle;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_phonetts_app_runtime_CosyVoiceNative_nativeSynthesize(
    JNIEnv *env,
    jclass /*clazz*/,
    jlong handle,
    jstring text,
    jstring voiceName
) {
#ifdef PHONETTS_HAVE_COSYVOICE
    std::lock_guard<std::mutex> lock(g_cosyMutex);
    auto *ctx = reinterpret_cast<cosyvoice3_tts_context *>(handle);
    if (ctx == nullptr) return nullptr;

    const char *text_c = env->GetStringUTFChars(text, nullptr);
    const char *voice_c = env->GetStringUTFChars(voiceName, nullptr);
    if (text_c == nullptr || voice_c == nullptr) {
        if (text_c != nullptr) env->ReleaseStringUTFChars(text, text_c);
        if (voice_c != nullptr) env->ReleaseStringUTFChars(voiceName, voice_c);
        return nullptr;
    }

    int n_samples = 0;
    float *pcm = cosyvoice3_tts_synth(ctx, text_c, voice_c, &n_samples);
    env->ReleaseStringUTFChars(text, text_c);
    env->ReleaseStringUTFChars(voiceName, voice_c);

    if (pcm == nullptr || n_samples <= 0) {
        if (pcm != nullptr) free(pcm);
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "nativeSynthesize: synth produced no audio (n=%d)", n_samples);
        return nullptr;
    }

    jfloatArray out = env->NewFloatArray(n_samples);
    if (out != nullptr) {
        env->SetFloatArrayRegion(out, 0, n_samples, pcm);
    }
    free(pcm);
    return out;
#else
    (void)env;
    (void)handle;
    (void)text;
    (void)voiceName;
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "nativeSynthesize: cosyvoice3_tts was not built in (stub library)");
    return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_phonetts_app_runtime_CosyVoiceNative_nativeFree(JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
#ifdef PHONETTS_HAVE_COSYVOICE
    std::lock_guard<std::mutex> lock(g_cosyMutex);
    auto *ctx = reinterpret_cast<cosyvoice3_tts_context *>(handle);
    if (ctx != nullptr) cosyvoice3_tts_free(ctx);
#else
    (void)handle;
#endif
}
