package com.phonetts.engines.kokoro

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Direct unit tests for the `.bin` decoder [KokoroVoiceBinReader] used by [KokoroVoiceTable] --
 * the REAL Kokoro voice format (`onnx-community/Kokoro-82M-v1.0-ONNX`, VALIDATED via
 * `scripts/model-verify/run_kokoro.py`): a raw little-endian float32 array, no header, shape
 * [510, 256].
 */
class KokoroVoiceBinReaderTest {
    @Test
    fun decodesKnownFloatValuesFromAHandBuiltLittleEndianBuffer() {
        val bytes = ByteArray(KokoroVoiceBinReader.EXPECTED_BYTE_COUNT)
        // IEEE-754 little-endian encodings of 1.0f and -2.5f, written as literal bytes independent
        // of this module's own encoder -- proves parseTable's byte order, not just a round trip.
        val oneFloatLe = byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F)
        val minusTwoPointFiveLe = byteArrayOf(0x00, 0x00, 0x20, 0xC0.toByte())
        oneFloatLe.copyInto(bytes, destinationOffset = 0)
        minusTwoPointFiveLe.copyInto(bytes, destinationOffset = 4)

        val table = requireNotNull(KokoroVoiceBinReader.parseTable(bytes))

        assertEquals(1.0f, table[0])
        assertEquals(-2.5f, table[1])
    }

    @Test
    fun decodesAFullSizedTableRoundTrip() {
        val table = KokoroBinFixtures.tableWithRowMarkers()

        val decoded = KokoroVoiceBinReader.parseTable(KokoroBinFixtures.bytesFor(table))

        assertContentEquals(table, decoded)
    }

    @Test
    fun rejectsAByteArrayThatIsNotTheExpectedSize() {
        assertNull(KokoroVoiceBinReader.parseTable(ByteArray(10)))
    }

    @Test
    fun rejectsAnEmptyByteArray() {
        assertNull(KokoroVoiceBinReader.parseTable(ByteArray(0)))
    }

    @Test
    fun styleRowSelectsTheRowIndexedByTokenCount() {
        val table = KokoroBinFixtures.tableWithRowMarkers()

        val row = KokoroVoiceBinReader.styleRow(table, tokenCount = 5)

        assertContentEquals(FloatArray(KokoroVoiceBinReader.COLS) { 5f }, row)
    }

    @Test
    fun styleRowClampsATokenCountAtOrBeyondTheLastRow() {
        val table = KokoroBinFixtures.tableWithRowMarkers()
        val lastRow = (KokoroVoiceBinReader.ROWS - 1).toFloat()

        val row = KokoroVoiceBinReader.styleRow(table, tokenCount = 10_000)

        assertContentEquals(FloatArray(KokoroVoiceBinReader.COLS) { lastRow }, row)
    }

    @Test
    fun styleRowClampsANegativeTokenCountToRowZero() {
        val table = KokoroBinFixtures.tableWithRowMarkers()

        val row = KokoroVoiceBinReader.styleRow(table, tokenCount = -1)

        assertContentEquals(FloatArray(KokoroVoiceBinReader.COLS) { 0f }, row)
    }
}
