package com.phonetts.app.audio.export

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression test for "export cannot be cancelled": [MediaCodecFileEncoder.drive]'s dequeue/drain
 * loop used to be `while (!session.outputDone) { ... }` with no cancellation checkpoint at all,
 * unlike [com.phonetts.app.hf.HfDownloader.copyCapped] which calls `coroutineContext.ensureActive()`
 * every buffer - so stopping or navigating away during a long AAC/Opus export could not interrupt
 * the codec pass. [runUntilDoneOrCancelled] is the extracted checkpoint the fix threads into
 * [MediaCodecFileEncoder.drive]; it is tested directly here (real MediaCodec/MediaMuxer need a
 * device or a Robolectric shadow neither available nor reliable in this environment for the encode
 * path, so the loop's own cancellation logic - the actual bug - is verified in isolation instead).
 */
class MediaCodecFileEncoderCancellationTest {
    @Test
    fun `stops immediately once isCancelled reports true, before running another step`() {
        var steps = 0
        var cancelled = false

        try {
            runUntilDoneOrCancelled(
                isDone = { false }, // never finishes on its own - only cancellation can stop it
                isCancelled = { cancelled },
            ) {
                steps++
                if (steps == 3) cancelled = true // simulate the caller's coroutine being cancelled
            }
            fail("expected a CancellationException once isCancelled() started reporting true")
        } catch (expected: CancellationException) {
            // The checkpoint is BEFORE each step, so cancelling during step 3 must stop the loop
            // right after step 3 runs, before a step 4 that a missing checkpoint would have allowed.
            assertEquals(3, steps)
        }
    }

    @Test
    fun `never checks cancellation once isDone is already true`() {
        var cancelledChecks = 0
        var steps = 0

        runUntilDoneOrCancelled(
            isDone = { true },
            isCancelled = { cancelledChecks++; false },
        ) {
            steps++
        }

        assertEquals(0, steps)
        assertEquals(0, cancelledChecks)
    }

    @Test
    fun `runs to completion normally when never cancelled`() {
        var remaining = 5
        var steps = 0

        runUntilDoneOrCancelled(
            isDone = { remaining <= 0 },
            isCancelled = { false },
        ) {
            steps++
            remaining--
        }

        assertEquals(5, steps)
        assertTrue(remaining <= 0)
    }
}
