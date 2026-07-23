package com.phonetts.engines.f5tts

import com.phonetts.core.model.ModelBundle
import java.io.ByteArrayOutputStream

// Shared scaffolding for this module's tests only (mirrors cosyvoice2/TestSupport.kt).

/** A bundle [F5TtsEngine.inspect] confidently recognizes: all three ONNX graphs + `vocab.txt`. */
internal fun validBundle(
    id: String = "f5-bundle",
    extraFiles: Set<String> = emptySet(),
): ModelBundle =
    ModelBundle(
        id = id,
        fileNames =
            setOf(
                F5TtsEngine.PREPROCESS_FILE,
                F5TtsEngine.TRANSFORMER_FILE,
                F5TtsEngine.DECODE_FILE,
                F5TtsEngine.VOCAB_FILE,
            ) + extraFiles,
        rootPath = "/models/$id",
    )

/** A tiny vocab.txt: one entry per line, id = 0-based line index (space is line 0, like the real one). */
internal val SAMPLE_VOCAB_TEXT = listOf(" ", "!", "a", "b", "c", "h", "i").joinToString("\n")

/**
 * Builds minimal RIFF/WAVE PCM16 bytes from [interleavedSamples] (frame-major, [channels] samples
 * per frame) - just enough of the format for [F5WavDecoder] to round-trip, used both by
 * `F5WavDecoderTest` directly and to fabricate bundled reference clips for engine-level tests.
 */
internal fun buildWavBytes(
    interleavedSamples: ShortArray,
    sampleRate: Int = 24_000,
    channels: Int = 1,
): ByteArray {
    val dataBytes = interleavedSamples.size * BYTES_PER_SAMPLE
    val out = ByteArrayOutputStream()

    fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))

    fun le32(v: Int) {
        out.write(v and BYTE_MASK)
        out.write((v ushr BYTE_BITS) and BYTE_MASK)
        out.write((v ushr (BYTE_BITS * 2)) and BYTE_MASK)
        out.write((v ushr (BYTE_BITS * 3)) and BYTE_MASK)
    }

    fun le16(v: Int) {
        out.write(v and BYTE_MASK)
        out.write((v ushr BYTE_BITS) and BYTE_MASK)
    }

    ascii("RIFF")
    le32(RIFF_HEADER_TAIL_SIZE + FMT_CHUNK_SIZE + DATA_CHUNK_HEADER_SIZE + dataBytes)
    ascii("WAVE")
    ascii("fmt ")
    le32(FMT_CHUNK_PAYLOAD_SIZE)
    le16(PCM_FORMAT_TAG)
    le16(channels)
    le32(sampleRate)
    le32(sampleRate * channels * BYTES_PER_SAMPLE)
    le16(channels * BYTES_PER_SAMPLE)
    le16(BITS_PER_SAMPLE)
    ascii("data")
    le32(dataBytes)
    for (sample in interleavedSamples) le16(sample.toInt())

    return out.toByteArray()
}

private const val BYTES_PER_SAMPLE = 2
private const val BITS_PER_SAMPLE = 16
private const val PCM_FORMAT_TAG = 1
private const val BYTE_MASK = 0xFF
private const val BYTE_BITS = 8
private const val FMT_CHUNK_PAYLOAD_SIZE = 16
private const val FMT_CHUNK_SIZE = 8 + FMT_CHUNK_PAYLOAD_SIZE
private const val DATA_CHUNK_HEADER_SIZE = 8
private const val RIFF_HEADER_TAIL_SIZE = 4 // "WAVE" tag, counted in the RIFF chunk size field
