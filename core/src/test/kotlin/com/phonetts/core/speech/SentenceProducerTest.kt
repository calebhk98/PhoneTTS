package com.phonetts.core.speech

import com.phonetts.core.audio.buffer.GeneratedAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The producer half in isolation, with no consumer anywhere in sight - it should not need one to
 * behave correctly (mission: generation shouldn't know if playback occurs at all).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SentenceProducerTest {
    @Test
    fun runDrainsEveryChunkInOrderAndMarksComplete() =
        runTest {
            val producer = SentenceProducer()
            val chunks = listOf(floatArrayOf(1f), floatArrayOf(2f, 3f), floatArrayOf(4f))

            producer.run(flowOf(*chunks.toTypedArray()))

            assertEquals(3, producer.ready.value)
            assertTrue(producer.complete.value)
            assertNull(producer.failure.value)
            assertContentEquals(chunks.flatMap { it.toList() }, producer.audio.snapshot().flatMap { it.toList() })
        }

    @Test
    fun aMidStreamFailureIsCapturedNotThrownAndPriorSentencesSurvive() =
        runTest {
            val boom = IllegalStateException("engine crashed")
            val producer = SentenceProducer()
            val failing =
                flow {
                    emit(floatArrayOf(1f))
                    emit(floatArrayOf(2f))
                    throw boom
                }

            producer.run(failing) // must not throw - the failure is captured, not propagated

            assertEquals(boom, producer.failure.value)
            assertTrue(producer.complete.value) // still marked complete so a waiting consumer wakes
            assertEquals(2, producer.ready.value)
            assertContentEquals(floatArrayOf(1f), producer.audio.chunkAt(0))
            assertContentEquals(floatArrayOf(2f), producer.audio.chunkAt(1))
        }

    @Test
    @Suppress("SwallowedException") // deliberate: asserting cancellation propagates, not a captured failure
    fun cancellationPropagatesInsteadOfBeingSwallowedAsAFailure() =
        runTest {
            val producer = SentenceProducer()
            val neverCompletes = flow<FloatArray> { awaitCancellation() }

            val job =
                launch(Dispatchers.Unconfined) {
                    try {
                        producer.run(neverCompletes)
                        fail("expected cancellation to propagate")
                    } catch (expectedCancellation: CancellationException) {
                        // Reaching this catch block IS the assertion: run() must rethrow
                        // CancellationException rather than swallow it as a captured failure.
                    }
                }
            job.cancel()
            job.join()

            assertNull(producer.failure.value)
            assertTrue(producer.complete.value) // markComplete still ran despite the cancellation
        }

    @Test
    fun aFreshProducerReportsNothingGeneratedYet() {
        val producer = SentenceProducer()

        assertEquals(0, producer.ready.value)
        assertFalse(producer.complete.value)
        assertNull(producer.failure.value)
    }

    @Test
    fun canBeConstructedOverAnExistingGeneratedAudioBuffer() =
        runTest {
            val audio = GeneratedAudio()
            val producer = SentenceProducer(audio)

            producer.run(flowOf(floatArrayOf(9f)))

            assertEquals(audio, producer.audio)
            assertEquals(1, audio.count.value)
        }
}
