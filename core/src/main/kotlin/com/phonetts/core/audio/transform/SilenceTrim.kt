package com.phonetts.core.audio.transform

import kotlin.math.abs

// Trims leading and trailing near-silence from the whole utterance (models often emit a beat of
// quiet at the start/end). It looks at the flattened signal to find the first and last sample
// above [threshold], then re-slices the original segments to that window so segment boundaries
// (which crossfade cares about) are preserved.
private const val DEFAULT_THRESHOLD = 0.01f

class SilenceTrim(
    private val threshold: Float = DEFAULT_THRESHOLD,
) : AudioTransform {
    override val id: String = ID
    override val displayName: String = "Trim silence"

    override fun apply(
        segments: List<FloatArray>,
        sampleRate: Int,
    ): List<FloatArray> {
        val flat = flatten(segments)
        if (flat.isEmpty()) return segments

        val start = flat.indexOfFirst { abs(it) > threshold }
        if (start < 0) return emptyList() // whole thing is below threshold
        val endExclusive = flat.indexOfLast { abs(it) > threshold } + 1
        return sliceSegments(segments, start, endExclusive)
    }

    private fun flatten(segments: List<FloatArray>): FloatArray {
        val out = FloatArray(segments.sumOf { it.size })
        var offset = 0
        for (segment in segments) {
            System.arraycopy(segment, 0, out, offset, segment.size)
            offset += segment.size
        }
        return out
    }

    // Re-slice the flattened [start, endExclusive) window back into segments, dropping segments
    // that fall entirely outside the window and clipping the ones straddling an edge.
    private fun sliceSegments(
        segments: List<FloatArray>,
        start: Int,
        endExclusive: Int,
    ): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        var offset = 0
        for (segment in segments) {
            val segStart = offset
            val segEnd = offset + segment.size
            offset = segEnd
            val from = maxOf(start, segStart)
            val to = minOf(endExclusive, segEnd)
            if (from >= to) continue
            out.add(segment.copyOfRange(from - segStart, to - segStart))
        }
        return out
    }

    companion object {
        const val ID = "silence-trim"
    }
}
