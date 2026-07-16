// Host-JVM twin of app/src/main/cpp/cosyvoice/cosyvoice_jni.cpp — the DESKTOP binding of the same
// CrispASR cosyvoice3_tts C ABI, used by RealModelAutoLoadTest to drive PhoneTTS's OWN Kotlin
// pipeline (Resolver -> inspect() -> ModelImporter -> EngineManager -> CosyVoice2Engine ->
// NativeTtsRuntime) against a real downloaded GGUF stack. It is the CosyVoice analog of the desktop
// onnxruntime jar the ONNX engines test through — the platform backend swapped for its JVM twin,
// the app code under test unchanged. Symbols target com.phonetts.integration.JvmCosyVoiceNative.
//
// Build (needs the CrispASR desktop static libs + a JDK):
//   scripts/model-verify/build_jvm_cosyvoice.sh   (documented there)
#include <jni.h>

#include "cosyvoice3_tts.h"

#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <string>

namespace {
// Same discovery the app bridge uses: first "cosyvoice3-<stage>-*.gguf" in the dir.
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
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_phonetts_integration_JvmCosyVoiceNative_nativeInit(
    JNIEnv *env, jclass, jstring modelDir, jint threads, jfloat temperature, jlong seed) {
    const char *dir_c = env->GetStringUTFChars(modelDir, nullptr);
    if (dir_c == nullptr) return 0;
    const std::string dir = dir_c;
    env->ReleaseStringUTFChars(modelDir, dir_c);

    const std::string llm = discover_gguf(dir, "cosyvoice3-llm");
    const std::string flow = discover_gguf(dir, "cosyvoice3-flow");
    const std::string hift = discover_gguf(dir, "cosyvoice3-hift");
    const std::string voices = discover_gguf(dir, "cosyvoice3-voices");
    if (llm.empty() || flow.empty() || hift.empty() || voices.empty()) return 0;

    cosyvoice3_tts_context_params cp = cosyvoice3_tts_context_default_params();
    cp.n_threads = threads > 0 ? threads : 4;
    cp.verbosity = 0;
    cp.use_gpu = false;
    cp.temperature = temperature;
    cp.seed = static_cast<uint64_t>(seed);

    cosyvoice3_tts_context *ctx = cosyvoice3_tts_init_from_file(llm.c_str(), cp);
    if (ctx == nullptr) return 0;
    const bool ok =
        cosyvoice3_tts_init_flow_from_file(ctx, flow.c_str()) == 0 &&
        cosyvoice3_tts_init_hift_from_file(ctx, hift.c_str()) == 0 &&
        cosyvoice3_tts_init_voices_from_file(ctx, voices.c_str()) == 0;
    if (!ok) {
        cosyvoice3_tts_free(ctx);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_phonetts_integration_JvmCosyVoiceNative_nativeSampleRate(JNIEnv *, jclass, jlong handle) {
    return handle != 0 ? 24000 : 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_phonetts_integration_JvmCosyVoiceNative_nativeVoiceNames(JNIEnv *env, jclass, jlong handle) {
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
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_phonetts_integration_JvmCosyVoiceNative_nativeSynthesize(
    JNIEnv *env, jclass, jlong handle, jstring text, jstring voiceName) {
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
        return nullptr;
    }
    jfloatArray out = env->NewFloatArray(n_samples);
    if (out != nullptr) env->SetFloatArrayRegion(out, 0, n_samples, pcm);
    free(pcm);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_phonetts_integration_JvmCosyVoiceNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<cosyvoice3_tts_context *>(handle);
    if (ctx != nullptr) cosyvoice3_tts_free(ctx);
}
