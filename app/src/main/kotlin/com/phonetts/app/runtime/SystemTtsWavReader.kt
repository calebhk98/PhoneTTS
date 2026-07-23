package com.phonetts.app.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal WAV parser for the files `TextToSpeech.synthesizeToFile()` produces (Android always
 * writes that output as a RIFF/WAVE PCM file). Walks chunks by id rather than assuming a fixed
 * 44-byte header - some engines emit extra chunks (e.g. `LIST`/`INFO`) before `data` - so the real
 * sample rate and PCM payload are always read from what the engine actually wrote (CLAUDE.md rule
 * "determine the real sample rate... rather than assuming").
 *
 * 16-bit PCM is the only format ever observed from `TextToSpeech.synthesizeToFile` (and the only
 * one this app's [com.phonetts.core.audio.Pcm16] pipeline understands elsewhere); an unrecognized
 * bit depth is treated as unparseable rather than guessed at.
 */
object SystemTtsWavReader {
    /** The facts read out of a WAV file's header, plus where/how much PCM payload follows. */
    data class Header(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Int,
    )

    private const val PCM_BITS_PER_SAMPLE = 16

    /** Reads [file]'s RIFF/WAVE header, or null if it isn't a well-formed WAV this parser understands. */
    fun readHeader(file: File): Header? =
        RandomAccessFile(file, "r").use { raf ->
            if (!hasRiffWaveTag(raf)) return null
            readChunks(raf)
        }

    private fun hasRiffWaveTag(raf: RandomAccessFile): Boolean {
        if (raf.length() < RIFF_HEADER_BYTES) return false
        val riff = fourCc(raf)
        raf.skipBytes(SIZE_FIELD_BYTES) // overall RIFF size, unused - the real sizes live per-chunk
        val wave = fourCc(raf)
        return riff == "RIFF" && wave == "WAVE"
    }

    private fun readChunks(raf: RandomAccessFile): Header? {
        var fmt: Fmt? = null
        var dataOffset = -1L
        var dataSize = 0
        while (raf.filePointer + CHUNK_TAG_HEADER_BYTES <= raf.length()) {
            val tag = fourCc(raf)
            val size = readLeInt(raf)
            when (tag) {
                "fmt " -> fmt = Fmt.parse(raf, size)
                "data" -> {
                    dataOffset = raf.filePointer
                    dataSize = size
                    raf.skipBytes(size)
                }
                else -> raf.skipBytes(size)
            }
            if (size % 2 == 1 && raf.filePointer < raf.length()) raf.skipBytes(1) // chunks are word-aligned
        }
        val format = fmt ?: return null
        if (format.sampleRate <= 0 || dataOffset < 0) return null
        return Header(format.sampleRate, format.channels, format.bitsPerSample, dataOffset, dataSize)
    }

    /**
     * Reads [file] and decodes its PCM16 payload to floats in `[-1, 1]`. Returns an empty array
     * (never throws) if the header can't be parsed or isn't 16-bit PCM - fail closed, the caller
     * treats an empty chunk as "nothing to play" rather than crashing mid-utterance.
     */
    fun readFloats(file: File): FloatArray {
        val header = readHeader(file) ?: return FloatArray(0)
        if (header.bitsPerSample != PCM_BITS_PER_SAMPLE) return FloatArray(0)
        val channels = header.channels.coerceAtLeast(1)
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(header.dataOffset)
            val bytes = ByteArray(header.dataSize)
            raf.readFully(bytes)
            decodePcm16(bytes, channels)
        }
    }

    // Downmixes multi-channel PCM16 to mono by averaging channels. No TTS engine observed in
    // practice emits stereo, but averaging rather than dropping channels means a surprise there
    // degrades to a (still correct-length, still correct-pitch) mono signal instead of corrupting it.
    private fun decodePcm16(
        bytes: ByteArray,
        channels: Int,
    ): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frameCount = bytes.size / (2 * channels)
        return FloatArray(frameCount) { frameSampleAverage(buffer, channels) }
    }

    private fun frameSampleAverage(
        buffer: ByteBuffer,
        channels: Int,
    ): Float {
        var sum = 0
        repeat(channels) { sum += buffer.short.toInt() }
        return (sum / channels) / SHORT_FULL_SCALE
    }

    private fun fourCc(raf: RandomAccessFile): String {
        val bytes = ByteArray(4).also { raf.readFully(it) }
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readLeInt(raf: RandomAccessFile): Int {
        val bytes = ByteArray(4).also { raf.readFully(it) }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private const val RIFF_HEADER_BYTES = 12L
    private const val SIZE_FIELD_BYTES = 4
    private const val CHUNK_TAG_HEADER_BYTES = 8L
    private const val SHORT_FULL_SCALE = 32768f

    private data class Fmt(val sampleRate: Int, val channels: Int, val bitsPerSample: Int) {
        companion object {
            fun parse(
                raf: RandomAccessFile,
                size: Int,
            ): Fmt {
                val bytes = ByteArray(size).also { raf.readFully(it) }
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                bb.short // audio format tag, unused (assumed PCM)
                val channels = bb.short.toInt()
                val sampleRate = bb.int
                bb.int // byte rate, derivable, unused
                bb.short // block align, derivable, unused
                val bitsPerSample = bb.short.toInt()
                return Fmt(sampleRate, channels, bitsPerSample)
            }
        }
    }
}
