package com.phonetts.engines.common

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.text.TextChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The parts of every engine's `load()`/`synthesize()` that are genuinely identical:
 *
 *  - `load()`: check the engine's runtime is actually available on this device BEFORE touching
 *    any weights (issue #18 item 3), then delegate the model-specific work to [doLoad]. This is a
 *    template method, not a full `load()` reimplementation - engine session topologies still
 *    differ (Piper keys N sessions by voice, MeloTTS drives a BERT session from its frontend,
 *    CosyVoice2 holds three) and [doLoad] is exactly as free to shape that as `load()` used to be.
 *    So no engine can forget the availability check, but no engine is forced through one topology.
 *  - `synthesize()`: guard the speed (both positivity and the model's own declared range, SSOT -
 *    never a literal), verify the engine is loaded, split the text into sentences and emit one
 *    chunk per sentence so playback/writing can start early (spec §8). Everything model-specific -
 *    the frontend, the tensor assembly with the native speed/voice params, the runtime call, the
 *    output→FloatArray conversion, and (for CosyVoice2) the autoregressive loop - is opaque to this
 *    base, hidden behind [synthesizeSentence]. So this class names no model and encodes no model
 *    fact.
 */
abstract class AbstractVoiceEngine(
    protected val context: EngineContext,
) : VoiceEngine {
    /** A human-readable label for error messages (typically the engine id). */
    protected abstract val engineLabel: String

    /** True once [load] has completed and the engine can synthesize. */
    protected abstract fun isLoaded(): Boolean

    /**
     * True if the runtime backend this engine needs is present and usable on this device.
     * Typically `requireRuntime(context, RUNTIME_ID, engineLabel).isAvailable()`. Checked by the
     * base [load] before [doLoad] runs (issue #18 item 3), so no engine can forget it.
     */
    protected abstract fun isRuntimeAvailable(): Boolean

    /**
     * The message [load] fails with when [isRuntimeAvailable] is false. Override to point at
     * something actionable (e.g. a native build flag); the default just names [engineLabel].
     */
    protected open fun runtimeUnavailableMessage(): String = "$engineLabel's runtime is not available on this device"

    /** Pull weights into memory - the model-specific part of `load()` (see class KDoc). */
    protected abstract suspend fun doLoad(descriptor: ModelDescriptor)

    /**
     * Synthesize exactly one sentence to audio samples. All model-specific work lives here - read
     * whatever declared parameters this engine supports from [params] (e.g. `params.speed`).
     */
    protected abstract fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        params: SynthesisParams,
    ): FloatArray

    // The descriptor most recently handed to load() - SSOT for synthesize()'s speed-range guard.
    // Set unconditionally here (not left to each engine) so every engine gets the guard for free.
    private var loadedDescriptor: ModelDescriptor? = null

    final override suspend fun load(descriptor: ModelDescriptor) {
        check(isRuntimeAvailable()) { runtimeUnavailableMessage() }
        doLoad(descriptor)
        loadedDescriptor = descriptor
    }

    final override fun synthesize(
        text: String,
        voiceId: String,
        params: SynthesisParams,
    ): Flow<FloatArray> {
        require(params.speed > 0f) { "speed must be positive, was ${params.speed}" }
        check(isLoaded()) { "$engineLabel.synthesize called before load()" }
        // SSOT (spec rule 1): the valid range comes from the loaded model's own descriptor, never
        // a literal here or in a subclass.
        val range = checkNotNull(loadedDescriptor) { "$engineLabel.synthesize called before load()" }.speedRange
        require(params.speed in range) {
            "$engineLabel: speed ${params.speed} is outside the supported range $range"
        }
        return flow {
            for (sentence in TextChunker.intoSentences(text)) {
                emit(synthesizeSentence(sentence, voiceId, params))
            }
            // flowOn moves the per-sentence inference off the collector's thread (rule 8: never on
            // the main thread), so a UI callsite can't accidentally run ONNX/AR inference on Main.
        }.flowOn(Dispatchers.Default)
    }
}
