package com.phonetts.engines.f5tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `F5DurationPlanner` is F5's native duration/speed route (CLAUDE.md rule 2, README-io.md): it
 * computes `max_duration`, a real declared input of `F5_Preprocess.onnx`, and never touches a
 * sample rate - these tests pin the formula and its "never resamples" property.
 */
class F5DurationPlannerTest {
    @Test
    fun `refAudioFrames divides samples by the hop length`() {
        val frames = 100
        assertEquals(frames, F5DurationPlanner.refAudioFrames(frames * F5DurationPlanner.HOP_LENGTH_SAMPLES))
    }

    @Test
    fun `maxDurationFrames at speed 1 matches the reference formula exactly`() {
        val refFrames = 50
        val refTextLen = 10
        val genTextLen = 20
        val expected = refFrames + (refFrames.toDouble() / refTextLen * genTextLen / 1.0).toInt()

        val actual = F5DurationPlanner.maxDurationFrames(refFrames, refTextLen, genTextLen, 1.0f)

        assertEquals(expected.toLong(), actual)
    }

    @Test
    fun `higher speed asks for fewer mel frames than a lower speed at the same text`() {
        val fast =
            F5DurationPlanner.maxDurationFrames(
                refAudioFrames = 100,
                refTextLength = 10,
                genTextLength = 10,
                speed = 2.0f,
            )
        val slow =
            F5DurationPlanner.maxDurationFrames(
                refAudioFrames = 100,
                refTextLength = 10,
                genTextLength = 10,
                speed = 0.5f,
            )

        assertTrue(fast < slow, "expected higher speed to request fewer mel frames: fast=$fast slow=$slow")
    }

    @Test
    fun `speed 1 is a no-op relative to the reference-derived pace`() {
        val frames =
            F5DurationPlanner.maxDurationFrames(
                refAudioFrames = 100,
                refTextLength = 10,
                genTextLength = 10,
                speed = 1.0f,
            )

        assertEquals(200L, frames)
    }

    @Test
    fun `rejects a non-positive speed`() {
        assertFailsWith<IllegalArgumentException> {
            F5DurationPlanner.maxDurationFrames(10, 5, 5, 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            F5DurationPlanner.maxDurationFrames(10, 5, 5, -1f)
        }
    }

    @Test
    fun `falls back to a floor of at least one frame for degenerate inputs`() {
        val frames =
            F5DurationPlanner.maxDurationFrames(refAudioFrames = 0, refTextLength = 0, genTextLength = 0, speed = 1.0f)

        assertTrue(frames >= 1)
    }
}
