package com.phonetts.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GenerationStatsTest {
    @Test
    fun realTimeFactorIsZeroUntilAnyAudioHasBeenProduced() {
        val stats = GenerationStats(audioSecondsProduced = 0.0, wallClockElapsedSeconds = 5.0, chunksDone = 0)

        assertEquals(0.0, stats.realTimeFactor)
    }

    @Test
    fun realTimeFactorIsMeasuredWallOverAudio() {
        val stats = GenerationStats(audioSecondsProduced = 2.0, wallClockElapsedSeconds = 4.0, chunksDone = 1)

        assertEquals(2.0, stats.realTimeFactor)
    }

    @Test
    fun progressFractionIsNullWithoutAKnownTotal() {
        val noTotal = GenerationStats(audioSecondsProduced = 1.0, wallClockElapsedSeconds = 1.0, chunksDone = 1)
        val zeroTotal =
            GenerationStats(
                audioSecondsProduced = 1.0,
                wallClockElapsedSeconds = 1.0,
                chunksDone = 1,
                totalChunks = 0,
            )

        assertNull(noTotal.progressFraction)
        assertNull(zeroTotal.progressFraction)
    }

    @Test
    fun progressFractionIsChunksDoneOverTotalChunksClampedToOne() {
        val partial =
            GenerationStats(audioSecondsProduced = 1.0, wallClockElapsedSeconds = 1.0, chunksDone = 2, totalChunks = 4)
        val overshoot =
            GenerationStats(audioSecondsProduced = 1.0, wallClockElapsedSeconds = 1.0, chunksDone = 9, totalChunks = 4)

        assertEquals(0.5, partial.progressFraction)
        assertEquals(1.0, overshoot.progressFraction)
    }

    @Test
    fun wordsPerSecondIsZeroWithoutBothTotalsOrWithoutElapsedTime() {
        val noWords =
            GenerationStats(audioSecondsProduced = 1.0, wallClockElapsedSeconds = 5.0, chunksDone = 2, totalChunks = 4)
        val noChunkTotal =
            GenerationStats(audioSecondsProduced = 1.0, wallClockElapsedSeconds = 5.0, chunksDone = 2, totalWords = 100)
        val noElapsed =
            GenerationStats(
                audioSecondsProduced = 0.0,
                wallClockElapsedSeconds = 0.0,
                chunksDone = 2,
                totalChunks = 4,
                totalWords = 100,
            )

        assertEquals(0.0, noWords.wordsPerSecond)
        assertEquals(0.0, noChunkTotal.wordsPerSecond)
        assertEquals(0.0, noElapsed.wordsPerSecond)
    }

    @Test
    fun wordsPerSecondScalesTotalWordsByProgressFraction() {
        val stats =
            GenerationStats(
                audioSecondsProduced = 2.0,
                wallClockElapsedSeconds = 5.0,
                chunksDone = 2,
                totalChunks = 4,
                totalWords = 100,
            )

        // fraction = 0.5, so 50 of the 100 words are (assumed) done in 5.0s -> 10 words/sec.
        assertEquals(10.0, stats.wordsPerSecond)
    }

    @Test
    fun estimatedRemainingSecondsIsNullWithoutProgressOrWithoutAnyChunksDone() {
        val noTotal = GenerationStats(audioSecondsProduced = 1.0, wallClockElapsedSeconds = 1.0, chunksDone = 1)
        val zeroChunksDone =
            GenerationStats(audioSecondsProduced = 0.0, wallClockElapsedSeconds = 0.0, chunksDone = 0, totalChunks = 4)

        assertNull(noTotal.estimatedRemainingSeconds)
        assertNull(zeroChunksDone.estimatedRemainingSeconds)
    }

    @Test
    fun estimatedRemainingSecondsExtrapolatesFromElapsedTimePerChunk() {
        val quarterDone =
            GenerationStats(audioSecondsProduced = 1.0, wallClockElapsedSeconds = 1.0, chunksDone = 1, totalChunks = 4)
        val allDone =
            GenerationStats(audioSecondsProduced = 4.0, wallClockElapsedSeconds = 4.0, chunksDone = 4, totalChunks = 4)

        // 1/4 of the way there after 1.0s -> projected total 4.0s -> 3.0s remaining.
        assertEquals(3.0, quarterDone.estimatedRemainingSeconds)
        assertEquals(0.0, allDone.estimatedRemainingSeconds)
    }

    @Test
    fun rejectsNegativeMeasurements() {
        assertFailsWith<IllegalArgumentException> {
            GenerationStats(audioSecondsProduced = -1.0, wallClockElapsedSeconds = 1.0, chunksDone = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            GenerationStats(audioSecondsProduced = 1.0, wallClockElapsedSeconds = -1.0, chunksDone = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            GenerationStats(audioSecondsProduced = 0.0, wallClockElapsedSeconds = 0.0, chunksDone = -1)
        }
    }
}
