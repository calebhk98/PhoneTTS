package com.phonetts.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThermalRegressionDetectorTest {
    private fun sample(
        timestamp: Long,
        rtf: Double,
    ) = BenchmarkRecord("piper", "A16", timestamp, rtf)

    @Test
    fun `no regression with fewer than two samples`() {
        assertNull(ThermalRegressionDetector.detect(emptyList()))
        assertNull(ThermalRegressionDetector.detect(listOf(sample(1L, 1.0))))
    }

    @Test
    fun `a run twice as slow as the last is flagged`() {
        val history = listOf(sample(1L, 1.0), sample(2L, 2.0))
        val regression = ThermalRegressionDetector.detect(history)
        assertNotNull(regression)
        assertEquals(2.0, regression.slowdownRatio, 1e-9)
        assertEquals(2L, regression.current.timestampMillis)
        assertEquals(1L, regression.baseline.timestampMillis)
    }

    @Test
    fun `within-noise variance is not flagged`() {
        val history = listOf(sample(1L, 1.0), sample(2L, 1.1))
        assertNull(ThermalRegressionDetector.detect(history))
    }

    @Test
    fun `a run that got faster is not flagged`() {
        val history = listOf(sample(1L, 2.0), sample(2L, 1.0))
        assertNull(ThermalRegressionDetector.detect(history))
    }

    @Test
    fun `compares the two most recent samples regardless of input order`() {
        val history = listOf(sample(3L, 3.0), sample(1L, 1.0), sample(2L, 1.0))
        val regression = ThermalRegressionDetector.detect(history)
        assertNotNull(regression)
        assertEquals(3L, regression.current.timestampMillis)
        assertEquals(2L, regression.baseline.timestampMillis)
        assertTrue(regression.slowdownRatio >= ThermalRegressionDetector.DEFAULT_SLOWDOWN_THRESHOLD)
    }
}
