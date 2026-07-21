package com.phonetts.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seam tests for the ±1-paragraph skip math (issue #26): mapping blank-line paragraph breaks onto
 * the sentence indices [TextChunker.intoSentences] produces, so a lock-screen skip restarts the one
 * generation flow from the right sentence.
 */
class ParagraphBoundaryTest {
    private val doc = "A. B.\n\nC. D. E.\n\nF."

    @Test
    fun `paragraph starts align with sentence indices`() {
        // Sentences: A. B. | C. D. E. | F.  -> paragraphs begin at sentence 0, 2, 5.
        assertEquals(listOf(0, 2, 5), TextChunker.paragraphStartSentenceIndices(doc))
        assertEquals(6, TextChunker.intoSentences(doc).size)
    }

    @Test
    fun `blank runs and no breaks degrade gracefully`() {
        assertEquals(listOf(0), TextChunker.paragraphStartSentenceIndices("One sentence only."))
        assertEquals(listOf(0), TextChunker.paragraphStartSentenceIndices(""))
        // Extra blank lines between paragraphs don't create phantom empty paragraphs.
        assertEquals(listOf(0, 1), TextChunker.paragraphStartSentenceIndices("A.\n\n\n\nB."))
    }

    @Test
    fun `next paragraph is the first start after the current sentence`() {
        assertEquals(2, TextChunker.nextParagraphStart(doc, 0))
        assertEquals(5, TextChunker.nextParagraphStart(doc, 2))
        assertEquals(5, TextChunker.nextParagraphStart(doc, 3))
    }

    @Test
    fun `next paragraph at the last one stays put`() {
        assertEquals(5, TextChunker.nextParagraphStart(doc, 5))
    }

    @Test
    fun `previous paragraph is the last start before the current sentence`() {
        // Mid-paragraph restarts the current paragraph; at a start it steps back one.
        assertEquals(2, TextChunker.previousParagraphStart(doc, 3))
        assertEquals(0, TextChunker.previousParagraphStart(doc, 2))
        assertEquals(2, TextChunker.previousParagraphStart(doc, 5))
    }

    @Test
    fun `previous paragraph at the top clamps to zero`() {
        assertEquals(0, TextChunker.previousParagraphStart(doc, 0))
        assertEquals(0, TextChunker.previousParagraphStart(doc, 1))
    }
}
