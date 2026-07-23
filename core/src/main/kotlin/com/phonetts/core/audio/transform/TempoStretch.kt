package com.phonetts.core.audio.transform

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

// Pitch- and formant-preserving time-stretch via WSOLA (Waveform Similarity Overlap-Add). This is
// the DELIBERATE, flagged exception to rule 2 (issue #43): the model's own native speed knob is the
// only thing wired to the "Speed" control, and it is never resampled. This transform is a SEPARATE,
// clearly-labeled, OFF-BY-DEFAULT step that a user opts into on the PLAYBACK path only when they
// want faster (or slower) than the model natively allows. It does NOT resample (which would shift
// pitch); WSOLA slides overlapping windows to the best waveform-similarity offset, so pitch and
// timbre are preserved while duration changes.
//
// [factor] is playback speed: > 1 faster (shorter), < 1 slower (longer), clamped to [0.1, 10]. It
// is this transform's own parameter and has nothing to do with any model's speed ModelParameter.
private const val FRAME_MS = 30
private const val TOLERANCE_MS = 10
private const val MILLIS_PER_SECOND = 1000
private const val NEUTRAL_EPSILON = 1e-3f
private const val NORM_EPSILON = 1e-6f

class TempoStretch(
    factor: Float,
) : AudioTransform {
    private val factor: Float = factor.coerceIn(MIN_FACTOR, MAX_FACTOR)

    override val id: String = ID
    override val displayName: String = "Extra tempo boost - post-processed, not native"

    override fun apply(
        segments: List<FloatArray>,
        sampleRate: Int,
    ): List<FloatArray> {
        if (segments.isEmpty()) return segments
        if (kotlin.math.abs(factor - 1f) < NEUTRAL_EPSILON) return segments.map { it.copyOf() }
        return segments.map { stretch(it, sampleRate) }
    }

    // WSOLA on one segment. Windowed frames are lifted from the input at an analysis hop scaled by
    // [factor], each nudged (within [tolerance]) to the offset that best continues the previous
    // frame's waveform, then overlap-added at a fixed synthesis hop. The running normalization
    // window undoes the Hann overlap so constant regions keep their amplitude.
    private fun stretch(
        input: FloatArray,
        sampleRate: Int,
    ): FloatArray {
        val n = frameSize(sampleRate)
        if (input.size <= n) return input.copyOf()

        val hann = hannWindow(n)
        val synthesisHop = n / 2
        val analysisHop = max(1, (synthesisHop * factor).roundToInt())
        val tolerance = max(1, sampleRate * TOLERANCE_MS / MILLIS_PER_SECOND)
        val outLen = max(1, (input.size / factor).roundToInt())

        val acc = Accumulator(FloatArray(outLen + n), FloatArray(outLen + n))
        overlapAll(input, hann, Hops(synthesisHop, analysisHop, tolerance), outLen, acc)
        return normalized(acc, outLen)
    }

    private fun overlapAll(
        input: FloatArray,
        hann: FloatArray,
        hops: Hops,
        outLen: Int,
        acc: Accumulator,
    ) {
        val n = hann.size
        var delta = 0
        var m = 0
        while (true) {
            val analysisStart = m * hops.analysis + delta
            val synthStart = m * hops.synthesis
            if (synthStart >= outLen || analysisStart + n > input.size) return
            overlapAdd(acc, synthStart, input, analysisStart, hann)
            delta = bestOffset(input, analysisStart + hops.synthesis, (m + 1) * hops.analysis, n, hops.tolerance)
            m++
        }
    }

    // Add one Hann-windowed frame of [input] (from [srcStart]) into the accumulator at [dstStart],
    // clipping to the accumulator's length so the tail frame can't overrun.
    private fun overlapAdd(
        acc: Accumulator,
        dstStart: Int,
        input: FloatArray,
        srcStart: Int,
        hann: FloatArray,
    ) {
        val count = minOf(hann.size, acc.data.size - dstStart, input.size - srcStart)
        for (i in 0 until count) {
            val w = hann[i]
            acc.data[dstStart + i] += input[srcStart + i] * w
            acc.weight[dstStart + i] += w
        }
    }

    // Search [-tolerance, +tolerance] around [nextBase] for the offset whose frame best correlates
    // with the current frame's natural continuation (starting at [naturalStart]).
    private fun bestOffset(
        input: FloatArray,
        naturalStart: Int,
        nextBase: Int,
        n: Int,
        tolerance: Int,
    ): Int {
        var bestK = 0
        var bestScore = -Float.MAX_VALUE
        var k = -tolerance
        while (k <= tolerance) {
            val score = correlation(input, naturalStart, nextBase + k, n)
            if (score > bestScore) {
                bestScore = score
                bestK = k
            }
            k++
        }
        return bestK
    }

    // Unnormalized cross-correlation (dot product) of two n-length windows; out-of-range windows
    // score lowest so they are never chosen.
    private fun correlation(
        input: FloatArray,
        aStart: Int,
        bStart: Int,
        n: Int,
    ): Float {
        if (aStart < 0 || bStart < 0) return -Float.MAX_VALUE
        if (aStart + n > input.size || bStart + n > input.size) return -Float.MAX_VALUE
        var sum = 0f
        for (i in 0 until n) {
            sum += input[aStart + i] * input[bStart + i]
        }
        return sum
    }

    private fun normalized(
        acc: Accumulator,
        outLen: Int,
    ): FloatArray {
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val w = acc.weight[i]
            out[i] = if (w > NORM_EPSILON) acc.data[i] / w else acc.data[i]
        }
        return out
    }

    private fun frameSize(sampleRate: Int): Int {
        val raw = sampleRate * FRAME_MS / MILLIS_PER_SECOND
        return max(MIN_FRAME_SAMPLES, raw + (raw and 1)) // even so synthesisHop = n/2 tiles cleanly
    }

    private fun hannWindow(n: Int): FloatArray =
        FloatArray(n) { i -> (HANN_HALF - HANN_HALF * cos(2.0 * PI * i / (n - 1))).toFloat() }

    private data class Hops(
        val synthesis: Int,
        val analysis: Int,
        val tolerance: Int,
    )

    private class Accumulator(
        val data: FloatArray,
        val weight: FloatArray,
    )

    companion object {
        const val ID = "tempo-stretch"
        const val MIN_FACTOR = 0.1f
        const val MAX_FACTOR = 10f
        private const val MIN_FRAME_SAMPLES = 4
        private const val HANN_HALF = 0.5
    }
}
