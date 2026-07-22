package com.phonetts.core.audio.buffer

import com.phonetts.core.audio.RecordingSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
}
