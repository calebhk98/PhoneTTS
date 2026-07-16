package com.phonetts.core.metrics

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val TEST_SAMPLE_RATE = 1_000

class GenerationTrackingTest {
    /** A deterministic clock: each call advances by [stepNanos], starting from zero. */
    private fun clockAdvancingBy(stepNanos: Long): () -> Long {
        var callCount = 0L
        return {
            val elapsed = callCount * stepNanos
            callCount++
            elapsed
        }
    }

    @Test
    fun emitsOneStatsSnapshotPerUpstreamChunkWithAccumulatingAudioSeconds() =
        runTest {
            val chunks = listOf(FloatArray(500), FloatArray(250), FloatArray(250))

            val results =
                flowOf(*chunks.toTypedArray())
                    .trackGeneration(sampleRate = TEST_SAMPLE_RATE, now = clockAdvancingBy(stepNanos = 1))
                    .toList()

            assertEquals(3, results.size)
            assertEquals(0.5, results[0].second.audioSecondsProduced)
            assertEquals(0.75, results[1].second.audioSecondsProduced)
            assertEquals(1.0, results[2].second.audioSecondsProduced)
            assertEquals(listOf(1, 2, 3), results.map { it.second.chunksDone })
        }

    @Test
    fun everyEmittedChunkIsThePassedThroughUpstreamChunkUnchanged() =
        runTest {
            val chunks = listOf(floatArrayOf(0.1f, -0.2f), floatArrayOf(0.3f))

            val results =
                flowOf(*chunks.toTypedArray())
                    .trackGeneration(sampleRate = TEST_SAMPLE_RATE, now = { 0L })
                    .toList()

            chunks.indices.forEach { i -> assertContentEquals(chunks[i], results[i].first) }
        }

    @Test
    fun wallClockElapsedGrowsWithTheFakeClockAsChunksArrive() =
        runTest {
            val chunks = listOf(FloatArray(100), FloatArray(100))
            val now = clockAdvancingBy(stepNanos = 500_000_000L) // 0.5s per now() call

            val results = flowOf(*chunks.toTypedArray()).trackGeneration(TEST_SAMPLE_RATE, now = now).toList()

            assertEquals(0.5, results[0].second.wallClockElapsedSeconds)
            assertEquals(1.0, results[1].second.wallClockElapsedSeconds)
        }

    @Test
    fun aClockThatAdvancesFasterYieldsALowerRealTimeFactorThanOneThatAdvancesSlower() =
        runTest {
            val chunks = listOf(FloatArray(1_000)) // exactly 1.0s of audio at TEST_SAMPLE_RATE

            val fast =
                flowOf(*chunks.toTypedArray())
                    .trackGeneration(TEST_SAMPLE_RATE, now = clockAdvancingBy(stepNanos = 500_000_000L))
                    .toList()
                    .single()
                    .second

            val slow =
                flowOf(*chunks.toTypedArray())
                    .trackGeneration(TEST_SAMPLE_RATE, now = clockAdvancingBy(stepNanos = 3_000_000_000L))
                    .toList()
                    .single()
                    .second

            assertTrue(fast.realTimeFactor < slow.realTimeFactor)
        }

    @Test
    fun estimatedRemainingSecondsShrinksToZeroAsProgressReachesTotalChunks() =
        runTest {
            val chunks = List(4) { FloatArray(250) }
            val now = clockAdvancingBy(stepNanos = 1_000_000_000L)

            val results =
                flowOf(*chunks.toTypedArray())
                    .trackGeneration(sampleRate = TEST_SAMPLE_RATE, totalChunks = 4, now = now)
                    .toList()

            assertEquals(listOf(3.0, 2.0, 1.0, 0.0), results.map { it.second.estimatedRemainingSeconds })
        }

    @Test
    fun wordsPerSecondTracksMeasuredProgressAgainstTotalWordsAndTotalChunks() =
        runTest {
            val chunks = List(4) { FloatArray(250) }
            val now = clockAdvancingBy(stepNanos = 1_000_000_000L)

            val results =
                flowOf(*chunks.toTypedArray())
                    .trackGeneration(sampleRate = TEST_SAMPLE_RATE, totalWords = 40, totalChunks = 4, now = now)
                    .toList()

            // after chunk 2: fraction 0.5 of 40 words = 20 words, over 2.0s elapsed -> 10 words/sec.
            assertEquals(10.0, results[1].second.wordsPerSecond)
        }

    @Test
    fun emptyUpstreamFlowProducesNoStatsSnapshots() =
        runTest {
            val results = flowOf<FloatArray>().trackGeneration(TEST_SAMPLE_RATE, now = { 0L }).toList()

            assertTrue(results.isEmpty())
        }

    @Test
    fun zeroLengthChunksAccumulateZeroAudioSecondsWithoutDividingByZero() =
        runTest {
            val results =
                flowOf(FloatArray(0), FloatArray(0))
                    .trackGeneration(TEST_SAMPLE_RATE, now = { 0L })
                    .toList()

            assertEquals(0.0, results[0].second.audioSecondsProduced)
            assertEquals(0.0, results[0].second.realTimeFactor)
            assertEquals(2, results[1].second.chunksDone)
        }

    @Test
    fun rejectsANonPositiveSampleRateEagerlyBeforeAnyCollection() {
        assertFailsWith<IllegalArgumentException> {
            flowOf(FloatArray(1)).trackGeneration(sampleRate = 0, now = { 0L })
        }
    }
}
