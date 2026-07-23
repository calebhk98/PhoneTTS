package com.phonetts.app.runtime

import android.util.Log
import com.phonetts.core.runtime.NativeTtsRequest
import com.phonetts.core.runtime.NativeTtsRuntime
import com.phonetts.core.runtime.NativeTtsSession
import com.phonetts.core.runtime.RuntimeOptions

/**
 * The concrete native ggml backend for CosyVoice - the "second, non-ONNX runtime" the spec
 * anticipated (§5.3), registered under the id `"cosyvoice"` that [com.phonetts.engines.cosyvoice2]
 * asks for. It wraps CrispStrobe/CrispASR's self-contained C++/ggml `cosyvoice3_tts` engine (Qwen2
 * LLM + DiT-CFM flow + HiFi-GAN/iSTFT HiFT + native BPE), which does the ENTIRE text→audio pipeline
 * in one call - proven end-to-end in `scripts/model-verify/run_cosy_native.sh`. It therefore
 * implements [NativeTtsRuntime], not the ONNX [com.phonetts.core.runtime.Runtime].
 *
 * NATIVE STATUS: the JNI declarations ([CosyVoiceNative]) and this Kotlin side COMPILE and are
 * wired; the underlying `cosyvoice3_tts` sources are proven on desktop and vendored for the NDK
 * build via `scripts/fetch-cosyvoice-ggml.sh`. The lib is opt-in (`-PwithCosyVoice=true`); when it
 * isn't built, [isAvailable] returns false and CosyVoice is never offered - the rest of the app
 * assembles and runs unchanged.
 */
class NativeCosyVoiceRuntime : NativeTtsRuntime {
    override val id: String = RUNTIME_ID

    override fun isAvailable(): Boolean = CosyVoiceNative.isLibraryLoaded

    override fun openTtsSession(
        modelDir: String,
        options: RuntimeOptions,
    ): NativeTtsSession {
        check(CosyVoiceNative.isLibraryLoaded) {
            "libphonetts_cosyvoice.so is not loaded - build the app with -PwithCosyVoice=true to enable CosyVoice"
        }
        val handle =
            CosyVoiceNative.nativeInit(
                modelDir = modelDir,
                threads = options.intraOpThreads.coerceAtLeast(1),
                temperature = SAMPLER_TEMPERATURE,
                seed = SAMPLER_SEED,
            )
        check(handle != 0L) { "cosyvoice3_tts failed to load the CosyVoice3 GGUF stack in '$modelDir'" }
        val sampleRate = CosyVoiceNative.nativeSampleRate(handle)
        val voices = CosyVoiceNative.nativeVoiceNames(handle)?.toList().orEmpty()
        return NativeCosyVoiceSession(handle, sampleRate, voices)
    }

    private companion object {
        const val RUNTIME_ID = "cosyvoice"

        // CV3 greedy decode (temperature 0) falls into a documented silent-token loop; the RAS
        // sampler needs a positive temperature to engage (docs/COSYVOICE2.md). 0.8 / seed 42 match
        // the CrispASR backend default and the values run_cosy_native.sh proved.
        const val SAMPLER_TEMPERATURE = 0.8f
        const val SAMPLER_SEED = 42L
    }
}

/**
 * One loaded CosyVoice3 pipeline. [synthesize] runs the entire native text→audio decode for one
 * utterance and hands back finished PCM; [close] frees the native handle. Blocking, like the ONNX
 * [com.phonetts.core.runtime.InferenceSession] - synthesis already runs off the main thread.
 */
private class NativeCosyVoiceSession(
    private val handle: Long,
    override val sampleRate: Int,
    override val voiceNames: List<String>,
) : NativeTtsSession {
    private var closed = false

    override fun synthesize(request: NativeTtsRequest): FloatArray {
        check(!closed) { "synthesize() called on a closed CosyVoice session" }
        val audio = CosyVoiceNative.nativeSynthesize(handle, request.text, request.voiceName)
        return audio ?: error("CosyVoice3 synth returned no audio for voice '${request.voiceName}' (native failure)")
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { CosyVoiceNative.nativeFree(handle) }
            .onFailure { Log.w(TAG, "nativeFree failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "NativeCosyVoice"
    }
}
