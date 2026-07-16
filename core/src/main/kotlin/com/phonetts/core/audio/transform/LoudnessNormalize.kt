package com.phonetts.core.audio.transform

import kotlin.math.abs

// Peak-normalizes the whole utterance to a target headroom: finds the loudest sample across all
// segments and scales everything by one gain so the peak lands at [targetPeak]. One shared gain
// keeps relative dynamics intact (this is not a compressor). Silent audio is left untouched.
private const val DEFAULT_TARGET_PEAK = 0.95f

class LoudnessNormalize(
    private val targetPeak: Float = DEFAULT_TARGET_PEAK,
) : AudioTransform {
    override val id: String = ID
    override val displayName: String = "Normalize volume"

    override fun apply(
        segments: List<FloatArray>,
        sampleRate: Int,
    ): List<FloatArray> {
        val peak = segments.maxOfOrNull { segment -> segment.maxOfOrNull { abs(it) } ?: 0f } ?: 0f
        if (peak == 0f) return segments
        val gain = targetPeak / peak
        if (gain == 1f) return segments
        return segments.map { segment -> FloatArray(segment.size) { segment[it] * gain } }
    }

    companion object {
        const val ID = "loudness-normalize"
    }
}
