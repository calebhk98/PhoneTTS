package com.phonetts.app.audio

import android.media.AudioTrack
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.phonetts.app.PhoneTtsApplication
import com.phonetts.app.speech.SpeechPlayer
import com.phonetts.app.ui.TtsViewModel
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.testing.FakeEngine
import com.phonetts.core.testing.testDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Implements(AudioTrack::class)
class PauseTestAudioTrack : ShadowAudioTrack() {
    @Implementation
    override fun play() {
        playCalls++
        super.play()
    }

    @Implementation
    protected fun write(
        samples: FloatArray,
        offset: Int,
        size: Int,
        mode: Int,
    ): Int {
        val callback = nextWrite
        nextWrite = null
        if (callback != null) {
            return callback()
        }
        return size
    }

    companion object {
        var playCalls = 0
        var nextWrite: (() -> Int)? = null
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [PauseTestAudioTrack::class])
class AudioTrackPauseTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pauseKeepsTheUiAndServiceActiveAndStopEndsTheRun() =
        runBlocking {
            Dispatchers.setMain(Dispatchers.Unconfined)
            val store = ViewModelStore()
            val app = ApplicationProvider.getApplicationContext<PhoneTtsApplication>()
            val graph = app.graph
            val descriptor = testDescriptor("pause-test", "pause-test")
            var syntheses = 0
            val engine =
                object : VoiceEngine by FakeEngine("pause-test") {
                    override fun synthesize(
                        text: String,
                        voiceId: String,
                        params: SynthesisParams,
                    ) = flow {
                        syntheses++
                        emit(floatArrayOf(0.1f))
                        awaitCancellation()
                    }
                }
            graph.engineRegistry.register(engine)
            graph.catalog.add(descriptor)
            val vm = TtsViewModel(graph, SpeechPlayer())
            store.put("reader", vm)
            try {
                vm.selectModel(descriptor)
                vm.setText("One sentence. Another sentence.")
                vm.play()
                withTimeout(5_000) { vm.state.first { it.playing && it.sentencesReady > 0 } }
                vm.pausePlayback()
                val paused = withTimeout(5_000) { vm.state.first { it.paused } }
                assertTrue("Pause must keep Resume and Stop available", paused.playing)
                assertTrue("The service must remain attached while paused", vm.playbackController.isPlaying)
                vm.resumePlayback()
                withTimeout(5_000) { vm.state.first { it.playing && !it.paused } }
                assertEquals("Resume must reuse the same generation", 1, syntheses)
                vm.stop()
                val stopped = withTimeout(5_000) { vm.state.first { !it.playing && !it.paused } }
                assertTrue("Stop halts audio while generation continues", stopped.generating)
            } finally {
                store.clear()
                graph.engineManager.unloadCurrent()
                graph.engineRegistry.unregister(engine.id)
                Dispatchers.resetMain()
            }
        }

    @Test
    fun aNewChunkCannotRestartAnIntentionallyPausedTrack() {
        val sink = AudioTrackSink()
        val worker = Executors.newSingleThreadExecutor()
        try {
            sink.onFormat(24_000)
            val playsBeforePause = PauseTestAudioTrack.playCalls
            sink.pause()
            val started = CountDownLatch(1)
            val write =
                worker.submit {
                    started.countDown()
                    sink.onChunk(floatArrayOf(0.1f))
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { write.get(150, TimeUnit.MILLISECONDS) }
            assertEquals(playsBeforePause, PauseTestAudioTrack.playCalls)
            sink.resume()
            write.get(5, TimeUnit.SECONDS)
        } finally {
            sink.stop()
            worker.shutdownNow()
        }
    }

    @Test
    fun pauseDuringAWriteRetainsTheUnwrittenTailUntilResume() {
        val sink = AudioTrackSink()
        val worker = Executors.newSingleThreadExecutor()
        val paused = CountDownLatch(1)
        try {
            sink.onFormat(24_000)
            PauseTestAudioTrack.nextWrite = {
                sink.pause()
                paused.countDown()
                0 // Android may return zero when a pause interrupts WRITE_BLOCKING.
            }
            val write = worker.submit { sink.onChunk(floatArrayOf(0.1f, 0.2f)) }
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { write.get(150, TimeUnit.MILLISECONDS) }
            sink.resume()
            write.get(5, TimeUnit.SECONDS)
        } finally {
            PauseTestAudioTrack.nextWrite = null
            sink.stop()
            worker.shutdownNow()
        }
    }

    @Test
    fun pausingTheLastQueuedAudioDoesNotTurnIntoNaturalCompletion() {
        val sink = AudioTrackSink()
        val worker = Executors.newSingleThreadExecutor()
        try {
            sink.onFormat(24_000)
            sink.onChunk(floatArrayOf(0.1f))
            sink.pause()
            val finish = worker.submit { sink.onEnd() }
            // Longer than the normal drain grace: a deliberate pause must suspend that deadline.
            assertThrows(TimeoutException::class.java) { finish.get(1_000, TimeUnit.MILLISECONDS) }
            sink.stop()
            finish.get(5, TimeUnit.SECONDS)
        } finally {
            sink.stop()
            worker.shutdownNow()
        }
    }

    @Test
    fun stopDuringABlockingWriteDoesNotReportAPlaybackFailure() {
        val sink = AudioTrackSink()
        try {
            sink.onFormat(24_000)
            PauseTestAudioTrack.nextWrite = {
                sink.stop()
                AudioTrack.ERROR_DEAD_OBJECT
            }
            sink.onChunk(floatArrayOf(0.1f))
        } finally {
            PauseTestAudioTrack.nextWrite = null
            sink.stop()
        }
    }

    @Test
    fun stopUnblocksAPausedWriterWithoutResumingIt() {
        val sink = AudioTrackSink()
        val worker = Executors.newSingleThreadExecutor()
        try {
            sink.onFormat(24_000)
            sink.pause()
            val playsBeforeStop = PauseTestAudioTrack.playCalls
            val write = worker.submit { sink.onChunk(floatArrayOf(0.1f)) }
            assertThrows(TimeoutException::class.java) { write.get(150, TimeUnit.MILLISECONDS) }
            sink.stop()
            write.get(5, TimeUnit.SECONDS)
            assertEquals(playsBeforeStop, PauseTestAudioTrack.playCalls)
        } finally {
            sink.stop()
            worker.shutdownNow()
        }
    }
}
