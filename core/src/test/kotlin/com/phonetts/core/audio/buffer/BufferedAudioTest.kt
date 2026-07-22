package com.phonetts.core.audio.buffer

import com.phonetts.core.audio.RecordingSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val RATE = 24_000

// The pause/resume and live-edge tests spin a playback coroutine concurrently with generation, so
// they use UnconfinedTestDispatcher to run each launched coroutine eagerly up to its next
// suspension point; runCurrent() then flushes work queued by a StateFlow update (append/pause).
@OptIn(ExperimentalCoroutinesApi::class)
class BufferedAudioTest {
    @Test
    fun collectIntoDrainsEveryChunkAndMarksComplete() =
        runTest {
            val audio = GeneratedAudio()
            val chunks = listOf(floatArrayOf(1f), floatArrayOf(2f, 3f), floatArrayOf(4f))

            flowOf(*chunks.toTypedArray()).collectInto(audio)

            assertEquals(3, audio.count.value)
            assertTrue(audio.complete.value)
            assertEquals(chunks.flatMap { it.toList() }, audio.snapshot().flatMap { it.toList() })
        }

    @Test
    fun playbackDrainsACompletedBufferInOrder() =
        runTest {
            val audio = GeneratedAudio()
            listOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.3f)).forEach(audio::append)
            audio.markComplete()
            val sink = RecordingSink()

            BufferedPlayback().play(audio, RATE, sink)

            assertEquals(RATE, sink.sampleRate)
            assertEquals(2, sink.chunkCount)
            assertTrue(sink.ended)
            assertEquals(listOf(0.1f, 0.2f, 0.3f), sink.recorded.toList())
        }

    @Test
    fun theSameBufferReplaysWithoutRegeneration() =
        runTest {
            val audio = GeneratedAudio()
            audio.append(floatArrayOf(0.5f))
            audio.markComplete()

            val first = RecordingSink()
            val second = RecordingSink()
            BufferedPlayback().play(audio, RATE, first)
            BufferedPlayback().play(audio, RATE, second) // replay the already-generated audio

            assertEquals(first.recorded.toList(), second.recorded.toList())
            assertEquals(listOf(0.5f), second.recorded.toList())
        }

    @Test
    fun pauseHaltsPlaybackWhileGenerationKeepsFillingThenResumeDeliversEverything() =
        runTest(UnconfinedTestDispatcher()) {
            val audio = GeneratedAudio()
            val playback = BufferedPlayback()
            val sink = RecordingSink()
            audio.append(floatArrayOf(1f)) // one chunk ready before playback starts

            backgroundScope.launch { playback.play(audio, RATE, sink) }
            runCurrent()
            assertEquals(1, sink.chunkCount) // played what was ready, now waiting at the live edge

            playback.pause()
            runCurrent()
            audio.append(floatArrayOf(2f)) // generation continues while playback is paused
            audio.append(floatArrayOf(3f))
            runCurrent()
            assertEquals(1, sink.chunkCount) // paused: nothing new delivered, but buffer grew

            playback.resume()
            audio.markComplete()
            runCurrent()
            assertEquals(3, sink.chunkCount) // resume delivered the audio generated while paused
            assertTrue(sink.ended)
            assertEquals(listOf(1f, 2f, 3f), sink.recorded.toList())
        }

    @Test
    fun stopBeforePlaybackDropsEveryBufferedChunk() =
        runTest {
            val audio = GeneratedAudio()
            listOf(floatArrayOf(1f), floatArrayOf(2f), floatArrayOf(3f)).forEach(audio::append)
            audio.markComplete()
            val playback = BufferedPlayback()
            val sink = RecordingSink()

            playback.stop() // barge-in before a single chunk is consumed
            playback.play(audio, RATE, sink)

            // The drop step of the 3-step cancel (issue #45): a stopped playback delivers NONE of
            // the buffered-but-unplayed chunks, even though the buffer is full and complete.
            assertEquals(0, sink.chunkCount)
            assertTrue(sink.recorded.isEmpty())
            assertTrue(sink.ended)
        }

    @Test
    fun stopMidPlaybackDropsRemainingAndLaterGeneratedChunks() =
        runTest(UnconfinedTestDispatcher()) {
            val audio = GeneratedAudio()
            audio.append(floatArrayOf(1f)) // one chunk ready before playback starts
            val playback = BufferedPlayback()
            val sink = RecordingSink()

            val job = backgroundScope.launch { playback.play(audio, RATE, sink) }
            runCurrent()
            assertEquals(1, sink.chunkCount) // played what was ready, now parked at the live edge

            playback.stop() // barge-in mid-utterance (stop / skip / switch-voice)
            audio.append(floatArrayOf(2f)) // more audio becomes available AFTER the cancel
            audio.append(floatArrayOf(3f))
            audio.markComplete()
            runCurrent()

            // Dropped: nothing generated after the cancel is ever delivered.
            assertEquals(1, sink.chunkCount)
            assertEquals(listOf(1f), sink.recorded.toList())
            assertTrue(sink.ended)
            job.join()
        }

    @Test
    fun playbackStartsBeforeGenerationCompletesAndFollowsTheLiveEdge() =
        runTest(UnconfinedTestDispatcher()) {
            val audio = GeneratedAudio()
            val sink = RecordingSink()

            val job = backgroundScope.launch { BufferedPlayback().play(audio, RATE, sink) }
            runCurrent() // nothing generated yet → playback waits, does not end
            assertEquals(0, sink.chunkCount)
            assertTrue(!sink.ended)

            audio.append(floatArrayOf(7f))
            runCurrent()
            assertEquals(1, sink.chunkCount) // consumed the moment it was generated

            audio.markComplete()
            runCurrent()
            assertTrue(sink.ended)
            job.join()
        }

    // TtsViewModel.startPlaybackFrom launches generation and playback as two SIBLING coroutines on
    // REAL dispatchers (genJob appends on Main after each chunk lands, playJob's BufferedPlayback.play
    // runs wholly on Dispatchers.IO) — not the deterministic, single-threaded virtual-time scheduling
    // every other test in this file uses (UnconfinedTestDispatcher / runTest). This test is the one
    // seam-level check that reproduces that shape with genuine multi-threading and real wall-clock
    // gaps between chunks (standing in for real generation latency — e.g. model load + inference
    // time before the first chunk, which is exactly the gap the app's "fresh Play plays nothing"
    // report pointed at), so a lost-wakeup or visibility bug in the count/complete StateFlow
    // hand-off — one that a fully-deterministic test could never exercise — would show up here as a
    // shorter-than-expected `sink.recorded` or a `play()` that never returns (caught by the timeout).
    // Repeated many times because a genuine race would not necessarily fail on every run.
    @Test
    fun liveGenerationAndPlaybackOnRealDispatchersNeverDropsOrEndsEarly() =
        runBlocking {
            repeat(REAL_THREAD_STRESS_ITERATIONS) { iteration ->
                withTimeout(PER_ITERATION_TIMEOUT_MS) {
                    val audio = GeneratedAudio()
                    val sink = RecordingSink()
                    val chunks = List(CHUNKS_PER_ITERATION) { i -> floatArrayOf(i.toFloat(), i.toFloat() + 0.5f) }

                    // A lost-wakeup bug would hang play() forever waiting at the live edge — the
                    // enclosing withTimeout turns that into a clear test failure instead of a hang.
                    val playDeferred =
                        async(Dispatchers.IO) { BufferedPlayback().play(audio, RATE, sink) }
                    val genDeferred =
                        async(Dispatchers.Default) {
                            // A real (if tiny) wall-clock gap between chunks, unlike a test-dispatcher's
                            // virtual time — this is what makes playback genuinely race generation
                            // instead of the two being lockstep-driven by runCurrent().
                            for (chunk in chunks) {
                                delay(1)
                                audio.append(chunk)
                            }
                            audio.markComplete()
                        }

                    genDeferred.await()
                    playDeferred.await()

                    val expected = chunks.flatMap { it.toList() }
                    assertEquals(
                        expected,
                        sink.recorded.toList(),
                        "iteration $iteration: live playback did not deliver every generated chunk",
                    )
                    assertTrue(sink.ended, "iteration $iteration: playback never reached onEnd")
                }
            }
        }

    // The fix for "Pause doesn't stop until the current sentence finishes": pausing must halt the
    // sink (the hardware) IMMEDIATELY, not only stop the read loop advancing to the next chunk. This
    // asserts BufferedPlayback forwards pause()/resume() straight to the active sink the moment
    // they're called — mid-chunk, before any boundary — which is what makes an in-flight sentence
    // stop at the AudioTrack level in the app.
    @Test
    fun pauseAndResumeSignalTheSinkImmediatelyNotOnlyTheReadIndex() =
        runTest(UnconfinedTestDispatcher()) {
            val audio = GeneratedAudio()
            val sink = PauseSpySink()
            val playback = BufferedPlayback()
            audio.append(floatArrayOf(1f))

            backgroundScope.launch { playback.play(audio, RATE, sink) }
            runCurrent()
            assertEquals(0, sink.pauses) // playing, nothing paused yet

            playback.pause()
            runCurrent()
            assertEquals(1, sink.pauses) // hardware halt signaled at once, not at the next chunk

            playback.resume()
            runCurrent()
            assertEquals(1, sink.resumes)

            playback.stop()
            audio.markComplete()
            runCurrent()
        }

    private companion object {
        const val REAL_THREAD_STRESS_ITERATIONS = 200
        const val CHUNKS_PER_ITERATION = 8
        const val PER_ITERATION_TIMEOUT_MS = 5_000L
    }
}

// Records how many times pause()/resume() reached the sink, so a test can prove BufferedPlayback
// signals the hardware immediately rather than only halting its read index between chunks.
private class PauseSpySink : com.phonetts.core.audio.AudioSink {
    var pauses = 0
        private set
    var resumes = 0
        private set

    override fun onFormat(sampleRate: Int) = Unit

    override fun onChunk(samples: FloatArray) = Unit

    override fun onEnd() = Unit

    override fun pause() {
        pauses++
    }

    override fun resume() {
        resumes++
    }
}
