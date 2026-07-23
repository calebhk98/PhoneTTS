package com.phonetts.integration

import com.phonetts.core.runtime.NativeTtsRequest
import com.phonetts.core.runtime.NativeTtsRuntime
import com.phonetts.core.runtime.NativeTtsSession
import com.phonetts.core.runtime.RuntimeOptions

// Desktop twin of the app's NativeCosyVoiceRuntime - identical Kotlin logic over the JVM JNI binding
// ([JvmCosyVoiceNative]) instead of the Android one. Registered under the same id "cosyvoice" the
// CosyVoice engine asks for, so the real pipeline resolves and drives it unchanged.
internal class JvmNativeCosyVoiceRuntime : NativeTtsRuntime {
    override val id: String = "cosyvoice"

    override fun isAvailable(): Boolean = JvmCosyVoiceNative.isLibraryLoaded

    override fun openTtsSession(
        modelDir: String,
        options: RuntimeOptions,
    ): NativeTtsSession {
        check(JvmCosyVoiceNative.isLibraryLoaded) { "libjvmcosyvoice.so not loaded (set -Dcosyvoice.nativeLib)" }
        val handle = JvmCosyVoiceNative.nativeInit(modelDir, options.intraOpThreads.coerceAtLeast(1), 0.8f, 42L)
        check(handle != 0L) { "cosyvoice3_tts failed to load the GGUF stack in '$modelDir'" }
        val sampleRate = JvmCosyVoiceNative.nativeSampleRate(handle)
        val voices = JvmCosyVoiceNative.nativeVoiceNames(handle)?.toList().orEmpty()
        return JvmSession(handle, sampleRate, voices)
    }

    private class JvmSession(
        private val handle: Long,
        override val sampleRate: Int,
        override val voiceNames: List<String>,
    ) : NativeTtsSession {
        private var closed = false

        override fun synthesize(request: NativeTtsRequest): FloatArray {
            check(!closed) { "synthesize() on a closed session" }
            return JvmCosyVoiceNative.nativeSynthesize(handle, request.text, request.voiceName)
                ?: error("CosyVoice3 synth returned no audio for '${request.voiceName}'")
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { JvmCosyVoiceNative.nativeFree(handle) }
        }
    }
}
