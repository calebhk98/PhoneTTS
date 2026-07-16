package com.phonetts.engines.melotts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves [MeloLexicon] reads the real `"<word> p1..pN t1..tN"` line shape (equal phoneme/tone
 * halves), lowercasing the word key exactly like the proven reference recipe.
 */
class MeloLexiconTest {
    @Test
    fun `parse splits a line into equal phoneme and tone halves keyed by lowercased word`() {
        val text = "hello hh ah l ow 7 8 7 9\n"

        val entries = MeloLexicon.parse(text)

        assertEquals(
            MeloLexicon.Entry(listOf("hh", "ah", "l", "ow"), listOf(7, 8, 7, 9)),
            entries.getValue("hello"),
        )
    }

    @Test
    fun `parse lowercases the word key`() {
        val text = "The dh ah 7 8\n"

        val entries = MeloLexicon.parse(text)

        assertEquals(MeloLexicon.Entry(listOf("dh", "ah"), listOf(7, 8)), entries.getValue("the"))
    }

    @Test
    fun `parse skips a line whose phoneme and tone counts are unequal`() {
        val text = "bad p iy uw 7 8\n"

        val entries = MeloLexicon.parse(text)

        assertNull(entries["bad"])
    }

    @Test
    fun `parse skips a line whose tone fields are not all integers`() {
        val text = "bad p iy notanumber x\n"

        val entries = MeloLexicon.parse(text)

        assertNull(entries["bad"])
    }

    @Test
    fun `parse skips a line with fewer than 3 whitespace-separated fields`() {
        val text = "onlyaword\n"

        assertEquals(emptyMap(), MeloLexicon.parse(text))
    }

    @Test
    fun `parse handles multiple entries across lines independently`() {
        val text = "hello hh ah l ow 7 8 7 9\nthe dh ah 7 8\n"

        val entries = MeloLexicon.parse(text)

        assertEquals(2, entries.size)
        assertEquals(listOf("dh", "ah"), entries.getValue("the").phonemes)
    }
}
