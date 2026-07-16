package com.phonetts.core.audio.transform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val RATE = 24_000

class TransformsTest {
    @Test
    fun silenceTrimDropsLeadingAndTrailingQuietButKeepsTheLoudMiddle() {
        val segments = listOf(floatArrayOf(0f, 0.001f, 0.5f, -0.4f, 0.002f, 0f))

        val result = SilenceTrim(threshold = 0.01f).apply(segments, RATE).flatMap { it.toList() }

        assertContentEquals(listOf(0.5f, -0.4f), result)
    }

    @Test
    fun silenceTrimIsNonDestructiveTowardTheInputArrays() {
        val original = floatArrayOf(0f, 0.5f, 0f)
        val copy = original.copyOf()

        SilenceTrim().apply(listOf(original), RATE)

        assertContentEquals(copy, original) // input untouched → toggling off replays the original
    }

    @Test
    fun loudnessNormalizeScalesPeakToTarget() {
        val segments = listOf(floatArrayOf(0.1f, -0.25f, 0.5f))

        val result = LoudnessNormalize(targetPeak = 1f).apply(segments, RATE).flatMap { it.toList() }

        assertEquals(1f, result.maxOf { kotlin.math.abs(it) }, 1e-6f)
        // relative dynamics preserved: every sample scaled by the same gain (0.5 -> 1.0 means x2)
        assertContentEquals(listOf(0.2f, -0.5f, 1f), result.map { it })
    }

    @Test
    fun loudnessNormalizeLeavesSilenceAlone() {
        val segments = listOf(floatArrayOf(0f, 0f))

        val result = LoudnessNormalize().apply(segments, RATE)

        assertContentEquals(floatArrayOf(0f, 0f), result.single())
    }

    @Test
    fun crossfadeMergesTwoSegmentsAndShortensByTheOverlap() {
        // 1ms fade at 24kHz = 24 samples, but segments are shorter, so overlap clamps to seg size.
        val a = FloatArray(50) { 1f }
        val b = FloatArray(50) { 1f }

        val merged = Crossfade(fadeMs = 1).apply(listOf(a, b), RATE).single()

        val overlap = RATE * 1 / 1000 // 24
        assertEquals(a.size + b.size - overlap, merged.size)
        // constant 1.0 either side → the fade of two equal signals stays ~1.0, no dip to zero
        assertTrue(merged.all { it > 0.99f }, "equal-signal crossfade should not dip")
    }

    @Test
    fun crossfadeLeavesASingleSegmentUntouched() {
        val only = floatArrayOf(0.1f, 0.2f)

        val result = Crossfade().apply(listOf(only), RATE)

        assertContentEquals(only, result.single())
    }

    @Test
    fun transformChainAppliesOnlyEnabledEntriesInOrder() {
        val chain =
            TransformChain.of(listOf(SilenceTrim(threshold = 0.01f), LoudnessNormalize(targetPeak = 1f)))
        val segments = listOf(floatArrayOf(0f, 0.25f, 0f))

        // Nothing enabled → identity (the raw audio comes back for re-listening).
        assertContentEquals(segments.single(), chain.apply(segments, RATE).single())

        // Enable both: trim removes the zeros, normalize scales 0.25 -> 1.0.
        val on = chain.withEnabled(SilenceTrim.ID, true).withEnabled(LoudnessNormalize.ID, true)
        assertContentEquals(floatArrayOf(1f), on.apply(segments, RATE).single())
    }

    @Test
    fun transformChainToggleIsImmutableAndReversible() {
        val chain = TransformChain.of(listOf(LoudnessNormalize()))

        val enabled = chain.withEnabled(LoudnessNormalize.ID, true)

        assertTrue(enabled.isEnabled(LoudnessNormalize.ID))
        assertTrue(!chain.isEnabled(LoudnessNormalize.ID)) // original chain unchanged
    }
}
