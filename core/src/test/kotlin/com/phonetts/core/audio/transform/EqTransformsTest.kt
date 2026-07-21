package com.phonetts.core.audio.transform

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

private const val RATE = 24_000
private const val TONE_SAMPLES = 12_000

// RMS of a signal — a level proxy so we can say "this band got quieter/louder".
private fun rms(x: FloatArray): Float {
    if (x.isEmpty()) return 0f
    var sum = 0.0
    for (v in x) sum += (v * v).toDouble()
    return sqrt(sum / x.size).toFloat()
}

private fun tone(
    freqHz: Double,
    amplitude: Float = 0.5f,
): FloatArray = FloatArray(TONE_SAMPLES) { i -> (amplitude * sin(2.0 * PI * freqHz * i / RATE)).toFloat() }

private fun assertFinite(x: FloatArray) {
    assertTrue(x.all { it.isFinite() }, "output must contain no NaN/Inf")
}

private fun assertBounded(
    x: FloatArray,
    limit: Float,
) {
    assertTrue(x.all { kotlin.math.abs(it) <= limit }, "output must stay bounded (<= $limit)")
}

class EqTransformsTest {
    @Test
    fun bassCutAttenuatesLowFrequenciesMoreThanHighs() {
        val low = listOf(tone(60.0))
        val high = listOf(tone(4000.0))

        val lowOut = BassCut().apply(low, RATE).single()
        val highOut = BassCut().apply(high, RATE).single()

        // A high-pass keeps the 4 kHz tone near its input level and knocks the 60 Hz tone right down.
        val lowRatio = rms(lowOut) / rms(low.single())
        val highRatio = rms(highOut) / rms(high.single())
        assertTrue(lowRatio < 0.4f, "60 Hz should be strongly attenuated, was ${lowRatio}x")
        assertTrue(highRatio > 0.8f, "4 kHz should pass, was ${highRatio}x")
        assertFinite(lowOut)
        assertFinite(highOut)
        assertBounded(highOut, 1.5f)
    }

    @Test
    fun presenceBoostRaisesTheClarityBand() {
        val presence = listOf(tone(3000.0))
        val boosted = PresenceBoost().apply(presence, RATE).single()

        val ratio = rms(boosted) / rms(presence.single())
        assertTrue(ratio > 1.2f, "3 kHz presence band should be boosted, was ${ratio}x")
        assertFinite(boosted)
        assertBounded(boosted, 2f)
    }

    @Test
    fun deEsserSoftensSibilantHighsButLeavesMidsAlone() {
        val sibilance = listOf(tone(8000.0))
        val mid = listOf(tone(1000.0))

        val sibOut = DeEsser().apply(sibilance, RATE).single()
        val midOut = DeEsser().apply(mid, RATE).single()

        val sibRatio = rms(sibOut) / rms(sibilance.single())
        val midRatio = rms(midOut) / rms(mid.single())
        assertTrue(sibRatio < 0.85f, "8 kHz sibilance should drop, was ${sibRatio}x")
        assertTrue(midRatio > 0.9f, "1 kHz mid should be roughly untouched, was ${midRatio}x")
        assertFinite(sibOut)
    }

    @Test
    fun eqTransformsAreNonDestructiveTowardInputArrays() {
        val original = tone(200.0)
        val copy = original.copyOf()

        BassCut().apply(listOf(original), RATE)
        PresenceBoost().apply(listOf(original), RATE)
        DeEsser().apply(listOf(original), RATE)

        assertTrue(copy.contentEquals(original), "input array must not be mutated")
    }

    @Test
    fun eqTransformsExposeStableIdsAndDisplayNames() {
        assertTrue(BassCut().id == BassCut.ID && BassCut().displayName.isNotBlank())
        assertTrue(PresenceBoost().id == PresenceBoost.ID && PresenceBoost().displayName.isNotBlank())
        assertTrue(DeEsser().id == DeEsser.ID && DeEsser().displayName.isNotBlank())
    }

    @Test
    fun eqTransformsPlugIntoTransformChainDisabledByDefault() {
        val chain = TransformChain.of(listOf(BassCut(), PresenceBoost(), DeEsser()))
        val tone = listOf(tone(60.0))

        // Off by default → identity (raw audio comes back).
        assertTrue(chain.apply(tone, RATE).single().contentEquals(tone.single()))

        // Enabled → the 60 Hz tone is attenuated by the bass cut.
        val on = chain.withEnabled(BassCut.ID, true)
        assertTrue(rms(on.apply(tone, RATE).single()) < rms(tone.single()))
    }
}
