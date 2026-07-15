package com.phonetts.core.model

import com.phonetts.core.engine.Voice

/**
 * Where a model came from. Recorded for display only — it must NEVER be used to branch
 * runtime logic. A sideloaded model is a first-class citizen (spec §1.1.7, §5.4).
 */
enum class Origin {
    BUILT_IN,
    SIDELOADED,
}

/**
 * The single authority for every user-visible fact about a model (spec §5.7).
 *
 * THE RULE THAT MAKES SSOT REAL: no model fact — sample rate, voice name, speed bound,
 * display name — may appear as a literal anywhere outside this descriptor/resolver layer.
 * The UI reads only from here. A model constant outside the resolver layer is a bug.
 */
data class ModelDescriptor(
    val modelId: String,
    val engineId: String,
    val displayName: String,
    val origin: Origin,
    /** Varies per model — playback and the WAV writer read THIS, never a constant. */
    val sampleRate: Int,
    /** The voice dropdown reads THIS. */
    val voices: List<Voice>,
    /** The speed slider configures its own bounds from THIS. */
    val speedRange: ClosedFloatingPointRange<Float>,
    val defaultVoiceId: String,
    val defaultSpeed: Float,
    /**
     * Logical file name → on-device absolute path for this model's weights and side files,
     * populated by the engine from the bundle it inspected. Generic (every model has files);
     * WHICH names an engine looks up is the engine's own business, never shared code's.
     */
    val assetPaths: Map<String, String> = emptyMap(),
) {
    init {
        require(voices.isNotEmpty()) { "descriptor $modelId must expose at least one voice" }
        require(voices.any { it.id == defaultVoiceId }) {
            "defaultVoiceId '$defaultVoiceId' is not among the voices of $modelId"
        }
        require(defaultSpeed in speedRange) {
            "defaultSpeed $defaultSpeed is outside speedRange $speedRange for $modelId"
        }
        require(sampleRate > 0) { "sampleRate must be positive for $modelId" }
    }
}
