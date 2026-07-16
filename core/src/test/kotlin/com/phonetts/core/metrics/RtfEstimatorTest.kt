package com.phonetts.core.metrics

import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.testing.FakeEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_SAMPLE_RATE = 1_000

class RtfEstimatorTest {
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
    fun measuresRtfFromActualWallClockAndActualAudioProduced() =
        runTest {
            // Two chunks of 500 samples each at sampleRate 1000 -> 1.0s of measured audio.
            val engine = FakeEngine(id = "e1", audio = listOf(FloatArray(500), FloatArray(500)))
            val now = clockAdvancingBy(stepNanos = 750_000_000L) // 0.75s per now() call

            val result =
                RtfEstimator.estimate(
                    engine = engine,
                    voiceId = "v0",
                    params = SynthesisParams.ofSpeed(1.0f),
                    calibrationText = "one two three four",
                    sampleRate = TEST_SAMPLE_RATE,
                    now = now,
                )

            assertEquals(4, result.calibrationWordCount)
            assertEquals(1.0, result.audioSecondsProduced)
            // now() is read once before collection starts and once after it fully drains:
            // call 0 -> 0ns, call 1 -> 750ms, so elapsed = 0.75s regardless of chunk count.
            assertEquals(0.75, result.wallClockElapsedSeconds)
            assertEquals(2, result.chunksProduced)
            assertEquals(0.75, result.realTimeFactor)
            assertEquals(187.5, result.secondsPer1000Words)
        }

    @Test
    fun engineThatProducesMoreAudioForTheSameElapsedClockHasALowerMeasuredRtfThanOneThatProducesLess() =
        runTest {
            val fastEngine = FakeEngine(id = "fast", audio = listOf(FloatArray(2_000)))
            val slowEngine = FakeEngine(id = "slow", audio = listOf(FloatArray(500)))

            val fastResult =
                RtfEstimator.estimate(
                    engine = fastEngine,
                    voiceId = "v0",
                    params = SynthesisParams.ofSpeed(1.0f),
                    calibrationText = "hello world",
                    sampleRate = TEST_SAMPLE_RATE,
                    now = clockAdvancingBy(stepNanos = 1_000_000_000L),
                )
            val slowResult =
                RtfEstimator.estimate(
                    engine = slowEngine,
                    voiceId = "v0",
                    params = SynthesisParams.ofSpeed(1.0f),
                    calibrationText = "hello world",
                    sampleRate = TEST_SAMPLE_RATE,
                    now = clockAdvancingBy(stepNanos = 1_000_000_000L),
                )

            assertTrue(fastResult.realTimeFactor < 1.0, "fast engine should render faster than real-time")
            assertTrue(slowResult.realTimeFactor > 1.0, "slow engine should render slower than real-time")
            assertTrue(fastResult.realTimeFactor < slowResult.realTimeFactor)
        }

    @Test
    fun passesVoiceIdAndSpeedThroughToTheEngineUnchanged() =
        runTest {
            val engine = FakeEngine(id = "e1", audio = listOf(FloatArray(10)))

            RtfEstimator.estimate(
                engine = engine,
                voiceId = "voice-7",
                params = SynthesisParams.ofSpeed(1.4f),
                calibrationText = "calibration phrase",
                sampleRate = TEST_SAMPLE_RATE,
                now = { 0L },
            )

            assertEquals("voice-7", engine.lastVoiceId)
            assertEquals(1.4f, engine.lastSpeed)
        }

    @Test
    fun blankCalibrationTextYieldsZeroWordCountAndNoWordRateEstimate() =
        runTest {
            val engine = FakeEngine(id = "e1", audio = listOf(FloatArray(10)))

            val result =
                RtfEstimator.estimate(
                    engine = engine,
                    voiceId = "v0",
                    params = SynthesisParams.ofSpeed(1.0f),
                    calibrationText = "   ",
                    sampleRate = TEST_SAMPLE_RATE,
                    now = { 0L },
                )

            assertEquals(0, result.calibrationWordCount)
            assertNull(result.secondsPer1000Words)
        }

    @Test
    fun engineThatProducesNoAudioYieldsZeroAudioSecondsAndZeroRealTimeFactor() =
        runTest {
            val engine = FakeEngine(id = "e1", audio = emptyList())

            val result =
                RtfEstimator.estimate(
                    engine = engine,
                    voiceId = "v0",
                    params = SynthesisParams.ofSpeed(1.0f),
                    calibrationText = "hello world",
                    sampleRate = TEST_SAMPLE_RATE,
                    now = clockAdvancingBy(stepNanos = 1_000_000_000L),
                )

            assertEquals(0.0, result.audioSecondsProduced)
            assertEquals(0, result.chunksProduced)
            assertEquals(0.0, result.realTimeFactor)
        }

    @Test
    fun rejectsANonPositiveSampleRate() =
        runTest {
            val engine = FakeEngine(id = "e1")

            assertFailsWith<IllegalArgumentException> {
                RtfEstimator.estimate(
                    engine = engine,
                    voiceId = "v0",
                    params = SynthesisParams.ofSpeed(1.0f),
                    calibrationText = "hello",
                    sampleRate = 0,
                    now = { 0L },
                )
            }
        }
}
