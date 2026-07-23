package com.phonetts.core.model

import com.phonetts.core.engine.Voice

/**
 * Where a model came from. Recorded for display only - it must NEVER be used to branch
 * runtime logic. A sideloaded model is a first-class citizen (spec §1.1.7, §5.4).
 */
enum class Origin {
    BUILT_IN,
    SIDELOADED,
}

/**
 * The single authority for every user-visible fact about a model (spec §5.7).
 *
 * THE RULE THAT MAKES SSOT REAL: no model fact - sample rate, voice name, speed bound,
 * display name - may appear as a literal anywhere outside this descriptor/resolver layer.
 * The UI reads only from here. A model constant outside the resolver layer is a bug.
 */
data class ModelDescriptor(
    val modelId: String,
    val engineId: String,
    val displayName: String,
    val origin: Origin,
    /** Varies per model - playback and the WAV writer read THIS, never a constant. */
    val sampleRate: Int,
    /** The voice dropdown reads THIS. */
    val voices: List<Voice>,
    val defaultVoiceId: String,
    /**
     * The tunable synthesis parameters this model actually supports - the SSOT the UI iterates to
     * render a control per knob (dynamic, so a model that adds an emotion selector needs no app
     * change; a model without a speed knob, like CosyVoice3, simply omits it). Discovered by the
     * engine when it inspects the model, never a hardcoded assumption. May be empty.
     */
    val parameters: List<ModelParameter> = emptyList(),
    /**
     * Logical file name → on-device absolute path for this model's weights and side files,
     * populated by the engine from the bundle it inspected. Generic (every model has files);
     * WHICH names an engine looks up is the engine's own business, never shared code's.
     */
    val assetPaths: Map<String, String> = emptyMap(),
    /**
     * Approximate resource footprint (peak RAM) of this model - the engine's a-priori estimate,
     * discovered when it inspects the model (issue #38). Surfaced as an inline, non-blocking hint so
     * the user can still ATTEMPT a heavy model on a small phone; refined at runtime from observed
     * peak RAM of previous loads. Defaults to [ResourceCost.UNKNOWN] so a model that declares nothing
     * simply shows "unknown".
     */
    val resourceCost: ResourceCost = ResourceCost.UNKNOWN,
    /**
     * Whether this model's graph represents voices as a CONTINUOUS speaker/style vector that can be
     * linearly interpolated between two voices for an in-between timbre (issue #42). A pure
     * descriptor fact so the "mix voices" UI is DERIVED, never a hardcoded per-model special case
     * (CLAUDE.md rule 5): the engine sets this from what its graph actually accepts - true for the
     * StyleTTS2 engines that feed a `style` vector (Kokoro/KittenTTS), false for models that select
     * a voice by a discrete integer/id or a separate graph (MeloTTS `sid`, Piper, CosyVoice2), which
     * cannot interpolate. Default false: a model is not blendable unless it says so.
     */
    val supportsVoiceBlend: Boolean = false,
) {
    /** The speed knob if this model has one, else null (e.g. CosyVoice3 exposes no speed parameter). */
    val speedParameter: ModelParameter?
        get() = parameters.firstOrNull { it.id == ModelParameter.SPEED_ID }

    /**
     * The speed slider's bounds, derived from [speedParameter]. A model without a speed knob reports
     * a locked `1.0..1.0` - honest-closed, so the UI shows no adjustable speed rather than faking one.
     */
    val speedRange: ClosedFloatingPointRange<Float>
        get() = speedParameter?.range ?: LOCKED_SPEED

    /** The default speed, derived from [speedParameter] (1.0 when the model has no speed knob). */
    val defaultSpeed: Float
        get() = speedParameter?.default ?: 1.0f

    init {
        require(voices.isNotEmpty()) { "descriptor $modelId must expose at least one voice" }
        require(voices.any { it.id == defaultVoiceId }) {
            "defaultVoiceId '$defaultVoiceId' is not among the voices of $modelId"
        }
        require(parameters.distinctBy { it.id }.size == parameters.size) {
            "descriptor $modelId has duplicate parameter ids"
        }
        require(sampleRate > 0) { "sampleRate must be positive for $modelId" }
    }

    private companion object {
        val LOCKED_SPEED = 1.0f..1.0f
    }
}
