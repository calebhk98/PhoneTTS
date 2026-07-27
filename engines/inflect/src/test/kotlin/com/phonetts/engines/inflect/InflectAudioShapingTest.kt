package com.phonetts.engines.inflect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The per-chunk shaping ported from upstream's `inference_onnx.py`, asserted against its table. */
class InflectAudioShapingTest {
    private companion object {
        const val SAMPLE_RATE = 24_000
    }

    @Test
    fun `the pause table matches upstream's boundary_pause_seconds`() {
        assertEquals(0.28f, InflectAudioShaping.boundaryPauseSeconds("Really?"))
        assertEquals(0.24f, InflectAudioShaping.boundaryPauseSeconds("Stop!"))
        assertEquals(0.22f, InflectAudioShaping.boundaryPauseSeconds("A sentence."))
        assertEquals(0.16f, InflectAudioShaping.boundaryPauseSeconds("One thing;"))
        assertEquals(0.13f, InflectAudioShaping.boundaryPauseSeconds("As follows:"))
        assertEquals(0.09f, InflectAudioShaping.boundaryPauseSeconds("First,"))
    }

    @Test
    fun `an untabulated or empty ending falls back to the default pause`() {
        assertEquals(InflectAudioShaping.DEFAULT_PAUSE_SECONDS, InflectAudioShaping.boundaryPauseSeconds("no ending"))
        assertEquals(InflectAudioShaping.DEFAULT_PAUSE_SECONDS, InflectAudioShaping.boundaryPauseSeconds(""))
    }

    @Test
    fun `trailing whitespace does not hide the punctuation the pause is keyed on`() {
        assertEquals(0.28f, InflectAudioShaping.boundaryPauseSeconds("Really?   "))
    }

    @Test
    fun `the fade is 5 ms at the sample rate, but never more than half a short chunk`() {
        assertEquals(120, InflectAudioShaping.edgeFadeFrames(sampleCount = 48_000, sampleRate = SAMPLE_RATE))
        assertEquals(2, InflectAudioShaping.edgeFadeFrames(sampleCount = 4, sampleRate = SAMPLE_RATE))
        assertEquals(0, InflectAudioShaping.edgeFadeFrames(sampleCount = 1, sampleRate = SAMPLE_RATE))
    }

    @Test
    fun `shaping fades both edges to silence and appends the sentence's pause`() {
        val samples = FloatArray(1_000) { 1f }

        val shaped = InflectAudioShaping.shape(samples, SAMPLE_RATE, "Done.")

        val pauseSamples = (SAMPLE_RATE * 0.22f).toInt()
        assertEquals(samples.size + pauseSamples, shaped.size)
        assertEquals(0f, shaped.first(), "the first sample is the start of the fade-in ramp")
        assertEquals(0f, shaped[samples.size - 1], "the last audio sample is the end of the fade-out ramp")
        assertEquals(1f, shaped[samples.size / 2], "the middle is untouched")
        assertTrue(shaped.drop(samples.size).all { it == 0f }, "the appended tail is silence")
    }

    @Test
    fun `samples are clamped into the audio range`() {
        val samples = FloatArray(1_000) { if (it % 2 == 0) 4f else -4f }

        val shaped = InflectAudioShaping.shape(samples, SAMPLE_RATE, "Loud.")

        assertTrue(shaped.all { it in -1f..1f }, "every sample must be clamped to [-1, 1]")
        assertTrue(shaped.any { it == 1f } && shaped.any { it == -1f }, "clamping must not zero the signal")
    }

    @Test
    fun `an empty chunk still yields its pause rather than throwing`() {
        val shaped = InflectAudioShaping.shape(FloatArray(0), SAMPLE_RATE, "Hm.")

        assertEquals((SAMPLE_RATE * 0.22f).toInt(), shaped.size)
        assertTrue(shaped.all { it == 0f })
    }
}
