package com.phonetts.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seam test for [CountFormat] - THE single parameter-count label (CLAUDE.md rule 1), factored out of
 * [com.phonetts.core.model.ModelListExport] where it used to be the only copy; two app screens had
 * their own re-implementations before this task.
 */
class CountFormatTest {
    @Test
    fun nonPositiveCountIsUnknown() {
        assertEquals("?", CountFormat.formatParamCount(0L))
        assertEquals("?", CountFormat.formatParamCount(-5L))
    }

    @Test
    fun belowAThousandIsTheBareDigits() {
        assertEquals("500", CountFormat.formatParamCount(500L))
    }

    @Test
    fun thousandsTier() {
        assertEquals("2K", CountFormat.formatParamCount(2_000L))
    }

    @Test
    fun millionsTier() {
        assertEquals("82M", CountFormat.formatParamCount(82_000_000L))
    }

    @Test
    fun billionsTierGetsOneDecimalPlace() {
        assertEquals("1.2B", CountFormat.formatParamCount(1_200_000_000L))
    }
}
