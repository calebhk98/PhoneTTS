package com.phonetts.engines.kokoro

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hand-builds raw little-endian float32 `voices/<name>.bin`-shaped byte layouts for Kokoro
 * voice-table tests — there is no numpy dependency (or a real `voices/<name>.bin` fixture file
 * checked into this module) to read from, so tests construct the exact byte layout
 * [KokoroVoiceBinReader]/[KokoroVoiceTable] are expected to decode.
 */
object KokoroBinFixtures {
    /**
     * A [KokoroVoiceBinReader.ROWS] x [KokoroVoiceBinReader.COLS] table where every value in row
     * `r` is `r.toFloat()`, so a decoded row is trivially checkable against the row index that
     * selected it (proves [KokoroVoiceBinReader.styleRow]'s token-count -> row indexing).
     */
    fun tableWithRowMarkers(): FloatArray =
        FloatArray(KokoroVoiceBinReader.ROWS * KokoroVoiceBinReader.COLS) { index ->
            (index / KokoroVoiceBinReader.COLS).toFloat()
        }

    /** A full-sized table where every value is [value], for tests that only care WHICH voice's table was picked. */
    fun uniformTable(value: Float): FloatArray =
        FloatArray(KokoroVoiceBinReader.ROWS * KokoroVoiceBinReader.COLS) { value }

    /** Encodes [table] as raw little-endian bytes, matching the real `voices/<name>.bin` layout (no header). */
    fun bytesFor(table: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(table.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        table.forEach { buffer.putFloat(it) }
        return buffer.array()
    }
}
