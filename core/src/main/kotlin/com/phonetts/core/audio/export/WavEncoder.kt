package com.phonetts.core.audio.export

import com.phonetts.core.audio.Pcm16
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The reference export format: canonical 44-byte-header, 16-bit mono PCM WAV. Every other format
// is modelled on this one (that's why the shared drain/transform logic lives in the parent). The
// total sample count isn't known until the flow drains, so PCM is buffered before the header —
// which needs the final byte counts — is written.
private const val NUM_CHANNELS = 1
private const val BITS_PER_SAMPLE = 16
private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
private const val PCM_AUDIO_FORMAT: Short = 1
private const val FMT_CHUNK_SIZE = 16
private const val HEADER_SIZE = 44
private const val RIFF_SIZE_MINUS_HEADER_TAGS = 36 // "RIFF"+size+"WAVE" is 12, header total is 44

class WavEncoder : AudioEncoder() {
    override val format: ExportFormat = FORMAT

    override suspend fun writeEncoded(
        segments: List<FloatArray>,
        sampleRate: Int,
        out: OutputStream,
    ) {
        val pcm = segments.fold(ByteArray(0)) { acc, segment -> acc + Pcm16.encode(segment) }
        out.write(header(sampleRate, pcm.size))
        out.write(pcm)
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

    companion object {
        val FORMAT =
            ExportFormat(
                id = "wav",
                displayName = "WAV (uncompressed)",
                fileExtension = "wav",
                mimeType = "audio/wav",
            )
    }
}
