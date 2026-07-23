package com.phonetts.core.engine

import com.phonetts.core.model.ModelParameter

/**
 * The chosen values for a synthesis call - a uniform, forward-compatible bag keyed by
 * [ModelParameter.id]. This is what flows through the one generation path
 * ([VoiceEngine.synthesize]) so that a parameter a model adds later (an emotion selector, a pitch
 * knob) reaches the engine with no signature change: the UI puts its value in here under the
 * parameter's id, and the engine reads it back by id.
 *
 * Values are [Float] to match [ModelParameter] (a CHOICE parameter's value is its selected index).
 * Unknown ids simply fall back to the caller-supplied default, so an engine only ever reads the
 * parameters it declared.
 */
class SynthesisParams(
    private val values: Map<String, Float> = emptyMap(),
) {
    /** The value chosen for [id], or [default] if the caller didn't set it. */
    fun value(
        id: String,
        default: Float,
    ): Float = values[id] ?: default

    /** Convenience for the near-universal speed knob; 1.0 (no change) when unset. */
    val speed: Float
        get() = value(ModelParameter.SPEED_ID, DEFAULT_SPEED)

    companion object {
        const val DEFAULT_SPEED = 1.0f

        /** No overrides - every parameter takes its default. */
        val DEFAULT = SynthesisParams()

        /** The common case: just a speed value. */
        fun ofSpeed(speed: Float): SynthesisParams = SynthesisParams(mapOf(ModelParameter.SPEED_ID to speed))
    }
}
