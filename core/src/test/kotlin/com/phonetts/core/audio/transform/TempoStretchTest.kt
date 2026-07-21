package com.phonetts.core.audio.transform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val RATE = 24_000
private const val ONE_SECOND = RATE
private const val TONE_HZ = 300.0

private fun tone(samples: Int): FloatArray =
    FloatArray(samples) { i -> (0.5 * sin(2.0 * PI * TONE_HZ * i / RATE)).toFloat() }

// Zero-crossing rate (sign changes per sample) — a resampling-free proxy for pitch/spectral
// content of a tonal signal. A naive resample-to-speed-up would DOUBLE this for a 2x speed; WSOLA,
// which preserves pitch, must leave it (nearly) unchanged.
private fun zeroCrossingRate(x: FloatArray): Float {
    var crossings = 0
    for (i in 1 until x.size) {
        if ((x[i - 1] < 0f) != (x[i] < 0f)) crossings++
    }
    return crossings.toFloat() / (x.size - 1)
}

class TempoStretchTest {
    @Test
    fun neutralFactorReturnsAudioUnchanged() {
        val input = tone(ONE_SECOND)
        val out = TempoStretch(1f).apply(listOf(input), RATE).single()
        assertEquals(input.size, out.size)
        assertTrue(input.contentEquals(out))
    }

    @Test
    fun fasterFactorShortensLengthByRoughlyTheFactor() {
        val input = tone(ONE_SECOND)
        val out = TempoStretch(2f).apply(listOf(input), RATE).single()

        val expected = input.size / 2f
        assertTrue(abs(out.size - expected) < expected * 0.05f, "len ${out.size}, expected ~$expected")
        assertTrue(out.all { it.isFinite() }, "no NaN/Inf")
        assertTrue(out.all { abs(it) <= 1.5f }, "bounded")
    }

    @Test
    fun slowerFactorLengthensLengthByRoughlyTheFactor() {
        val input = tone(ONE_SECOND / 2)
        val out = TempoStretch(0.5f).apply(listOf(input), RATE).single()

        val expected = input.size * 2f
        assertTrue(abs(out.size - expected) < expected * 0.05f, "len ${out.size}, expected ~$expected")
    }

    @Test
    fun pitchIsPreservedNotResampled() {
        val input = tone(ONE_SECOND)
        val inZcr = zeroCrossingRate(input)

        val fast = TempoStretch(2f).apply(listOf(input), RATE).single()
        val slow = TempoStretch(0.5f).apply(listOf(input), RATE).single()

        // Within 15%: pitch stays put. (Resampling to 2x would push the rate toward 2x — rejected.)
        assertTrue(abs(zeroCrossingRate(fast) - inZcr) < inZcr * 0.15f, "2x pitch drifted")
        assertTrue(abs(zeroCrossingRate(slow) - inZcr) < inZcr * 0.15f, "0.5x pitch drifted")
    }

    @Test
    fun extremeFactorsStayFiniteAndBounded() {
        val input = tone(ONE_SECOND / 2)

        val veryFast = TempoStretch(10f).apply(listOf(input), RATE).single()
        val verySlow = TempoStretch(0.1f).apply(listOf(input), RATE).single()

        assertTrue(veryFast.all { it.isFinite() } && verySlow.all { it.isFinite() }, "no NaN/Inf")
        assertTrue(veryFast.all { abs(it) <= 1.5f } && verySlow.all { abs(it) <= 1.5f }, "bounded")
        assertTrue(veryFast.size < input.size && verySlow.size > input.size, "length tracks factor")
    }

    @Test
    fun factorIsClampedToTheAdvertisedRange() {
        val input = tone(ONE_SECOND / 4)
        // 100x is clamped to MAX_FACTOR (10x): output must not vanish to near-nothing beyond 10x.
        val clampedFast = TempoStretch(100f).apply(listOf(input), RATE).single()
        val atMax = TempoStretch(TempoStretch.MAX_FACTOR).apply(listOf(input), RATE).single()
        assertEquals(atMax.size, clampedFast.size, "over-range factor clamps to MAX_FACTOR")
    }

    @Test
    fun tempoStretchIsNonDestructiveAndOffByDefaultInAChain() {
        val input = tone(ONE_SECOND / 2)
        val copy = input.copyOf()

        val chain = TransformChain.of(listOf(TempoStretch(2f)))
        // Off by default → identity, raw audio returned for re-listening.
        assertTrue(chain.apply(listOf(input), RATE).single().contentEquals(input))
        // Input array never mutated.
        assertTrue(copy.contentEquals(input))
    }
}
