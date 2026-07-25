package com.phonetts.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seam test for [ByteFormat] - THE single byte-count renderer (CLAUDE.md rule 1). Before this, six
 * separate implementations across the app disagreed on the same fact (bare integer MB with no GB
 * tier vs a log-scale B/KB/MB/GB scheme); this locks the one canonical behavior every caller shares.
 */
class ByteFormatTest {
    @Test
    fun belowOneKilobyteIsShownAsBareBytes() {
        assertEquals("0 B", ByteFormat.format(0L))
        assertEquals("512 B", ByteFormat.format(512L))
        assertEquals("1023 B", ByteFormat.format(1023L))
    }

    @Test
    fun kilobyteTierGetsOneDecimalPlace() {
        assertEquals("1.0 KB", ByteFormat.format(1024L))
        assertEquals("320.5 KB", ByteFormat.format((320.5 * 1024).toLong()))
    }

    @Test
    fun megabyteTier() {
        assertEquals("1.0 MB", ByteFormat.format(1024L * 1024L))
        assertEquals("85.4 MB", ByteFormat.format((85.4 * 1024 * 1024).toLong()))
    }

    @Test
    fun gigabyteTier() {
        assertEquals("1.5 GB", ByteFormat.format((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatOrUnknownPassesThroughAKnownValue() {
        assertEquals(ByteFormat.format(2048L), ByteFormat.formatOrUnknown(2048L))
    }

    @Test
    fun formatOrUnknownFallsBackWhenNull() {
        assertEquals("unknown", ByteFormat.formatOrUnknown(null))
        assertEquals("n/a", ByteFormat.formatOrUnknown(null, unknown = "n/a"))
    }
}
