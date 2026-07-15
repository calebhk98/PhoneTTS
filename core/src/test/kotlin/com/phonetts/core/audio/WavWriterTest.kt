package com.phonetts.core.audio

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val QUANTIZATION_TOLERANCE = 1.0f / Short.MAX_VALUE + 1e-4f

class WavWriterTest {
    @Test
    fun wavRoundTripPreservesHeaderFieldsAndSamplesWithinQuantizationTolerance() =
        runTest {
            val chunks =
                listOf(
                    floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f),
                    floatArrayOf(0.25f, -0.25f, 0.999f),
                )
            val expected = chunks.flatMap { it.toList() }
            val out = ByteArrayOutputStream()

            WavWriter().write(flowOf(*chunks.toTypedArray()), sampleRate = 22_050, out = out)
            val parsed = parseWav(out.toByteArray())

            assertEquals("RIFF", parsed.riff)
            assertEquals("WAVE", parsed.wave)
            assertEquals("fmt ", parsed.fmt)
            assertEquals("data", parsed.dataTag)
            assertEquals(22_050, parsed.sampleRate)
            assertEquals(1, parsed.numChannels)
            assertEquals(16, parsed.bitsPerSample)
            assertEquals(expected.size, parsed.samples.size)
            expected.zip(parsed.samples).forEach { (original, decoded) ->
                assertTrue(
                    abs(original - decoded) <= QUANTIZATION_TOLERANCE,
                    "original=$original decoded=$decoded exceeded 16-bit quantization tolerance",
                )
            }
        }
}

/** The fields of a canonical 44-byte PCM WAV header, plus the decoded PCM samples. */
internal data class ParsedWav(
    val riff: String,
    val wave: String,
    val fmt: String,
    val numChannels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataTag: String,
    val samples: List<Float>,
)

/** Parses a WAV file produced by [WavWriter] back into its header fields and PCM samples. */
internal fun parseWav(bytes: ByteArray): ParsedWav {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val riff = readFourCc(buffer)
    buffer.int // RIFF chunk size, unused here
    val wave = readFourCc(buffer)
    val fmt = readFourCc(buffer)
    buffer.int // fmt chunk size
    buffer.short // audio format
    val numChannels = buffer.short.toInt()
    val sampleRate = buffer.int
    buffer.int // byte rate
    buffer.short // block align
    val bitsPerSample = buffer.short.toInt()
    val dataTag = readFourCc(buffer)
    val dataSize = buffer.int
    val samples = (0 until dataSize / 2).map { buffer.short.toFloat() / Short.MAX_VALUE }
    return ParsedWav(riff, wave, fmt, numChannels, sampleRate, bitsPerSample, dataTag, samples)
}

private fun readFourCc(buffer: ByteBuffer): String {
    val bytes = ByteArray(4)
    buffer.get(bytes)
    return String(bytes, Charsets.US_ASCII)
}
