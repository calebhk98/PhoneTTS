package com.phonetts.engines.executorch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hand-builds raw little-endian float32 `voices/<name>.bin`-shaped byte layouts for tests - no
 * numpy dependency or real fixture file to read from, so tests construct the exact byte layout
 * [ExecuTorchKokoroVoiceBinReader]/[ExecuTorchKokoroVoiceTable] are expected to decode. Mirrors
 * `:engines:kokoro`'s `KokoroBinFixtures` (duplicated, not shared - see
 * [ExecuTorchKokoroVoiceBinReader]'s kdoc for why).
 */
object ExecuTorchKokoroBinFixtures {
    /** A full-sized table where every value is [value] - for tests that only care WHICH voice's table was picked. */
    fun uniformTable(value: Float): FloatArray =
        FloatArray(ExecuTorchKokoroVoiceBinReader.ROWS * ExecuTorchKokoroVoiceBinReader.COLS) { value }

    /** Encodes [table] as raw little-endian bytes, matching the real `voices/<name>.bin` layout (no header). */
    fun bytesFor(table: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(table.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        table.forEach { buffer.putFloat(it) }
        return buffer.array()
    }
}
