package com.phonetts.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkEtaTest {
    // One minute of audio at the listening base rate, so the audio-seconds math is easy to reason
    // about: WORDS_PER_MINUTE words -> 60s of audio at 1.0x.
    private val oneMinuteOfWords = ListeningTimeEstimator.WORDS_PER_MINUTE.toInt()

    @Test
    fun `model estimate is load time plus audio divided by the realtime multiple`() {
        // 60s of audio at 2x real time -> 30s of generation, plus a 3s load allowance -> 33s.
        val seconds = BenchmarkEta.estimateModelSeconds(oneMinuteOfWords, realtimeMultiple = 2.0, loadSeconds = 3.0)

        assertEquals(33.0, seconds, 0.001)
    }

    @Test
    fun `a faster model is estimated to take less time`() {
        val slow = BenchmarkEta.estimateModelSeconds(oneMinuteOfWords, realtimeMultiple = 1.0, loadSeconds = 0.0)
        val fast = BenchmarkEta.estimateModelSeconds(oneMinuteOfWords, realtimeMultiple = 4.0, loadSeconds = 0.0)

        assertTrue(fast < slow)
        assertEquals(60.0, slow, 0.001)
        assertEquals(15.0, fast, 0.001)
    }

    @Test
    fun `a non-positive realtime multiple contributes only the load allowance`() {
        val seconds = BenchmarkEta.estimateModelSeconds(oneMinuteOfWords, realtimeMultiple = 0.0, loadSeconds = 2.5)

        assertEquals(2.5, seconds, 0.001)
    }

    @Test
    fun `total is the sum of the per-model estimates`() {
        assertEquals(60.0, BenchmarkEta.estimateTotalSeconds(listOf(10.0, 20.0, 30.0)), 0.001)
        assertEquals(0.0, BenchmarkEta.estimateTotalSeconds(emptyList()), 0.001)
    }

    @Test
    fun `before any model finishes remaining equals the whole estimate`() {
        val perModel = listOf(30.0, 30.0)
        val progress =
            BenchmarkEta.progress(
                startMillis = 0L,
                nowMillis = 5_000L,
                perModelSeconds = perModel,
                completedModels = 0,
            )

        assertEquals(5.0, progress.elapsedSeconds, 0.001)
        assertEquals(60.0, progress.estimatedTotalSeconds, 0.001)
        assertEquals(60.0, progress.remainingSeconds, 0.001)
    }

    @Test
    fun `remaining rescales by how the finished models actually fared`() {
        // Two models estimated at 30s each. The first actually took 60s (2x slower than estimated),
        // so the second is now predicted to take ~60s too, not its stale 30s estimate.
        val perModel = listOf(30.0, 30.0)
        val progress =
            BenchmarkEta.progress(
                startMillis = 0L,
                nowMillis = 60_000L,
                perModelSeconds = perModel,
                completedModels = 1,
            )

        assertEquals(60.0, progress.elapsedSeconds, 0.001)
        assertEquals(60.0, progress.estimatedTotalSeconds, 0.001)
        assertEquals(60.0, progress.remainingSeconds, 0.001)
    }

    @Test
    fun `elapsed may exceed the original estimate while still predicting remaining time`() {
        // Elapsed (270s) is already past the whole-run estimate (60s) yet a remaining time is still
        // predicted from the live rate rather than clamping to zero (issue #116's example shape).
        val perModel = listOf(30.0, 30.0)
        val progress =
            BenchmarkEta.progress(
                startMillis = 0L,
                nowMillis = 270_000L,
                perModelSeconds = perModel,
                completedModels = 1,
            )

        assertEquals(270.0, progress.elapsedSeconds, 0.001)
        assertEquals(60.0, progress.estimatedTotalSeconds, 0.001)
        assertTrue(progress.remainingSeconds > 0.0)
        assertEquals(270.0, progress.remainingSeconds, 0.001)
    }

    @Test
    fun `all models finished predicts zero remaining`() {
        val perModel = listOf(30.0, 30.0)
        val progress =
            BenchmarkEta.progress(
                startMillis = 0L,
                nowMillis = 40_000L,
                perModelSeconds = perModel,
                completedModels = 2,
            )

        assertEquals(0.0, progress.remainingSeconds, 0.001)
    }

    @Test
    fun `progress never reports negative elapsed for an out-of-order clock`() {
        val progress =
            BenchmarkEta.progress(
                startMillis = 10_000L,
                nowMillis = 5_000L,
                perModelSeconds = listOf(30.0),
                completedModels = 0,
            )

        assertEquals(0.0, progress.elapsedSeconds, 0.001)
    }

    @Test
    fun `formatClock renders minutes and zero-padded seconds`() {
        assertEquals("3:00", BenchmarkEta.formatClock(180.0))
        assertEquals("4:30", BenchmarkEta.formatClock(270.0))
        assertEquals("2:45", BenchmarkEta.formatClock(165.0))
        assertEquals("0:00", BenchmarkEta.formatClock(0.0))
        assertEquals("0:05", BenchmarkEta.formatClock(4.6))
    }

    @Test
    fun `formatClock never renders a negative time`() {
        assertEquals("0:00", BenchmarkEta.formatClock(-10.0))
    }

    @Test
    fun `label reads elapsed slash estimated comma remaining left`() {
        val progress =
            BenchmarkProgress(
                elapsedSeconds = 270.0,
                estimatedTotalSeconds = 180.0,
                remainingSeconds = 165.0,
            )

        assertEquals("4:30 / 3:00, 2:45 left", progress.label)
    }
}
