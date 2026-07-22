package com.phonetts.core.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Matches WavWriterTest's tolerance: the WAV leg round-trips through 16-bit PCM quantization.
private const val FINAL_CHUNK_QUANTIZATION_TOLERANCE = 1.0f / Short.MAX_VALUE + 1e-4f

@OptIn(ExperimentalCoroutinesApi::class)
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

    /**
     * Directly targets "last word cut off" / "exported WAV errors at EOF": both symptoms trace back
     * to a consumer that stops one flow element short of the producer. This asserts the FINAL emitted
     * chunk specifically — not just the aggregate sample count — reaches both consumers byte-for-byte,
     * using an uneven trailing chunk (smaller than the others, like a short last sentence) so a
     * boundary bug that only drops a short/partial final element would be caught, not masked by
     * uniform chunk sizes.
     */
    @Test
    fun theFinalFlowElementReachesBothConsumersUntruncated() =
        runTest {
            val chunks =
                listOf(
                    floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f),
                    floatArrayOf(0.5f, 0.6f, 0.7f, 0.8f),
                    // Short trailing chunk — the one most likely to be silently dropped.
                    floatArrayOf(0.9f),
                )
            val lastChunkValues = chunks.last().toList()
            val sink = RecordingSink()
            val out = ByteArrayOutputStream()
            val totalSamples = chunks.sumOf { it.size }

            StreamingConsumer().play(flowOf(*chunks.toTypedArray()), sampleRate = 24_000, sink = sink)
            WavWriter().write(flowOf(*chunks.toTypedArray()), sampleRate = 24_000, out = out)

            val streamedValues = sink.recorded.toList()
            val exportedValues = parseWav(out.toByteArray()).samples
            assertEquals(totalSamples, streamedValues.size, "streamed sample count is short of what was emitted")
            assertEquals(totalSamples, exportedValues.size, "exported sample count is short of what was emitted")
            // The streaming sink is never quantized, so this leg must match exactly.
            assertEquals(lastChunkValues, streamedValues.takeLast(lastChunkValues.size))
            // The WAV leg round-trips through 16-bit PCM, so compare within quantization tolerance.
            lastChunkValues.zip(exportedValues.takeLast(lastChunkValues.size)).forEach { (original, decoded) ->
                assertTrue(
                    abs(original - decoded) <= FINAL_CHUNK_QUANTIZATION_TOLERANCE,
                    "final chunk sample mismatch beyond quantization: original=$original decoded=$decoded",
                )
            }
        }

    @Test
    fun playBuffersOneChunkAheadSoTheNextChunkGeneratesWhileTheCurrentOnePlays() =
        // UnconfinedTestDispatcher runs the buffer()-launched producer coroutine eagerly, so the
        // interleaving below reflects genuine look-ahead rather than an artifact of test scheduling
        // (mirrors the pattern GeneratedAudio/BufferedPlayback tests already use for this reason).
        runTest(UnconfinedTestDispatcher()) {
            val events = mutableListOf<String>()
            val flow =
                flow {
                    events += "generate-1"
                    emit(floatArrayOf(0.1f))
                    events += "generate-2"
                    emit(floatArrayOf(0.2f))
                    events += "generate-3"
                    emit(floatArrayOf(0.3f))
                }
            var consumed = 0
            val sink =
                object : AudioSink {
                    override fun onFormat(sampleRate: Int) = Unit

                    override fun onChunk(samples: FloatArray) {
                        consumed++
                        events += "consume-$consumed"
                    }

                    override fun onEnd() {
                        events += "end"
                    }
                }

            StreamingConsumer().play(flow, sampleRate = 24_000, sink = sink)

            // Without buffering, chunk 2 could not start generating until chunk 1 had already been
            // handed to the sink. With one chunk of look-ahead, generation of chunk 2 races ahead of
            // (and here, completes before) the sink consuming chunk 1.
            val generatedChunkTwoAt = events.indexOf("generate-2")
            val consumedChunkOneAt = events.indexOf("consume-1")
            assertTrue(
                generatedChunkTwoAt in 0 until consumedChunkOneAt,
                "expected chunk 2 to start generating before chunk 1 was consumed, got: $events",
            )
            assertEquals(3, consumed)
            assertEquals("end", events.last())
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
