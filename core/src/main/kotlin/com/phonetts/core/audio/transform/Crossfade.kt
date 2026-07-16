package com.phonetts.core.audio.transform

// Smooths the joins between consecutive sentence segments with a short equal-length overlap-add:
// the tail of one segment fades out while the head of the next fades in, summed over the overlap.
// This removes the click/discontinuity that can appear where two independently-synthesized
// sentences are butted together. Segments shorter than the overlap are concatenated as-is.
private const val DEFAULT_FADE_MS = 8

class Crossfade(
    private val fadeMs: Int = DEFAULT_FADE_MS,
) : AudioTransform {
    override val id: String = ID
    override val displayName: String = "Crossfade joins"

    override fun apply(
        segments: List<FloatArray>,
        sampleRate: Int,
    ): List<FloatArray> {
        if (segments.size < 2) return segments
        val fade = (sampleRate * fadeMs / MILLIS_PER_SECOND).coerceAtLeast(1)

        var merged = segments.first()
        for (index in 1 until segments.size) {
            merged = join(merged, segments[index], fade)
        }
        return listOf(merged)
    }

    // Overlap the last [fade] samples of [left] with the first [fade] of [right], linearly cross-
    // fading; fall back to plain concatenation when either side is too short to overlap.
    private fun join(
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

    companion object {
        const val ID = "crossfade"
        private const val MILLIS_PER_SECOND = 1000
    }
}
