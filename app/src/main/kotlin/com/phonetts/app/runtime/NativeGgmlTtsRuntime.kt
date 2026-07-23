package com.phonetts.app.runtime

import android.util.Log
import com.phonetts.core.runtime.NativeTtsRequest
import com.phonetts.core.runtime.NativeTtsRuntime
import com.phonetts.core.runtime.NativeTtsSession
import com.phonetts.core.runtime.RuntimeOptions

/**
 * The generalized native ggml backend - the SAME [NativeTtsRuntime] seam
 * [NativeCosyVoiceRuntime] proved for CosyVoice3 (spec §5.3), but parameterized by a CrispASR
 * `--backend` id instead of hardcoding `"cosyvoice"`. CrispASR (CrispStrobe/CrispASR, the ggml
 * project the app already vendors, see docs/COSYVOICE2.md) is a 34-backend project; its own docs
 * mark **Piper, Kokoro, MeloTTS, F5-TTS, Qwen3-TTS** "production-ready", all behind the identical
 * C ABI shape `NativeTtsRuntime` already models (`docs/research/runtime-feasibility-2026-07.md`
 * §2). This class is the Kotlin half of that generalization: `com.phonetts.engines.ggmltts`'s
 * `GgmlTtsEngine` discovers which backend a downloaded/sideloaded GGUF bundle needs (from its
 * companion manifest - see that module's KDoc) and threads it through here via
 * [RuntimeOptions.extras], never a per-backend subclass or a `when(backend)` branch.
 *
 * [defaultBackend] is an optional fallback for a caller that opens a session without setting
 * [BACKEND_OPTION_KEY] in [RuntimeOptions.extras]; every real caller (`GgmlTtsEngine`) always sets
 * it explicitly, so this only matters for a manual/test-tool invocation.
 *
 * NATIVE STATUS: this Kotlin side and [GgmlTtsNative]'s JNI declarations COMPILE with no native
 * library present (`isAvailable()` is false, exactly like [NativeCosyVoiceRuntime] before its
 * `.so` is built) - see `engines/ggmltts/INTEGRATION.md` for the CMake/Gradle wiring the PARENT
 * session still needs to add (a `-PwithGgmlTts=true` flag, a `phonetts_ggmltts` CMake target, and
 * - the actual remaining risk, same as CosyVoice - a successful NDK cross-compile, issue #46).
 */
class NativeGgmlTtsRuntime(
    override val id: String = RUNTIME_ID,
    private val defaultBackend: String = "piper",
) : NativeTtsRuntime {
    override fun isAvailable(): Boolean = GgmlTtsNative.isLibraryLoaded

    override fun openTtsSession(
        modelDir: String,
        options: RuntimeOptions,
    ): NativeTtsSession {
        check(GgmlTtsNative.isLibraryLoaded) {
            "libphonetts_ggmltts.so is not loaded - build the app with -PwithGgmlTts=true to enable" +
                " the generalized ggml backends (see engines/ggmltts/INTEGRATION.md)"
        }
        val backend = options.extras[BACKEND_OPTION_KEY] ?: defaultBackend
        val handle =
            GgmlTtsNative.nativeInit(
                backend = backend,
                modelDir = modelDir,
                threads = options.intraOpThreads.coerceAtLeast(1),
                temperature = SAMPLER_TEMPERATURE,
                seed = SAMPLER_SEED,
            )
        check(handle != 0L) { "ggml backend '$backend' failed to load the GGUF stack in '$modelDir'" }
        val sampleRate = GgmlTtsNative.nativeSampleRate(handle)
        val voices = GgmlTtsNative.nativeVoiceNames(handle)?.toList().orEmpty()
        return NativeGgmlTtsSession(handle, sampleRate, voices)
    }

    companion object {
        /** The [RuntimeRegistry][com.phonetts.core.registry.RuntimeRegistry] id this runtime registers under. */
        const val RUNTIME_ID = "ggml"

        /**
         * The [RuntimeOptions.extras] key this runtime reads the CrispASR backend id from. Must
         * match `com.phonetts.engines.ggmltts.GgmlTtsEngine.BACKEND_OPTION_KEY` - the same
         * cross-module string-contract pattern [NativeCosyVoiceRuntime]'s `"cosyvoice"` id already
         * uses with `CosyVoice2Engine.NATIVE_RUNTIME_ID` (neither module can see the other's
         * symbols at compile time: `:app` only ever depends on engine modules `runtimeOnly`).
         */
        const val BACKEND_OPTION_KEY = "backend"

        // Matches the CrispASR default this app already validated end-to-end for CosyVoice3
        // (docs/COSYVOICE2.md); backends that need different sampler defaults pass their own via
        // a future RuntimeOptions.extras entry, not a literal here.
        private const val SAMPLER_TEMPERATURE = 0.8f
        private const val SAMPLER_SEED = 42L
    }
}

/**
 * One loaded ggml pipeline for whichever backend it was opened with. [synthesize] runs the entire
 * native text→audio decode for one utterance; [close] frees the native handle. Blocking, like the
 * ONNX [com.phonetts.core.runtime.InferenceSession] - synthesis already runs off the main thread.
 */
private class NativeGgmlTtsSession(
    private val handle: Long,
    override val sampleRate: Int,
    override val voiceNames: List<String>,
) : NativeTtsSession {
    private var closed = false

    override fun synthesize(request: NativeTtsRequest): FloatArray {
        check(!closed) { "synthesize() called on a closed ggml session" }
        val audio = GgmlTtsNative.nativeSynthesize(handle, request.text, request.voiceName)
        return audio ?: error("ggml synth returned no audio for voice '${request.voiceName}' (native failure)")
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { GgmlTtsNative.nativeFree(handle) }
            .onFailure { Log.w(TAG, "nativeFree failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "NativeGgmlTts"
    }
}
