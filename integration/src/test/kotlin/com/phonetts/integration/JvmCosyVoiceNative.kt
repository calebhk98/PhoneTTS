package com.phonetts.integration

// Desktop twin of the app's com.phonetts.app.runtime.CosyVoiceNative, backed by the same CrispASR
// cosyvoice3_tts C ABI via integration/src/test/cpp/jvm_cosyvoice_jni.cpp. Loaded from the .so path
// in the `cosyvoice.nativeLib` system property (built by scripts/model-verify/build_jvm_cosyvoice.sh);
// isLibraryLoaded is false when that property/lib is absent, so the CosyVoice real-model test skips
// cleanly on a machine without the native build (exactly like the app's runtime when
// -PwithCosyVoice is off).
internal object JvmCosyVoiceNative {
    val isLibraryLoaded: Boolean =
        runCatching {
            val path = System.getProperty("cosyvoice.nativeLib") ?: error("cosyvoice.nativeLib not set")
            System.load(path)
        }.isSuccess

    @JvmStatic
    external fun nativeInit(
        modelDir: String,
        threads: Int,
        temperature: Float,
        seed: Long,
    ): Long

    @JvmStatic
    external fun nativeSampleRate(handle: Long): Int

    @JvmStatic
    external fun nativeVoiceNames(handle: Long): Array<String>?

    @JvmStatic
    external fun nativeSynthesize(
        handle: Long,
        text: String,
        voiceName: String,
    ): FloatArray?

    @JvmStatic
    external fun nativeFree(handle: Long)
}
