package com.phonetts.app.runtime

import android.util.Log

// Thin declaration of the JNI surface implemented in app/src/main/cpp/cosyvoice/cosyvoice_jni.cpp,
// which bridges to CrispStrobe/CrispASR's native ggml `cosyvoice3_tts` C ABI (proven end-to-end in
// scripts/model-verify/run_cosy_native.sh). Mirrors EspeakNative: a bare object with no logic of its
// own -- NativeCosyVoiceRuntime owns every decision (whether native loading succeeded, handles,
// teardown). The native lib is built ONLY when the app is assembled with -PwithCosyVoice=true (NDK +
// CMake + a CrispASR cosyvoice3 source checkout via scripts/fetch-cosyvoice-ggml.sh); otherwise this
// object's loadLibrary fails and isLibraryLoaded stays false, so NativeCosyVoiceRuntime.isAvailable()
// reports false and CosyVoice is simply not offered.
internal object CosyVoiceNative {
    private const val LIB_NAME = "phonetts_cosyvoice"
    private const val TAG = "CosyVoiceNative"

    /** Attempted once, at class-init time. False if the .so wasn't built (the -PwithCosyVoice off case). */
    val isLibraryLoaded: Boolean =
        runCatching { System.loadLibrary(LIB_NAME) }
            .onFailure { Log.w(TAG, "libphonetts_cosyvoice.so failed to load: ${it.message}") }
            .isSuccess

    /**
     * Load the four-GGUF CosyVoice3 stack from [modelDir] (the native side discovers
     * `cosyvoice3-{llm,flow,hift,voices}-*.gguf` as siblings, exactly like the CrispASR CLI). Returns
     * an opaque native handle (>0), or 0 on failure. [temperature] must be > 0 to engage the RAS
     * sampler (greedy falls into CV3's silent-token loop — see docs/COSYVOICE2.md); [seed] makes a
     * run reproducible.
     */
    @JvmStatic
    external fun nativeInit(
        modelDir: String,
        threads: Int,
        temperature: Float,
        seed: Long,
    ): Long

    /** The output sample rate of the loaded model (24000 for CosyVoice3), or 0 on failure. */
    @JvmStatic
    external fun nativeSampleRate(handle: Long): Int

    /** The baked voice names the loaded voices GGUF carries (the SSOT for the engine's voice list). */
    @JvmStatic
    external fun nativeVoiceNames(handle: Long): Array<String>?

    /**
     * Run the FULL native text→audio pipeline for one utterance: Qwen2 BPE tokenization, the AR
     * speech-token loop (RAS sampler), the flow-matching ODE and the HiFT vocoder all run inside the
     * native library (cosyvoice3_tts_synth). Returns finished 24 kHz mono PCM, or null on failure.
     */
    @JvmStatic
    external fun nativeSynthesize(
        handle: Long,
        text: String,
        voiceName: String,
    ): FloatArray?

    /** Free the native handle opened by [nativeInit]. */
    @JvmStatic
    external fun nativeFree(handle: Long)
}
