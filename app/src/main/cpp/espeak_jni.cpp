// JNI bridge between com.phonetts.app.text.EspeakNative (Kotlin) and espeak-ng.
//
// Exposes exactly two entry points, both synchronous and safe to call from any thread the
// Kotlin side chooses to serialize onto (EspeakPhonemizer uses a single-threaded dispatcher —
// espeak-ng keeps mutable global state (current voice, synthesis context) and is documented as
// NOT reentrant across threads; see docs/research/espeak-ng.md §6.1):
//
//   nativeInit(dataPath)                  -> sample rate (>=0) on success, negative on failure
//   nativeTextToPhonemesIpa(text, voice)  -> IPA phoneme string, or null on failure
//
// PHONETTS_HAVE_ESPEAK is defined by CMakeLists.txt only when the espeak-ng source tree was
// present at configure time and actually linked in. When it is NOT defined, every function
// below compiles against no espeak-ng headers at all and simply reports "unavailable" — this is
// what keeps the module buildable before scripts/fetch-espeak-ng.sh has been run (see
// docs/espeak-ng-integration.md).
#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>

#ifdef PHONETTS_HAVE_ESPEAK
// ASSUMPTION (unverified in this environment — no NDK/espeak-ng source checkout available here):
// espeak-ng's CMake target exports its own public include directory (upstream CMakeLists.txt
// does `target_include_directories(espeak-ng PUBLIC src/include)`), so this header resolves once
// CMakeLists.txt links `phonetts_espeak` against the `espeak-ng` target. If the real header path
// differs from a released espeak-ng 1.52.0 tree, this is the one line to fix.
#include <espeak-ng/speak_lib.h>
#endif

namespace {

constexpr const char *kLogTag = "PhoneTTS/espeak";

// Serializes every call into espeak-ng. Cheap defense-in-depth on top of the Kotlin-side
// single-threaded dispatcher (docs/research/espeak-ng.md §6.1) — never a hot path.
std::mutex g_espeakMutex;

// Hard ceiling on the clause-splitting loop below so a pathological input (or an unexpected
// espeak-ng return-pointer behavior we can't validate without a device) can't spin forever.
constexpr int kMaxClauseIterations = 20000;

jstring toJavaString(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_phonetts_app_text_EspeakNative_nativeInit(JNIEnv *env, jclass /*clazz*/, jstring dataPath) {
#ifdef PHONETTS_HAVE_ESPEAK
    std::lock_guard<std::mutex> lock(g_espeakMutex);

    const char *path = env->GetStringUTFChars(dataPath, nullptr);
    if (path == nullptr) return -1;

    // AUDIO_OUTPUT_RETRIEVAL: we never call espeak_Synth, only espeak_TextToPhonemes, so there
    // is no audio device to open (docs/research/espeak-ng.md §3.3). buflength=0 and options=0
    // are the documented defaults for this mode.
    const int result = espeak_Initialize(AUDIO_OUTPUT_RETRIEVAL, 0, path, 0);
    env->ReleaseStringUTFChars(dataPath, path);

    if (result < 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "espeak_Initialize failed: %d", result);
    }
    return result;
#else
    (void)env;
    (void)dataPath;
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "nativeInit: espeak-ng was not built in (stub library)");
    return -1;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phonetts_app_text_EspeakNative_nativeTextToPhonemesIpa(
    JNIEnv *env,
    jclass /*clazz*/,
    jstring text,
    jstring voice
) {
#ifdef PHONETTS_HAVE_ESPEAK
    std::lock_guard<std::mutex> lock(g_espeakMutex);

    const char *voiceName = env->GetStringUTFChars(voice, nullptr);
    const espeak_ERROR voiceResult = espeak_SetVoiceByName(voiceName);
    env->ReleaseStringUTFChars(voice, voiceName);
    if (voiceResult != EE_OK) {
        // Not fatal: fall through and phonemize with whatever voice is already active rather
        // than failing closed here — an unrecognized language code should degrade, not crash.
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "espeak_SetVoiceByName failed: %d", voiceResult);
    }

    const char *textUtf8 = env->GetStringUTFChars(text, nullptr);
    if (textUtf8 == nullptr) return nullptr;
    // espeak_TextToPhonemes advances the pointer it's given as it consumes clauses; copy the
    // text into a buffer we own so the JNI-owned chars can be released immediately.
    const std::string textCopy(textUtf8);
    env->ReleaseStringUTFChars(text, textUtf8);

    const char *textPtr = textCopy.c_str();
    std::string phonemes;
    int iterations = 0;
    while (textPtr != nullptr && iterations++ < kMaxClauseIterations) {
        const char *clause =
            espeak_TextToPhonemes(reinterpret_cast<const void **>(&textPtr), espeakCHARS_UTF8, espeakPHONEMES_IPA);
        if (clause == nullptr) continue;
        if (!phonemes.empty()) phonemes += ' ';
        phonemes += clause;
    }
    return toJavaString(env, phonemes);
#else
    (void)env;
    (void)text;
    (void)voice;
    __android_log_print(
        ANDROID_LOG_WARN, kLogTag, "nativeTextToPhonemesIpa: espeak-ng was not built in (stub library)");
    return nullptr;
#endif
}
