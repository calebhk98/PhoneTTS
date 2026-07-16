package com.phonetts.engines.kokoro

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a single Kokoro voice `.bin` file — the REAL per-voice format shipped by
 * `onnx-community/Kokoro-82M-v1.0-ONNX` under `voices/<name>.bin`, proven end-to-end in
 * `scripts/model-verify/run_kokoro.py` (11s of clean audio). Unlike KittenTTS's `.npy`/`.npz`
 * (which carry a numpy header), a Kokoro voice file has **no header at all**: it is a raw
 * little-endian float32 array, shape [ROWS, COLS] = [510, 256] flattened row-major —
 * [EXPECTED_BYTE_COUNT] bytes total. The only validation available is the byte count, which this
 * fails closed on (returns null) rather than guessing a different reshape for a malformed file.
 *
 * Synthesis does not feed the whole table to the model: `run_kokoro.py` selects exactly ONE
 * [COLS]-wide row, indexed by how many input tokens the sentence produced
 * (`style[min(len(tokens), 509)]`), reshaped to `[1, 256]`. [styleRow] is that exact selection.
 */
object KokoroVoiceBinReader {
    const val ROWS = 510
    const val COLS = 256
    const val EXPECTED_BYTE_COUNT = ROWS * COLS * Float.SIZE_BYTES

    /** Decodes [bytes] into a flattened [ROWS] x [COLS] row-major float table, or null if malformed. */
    fun parseTable(bytes: ByteArray): FloatArray? {
        if (bytes.size != EXPECTED_BYTE_COUNT) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(ROWS * COLS) { buffer.float }
    }

    /**
     * The style row to feed the model for a sentence whose frontend produced [tokenCount] input
     * ids: `row = min(tokenCount, ROWS - 1)` (VALIDATED recipe, `run_kokoro.py` line 23), returned
     * as a fresh [COLS]-length slice ready to reshape to `[1, 256]`.
     */
    fun styleRow(
        table: FloatArray,
        tokenCount: Int,
    ): FloatArray {
        val row = tokenCount.coerceIn(0, ROWS - 1)
        val start = row * COLS
        return table.copyOfRange(start, start + COLS)
    }
}
