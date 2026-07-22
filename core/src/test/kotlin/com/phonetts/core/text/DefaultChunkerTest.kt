package com.phonetts.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hardening tests for [DefaultChunker]'s three rules beyond plain terminator-splitting:
 * abbreviations, decimal numbers, and the max-chunk-length safety bound.
 */
class DefaultChunkerTest {
    @Test
    fun doesNotSplitAfterACommonAbbreviation() {
        val chunks = DefaultChunker.intoSentences("Dr. Smith met Mr. Jones. They spoke briefly.")
        assertEquals(listOf("Dr. Smith met Mr. Jones.", "They spoke briefly."), chunks)
    }

    @Test
    fun doesNotSplitAfterOtherCommonAbbreviations() {
        val chunks = DefaultChunker.intoSentences("Take St. James St. to the store, e.g. the corner shop.")
        assertEquals(listOf("Take St. James St. to the store, e.g. the corner shop."), chunks)
    }

    @Test
    fun handlesEtcAndIeAbbreviationsMidSentence() {
        val chunks =
            DefaultChunker.intoSentences(
                "Bring apples, oranges, etc. Also bring cutlery, i.e. forks and spoons. Thanks!",
            )
        assertEquals(
            listOf(
                "Bring apples, oranges, etc. Also bring cutlery, i.e. forks and spoons.",
                "Thanks!",
            ),
            chunks,
        )
    }

    @Test
    fun doesNotSplitOnADecimalPoint() {
        val chunks = DefaultChunker.intoSentences("The value is 3.14 meters long. It fits.")
        assertEquals(listOf("The value is 3.14 meters long.", "It fits."), chunks)
    }

    @Test
    fun stillSplitsOnAnOrdinarySentenceEndingPeriod() {
        // Regression: hardening must not swallow every period, only the protected ones.
        val chunks = DefaultChunker.intoSentences("Hello there. How are you?")
        assertEquals(listOf("Hello there.", "How are you?"), chunks)
    }

    @Test
    fun boundsAChunkThatHasNoTerminatorsAtAll() {
        val word = "a".repeat(50)
        val text = List(20) { word }.joinToString(" ") // 20 * 51 chars, well past the cap, no terminators
        val chunks = DefaultChunker.intoSentences(text)

        assertTrue(chunks.size > 1, "expected the terminator-free text to be split into multiple chunks")
        for (chunk in chunks) {
            assertTrue(chunk.length <= 480, "chunk of length ${chunk.length} exceeded the hard cap: $chunk")
        }
        // No characters lost or reordered by the forced split.
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun boundsAChunkEvenWithoutAnyWhitespaceToSplitOn() {
        val text = "a".repeat(1000)
        val chunks = DefaultChunker.intoSentences(text)

        assertTrue(chunks.size > 1, "expected a single giant run to still be split")
        for (chunk in chunks) {
            assertTrue(chunk.length <= 480, "chunk of length ${chunk.length} exceeded the hard cap")
        }
        assertEquals(text, chunks.joinToString(""))
    }
}
