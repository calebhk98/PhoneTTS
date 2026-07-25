package com.phonetts.core.text.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportQualityAnalyzerTest {
    // --- OK ---

    @Test
    fun normalDocumentIsOk() {
        val paragraph =
            "This is a perfectly ordinary paragraph of real prose that reads naturally from start to " +
                "finish, with no page furniture or broken lines anywhere inside it."
        val text = (1..5).joinToString("\n\n") { "$paragraph Section $it continues the same idea further." }
        val report = ImportQualityAnalyzer.analyze(text, sourceByteSize = 40_000L, pageCount = 5)
        assertEquals(ImportQualityVerdict.OK, report.verdict)
        assertTrue(!report.isProblem)
    }

    // --- SUSPICIOUSLY_SHORT ---

    @Test
    fun nearEmptyTextWithKnownPageCountIsSuspiciouslyShort() {
        // A 10-page scan whose "text layer" is just OCR noise - a few characters total.
        val report = ImportQualityAnalyzer.analyze("...\n\n", sourceByteSize = 900_000L, pageCount = 10)
        assertEquals(ImportQualityVerdict.SUSPICIOUSLY_SHORT, report.verdict)
        assertTrue(report.isProblem)
        assertTrue(report.message.contains("scanned", ignoreCase = true))
        assertTrue(report.message.contains("10 page", ignoreCase = true), report.message)
    }

    @Test
    fun tinyTextFromNonTrivialSourceIsSuspiciouslyShortEvenWithoutPageCount() {
        val report = ImportQualityAnalyzer.analyze("hi", sourceByteSize = 1_000L, pageCount = null)
        assertEquals(ImportQualityVerdict.SUSPICIOUSLY_SHORT, report.verdict)
    }

    @Test
    fun lowTextToSourceRatioIsSuspiciouslyShortWhenPageCountUnknown() {
        // 30 chars of "extracted text" out of a 5000-byte source, no page count available (e.g. a
        // format without pagination) - the ratio floor should catch it even though 30 chars alone
        // clears the absolute floor.
        val text = "Untitled document with no content"
        val report = ImportQualityAnalyzer.analyze(text, sourceByteSize = 5_000L, pageCount = null)
        assertEquals(ImportQualityVerdict.SUSPICIOUSLY_SHORT, report.verdict)
    }

    @Test
    fun shortTextFromATinySourceIsNotFlagged() {
        // A genuinely short document (a one-line note) from an equally tiny source shouldn't be
        // treated as suspicious - there's no "should have had more text" signal here.
        val report = ImportQualityAnalyzer.analyze("Meeting moved to 3pm.", sourceByteSize = 40L, pageCount = null)
        assertEquals(ImportQualityVerdict.OK, report.verdict)
    }

    // --- LIKELY_STRUCTURED_BADLY ---

    @Test
    fun manyPageNumberLinesAreFlaggedAsBadlyStructured() {
        val content =
            (1..15).map { "This is normal sentence content number $it that reads just fine on its own." }
        val digits = listOf("1", "2", "3", "4", "5")
        val lines = mutableListOf<String>()
        content.zip(digits + List(content.size - digits.size) { "" }).forEach { (line, maybeDigit) ->
            lines.add(line)
            if (maybeDigit.isNotEmpty()) lines.add(maybeDigit)
        }
        val text = lines.joinToString("\n")
        val report = ImportQualityAnalyzer.analyze(text, sourceByteSize = 10_000L, pageCount = null)
        assertEquals(ImportQualityVerdict.LIKELY_STRUCTURED_BADLY, report.verdict)
        assertTrue(report.message.contains("page number", ignoreCase = true), report.message)
    }

    @Test
    fun manyVeryShortLinesAreFlaggedAsBadlyStructured() {
        val lines =
            listOf(
                "The cat sat",
                "on the mat",
                "and looked",
                "outside the",
                "window today",
                "while the rain",
                "fell softly down",
                "onto the roof",
                "making a sound",
                "like tiny drums",
                "playing all night",
                "without stopping once",
            )
        val text = lines.joinToString("\n")
        // Small enough source that the "text-to-source-size ratio" SUSPICIOUSLY_SHORT check (which
        // needs at least MIN_SOURCE_BYTES_FOR_RATIO_CHECK bytes to engage) doesn't fire first and mask
        // the structural signal this test is isolating.
        val report = ImportQualityAnalyzer.analyze(text, sourceByteSize = 1_000L, pageCount = null)
        assertEquals(ImportQualityVerdict.LIKELY_STRUCTURED_BADLY, report.verdict)
        assertTrue(report.message.contains("choppy", ignoreCase = true), report.message)
    }

    @Test
    fun repeatedHeaderLinesAreFlaggedAsBadlyStructured() {
        val header = "Confidential Draft - Internal Use"
        val content =
            (1..16).map { "Paragraph number $it discusses a distinct topic in enough words to not look choppy." }
        val lines = mutableListOf<String>()
        content.forEachIndexed { index, line ->
            if (index % 4 == 0) lines.add(header)
            lines.add(line)
        }
        val text = lines.joinToString("\n")
        val report = ImportQualityAnalyzer.analyze(text, sourceByteSize = 10_000L, pageCount = null)
        assertEquals(ImportQualityVerdict.LIKELY_STRUCTURED_BADLY, report.verdict)
        assertTrue(report.message.contains("headers/footers", ignoreCase = true), report.message)
    }

    @Test
    fun tooFewLinesNeverTriggersStructureCheck() {
        // Below MIN_LINES_FOR_STRUCTURE_CHECK - even an all-digit "document" is too small a sample
        // to call badly structured (it might just be a short list of numbers the user actually wants).
        val text = listOf("1", "2", "3").joinToString("\n")
        val report = ImportQualityAnalyzer.analyze(text, sourceByteSize = 50L, pageCount = null)
        assertEquals(ImportQualityVerdict.OK, report.verdict)
    }
}
