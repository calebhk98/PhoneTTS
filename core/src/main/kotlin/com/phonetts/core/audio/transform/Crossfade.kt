package com.phonetts.core.audio.transform

// Smooths the joins between consecutive sentence segments with a short equal-length overlap-add:
// the tail of one segment fades out while the head of the next fades in, summed over the overlap.
// This removes the click/discontinuity that can appear where two independently-synthesized
// sentences are butted together. Segments shorter than the overlap are concatenated as-is.
//
// Streams (implements [IncrementalTransform]) with a bounded lookback: only the last [fadeMs]-worth
// of merged audio can still change when the next segment arrives, so everything before that is
// settled and emitted immediately. The streamed output concatenates to exactly what [apply]
// returns, so byte-for-byte export is unchanged whether the batch or streaming path runs it.
private const val DEFAULT_FADE_MS = 8
private const val MILLIS_PER_SECOND = 1000

class Crossfade(
    private val fadeMs: Int = DEFAULT_FADE_MS,
) : AudioTransform, IncrementalTransform {
    override val id: String = ID
    override val displayName: String = "Crossfade joins"

    override fun apply(
        segments: List<FloatArray>,
        sampleRate: Int,
    ): List<FloatArray> {
        if (segments.size < 2) return segments
        val fade = fadeSamples(sampleRate)

        var merged = segments.first()
        for (index in 1 until segments.size) {
            merged = joinCrossfade(merged, segments[index], fade)
        }
        return listOf(merged)
    }

    override fun openStage(sampleRate: Int): TransformStage = CrossfadeStage(fadeSamples(sampleRate))

    private fun fadeSamples(sampleRate: Int): Int = (sampleRate * fadeMs / MILLIS_PER_SECOND).coerceAtLeast(1)

    companion object {
        const val ID = "crossfade"
    }
}

// One export's streaming crossfade state. [pending] holds the not-yet-settled tail (at most [fade]
// samples once the merged run is long enough), which is all the next segment can still overlap.
private class CrossfadeStage(
    private val fade: Int,
) : TransformStage {
    private var pending: FloatArray? = null

    override fun push(
        segment: FloatArray,
        emit: (FloatArray) -> Unit,
    ) {
        val left = pending
        if (left == null) {
            pending = segment
            return
        }
        val merged = joinCrossfade(left, segment, minOf(fade, left.size, segment.size))
        val keep = minOf(fade, merged.size)
        val settledLen = merged.size - keep
        pending = merged.copyOfRange(settledLen, merged.size)
        if (settledLen > 0) emit(merged.copyOfRange(0, settledLen))
    }

    override fun finish(emit: (FloatArray) -> Unit) {
        pending?.let(emit)
    }
}

// Overlap the last [fade] samples of [left] with the first [fade] of [right], linearly cross-
// fading; fall back to plain concatenation when either side is too short to overlap. Shared by the
// batch and streaming paths so both produce identical samples.
private fun joinCrossfade(
    left: FloatArray,
    right: FloatArray,
    fade: Int,
): FloatArray {
    val overlap = minOf(fade, left.size, right.size)
    if (overlap == 0) return left + right

    val out = FloatArray(left.size + right.size - overlap)
    System.arraycopy(left, 0, out, 0, left.size - overlap)
    for (i in 0 until overlap) {
        val t = (i + 1).toFloat() / (overlap + 1)
        out[left.size - overlap + i] = left[left.size - overlap + i] * (1f - t) + right[i] * t
    }
    System.arraycopy(right, overlap, out, left.size, right.size - overlap)
    return out
}
