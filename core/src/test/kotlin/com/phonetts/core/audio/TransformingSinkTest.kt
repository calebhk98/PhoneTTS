package com.phonetts.core.audio

import com.phonetts.core.audio.transform.AudioTransform
import com.phonetts.core.audio.transform.TempoStretch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val RATE = 24_000

// A transform that doubles every sample's amplitude — an easy-to-verify stand-in for asserting the
// decorator actually routes chunks through the transform before the downstream sink sees them.
private class GainTransform(
    private val gain: Float,
) : AudioTransform {
    override val id = "test-gain"
    override val displayName = "gain"

    override fun apply(
        segments: List<FloatArray>,
        sampleRate: Int,
    ): List<FloatArray> = segments.map { seg -> FloatArray(seg.size) { seg[it] * gain } }
}

class TransformingSinkTest {
    @Test
    fun forwardsFormatAndEndAndAppliesTransformToEachChunk() {
        val downstream = RecordingSink()
        val sink = TransformingSink(downstream, GainTransform(2f))

        sink.onFormat(RATE)
        sink.onChunk(floatArrayOf(0.1f, -0.2f))
        sink.onChunk(floatArrayOf(0.3f))
        sink.onEnd()

        assertEquals(RATE, downstream.sampleRate) // format routed through
        assertTrue(downstream.ended) // end routed through
        assertTrue(
            floatArrayOf(0.2f, -0.4f, 0.6f).contentEquals(downstream.recorded),
            "each chunk should pass through the transform",
        )
    }

    @Test
    fun tempoStretchOnPlaybackSinkShortensAudioWithoutTouchingGeneration() {
        val downstream = RecordingSink()
        val sink = TransformingSink(downstream, TempoStretch(2f))
        val tone = FloatArray(RATE) { i -> (0.5 * sin(2.0 * PI * 300.0 * i / RATE)).toFloat() }

        sink.onFormat(RATE)
        sink.onChunk(tone)
        sink.onEnd()

        // The playback sink emitted a ~half-length, finite, bounded chunk — generation never saw it.
        val out = downstream.recorded
        assertTrue(out.size < tone.size, "2x tempo should shorten the played audio")
        assertTrue(out.all { it.isFinite() && kotlin.math.abs(it) <= 1.5f }, "finite and bounded")
    }
}
