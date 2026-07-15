package com.phonetts.core.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

private const val NUM_CHANNELS = 1
private const val BITS_PER_SAMPLE = 16
private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
private const val PCM_AUDIO_FORMAT: Short = 1
private const val FMT_CHUNK_SIZE = 16
private const val HEADER_SIZE = 44
private const val RIFF_SIZE_MINUS_HEADER_TAGS = 36 // "RIFF"+size+"WAVE" is 12, header total is 44

/**
 * Encodes a generated-audio `Flow<FloatArray>` as a canonical 44-byte-header, 16-bit PCM, mono
 * WAV file written to [OutputStream] (spec §6.1, §9.6). This is the WAV half of the
 * dual-consumer audio layer; [StreamingConsumer] is the other. The caller must pass
 * [com.phonetts.core.model.ModelDescriptor.sampleRate] as [sampleRate] — never a constant.
 *
 * The total sample count is not known until the flow drains, so PCM data is buffered in memory
 * before the header (which needs the final byte counts) is written.
 */
class WavWriter {
    suspend fun write(
        flow: Flow<FloatArray>,
        sampleRate: Int,
        out: OutputStream,
    ) {
        val pcm = ByteArrayOutputStream()
        flow.collect { chunk -> pcm.write(encodeChunk(chunk)) }
        val data = pcm.toByteArray()
        out.write(header(sampleRate, data.size))
        out.write(data)
    }

    private fun encodeChunk(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            buffer.putShort(floatToInt16(sample))
        }
        return buffer.array()
    }

    private fun floatToInt16(sample: Float): Short {
        val scaled = (sample * Short.MAX_VALUE).roundToInt()
        return scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun header(
        sampleRate: Int,
        dataSize: Int,
    ): ByteArray {
        val blockAlign = NUM_CHANNELS * BYTES_PER_SAMPLE
        val byteRate = sampleRate * blockAlign
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(tag("RIFF"))
        buffer.putInt(RIFF_SIZE_MINUS_HEADER_TAGS + dataSize)
        buffer.put(tag("WAVE"))
        buffer.put(tag("fmt "))
        buffer.putInt(FMT_CHUNK_SIZE)
        buffer.putShort(PCM_AUDIO_FORMAT)
        buffer.putShort(NUM_CHANNELS.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(BITS_PER_SAMPLE.toShort())
        buffer.put(tag("data"))
        buffer.putInt(dataSize)
        return buffer.array()
    }

    private fun tag(fourCc: String): ByteArray = fourCc.toByteArray(Charsets.US_ASCII)
}
