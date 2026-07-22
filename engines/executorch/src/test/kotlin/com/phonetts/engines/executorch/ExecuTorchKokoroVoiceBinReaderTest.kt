package com.phonetts.engines.executorch

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExecuTorchKokoroVoiceBinReaderTest {
    @Test
    fun parseTableDecodesTheExpectedByteCount() {
        val bytes = ExecuTorchKokoroBinFixtures.bytesFor(ExecuTorchKokoroBinFixtures.uniformTable(0.25f))

        val table = ExecuTorchKokoroVoiceBinReader.parseTable(bytes)

        assertEquals(ExecuTorchKokoroVoiceBinReader.ROWS * ExecuTorchKokoroVoiceBinReader.COLS, table?.size)
        assertEquals(0.25f, table?.get(0))
    }

    @Test
    fun parseTableFailsClosedOnTheWrongByteCount() {
        assertNull(ExecuTorchKokoroVoiceBinReader.parseTable(ByteArray(10)))
    }

    @Test
    fun voiceRowSelectsTheRowIndexedByTokenCountMinusOne() {
        // Row r's every value is r.toFloat(), so a decoded row is trivially checkable.
        val table =
            FloatArray(ExecuTorchKokoroVoiceBinReader.ROWS * ExecuTorchKokoroVoiceBinReader.COLS) { index ->
                (index / ExecuTorchKokoroVoiceBinReader.COLS).toFloat()
            }

        val row = ExecuTorchKokoroVoiceBinReader.voiceRow(table, tokenCount = 5)

        assertContentEquals(FloatArray(ExecuTorchKokoroVoiceBinReader.COLS) { 4f }, row)
    }

    @Test
    fun voiceRowClampsAZeroTokenCountToRowZeroRatherThanGoingNegative() {
        val table =
            FloatArray(ExecuTorchKokoroVoiceBinReader.ROWS * ExecuTorchKokoroVoiceBinReader.COLS) { index ->
                (index / ExecuTorchKokoroVoiceBinReader.COLS).toFloat()
            }

        val row = ExecuTorchKokoroVoiceBinReader.voiceRow(table, tokenCount = 0)

        assertContentEquals(FloatArray(ExecuTorchKokoroVoiceBinReader.COLS) { 0f }, row)
    }

    @Test
    fun styleSliceIsTheLastHalfOfTheVoiceRow() {
        val row =
            FloatArray(ExecuTorchKokoroVoiceBinReader.COLS) { index ->
                if (index < ExecuTorchKokoroVoiceBinReader.STYLE_COLS) 0f else 1f
            }

        val style = ExecuTorchKokoroVoiceBinReader.styleSlice(row)

        assertEquals(ExecuTorchKokoroVoiceBinReader.STYLE_COLS, style.size)
        assertContentEquals(FloatArray(ExecuTorchKokoroVoiceBinReader.STYLE_COLS) { 1f }, style)
    }
}
