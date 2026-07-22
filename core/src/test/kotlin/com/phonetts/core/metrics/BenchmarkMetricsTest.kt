package com.phonetts.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BenchmarkMetricsTest {
    private fun rtf(
        wallClockElapsedSeconds: Double = 2.0,
        timeToFirstAudioSeconds: Double? = 0.5,
    ) = RtfResult(
        calibrationWordCount = 3,
        audioSecondsProduced = 1.0,
        wallClockElapsedSeconds = wallClockElapsedSeconds,
        chunksProduced = 1,
        timeToFirstAudioSeconds = timeToFirstAudioSeconds,
    )

    @Test
    fun generationSecondsPassesThroughTheMeasuredWallClockTimeUnchanged() {
        val metrics = BenchmarkMetrics(rtf = rtf(wallClockElapsedSeconds = 3.3), modelLoadSeconds = 1.0)

        assertEquals(3.3, metrics.generationSeconds)
    }

    @Test
    fun timeToFirstAudioSecondsPassesThroughFromTheRtfResult() {
        val metrics = BenchmarkMetrics(rtf = rtf(timeToFirstAudioSeconds = 0.42), modelLoadSeconds = null)

        assertEquals(0.42, metrics.timeToFirstAudioSeconds)
    }

    @Test
    fun totalWallSecondsAddsLoadAndGenerationWhenLoadTimeIsKnown() {
        val metrics = BenchmarkMetrics(rtf = rtf(wallClockElapsedSeconds = 2.0), modelLoadSeconds = 1.5)

        assertEquals(3.5, metrics.totalWallSeconds)
    }

    @Test
    fun totalWallSecondsIsNullWhenLoadTimeIsUnknownRatherThanFakingIt() {
        val metrics = BenchmarkMetrics(rtf = rtf(), modelLoadSeconds = null)

        assertNull(metrics.totalWallSeconds)
    }

    @Test
    fun ramUsageFractionIsUsedOverAvailableClampedToOne() {
        val metrics =
            BenchmarkMetrics(
                rtf = rtf(),
                modelLoadSeconds = 1.0,
                processMemoryBytes = 800L,
                availableRamBytes = 1_000L,
            )

        assertEquals(0.8, metrics.ramUsageFraction)
    }

    @Test
    fun ramUsageFractionClampsWhenProcessExceedsReportedAvailable() {
        // A process footprint can exceed the "available" figure read at a different instant; clamp
        // rather than report a nonsense fraction over 100%.
        val metrics =
            BenchmarkMetrics(
                rtf = rtf(),
                modelLoadSeconds = 1.0,
                processMemoryBytes = 1_200L,
                availableRamBytes = 1_000L,
            )

        assertEquals(1.0, metrics.ramUsageFraction)
    }

    @Test
    fun ramUsageFractionIsNullWhenEitherFigureIsUnknown() {
        val noProcess = BenchmarkMetrics(rtf = rtf(), modelLoadSeconds = 1.0, availableRamBytes = 1_000L)
        val noAvailable = BenchmarkMetrics(rtf = rtf(), modelLoadSeconds = 1.0, processMemoryBytes = 800L)

        assertNull(noProcess.ramUsageFraction)
        assertNull(noAvailable.ramUsageFraction)
    }

    @Test
    fun ramUsageFractionIsNullRatherThanDividingByZeroAvailable() {
        val metrics =
            BenchmarkMetrics(rtf = rtf(), modelLoadSeconds = 1.0, processMemoryBytes = 800L, availableRamBytes = 0L)

        assertNull(metrics.ramUsageFraction)
    }

    @Test
    fun rejectsANegativeModelLoadSeconds() {
        assertFailsWith<IllegalArgumentException> {
            BenchmarkMetrics(rtf = rtf(), modelLoadSeconds = -1.0)
        }
    }

    @Test
    fun rejectsNegativeMemoryFigures() {
        assertFailsWith<IllegalArgumentException> {
            BenchmarkMetrics(rtf = rtf(), modelLoadSeconds = 1.0, processMemoryBytes = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            BenchmarkMetrics(rtf = rtf(), modelLoadSeconds = 1.0, availableRamBytes = -1L)
        }
    }
}
