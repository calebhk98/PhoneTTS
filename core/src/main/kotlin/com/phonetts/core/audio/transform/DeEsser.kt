package com.phonetts.core.audio.transform

// Softens harsh sibilance ("sss"/"shh", ~6 kHz and up) with a gentle negative high-shelf. Neural
// TTS and bright speakers can make esses spit; this pulls the top back a few dB so listening stays
// comfortable, especially at length. It is a static shelf (not a dynamic de-esser) - deliberately
// simple, per segment, non-destructive, and speed-preserving (rule 2 untouched). Corner/gain are
// this transform's own tuning constants.
private const val DEFAULT_CORNER_HZ = 6500f
private const val DEFAULT_GAIN_DB = -4f
private const val DEFAULT_Q = 0.707f

class DeEsser(
    private val cornerHz: Float = DEFAULT_CORNER_HZ,
    private val gainDb: Float = DEFAULT_GAIN_DB,
    private val q: Float = DEFAULT_Q,
) : AudioTransform {
    override val id: String = ID
    override val displayName: String = "De-ess (soften sibilance)"

    override fun apply(
        segments: List<FloatArray>,
        sampleRate: Int,
    ): List<FloatArray> {
        if (segments.isEmpty()) return segments
        val filter = Biquad.highShelf(sampleRate, cornerHz, q, gainDb)
        return segments.map { filter.process(it) }
    }

    companion object {
        const val ID = "de-ess"
    }
}
