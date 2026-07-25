package com.phonetts.core.speech

import com.phonetts.core.audio.RecordingSink
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.testing.FakeEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val RATE = 24_000

/**
 * Proof for the mission: generation and playback are independent collaborators behind ONE SSOT
 * state, built on top of the existing [com.phonetts.core.audio.buffer.GeneratedAudio] /
 * [com.phonetts.core.audio.buffer.BufferedPlayback] seams. Every test uses
 * [UnconfinedTestDispatcher] so launched producer/consumer coroutines run eagerly to their next
 * suspension point and [runCurrent] flushes the rest - the same idiom `BufferedAudioTest` already
 * uses for exactly this shape of concurrent producer/consumer test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeechSessionTest {
    private fun fiveSentenceEngine(): FakeEngine =
        FakeEngine(
            id = "fake",
            audio = listOf(floatArrayOf(0f), floatArrayOf(1f), floatArrayOf(2f), floatArrayOf(3f), floatArrayOf(4f)),
        )

    // Spaces the FakeEngine's five chunks 1ms apart in virtual time, so a test can attach playback
    // and pause/resume it WHILE generation is still in flight - a fully-instant flow would finish
    // generating before play() even started, making "pause does not stop generation" untestable.
    private fun FakeEngine.spacedSynthesize() = synthesize("text", "v0", SynthesisParams.DEFAULT).onEach { delay(1) }

    @Test
    fun pauseDoesNotStopGenerationItKeepsFillingWhilePaused() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = fiveSentenceEngine()
            val session = SpeechSession(backgroundScope, UnconfinedTestDispatcher(testScheduler), RATE)
            session.start(engine.spacedSynthesize())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, session.producer.ready.value) // only the first sentence has landed so far

            val sink = RecordingSink()
            session.play(sink)
            runCurrent()
            session.pause()
            runCurrent()

            // Let the rest of generation finish while playback sits paused.
            advanceTimeBy(10)
            runCurrent()

            // The buffer kept growing to completion even though nothing new reached the sink - the
            // producer never learned playback existed, let alone that it paused.
            assertTrue(session.producer.complete.value)
            assertEquals(5, session.producer.ready.value)
            assertTrue(session.state.value.isPaused)
            assertTrue(sink.chunkCount < 5, "paused playback must not keep delivering chunks")
        }

    @Test
    fun resumeContinuesFromExactSentenceWithNoResynthesis() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = fiveSentenceEngine()
            val session = SpeechSession(backgroundScope, UnconfinedTestDispatcher(testScheduler), RATE)
            session.start(engine.spacedSynthesize())
            runCurrent()

            val sink = RecordingSink()
            session.play(sink)
            runCurrent()
            session.pause()
            runCurrent()
            advanceTimeBy(10) // generation finishes in the background while paused
            runCurrent()
            val deliveredBeforeResume = sink.chunkCount
            session.resume()
            runCurrent()

            assertEquals(5, sink.chunkCount)
            assertEquals(listOf(0f, 1f, 2f, 3f, 4f), sink.recorded.toList())
            assertTrue(sink.chunkCount > deliveredBeforeResume) // resume actually delivered the rest
            // One synthesize() call for the whole document, however many times play/pause/resume ran.
            assertEquals(1, engine.synthesizeCount)
        }

    @Test
    fun seekBackwardToAGeneratedSentenceCostsZeroAdditionalSynthesis() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = fiveSentenceEngine()
            val session = SpeechSession(backgroundScope, UnconfinedTestDispatcher(testScheduler), RATE)
            session.start(engine.synthesize("text", "v0", SynthesisParams.DEFAULT))
            runCurrent()

            session.play(RecordingSink())
            runCurrent()

            val callsBeforeSeek = engine.synthesizeCount
            val secondSink = RecordingSink()
            session.seek(secondSink, 1) // sentence 1 was already generated in the first pass
            runCurrent()

            assertEquals(callsBeforeSeek, engine.synthesizeCount)
            assertEquals(listOf(1f, 2f, 3f, 4f), secondSink.recorded.toList())
        }

    @Test
    fun seekForwardToAnUngeneratedSentenceWaitsThenPlaysAndKeepsEarlierAudio() =
        runTest(UnconfinedTestDispatcher()) {
            val slow =
                flow {
                    emit(floatArrayOf(0f))
                    emit(floatArrayOf(1f))
                    delay(1) // don't emit sentence 2 yet - let the seek race ahead of it
                    emit(floatArrayOf(2f))
                }
            val session = SpeechSession(backgroundScope, UnconfinedTestDispatcher(testScheduler), RATE)
            session.start(slow)
            runCurrent() // only sentences 0 and 1 are generated so far

            assertEquals(2, session.producer.ready.value)
            val sink = RecordingSink()
            session.seek(sink, 2) // not generated yet - must wait, not fail or skip
            runCurrent()
            assertEquals(0, sink.chunkCount) // still waiting

            advanceTimeBy(2)
            runCurrent()

            assertEquals(listOf(2f), sink.recorded.toList())
            // Earlier audio (sentences 0 and 1) is still in the buffer, unaffected by the seek.
            assertEquals(0f, session.producer.audio.chunkAt(0)[0])
            assertEquals(1f, session.producer.audio.chunkAt(1)[0])
        }

    @Test
    fun stopThenPlayAgainResumesFromWhereItStoppedNotFromZero() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = fiveSentenceEngine()
            val session = SpeechSession(backgroundScope, UnconfinedTestDispatcher(testScheduler), RATE)
            session.start(engine.synthesize("text", "v0", SynthesisParams.DEFAULT))
            runCurrent()

            session.play(RecordingSink())
            runCurrent() // fully drains once (all 5 sentences already generated)
            session.stop()
            runCurrent()

            val resumed = RecordingSink()
            session.play(resumed) // no fromIndex - must pick up after where the last run stopped
            runCurrent()

            // The prior run had already delivered all 5, so playing "again" has nothing left to
            // add - proving position is remembered, not reset to 0 (which would replay everything).
            assertTrue(resumed.recorded.isEmpty())
        }

    @Test
    fun stopMidwayThenPlayAgainResumesFromTheStoppedPositionNotZero() =
        runTest(UnconfinedTestDispatcher()) {
            val neverEnds =
                flow {
                    emit(floatArrayOf(0f))
                    emit(floatArrayOf(1f))
                    delay(100)
                    emit(floatArrayOf(2f))
                }
            val session = SpeechSession(backgroundScope, UnconfinedTestDispatcher(testScheduler), RATE)
            session.start(neverEnds)
            runCurrent()

            val first = RecordingSink()
            session.play(first)
            runCurrent()
            assertEquals(2, first.chunkCount) // delivered sentences 0 and 1, now waiting on 2
            session.stop()
            runCurrent()

            val second = RecordingSink()
            session.play(second) // resume, not restart
            advanceTimeBy(101)
            runCurrent()

            // Only the sentence that hadn't been heard yet shows up on the new run - sentences 0/1
            // are not replayed a second time.
            assertEquals(listOf(2f), second.recorded.toList())
        }

    @Test
    fun aProducerFailureMidwayLeavesPriorSentencesPlayableAndSurfacesTheError() =
        runTest(UnconfinedTestDispatcher()) {
            val boom = IllegalStateException("model crashed")
            val failing =
                flow {
                    emit(floatArrayOf(0f))
                    emit(floatArrayOf(1f))
                    throw boom
                }
            val session = SpeechSession(backgroundScope, UnconfinedTestDispatcher(testScheduler), RATE)
            session.start(failing)
            runCurrent()

            assertEquals(boom, session.state.value.generationError)
            assertFalse(session.state.value.isComplete)
            assertEquals(2, session.producer.ready.value)

            val sink = RecordingSink()
            session.play(sink)
            runCurrent()

            assertEquals(listOf(0f, 1f), sink.recorded.toList())
        }

    @Test
    fun theStateSnapshotIsInternallyConsistentUnderEveryTransition() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = fiveSentenceEngine()
            val session = SpeechSession(backgroundScope, UnconfinedTestDispatcher(testScheduler), RATE)
            val snapshots = mutableListOf<SpeechSessionState>()
            backgroundScope.launch { session.state.collect { snapshots.add(it) } }
            runCurrent()

            session.start(engine.synthesize("text", "v0", SynthesisParams.DEFAULT))
            runCurrent()
            session.play(RecordingSink())
            runCurrent()
            session.pause()
            runCurrent()
            session.resume()
            runCurrent()
            session.seek(RecordingSink(), 2)
            runCurrent()
            session.stop()
            runCurrent()

            // Never both playing AND paused at once, and never "playing" once stopped/finished with
            // no run attached.
            assertTrue(snapshots.none { it.isPlaying && it.isPaused })
            assertFalse(snapshots.last().isPlaying)
        }

    @Test
    fun generationCompletingWhilePausedThenResumingDrainsCorrectly() =
        runTest(UnconfinedTestDispatcher()) {
            val trickle =
                flow {
                    emit(floatArrayOf(0f))
                    delay(1)
                    emit(floatArrayOf(1f))
                    delay(1)
                    emit(floatArrayOf(2f))
                }
            val session = SpeechSession(backgroundScope, UnconfinedTestDispatcher(testScheduler), RATE)
            session.start(trickle)
            runCurrent()

            val sink = RecordingSink()
            session.play(sink)
            runCurrent()
            session.pause()
            runCurrent()

            // Let the rest of generation finish while playback sits paused.
            advanceTimeBy(3)
            runCurrent()
            assertTrue(session.producer.complete.value)
            assertEquals(1, sink.chunkCount) // nothing new delivered while paused

            session.resume()
            runCurrent()

            assertEquals(listOf(0f, 1f, 2f), sink.recorded.toList())
            assertTrue(sink.ended)
        }
}
