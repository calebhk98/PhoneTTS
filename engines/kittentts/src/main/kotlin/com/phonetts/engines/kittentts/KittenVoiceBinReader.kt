package com.phonetts.engines.kittentts

import com.phonetts.core.engine.Voice
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes KittenTTS's OTHER real voice layout: the `onnx-community/kitten-tts-nano-0.1-ONNX`
 * conversion does NOT ship a single `voices.npz`; it ships one `voices/<name>.bin` per voice,
 * exactly like Kokoro's per-voice files but a single [STYLE_DIM]-wide row each
 * ([EXPECTED_BYTE_COUNT] bytes) rather than Kokoro's [510, 256] table. Each file is a raw
 * little-endian float32 array with no numpy header (unlike [KittenVoiceTable]'s `.npy` entries),
 * so the only validation available is the byte count, which this fails closed on (returns null)
 * rather than guessing a different reshape for a malformed file.
 *
 * This is the load-time counterpart of the bin-layout fingerprint [KittenEngine.inspect] uses:
 * `inspect()` can only confirm the `voices/<name>.bin` files are present by name; the real
 * embeddings are decoded here once [KittenEngine.load] has actual bytes to read.
 */
object KittenVoiceBinReader {
    const val STYLE_DIM = 256
    const val BIN_SUFFIX = ".bin"
    const val EXPECTED_BYTE_COUNT = STYLE_DIM * Float.SIZE_BYTES

    /** Decodes [bytes] into a single [STYLE_DIM]-wide style embedding, or null if malformed. */
    fun parseStyleRow(bytes: ByteArray): FloatArray? {
        if (bytes.size != EXPECTED_BYTE_COUNT) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(STYLE_DIM) { buffer.float }
    }

    /**
     * Reads each of [voices]'s `<dirPath>/<id>.bin` embedding via [readBytes], skipping any voice
     * whose file is missing or malformed (fail closed per voice, never guess).
     */
    fun readVoices(
        dirPath: String,
        voices: List<Voice>,
        readBytes: (String) -> ByteArray,
    ): List<KittenVoiceTable.Entry> =
        voices.mapNotNull { voice ->
            val bytes = runCatching { readBytes("$dirPath/${voice.id}$BIN_SUFFIX") }.getOrNull()
            val row = bytes?.let { parseStyleRow(it) } ?: return@mapNotNull null
            KittenVoiceTable.Entry(voice, row)
        }
}
