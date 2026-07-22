package com.phonetts.engines.executorch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a single Kokoro-on-ExecuTorch voice `.bin` file — VERIFIED (Hugging Face
 * `software-mansion/react-native-executorch-kokoro`, `voices/<name>.bin`, each 522240 bytes) to be the
 * exact SAME raw layout `:engines:kokoro`'s `KokoroVoiceBinReader` already decodes for the ONNX
 * export: no header, little-endian float32, shape [ROWS, COLS] = [510, 256] flattened row-major.
 * Duplicated here (not shared via `:engines:common`) deliberately — this module depends on
 * nothing but `:core` + `:engines:common`, same as every other engine module, so deleting this
 * directory removes ExecuTorch support cleanly with no cross-engine dependency to unwind.
 *
 * Unlike the ONNX Kokoro engine, which feeds the WHOLE selected row as one `style` input, the
 * ExecuTorch export's two-graph pipeline (VALIDATED: `NorbertKlockiewicz/kokoro-export`,
 * `demo/inference_example.py`) splits the row in two:
 *  - the duration predictor's `v_style` input is [styleSlice] — the LAST [STYLE_COLS] columns.
 *  - the synthesizer's `voice_vec` input is [voiceRow] — the FULL [COLS]-wide row.
 *
 * so both are exposed here rather than one `styleRow` accessor like the ONNX reader.
 */
object ExecuTorchKokoroVoiceBinReader {
    const val ROWS = 510
    const val COLS = 256
    const val STYLE_COLS = 128
    const val EXPECTED_BYTE_COUNT = ROWS * COLS * Float.SIZE_BYTES

    /** Decodes [bytes] into a flattened [ROWS] x [COLS] row-major float table, or null if malformed. */
    fun parseTable(bytes: ByteArray): FloatArray? {
        if (bytes.size != EXPECTED_BYTE_COUNT) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(ROWS * COLS) { buffer.float }
    }

    /**
     * `voice_vec = voice[len(phonemes) - 1]` (VALIDATED recipe, `inference_example.py`): the full
     * [COLS]-wide row for a sentence whose frontend produced [tokenCount] INNER phoneme tokens
     * (before pad-wrapping). Clamped to `[0, ROWS - 1]` rather than trusting an out-of-range count.
     */
    fun voiceRow(
        table: FloatArray,
        tokenCount: Int,
    ): FloatArray {
        val row = (tokenCount - 1).coerceIn(0, ROWS - 1)
        val start = row * COLS
        return table.copyOfRange(start, start + COLS)
    }

    /** `voice_vec[:, 128:]` (VALIDATED recipe): the last [STYLE_COLS] columns of a [voiceRow]. */
    fun styleSlice(voiceRow: FloatArray): FloatArray = voiceRow.copyOfRange(COLS - STYLE_COLS, COLS)
}
