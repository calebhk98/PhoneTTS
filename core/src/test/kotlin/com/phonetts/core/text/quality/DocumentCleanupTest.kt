package com.phonetts.core.text.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentCleanupTest {
    // --- stripRepeatedLines ---

    @Test
    fun stripRepeatedLinesDropsAShortLineRepeatedAcrossTheDocument() {
        val header = "Confidential Draft"
        val text =
            listOf(
                header,
                "The quarterly results exceeded expectations across every region.",
                header,
                "Management remains cautious about next year's outlook regardless.",
                header,
                "A final decision on expansion will be made by the board next month.",
            ).joinToString("\n")
        val cleaned = DocumentCleanup.stripRepeatedLines(text)
        assertFalse(cleaned.contains(header))
        assertTrue(cleaned.contains("quarterly results"))
        assertTrue(cleaned.contains("board next month"))
    }

    @Test
    fun stripRepeatedLinesLeavesALineThatOnlyRepeatsTwice() {
        val line = "Confidential Draft"
        val text = listOf(line, "Some content here.", line, "More content follows.").joinToString("\n")
        val cleaned = DocumentCleanup.stripRepeatedLines(text, minOccurrences = 3)
        assertTrue(cleaned.contains(line))
    }

    @Test
    fun stripRepeatedLinesIgnoresALongLineEvenIfRepeated() {
        val longLine = "This sentence is long enough that it should never be treated as a running header."
        val text = List(4) { longLine }.joinToString("\n")
        val cleaned = DocumentCleanup.stripRepeatedLines(text, maxWords = 8)
        assertTrue(cleaned.contains(longLine))
    }

    // --- dropPageNumberLines ---

    @Test
    fun dropPageNumberLinesRemovesBareDigitsAndPageOfStyles() {
        val text =
            listOf(
                "Chapter One",
                "42",
                "Page 3",
                "Page 3 of 10",
                "3 of 10",
                "- 7 -",
                "Real content continues here.",
            ).joinToString("\n")
        val cleaned = DocumentCleanup.dropPageNumberLines(text)
        assertEquals(listOf("Chapter One", "Real content continues here.").joinToString("\n"), cleaned)
    }

    @Test
    fun dropPageNumberLinesKeepsALineThatHappensToStartWithADigit() {
        val text = "3 apples were left on the table."
        assertEquals(text, DocumentCleanup.dropPageNumberLines(text))
    }

    // --- repairHyphenation ---

    @Test
    fun repairHyphenationJoinsAWordBrokenAcrossALineBreak() {
        val text = "This is an inter-\nnational cooperation effort."
        assertEquals("This is an international cooperation effort.", DocumentCleanup.repairHyphenation(text))
    }

    @Test
    fun repairHyphenationDoesNotJoinARealTrailingDashBeforeANewSentence() {
        val text = "Wait -\nThen he left the room."
        assertEquals(text, DocumentCleanup.repairHyphenation(text))
    }

    // --- joinWrappedLines ---

    @Test
    fun joinWrappedLinesMergesAMidSentenceLineBreak() {
        val text = "The quick brown fox\njumped over the lazy dog."
        assertEquals("The quick brown fox jumped over the lazy dog.", DocumentCleanup.joinWrappedLines(text))
    }

    @Test
    fun joinWrappedLinesPreservesAnIntentionalParagraphBreak() {
        val text = "First paragraph ends here.\n\nSecond paragraph starts here."
        assertEquals(text, DocumentCleanup.joinWrappedLines(text))
    }

    @Test
    fun joinWrappedLinesDoesNotMergeWhenNextLineStartsANewSentence() {
        val text = "This sentence ends properly.\nThis one starts a new one."
        assertEquals(text, DocumentCleanup.joinWrappedLines(text))
    }

    // --- stripCitationMarkers ---

    @Test
    fun stripCitationMarkersRemovesBracketedNumericReferences() {
        val text = "This claim is well supported [12] and further confirmed [3, 4] by later work."
        assertEquals("This claim is well supported and further confirmed by later work.", DocumentCleanup.stripCitationMarkers(text))
    }

    @Test
    fun stripCitationMarkersLeavesNonNumericBracketsAlone() {
        val text = "See the [Appendix] for details."
        assertEquals(text, DocumentCleanup.stripCitationMarkers(text))
    }

    // --- full pipeline ---

    @Test
    fun cleanFixesAllIssuesAtOnceInARealisticPdfDump() {
        // Three "pages": a running header + a bare page number on each, a word hyphenated across a
        // line break on page 1, and a wrapped (not actually ended) sentence spanning pages 2's lines.
        val text =
            listOf(
                "Annual Report 2024",
                "1",
                "The company achieved strong growth in every inter-",
                "national market it entered this year, citing demand [7].",
                "Annual Report 2024",
                "2",
                "Looking ahead, the board expects momentum",
                "to continue into the following fiscal year.",
                "Annual Report 2024",
                "3",
                "Final remarks conclude the discussion for now.",
            ).joinToString("\n")
        val cleaned = DocumentCleanup.clean(text)
        val expected =
            listOf(
                "The company achieved strong growth in every international market it entered this year, " +
                    "citing demand.",
                "Looking ahead, the board expects momentum to continue into the following fiscal year.",
                "Final remarks conclude the discussion for now.",
            ).joinToString("\n")
        assertEquals(expected, cleaned)
    }

    @Test
    fun cleanSkipsAStepWhenItsFlagIsOff() {
        val text = "42\nReal content here."
        val cleaned = DocumentCleanup.clean(text, CleanupOptions(dropPageNumbers = false))
        assertTrue(cleaned.contains("42"))
    }
}
