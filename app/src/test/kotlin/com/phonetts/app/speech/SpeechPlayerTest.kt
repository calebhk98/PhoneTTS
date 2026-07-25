package com.phonetts.app.speech

import com.phonetts.core.audio.AudioSink
import com.phonetts.core.audio.buffer.GeneratedAudio
import com.phonetts.core.speech.SpeechSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val RATE = 24_000

/** A minimal recording [AudioSink] test double - app tests can't reach core's own `RecordingSink`
 * (it lives in core's `test` source set, not `testFixtures`), so this is a small local stand-in. */
private class FakeSink : AudioSink {
    val chunks = mutableListOf<FloatArray>()
    var ended = false

    override fun onFormat(sampleRate: Int) = Unit

    override fun onChunk(samples: FloatArray) {
        chunks += samples
    }

    override fun onEnd() {
        ended = true
    }
}

/** An [AudioSink] whose every call throws, for [ReportingSink] coverage. */
private class ExplodingSink : AudioSink {
    override fun onFormat(sampleRate: Int) = error("boom")

    override fun onChunk(samples: FloatArray) = error("boom")

    override fun onEnd() = error("boom")
}

/**
 * [SpeechPlayer] is the fix for "start reading on Home, background it, open Compare and hit
 * preview, and you get two audio streams" (this session's mission) - these tests pin the barge-in
 * guarantee at the unit level, independent of any ViewModel: starting playback on a NEW
 * [SpeechSession] must stop whatever a DIFFERENT session was doing, and a repeat call for the SAME
 * session (a seek) must never stop itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechPlayerTest {
    // A buffer that is NOT yet complete: BufferedPlayback delivers what it has, then suspends at
    // the live edge forever (nothing ever marks it complete) - so its consumer run stays genuinely
    // "in flight" until something else (a barge-in, or an explicit stop) interrupts it. Needed to
    // test barge-in/stop against a run that hasn't already finished on its own.
    private fun TestScope.pendingSession(vararg chunks: FloatArray): SpeechSession {
        val session = SpeechSession(backgroundScope, UnconfinedTestDispatcher(testScheduler), RATE)
        chunks.forEach(session.producer.audio::append)
        return session
    }

    // A buffer that's already fully generated - useful when a test only cares about what a replay
    // actually delivers, not about interrupting an in-progress run.
    private fun TestScope.completedSession(vararg chunks: FloatArray): SpeechSession {
        val session = pendingSession(*chunks)
        session.producer.audio.markComplete()
        return session
    }

    @Test
    fun playingANewSessionStopsWhateverADifferentSessionWasDoing() =
        runTest(UnconfinedTestDispatcher()) {
            val player = SpeechPlayer()
            val first = pendingSession(floatArrayOf(0f), floatArrayOf(1f), floatArrayOf(2f))
            val second = pendingSession(floatArrayOf(9f))

            player.play(first, FakeSink())
            runCurrent()
            assertTrue("first session should still be playing/parked at the live edge", first.state.value.isPlaying)

            // A different screen starts playing - this must barge in on `first`.
            player.play(second, FakeSink())
            runCurrent()

            assertFalse("barging in must stop the previous session's run", first.state.value.isPlaying)
        }

    @Test
    fun replayingTheSameSessionNeverStopsItself() =
        runTest(UnconfinedTestDispatcher()) {
            val player = SpeechPlayer()
            val session = completedSession(floatArrayOf(0f), floatArrayOf(1f))
            player.play(session, FakeSink())
            runCurrent()

            // Same session, a fresh seek to the top - this is NOT a barge-in.
            val replaySink = FakeSink()
            player.play(session, replaySink, fromIndex = 0)
            runCurrent()

            assertEquals(listOf(0f, 1f), replaySink.chunks.map { it[0] })
        }

    @Test
    fun stopHaltsTheAttachedSessionAndForgetsIt() =
        runTest(UnconfinedTestDispatcher()) {
            val player = SpeechPlayer()
            val session = pendingSession(floatArrayOf(0f), floatArrayOf(1f), floatArrayOf(2f))
            player.play(session, FakeSink())
            runCurrent()
            assertTrue(session.state.value.isPlaying)

            player.stop()
            runCurrent()

            assertFalse(session.state.value.isPlaying)
            // A second stop() naming a session that is no longer attached must be a harmless no-op.
            player.stop(session)
        }

    @Test
    fun reportingSinkCatchesAFailureInsteadOfPropagatingIt() {
        var reported: Throwable? = null
        val sink = ReportingSink(ExplodingSink()) { e -> reported = e }

        sink.onFormat(RATE) // must not throw

        assertTrue(reported != null)
        assertEquals("boom", reported?.message)
    }

    @Test
    fun reportingSinkStopsForwardingAfterItsFirstFailure() {
        var failures = 0
        val delegate = FakeSink()
        var thrown = false
        val failingOnce =
            object : AudioSink by delegate {
                override fun onChunk(samples: FloatArray) {
                    if (!thrown) {
                        thrown = true
                        error("first chunk fails")
                    }
                    delegate.onChunk(samples)
                }
            }
        val sink = ReportingSink(failingOnce) { failures++ }

        sink.onChunk(floatArrayOf(1f)) // fails, reported once
        sink.onChunk(floatArrayOf(2f)) // must NOT be forwarded - the sink is presumed gone

        assertEquals(1, failures)
        assertTrue(delegate.chunks.isEmpty())
    }
}
