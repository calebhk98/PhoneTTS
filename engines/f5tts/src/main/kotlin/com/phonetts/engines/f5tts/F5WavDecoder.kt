package com.phonetts.engines.f5tts

/**
 * Minimal RIFF/WAVE PCM16 decoder for BUNDLED reference-audio clips (`README-io.md` "Voice = a
 * bundled reference clip"). Reads exactly the subset of the WAV format this engine needs —
 * uncompressed 16-bit PCM, any channel count (multi-channel is downmixed by averaging) — into
 * normalized `FloatArray` samples in `[-1, 1]`, the convention this app uses everywhere
 * (`core/audio/Pcm16.kt`).
 *
 * Deliberately NOT a general-purpose audio decoder (no float/24-bit/compressed-PCM support): this
 * module only needs to read back small reference clips a model bundle ships, so the scope is kept
 * to exactly what's needed and unit-testable, not a speculative general codec. `:core` has a WAV
 * *encoder* ([com.phonetts.core.audio.export.WavEncoder]) but no decoder, so this is a small,
 * self-contained one local to this module rather than a `:core` change (out of this issue's scope).
 */
object F5WavDecoder {
    data class Decoded(
        val samples: FloatArray,
        val sampleRate: Int,
        val channels: Int,
    )

    fun decode(bytes: ByteArray): Decoded {
        require(bytes.size >= HEADER_SIZE) { "WAV data too short (${bytes.size} bytes)" }
        require(fourCc(bytes, RIFF_OFFSET) == "RIFF") { "not a RIFF/WAVE file (missing RIFF header)" }
        require(fourCc(bytes, WAVE_OFFSET) == "WAVE") { "not a RIFF/WAVE file (missing WAVE tag)" }

        var channels = 1
        var sampleRate = DEFAULT_SAMPLE_RATE
        var pcm: ByteArray? = null
        var offset = HEADER_SIZE
        while (offset + CHUNK_HEADER_SIZE <= bytes.size && pcm == null) {
            val chunkId = fourCc(bytes, offset)
            val chunkSize = littleEndianInt(bytes, offset + CHUNK_ID_SIZE)
            val dataStart = offset + CHUNK_HEADER_SIZE
            when (chunkId) {
                FMT_CHUNK_ID -> {
                    channels = littleEndianShort(bytes, dataStart + FMT_CHANNELS_OFFSET)
                    sampleRate = littleEndianInt(bytes, dataStart + FMT_SAMPLE_RATE_OFFSET)
                }
                DATA_CHUNK_ID -> {
                    val end = (dataStart + chunkSize).coerceAtMost(bytes.size)
                    pcm = bytes.copyOfRange(dataStart, end)
                }
            }
            offset = dataStart + chunkSize + (chunkSize and 1) // chunks are word-aligned
        }
        val pcmBytes = checkNotNull(pcm) { "WAV data has no 'data' chunk" }
        return Decoded(toNormalizedMono(pcmBytes, channels.coerceAtLeast(1)), sampleRate, channels)
    }

    private fun toNormalizedMono(
        pcm16: ByteArray,
        channels: Int,
    ): FloatArray {
        val frameStride = channels * BYTES_PER_SAMPLE
        val frameCount = pcm16.size / frameStride
        return FloatArray(frameCount) { frame ->
            var sum = 0
            for (channel in 0 until channels) {
                sum += littleEndianShort(pcm16, frame * frameStride + channel * BYTES_PER_SAMPLE)
            }
            (sum.toFloat() / channels) / SHORT_SCALE
        }
    }

    private fun fourCc(
        bytes: ByteArray,
        offset: Int,
    ): String = String(bytes, offset, FOUR_CC_SIZE, Charsets.US_ASCII)

    private fun littleEndianShort(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        val high = bytes[offset + 1].toInt() and BYTE_MASK
        val low = bytes[offset].toInt() and BYTE_MASK
        val unsigned = (high shl BYTE_BITS) or low
        return unsigned.toShort().toInt()
    }

    private fun littleEndianInt(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        var value = 0
        for (byteIndex in 0 until INT_SIZE_BYTES) {
            value = value or ((bytes[offset + byteIndex].toInt() and BYTE_MASK) shl (BYTE_BITS * byteIndex))
        }
        return value
    }

    private const val FOUR_CC_SIZE = 4
    private const val INT_SIZE_BYTES = 4
    private const val RIFF_OFFSET = 0
    private const val WAVE_OFFSET = 8
    private const val HEADER_SIZE = 12
    private const val CHUNK_ID_SIZE = 4
    private const val CHUNK_HEADER_SIZE = 8
    private const val FMT_CHUNK_ID = "fmt "
    private const val DATA_CHUNK_ID = "data"
    private const val FMT_CHANNELS_OFFSET = 2
    private const val FMT_SAMPLE_RATE_OFFSET = 4
    private const val BYTES_PER_SAMPLE = 2
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
    private const val SHORT_SCALE = 32768f
    private const val DEFAULT_SAMPLE_RATE = 24_000
}
