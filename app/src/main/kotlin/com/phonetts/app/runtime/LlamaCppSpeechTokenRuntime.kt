package com.phonetts.app.runtime

import android.util.Log
import com.phonetts.core.runtime.RuntimeOptions
import com.phonetts.core.runtime.SpeechTokenRequest
import com.phonetts.core.runtime.SpeechTokenRuntime
import com.phonetts.core.runtime.SpeechTokenSession

/**
 * The concrete llama.cpp / ggml backend for CosyVoice2's autoregressive speech-token LLM — the
 * "second, LLM-style runtime" the spec anticipated (§5.3), registered under the id
 * `"cosyvoice-llm"` that [com.phonetts.engines.cosyvoice2] asks for. It is the mobile path the
 * research settled on (docs/research/cosyvoice2-mobile.md): a GGUF Qwen2-0.5B decode loop, NOT
 * ONNX, so it implements [SpeechTokenRuntime] rather than the ONNX [com.phonetts.core.runtime.Runtime].
 *
 * NATIVE STATUS: the JNI declarations ([LlamaCppNative]) and this Kotlin side COMPILE and are wired,
 * but the native library is opt-in (`-PwithCosyVoice=true`) and UNVERIFIED on-device — the ggml
 * speech-token decode + repeat-aware sampler still have to be integrated (see cosyvoice_jni.cpp and
 * docs/COSYVOICE2.md). When the lib isn't built, [isAvailable] returns false and CosyVoice2 is
 * never offered; the rest of the app assembles and runs unchanged.
 */
class LlamaCppSpeechTokenRuntime : SpeechTokenRuntime {
    override val id: String = RUNTIME_ID

    override fun isAvailable(): Boolean = LlamaCppNative.isLibraryLoaded

    override fun openSpeechSession(
        modelPath: String,
        options: RuntimeOptions,
    ): SpeechTokenSession {
        check(LlamaCppNative.isLibraryLoaded) {
            "libphonetts_cosyvoice.so is not loaded — build the app with -PwithCosyVoice=true to enable CosyVoice2"
        }
        val handle = LlamaCppNative.nativeInit(modelPath, options.intraOpThreads.coerceAtLeast(1))
        check(handle != 0L) { "llama.cpp failed to load the CosyVoice2 GGUF model at '$modelPath'" }
        return LlamaCppSpeechTokenSession(handle)
    }

    private companion object {
        const val RUNTIME_ID = "cosyvoice-llm"
    }
}

/**
 * One loaded GGUF decoder. [generate] runs the entire native AR decode for one utterance and hands
 * back the speech token ids; [close] frees the native handle. Blocking, like the ONNX
 * [com.phonetts.core.runtime.InferenceSession] — synthesis already runs off the main thread.
 */
private class LlamaCppSpeechTokenSession(
    private val handle: Long,
) : SpeechTokenSession {
    private var closed = false

    override fun generate(request: SpeechTokenRequest): LongArray {
        check(!closed) { "generate() called on a closed CosyVoice2 speech-token session" }
        val tokens =
            LlamaCppNative.nativeGenerate(
                handle = handle,
                textTokenIds = request.textTokenIds,
                speakerEmbedding = request.speakerEmbedding,
                promptSpeechTokens = request.promptSpeechTokens,
                speed = request.speed,
            )
        return tokens ?: error("CosyVoice2 speech-token decode returned no tokens (native failure)")
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { LlamaCppNative.nativeFree(handle) }
            .onFailure { Log.w(TAG, "nativeFree failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "LlamaCppSpeechToken"
    }
}
