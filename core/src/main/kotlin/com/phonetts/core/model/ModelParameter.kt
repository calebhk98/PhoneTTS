package com.phonetts.core.model

/**
 * One tunable synthesis parameter a model exposes - the SSOT for "what knobs does this model have?"
 *
 * This is the introspective, dynamic answer to the design rule that everything the UI shows is
 * derived at runtime from descriptors (CLAUDE.md). An engine does NOT hardcode "there is a speed
 * slider"; it inspects the actual model and declares the parameters that model genuinely supports -
 * Piper/Kokoro/Kitten/Melo advertise a [speed] knob (their graphs have a native duration/speed
 * input), CosyVoice3 advertises none (its native synth exposes no speed knob), and a future model
 * that adds, say, an emotion selector declares a [CHOICE] parameter for it and the UI renders a
 * control **with no app-code change**. The UI iterates [ModelDescriptor.parameters] and draws one
 * control per entry; [com.phonetts.core.engine.SynthesisParams] carries the chosen values back to
 * the engine.
 *
 * Values are always [Float] so a single uniform bag can carry every parameter through the one
 * generation path: a [CONTINUOUS] parameter's value is the number itself; a [CHOICE] parameter's
 * value is the selected index into [choices].
 */
data class ModelParameter(
    val id: String,
    val displayName: String,
    val kind: Kind,
    /** Inclusive bounds for a [CONTINUOUS] parameter (a slider reads these); null for [CHOICE]. */
    val range: ClosedFloatingPointRange<Float>? = null,
    /** The options for a [CHOICE] parameter (a dropdown reads these); empty for [CONTINUOUS]. */
    val choices: List<String> = emptyList(),
    /** Default value: the number for [CONTINUOUS], or the default index into [choices] for [CHOICE]. */
    val default: Float,
) {
    enum class Kind { CONTINUOUS, CHOICE }

    init {
        require(id.isNotBlank()) { "parameter id must not be blank" }
        when (kind) {
            Kind.CONTINUOUS -> {
                val bounds = requireNotNull(range) { "CONTINUOUS parameter '$id' needs a range" }
                require(default in bounds) { "default $default outside range $bounds for '$id'" }
            }
            Kind.CHOICE -> {
                require(choices.isNotEmpty()) { "CHOICE parameter '$id' needs at least one choice" }
                require(default.toInt().toFloat() == default && default.toInt() in choices.indices) {
                    "default index $default is not a valid choice index for '$id'"
                }
            }
        }
    }

    companion object {
        /** The well-known id for the near-universal speed/duration knob (CLAUDE.md rule 2). */
        const val SPEED_ID = "speed"

        /** A [CONTINUOUS] speed parameter over [range] defaulting to [default] (typically 1.0). */
        fun speed(
            range: ClosedFloatingPointRange<Float>,
            default: Float = 1.0f,
        ): ModelParameter =
            ModelParameter(
                id = SPEED_ID,
                displayName = "Speed",
                kind = Kind.CONTINUOUS,
                range = range,
                default = default,
            )
    }
}
