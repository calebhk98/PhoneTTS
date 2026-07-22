package com.phonetts.core.audio.transform

// Rolls off low-frequency energy (rumble, boom) with a high-pass biquad. Aimed at car listening,
// where a phone's speaker/BT stream plus road noise turns bass into mud — cutting it back makes
// speech sit clearer over the drone. Operates on timbre only, per segment, non-destructively
// (rule 2 untouched: nothing here changes playback speed). [cutoffHz] is the transform's own
// tuning constant, the same way [SilenceTrim]'s threshold or [Crossfade]'s fade length are.
private const val DEFAULT_CUTOFF_HZ = 120f
private const val DEFAULT_Q = 0.707f

class BassCut(
    private val cutoffHz: Float = DEFAULT_CUTOFF_HZ,
    private val q: Float = DEFAULT_Q,
) : AudioTransform {
    override val id: String = ID
    override val displayName: String = "Bass cut (car)"

    override fun apply(
        segments: List<FloatArray>,
        sampleRate: Int,
    ): List<FloatArray> {
        if (segments.isEmpty()) return segments
        val filter = Biquad.highPass(sampleRate, cutoffHz, q)
        return segments.map { filter.process(it) }
    }

    companion object {
        const val ID = "bass-cut"
    }
}
