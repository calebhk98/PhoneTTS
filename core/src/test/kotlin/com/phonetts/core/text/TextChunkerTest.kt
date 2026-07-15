package com.phonetts.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextChunkerTest {
    @Test
    fun splitsOnSentenceTerminatorsKeepingThem() {
        val chunks = TextChunker.intoSentences("Hello there. How are you? I am fine!")
        assertEquals(listOf("Hello there.", "How are you?", "I am fine!"), chunks)
    }

    @Test
    fun trimsWhitespaceAndDropsEmptyFragments() {
        val chunks = TextChunker.intoSentences("  One.\n\n  Two.  ")
        assertEquals(listOf("One.", "Two."), chunks)
    }

    @Test
    fun keepsTrailingTextWithoutTerminator() {
        assertEquals(listOf("no terminator here"), TextChunker.intoSentences("no terminator here"))
    }

    @Test
    fun blankInputYieldsNoChunks() {
        assertTrue(TextChunker.intoSentences("   \n  ").isEmpty())
    }
}
