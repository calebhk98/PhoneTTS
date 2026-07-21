package com.phonetts.core.audio.export

import com.phonetts.core.audio.parseWav
import com.phonetts.core.audio.transform.AudioTransform
import com.phonetts.core.audio.transform.Crossfade
import com.phonetts.core.audio.transform.LoudnessNormalize
import com.phonetts.core.audio.transform.SilenceTrim
import com.phonetts.core.audio.transform.TransformChain
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val RATE = 22_050
private const val QUANTIZATION_TOLERANCE = 1.0f / Short.MAX_VALUE + 1e-4f

// Seam tests for the bounded-memory streaming export path (issue #33): the encoder must write each
// segment incrementally instead of `flow.toList()`, and the streaming transforms must produce the
// exact same audio the batch transforms do.
class StreamingEncoderTest {
    @Test
    fun encoderWritesEachSegmentBeforeTheNextIsGenerated() =
        runTest {
            // A spy encoder that records segment sizes as they are written, and a flow that asserts,
            // before emitting chunk i, that chunks 0..i-1 have ALREADY been written. If the base
            // buffered the whole flow (the old toList()), nothing would be written until the end and
            // this invariant would fail on the second emission.
            val written = mutableListOf<Int>()
            val encoder = spyEncoder(written)
            var emitted = 0
            val flow =
                flow {
                    repeat(4) { index ->
                        assertEquals(emitted, written.size, "encoder buffered instead of streaming at chunk $index")
                        emitted++
                        emit(FloatArray(3) { index.toFloat() })
                    }
                }

            encoder.encode(flow, RATE, ByteArrayOutputStream())

            assertEquals(listOf(3, 3, 3, 3), written)
        }

    @Test
    fun wavRoundTripsAcrossManyChunksThroughTheStreamingPath() =
        runTest {
            val chunks = (0 until 200).map { i -> FloatArray(64) { ((i + it) % 7 - 3) / 4f } }
            val expected = chunks.flatMap { it.toList() }
            val out = ByteArrayOutputStream()

            WavEncoder().encode(flowOf(*chunks.toTypedArray()), RATE, out)
            val parsed = parseWav(out.toByteArray())

            assertEquals(RATE, parsed.sampleRate)
            assertEquals(expected.size, parsed.samples.size)
            expected.zip(parsed.samples).forEach { (original, decoded) ->
                assertTrue(abs(original - decoded) <= QUANTIZATION_TOLERANCE, "drift $original vs $decoded")
            }
        }

    @Test
    fun loudnessNormalizeStreamsTwoPassIdenticallyToBatch() {
        val segments =
            listOf(
                floatArrayOf(0.1f, -0.5f, 0.2f),
                floatArrayOf(0.25f, 0.4f),
                floatArrayOf(-0.15f, 0.05f),
            )
        val batch = LoudnessNormalize().apply(segments, RATE).flatMap { it.toList() }

        val streamed = runPipeline(enabled(LoudnessNormalize()), segments)

        assertEquals(batch, streamed.flatMap { it.toList() })
    }

    @Test
    fun crossfadeStreamsIdenticallyToBatch() {
        val segments = listOf(FloatArray(40) { 1f }, FloatArray(40) { 0.5f }, FloatArray(10) { -0.5f })
        val batch = Crossfade(fadeMs = 1).apply(segments, RATE).flatMap { it.toList() }

        val streamed = runPipeline(enabled(Crossfade(fadeMs = 1)), segments)

        assertEquals(batch, streamed.flatMap { it.toList() })
    }

    @Test
    fun fullBufferTransformFallsBackButStillMatchesBatch() {
        // SilenceTrim needs the whole utterance (trailing-silence scan); it has no streaming form so
        // the pipeline buffers it. Result must still equal the batch transform.
        val segments = listOf(floatArrayOf(0f, 0.001f, 0.5f), floatArrayOf(-0.4f, 0.002f, 0f))
        val batch = SilenceTrim(threshold = 0.01f).apply(segments, RATE).flatMap { it.toList() }

        val streamed = runPipeline(enabled(SilenceTrim(threshold = 0.01f)), segments)

        assertEquals(batch, streamed.flatMap { it.toList() })
    }

    private fun enabled(transform: AudioTransform): TransformChain =
        TransformChain.of(listOf(transform)).withEnabled(transform.id, true)

    private fun runPipeline(
        chain: TransformChain,
        segments: List<FloatArray>,
    ): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        val pipeline = chain.pipeline(RATE) { out.add(it) }
        segments.forEach { pipeline.push(it) }
        pipeline.finish()
        return out
    }

    private fun spyEncoder(written: MutableList<Int>): AudioEncoder =
        object : AudioEncoder() {
            override val format: ExportFormat = WavEncoder.FORMAT

            override fun openWriter(
                sampleRate: Int,
                out: OutputStream,
            ): SegmentWriter =
                object : SegmentWriter {
                    override fun write(segment: FloatArray) {
                        written.add(segment.size)
                    }

                    override fun close() = Unit
                }
        }
}
