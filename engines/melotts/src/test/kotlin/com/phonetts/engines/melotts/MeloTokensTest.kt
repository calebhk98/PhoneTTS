package com.phonetts.engines.melotts

import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves [MeloTokens] reads the real `"<symbol> <id>"` line shape, skipping malformed lines. */
class MeloTokensTest {
    @Test
    fun `parse builds a symbol to id map from one entry per line`() {
        val text = "_ 0\nAA 7\nSP 3\n"

        val symbols = MeloTokens.parse(text)

        assertEquals(mapOf("_" to 0, "AA" to 7, "SP" to 3), symbols)
    }

    @Test
    fun `parse skips blank lines and lines with the wrong number of fields`() {
        val text = "_ 0\n\nmalformed\np 1 extra\nq 2\n"

        val symbols = MeloTokens.parse(text)

        assertEquals(mapOf("_" to 0, "q" to 2), symbols)
    }

    @Test
    fun `parse skips a line whose id is not an integer`() {
        val text = "_ 0\np notanumber\n"

        val symbols = MeloTokens.parse(text)

        assertEquals(mapOf("_" to 0), symbols)
    }

    @Test
    fun `parse of empty text yields an empty table`() {
        assertEquals(emptyMap(), MeloTokens.parse(""))
    }
}
