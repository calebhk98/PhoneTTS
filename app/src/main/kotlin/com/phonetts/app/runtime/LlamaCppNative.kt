package com.phonetts.app.runtime

import android.util.Log

// Thin declaration of the JNI surface implemented in app/src/main/cpp/cosyvoice/cosyvoice_jni.cpp.
// Mirrors EspeakNative exactly: a bare object with no logic of its own -- LlamaCppSpeechTokenRuntime
// owns every decision (whether native loading succeeded, model handles, teardown). The native lib
// is built ONLY when the app is assembled with -PwithCosyVoice=true (NDK + CMake + a llama.cpp
// checkout); otherwise this object's loadLibrary fails and isLibraryLoaded stays false, so
// LlamaCppSpeechTokenRuntime.isAvailable() reports false and CosyVoice2 is simply not offered.
internal object LlamaCppNative {
    private const val LIB_NAME = "phonetts_cosyvoice"
    private const val TAG = "LlamaCppNative"

    /** Attempted once, at class-init time. False if the .so wasn't built (the -PwithCosyVoice off case). */
    val isLibraryLoaded: Boolean =
        runCatching { System.loadLibrary(LIB_NAME) }
            .onFailure { Log.w(TAG, "libphonetts_cosyvoice.so failed to load: ${it.message}") }
            .isSuccess

    /** Load a GGUF speech-token LLM; returns an opaque native handle (>0), or 0 on failure. */
    @JvmStatic
    external fun nativeInit(
        modelPath: String,
        threads: Int,
    ): Long

    /**
     * Run the full autoregressive speech-token decode for one utterance. The native side owns the
     * KV-cache loop, the repeat-aware sampler and the stop condition (see the native TODO in
     * cosyvoice_jni.cpp / docs/COSYVOICE2.md). Returns the generated speech token ids, or null on
     * failure. [speed] is the native token-rate knob (CLAUDE.md rule 2).
     */
    @JvmStatic
    external fun nativeGenerate(
        handle: Long,
        textTokenIds: LongArray,
        speakerEmbedding: FloatArray,
        promptSpeechTokens: LongArray,
        speed: Float,
    ): LongArray?

    /** Free the native model handle opened by [nativeInit]. */
    @JvmStatic
    external fun nativeFree(handle: Long)
}
