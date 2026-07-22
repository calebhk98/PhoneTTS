package com.phonetts.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Demonstrates the point of the [Chunker] abstraction: two independent implementations can be run
 * over the same input and diffed, without either one knowing about the other or about [TextChunker].
 * This is the "swap to a different chunker in 30 seconds, or A/B two of them" seam the interface
 * exists for (issue #18 item 5).
 */
class ChunkerSwapTest {
    /** A trivial alternative [Chunker]: one chunk per line, ignoring punctuation entirely. */
    private object LineChunker : Chunker {
        override fun intoSentences(text: String): List<String> =
            text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Another trivial alternative: the whole input as a single chunk (a no-op splitter). */
    private object WholeTextChunker : Chunker {
        override fun intoSentences(text: String): List<String> {
            val trimmed = text.trim()
            return if (trimmed.isEmpty()) emptyList() else listOf(trimmed)
        }
    }

    @Test
    fun differentChunkersDisagreeOnTheSameInputAndBothAreEasyToRun() {
        val text = "First sentence. Second sentence.\nThird sentence."

        val defaultResult = DefaultChunker.intoSentences(text)
        val lineResult = LineChunker.intoSentences(text)
        val wholeResult = WholeTextChunker.intoSentences(text)

        // Three implementations of the same interface, three different sentence counts for the
        // same input—exactly the A/B comparison the abstraction is meant to make trivial.
        assertEquals(3, defaultResult.size)
        assertEquals(2, lineResult.size)
        assertEquals(1, wholeResult.size)
        assertNotEquals(defaultResult, lineResult)
        assertNotEquals(defaultResult, wholeResult)
    }

    @Test
    fun anyChunkerCanStandInWhereverTheInterfaceIsExpected() {
        // A caller that only knows about `Chunker` (not any concrete implementation) works
        // identically no matter which implementation is wired in—the actual "swap" step.
        fun countSentences(
            chunker: Chunker,
            text: String,
        ) = chunker.intoSentences(text).size

        val text = "One line.\nAnother line."
        assertEquals(2, countSentences(DefaultChunker, text))
        assertEquals(2, countSentences(LineChunker, text))
        assertEquals(1, countSentences(WholeTextChunker, text))
    }

    @Test
    fun textChunkerIsItselfAUsableChunkerImplementation() {
        // TextChunker satisfies the Chunker contract directly, so it too can be handed to any
        // Chunker-typed call site or swapped for an alternative without changing that call site.
        val chunker: Chunker = TextChunker
        assertEquals(TextChunker.intoSentences("Hi there. Bye."), chunker.intoSentences("Hi there. Bye."))
    }

    @Test
    fun allExistingTextChunkerSeamTestsStillPassAgainstTheDefaultImplementationDirectly() {
        // TextChunker.intoSentences delegates to DefaultChunker; asserting the two agree pins that
        // delegation down so a future default swap can't silently change TextChunker's own contract.
        val samples =
            listOf(
                "Hello there. How are you? I am fine!",
                "  One.\n\n  Two.  ",
                "no terminator here",
                "Dr. Smith met Mr. Jones today.",
            )
        for (text in samples) {
            assertEquals(DefaultChunker.intoSentences(text), TextChunker.intoSentences(text))
        }
        assertTrue(TextChunker.intoSentences("   \n  ").isEmpty())
    }
}
