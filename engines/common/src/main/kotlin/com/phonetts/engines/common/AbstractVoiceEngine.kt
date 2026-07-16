package com.phonetts.engines.common

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.text.TextChunker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The one part of every engine's `synthesize()` that is genuinely identical: guard the speed,
 * verify the engine is loaded, split the text into sentences and emit one chunk per sentence so
 * playback/writing can start early (spec §8). Everything model-specific — the frontend, the
 * tensor assembly with the native speed/voice params, the runtime call, the output→FloatArray
 * conversion, and (for CosyVoice2) the autoregressive loop — is opaque to this base, hidden
 * behind [synthesizeSentence]. So this class names no model and encodes no model fact.
 *
 * Deliberately NOT templating `load()`/`unload()`: engine session topologies differ (Piper keys
 * N sessions by voice, MeloTTS drives a BERT session from its frontend, CosyVoice2 holds three),
 * and forcing them through one shape would require branching on topology — exactly the
 * per-model coupling the design forbids. Those use the leaf helpers instead.
 */
abstract class AbstractVoiceEngine(
    protected val context: EngineContext,
) : VoiceEngine {
    /** A human-readable label for error messages (typically the engine id). */
    protected abstract val engineLabel: String

    /** True once [load] has completed and the engine can synthesize. */
    protected abstract fun isLoaded(): Boolean

    /** Synthesize exactly one sentence to audio samples. All model-specific work lives here. */
    protected abstract fun synthesizeSentence(
        sentence: String,
        voiceId: String,
        speed: Float,
    ): FloatArray

    final override fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
    ): Flow<FloatArray> {
        require(speed > 0f) { "speed must be positive, was $speed" }
        check(isLoaded()) { "$engineLabel.synthesize called before load()" }
        return flow {
            for (sentence in TextChunker.intoSentences(text)) {
                emit(synthesizeSentence(sentence, voiceId, speed))
            }
        }
    }
}
