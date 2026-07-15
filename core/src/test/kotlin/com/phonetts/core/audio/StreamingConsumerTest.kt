package com.phonetts.core.audio

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingConsumerTest {
    @Test
    fun playAnnouncesFormatThenForwardsEveryChunkInOrderThenSignalsEnd() =
        runTest {
            val chunks = listOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(-0.3f), floatArrayOf(0.4f, 0.5f, 0.6f))
            val sink = RecordingSink()

            StreamingConsumer().play(flowOf(*chunks.toTypedArray()), sampleRate = 24_000, sink = sink)

            assertEquals(24_000, sink.sampleRate)
            assertEquals(chunks.size, sink.chunkCount)
            assertContentEquals(chunks.flatMap { it.toList() }.toFloatArray(), sink.recorded)
            assertTrue(sink.ended)
        }

    @Test
    fun playAnnouncesFormatAndSignalsEndEvenForAnEmptyFlow() =
        runTest {
            val sink = RecordingSink()

            StreamingConsumer().play(flowOf<FloatArray>(), sampleRate = 22_050, sink = sink)

            assertEquals(22_050, sink.sampleRate)
            assertEquals(0, sink.chunkCount)
            assertTrue(sink.recorded.isEmpty())
            assertTrue(sink.ended)
        }

    @Test
    fun streamingConsumerAndWavWriterBothFullyDrainTheSameEmittedChunks() =
        runTest {
            val chunks =
                listOf(
                    floatArrayOf(0.1f, 0.2f, 0.3f),
                    floatArrayOf(-0.4f, -0.5f),
                    floatArrayOf(0.6f, 0.7f, 0.8f, 0.9f),
                )
            val totalInputSamples = chunks.sumOf { it.size }
            val sink = RecordingSink()
            val out = ByteArrayOutputStream()

            StreamingConsumer().play(flowOf(*chunks.toTypedArray()), sampleRate = 22_050, sink = sink)
            WavWriter().write(flowOf(*chunks.toTypedArray()), sampleRate = 22_050, out = out)

            assertEquals(totalInputSamples, sink.recorded.size)
            assertEquals(totalInputSamples, parseWav(out.toByteArray()).samples.size)
        }
}

/**
 * Test double for [AudioSink]: records the sample rate it was told and concatenates every
 * chunk it receives, so tests can assert both routing (9.5) and full drain (9.7). Shared by
 * [StreamingConsumerTest] and [SampleRateRoutingTest] via same-package visibility.
 */
class RecordingSink : AudioSink {
    var sampleRate: Int? = null
        private set
    var chunkCount: Int = 0
        private set
    var ended: Boolean = false
        private set

    private val samples = mutableListOf<Float>()

    val recorded: FloatArray
        get() = samples.toFloatArray()

    override fun onFormat(sampleRate: Int) {
        this.sampleRate = sampleRate
    }

    override fun onChunk(samples: FloatArray) {
        chunkCount++
        this.samples.addAll(samples.toList())
    }

    override fun onEnd() {
        ended = true
    }
}
