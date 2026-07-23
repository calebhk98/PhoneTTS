package com.phonetts.core.audio.transform

// Lifts the presence band (~3 kHz, where consonant detail and intelligibility live) with a peaking
// biquad. Tiny phone speakers are weak exactly there, so a gentle boost makes speech cut through
// without turning up overall volume. Timbre only, per segment, non-destructive - playback speed is
// never touched (rule 2). Center/gain/Q are this transform's own tuning constants.
private const val DEFAULT_CENTER_HZ = 3000f
private const val DEFAULT_GAIN_DB = 5f
private const val DEFAULT_Q = 0.9f

class PresenceBoost(
    private val centerHz: Float = DEFAULT_CENTER_HZ,
    private val gainDb: Float = DEFAULT_GAIN_DB,
    private val q: Float = DEFAULT_Q,
) : AudioTransform {
    override val id: String = ID
    override val displayName: String = "Presence boost (clarity)"

    override fun apply(
        segments: List<FloatArray>,
        sampleRate: Int,
    ): List<FloatArray> {
        if (segments.isEmpty()) return segments
        val filter = Biquad.peaking(sampleRate, centerHz, q, gainDb)
        return segments.map { filter.process(it) }
    }

    companion object {
        const val ID = "presence-boost"
    }
}
