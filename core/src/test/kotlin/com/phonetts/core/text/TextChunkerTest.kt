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

    @Test
    fun sentenceIndexAtMapsOffsetInsideEachSentenceToItsIndex() {
        val text = "Hello there. How are you? I am fine!"
        // "Hello there." occupies 0..11, "How are you?" 13..24, "I am fine!" 26..35.
        assertEquals(0, TextChunker.sentenceIndexAt(text, 3))
        assertEquals(1, TextChunker.sentenceIndexAt(text, 18))
        assertEquals(2, TextChunker.sentenceIndexAt(text, 30))
    }

    @Test
    fun sentenceIndexAtMapsAnOffsetPastTheEndToTheLastSentence() {
        val text = "One. Two. Three."
        assertEquals(2, TextChunker.sentenceIndexAt(text, text.length))
    }

    @Test
    fun sentenceIndexAtOnBlankTextIsZero() {
        assertEquals(0, TextChunker.sentenceIndexAt("   ", 2))
    }

    @Test
    fun sentenceIndexAtIndexesTheSameSentencesIntoSentencesProduces() {
        val text = "  One.\n\n  Two.  Three."
        val sentences = TextChunker.intoSentences(text)
        // The index for an offset in the last sentence must be a valid index into that list.
        val idx = TextChunker.sentenceIndexAt(text, text.indexOf("Three"))
        assertEquals("Three.", sentences[idx])
    }
}
