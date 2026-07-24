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
            // now() is read once before collection starts, once at the first chunk (to measure TTFA),
            // and once after collection fully drains: call 0 -> 0ns, call 1 (1st chunk) -> 750ms,
            // call 2 (drain complete) -> 1500ms, so elapsed = 1.5s regardless of remaining chunk count.
            assertEquals(1.5, result.wallClockElapsedSeconds)
            assertEquals(2, result.chunksProduced)
            assertEquals(1.5, result.realTimeFactor)
            assertEquals(375.0, result.secondsPer1000Words)
            assertEquals(0.75, result.timeToFirstAudioSeconds)
        }

    @Test
    fun engineThatProducesMoreAudioForTheSameElapsedClockHasALowerMeasuredRtfThanOneThatProducesLess() =
        runTest {
            // Both engines emit a single chunk, so both pay the same extra now() call at that chunk
            // (to measure TTFA) plus the start/drain calls -> identical 2.0s wall-clock elapsed for
            // both. "fast" must still produce enough audio to land under 1.0x at that elapsed time.
            val fastEngine = FakeEngine(id = "fast", audio = listOf(FloatArray(4_000)))
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
    fun measuresTimeToFirstAudioAsTheElapsedTimeAtTheFirstChunk() =
        runTest {
            // Three chunks; now() advances by 1s per call. Sequence: startNanos=0 (call 0),
            // first chunk lands at call 1 -> 1s, second at call 2 -> 2s, final `now()` at call 3 -> 3s.
            val engine = FakeEngine(id = "e1", audio = listOf(FloatArray(10), FloatArray(10), FloatArray(10)))

            val result =
                RtfEstimator.estimate(
                    engine = engine,
                    voiceId = "v0",
                    params = SynthesisParams.ofSpeed(1.0f),
                    calibrationText = "hello world",
                    sampleRate = TEST_SAMPLE_RATE,
                    now = clockAdvancingBy(stepNanos = 1_000_000_000L),
                )

            assertEquals(1.0, result.timeToFirstAudioSeconds)
            // TTFA must never exceed the total measured wall-clock time.
            assertTrue(result.timeToFirstAudioSeconds!! <= result.wallClockElapsedSeconds)
        }

    @Test
    fun timeToFirstAudioIsNullWhenNoChunkEverArrives() =
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

            assertNull(result.timeToFirstAudioSeconds)
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

    @Test
    fun implausiblyShortAudioForTheWordCountIsFlaggedAsNotRealSpeech() {
        // 19 words but only 0.06s of audio (the MMS-ara case): far below the per-word floor, so broken.
        val broken =
            RtfResult(
                calibrationWordCount = 19,
                audioSecondsProduced = 0.06,
                wallClockElapsedSeconds = 0.5,
                chunksProduced = 1,
            )
        assertTrue(!broken.isPlausibleSpeech, "0.06s for 19 words must be flagged as broken output")
    }

    @Test
    fun ampleAudioForTheWordCountIsPlausibleSpeech() {
        // 19 words, ~7s of audio: comfortably above the floor, a real (even if fast) result.
        val real =
            RtfResult(
                calibrationWordCount = 19,
                audioSecondsProduced = 7.0,
                wallClockElapsedSeconds = 2.0,
                chunksProduced = 3,
            )
        assertTrue(real.isPlausibleSpeech, "ample audio for the word count should be plausible")
    }

    @Test
    fun outputIsNeverFlaggedWhenThereAreNoWordsToJudgeAgainst() {
        // No countable words -> can't judge plausibility, so it must pass (fail-open on measurement).
        val noWords =
            RtfResult(
                calibrationWordCount = 0,
                audioSecondsProduced = 0.0,
                wallClockElapsedSeconds = 0.0,
                chunksProduced = 0,
            )
        assertTrue(noWords.isPlausibleSpeech, "a zero-word calibration can't be judged and must pass")
    }

    @Test
    fun rejectsANegativeTimeToFirstAudio() {
        assertFailsWith<IllegalArgumentException> {
            RtfResult(
                calibrationWordCount = 1,
                audioSecondsProduced = 1.0,
                wallClockElapsedSeconds = 1.0,
                chunksProduced = 1,
                timeToFirstAudioSeconds = -1.0,
            )
        }
    }
}
