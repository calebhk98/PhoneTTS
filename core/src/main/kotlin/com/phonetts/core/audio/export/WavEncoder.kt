package com.phonetts.core.audio.export

import com.phonetts.core.audio.Pcm16
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The reference export format: canonical 44-byte-header, 16-bit mono PCM WAV. Every other format
// is modelled on this one (that's why the shared drain/transform logic lives in the parent).
//
// The WAV header needs the total byte count up front, but a cold synthesis flow doesn't know it
// until it drains — and buffering the whole thing in RAM is exactly the OOM this fixes (issue #33).
// So [WavStreamWriter] streams the PCM to a scratch file as segments arrive (bounded heap: one
// segment at a time), then on close() writes the now-known header to [out] and copies the scratch
// PCM after it. Output bytes are identical to the old buffer-everything encoder.
private const val NUM_CHANNELS = 1
private const val BITS_PER_SAMPLE = 16
private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
private const val PCM_AUDIO_FORMAT: Short = 1
private const val FMT_CHUNK_SIZE = 16
private const val HEADER_SIZE = 44
private const val RIFF_SIZE_MINUS_HEADER_TAGS = 36 // "RIFF"+size+"WAVE" is 12, header total is 44

class WavEncoder(
    // Scratch dir for the streaming PCM spill; null = JVM default temp (app-private cache on Android).
    private val scratchDir: File? = null,
) : AudioEncoder() {
    override val format: ExportFormat = FORMAT

    override fun openWriter(
        sampleRate: Int,
        out: OutputStream,
    ): SegmentWriter = WavStreamWriter(sampleRate, out, scratchDir)

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

private class WavStreamWriter(
    private val sampleRate: Int,
    private val out: OutputStream,
    scratchDir: File?,
) : SegmentWriter {
    private val scratch: File = File.createTempFile("phonetts_wav_", ".pcm", scratchDir)
    private val pcmOut = BufferedOutputStream(FileOutputStream(scratch))
    private var dataSize = 0L

    override fun write(segment: FloatArray) {
        val bytes = Pcm16.encode(segment)
        pcmOut.write(bytes)
        dataSize += bytes.size
    }

    override fun close() {
        pcmOut.flush()
        pcmOut.close()
        try {
            out.write(header(sampleRate, dataSize.toInt()))
            scratch.inputStream().use { it.copyTo(out) }
        } finally {
            scratch.delete()
        }
    }
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
