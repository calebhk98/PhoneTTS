package com.phonetts.core.speech

import com.phonetts.core.audio.buffer.ChunkSpill
import com.phonetts.core.audio.buffer.GeneratedAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechSessionLifecycleTest {
    @Test
    fun closeWaitsForProducerCleanupBeforeReleasingItsBuffer() =
        runTest(UnconfinedTestDispatcher()) {
            val spill = File.createTempFile("session-close", ".pcm")
            val audio = GeneratedAudio(ChunkSpill(spill, maxLiveSamples = 1))
            val session = SpeechSession(backgroundScope, UnconfinedTestDispatcher(testScheduler), 100, audio)
            val release = CompletableDeferred<Unit>()
            session.start(
                flow {
                    try {
                        emit(FloatArray(2))
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { release.await() }
                    }
                },
            )
            try {
                session.close()
                runCurrent()
                assertTrue(spill.exists(), "a producer still unwinding must retain its scratch file")
            } finally {
                release.complete(Unit)
            }
            runCurrent()
            assertFalse(spill.exists())
            assertTrue(audio.snapshot().isEmpty())
        }
}
