package com.phonetts.core.text.quality

/**
 * What an imported document's extracted text looks like, before we do anything else with it
 * (issue #138). Ordered roughly by severity: [SUSPICIOUSLY_SHORT] means the import is close to
 * useless (near-empty output from a source that plainly had content - almost always a scanned/
 * image-only PDF with no selectable text layer); [LIKELY_STRUCTURED_BADLY] means there IS real
 * text, but a naive per-line dump baked page furniture (page numbers, running headers/footers,
 * a multi-column layout read row-by-row) into the flow that a listener would otherwise hear read
 * aloud mid-sentence; [OK] means neither pattern was detected.
 */
enum class ImportQualityVerdict {
    OK,
    SUSPICIOUSLY_SHORT,
    LIKELY_STRUCTURED_BADLY,
}

/** The verdict plus a plain-language, user-facing explanation of it (never a bare enum name). */
data class ImportQualityReport(
    val verdict: ImportQualityVerdict,
    val message: String,
) {
    /** Convenience for call sites that only want to react when something looks off. */
    val isProblem: Boolean get() = verdict != ImportQualityVerdict.OK
}

/**
 * A pure function over already-extracted text (issue #138): given the text an extractor produced
 * plus a couple of cheap facts about its source (raw byte size, and page count when the format has
 * one), decides whether the import likely went well. Deliberately independent of any particular
 * file format or extractor - it never opens a PDF/DOCX itself, so it is fully seam-testable with
 * hand-built text fixtures (no real PDF binary needed, see `ImportQualityAnalyzerTest`).
 *
 * This runs on the RAW extracted text, before [com.phonetts.core.text.quality.DocumentCleanup]
 * touches it - the symptoms [LIKELY_STRUCTURED_BADLY] looks for (page-number lines, repeated
 * headers/footers, a wall of short lines) are exactly what that cleanup fixes, so checking after
 * cleanup would hide the very problem this exists to report. Both still run independently: the
 * cleaned text is what the user actually gets, this report is what they're told about it.
 *
 * Thresholds below are round numbers chosen to be honest without being trigger-happy - they favor
 * "OK" for anything ambiguous, since a false "suspiciously short" verdict on a short-but-legitimate
 * memo is a bigger annoyance than an occasional miss.
 */
object ImportQualityAnalyzer {
    // A page of real text is usually well into the hundreds/low thousands of characters; a scanned
    // page with no text layer yields (near) zero. This floor is deliberately far below a normal
    // page so only genuinely empty-ish pages trip it.
    private const val MIN_CHARS_PER_PAGE = 25.0

    // Independent of page count: a source with a meaningful number of bytes that yielded almost no
    // text at all (regardless of format overhead) is suspicious on its own.
    private const val ABSOLUTE_MIN_CHARS = 20
    private const val SOURCE_TRIVIAL_BYTES = 200L

    // When page count isn't known (non-paginated formats), fall back to a text-to-source-size
    // ratio floor. PDF/DOCX overhead (XML, fonts, embedded structure) means even a legitimate
    // document's extracted text is a small fraction of the raw byte size, so this stays generous.
    private const val MIN_SOURCE_BYTES_FOR_RATIO_CHECK = 2_000L
    private const val MIN_CHARS_PER_SOURCE_BYTE = 1.0 / 50.0

    private const val MIN_LINES_FOR_STRUCTURE_CHECK = 8
    private const val DIGIT_ONLY_LINE_RATIO_THRESHOLD = 0.15
    private const val SHORT_LINE_RATIO_THRESHOLD = 0.5
    private const val SHORT_LINE_MAX_WORDS = 4
    private const val REPEATED_LINE_MIN_OCCURRENCES = 3
    private const val REPEATED_LINE_RATIO_THRESHOLD = 0.1

    private val pageNumberLine = Regex("""^\d{1,4}$|^(?i:page)\s+\d+(\s+of\s+\d+)?$|^\d+\s+of\s+\d+$""")

    /**
     * Classifies [text] (the raw output of a [com.phonetts.core.text.extract.TextExtractor]) given
     * the source's [sourceByteSize] and, when known, its [pageCount] (e.g. a PDF's page count - see
     * `PageCountAwareTextExtractor`). Always returns a report, including for [ImportQualityVerdict.OK].
     */
    fun analyze(
        text: String,
        sourceByteSize: Long,
        pageCount: Int? = null,
    ): ImportQualityReport {
        suspiciouslyShort(text, sourceByteSize, pageCount)?.let { return it }
        structuredBadly(text)?.let { return it }
        return ImportQualityReport(ImportQualityVerdict.OK, "The imported text looks fine.")
    }

    private fun suspiciouslyShort(
        text: String,
        sourceByteSize: Long,
        pageCount: Int?,
    ): ImportQualityReport? {
        val chars = text.trim().length
        if (chars < ABSOLUTE_MIN_CHARS && sourceByteSize > SOURCE_TRIVIAL_BYTES) {
            return shortReport(pageCount)
        }
        if (pageCount != null && pageCount > 0 && chars / pageCount.toDouble() < MIN_CHARS_PER_PAGE) {
            return shortReport(pageCount)
        }
        if (pageCount == null && sourceByteSize >= MIN_SOURCE_BYTES_FOR_RATIO_CHECK) {
            if (chars < sourceByteSize * MIN_CHARS_PER_SOURCE_BYTE) return shortReport(null)
        }
        return null
    }

    private fun shortReport(pageCount: Int?): ImportQualityReport {
        val pages = if (pageCount != null && pageCount > 0) " across $pageCount page(s)" else ""
        return ImportQualityReport(
            ImportQualityVerdict.SUSPICIOUSLY_SHORT,
            "This looks like a scanned or image-only document - very little selectable text was found" +
                "$pages. Try a text-based file instead, or run it through an OCR tool first.",
        )
    }

    private fun structuredBadly(text: String): ImportQualityReport? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < MIN_LINES_FOR_STRUCTURE_CHECK) return null
        val reasons = mutableListOf<String>()
        if (digitOnlyRatio(lines) > DIGIT_ONLY_LINE_RATIO_THRESHOLD) reasons.add("page numbers")
        if (shortLineRatio(lines) > SHORT_LINE_RATIO_THRESHOLD) reasons.add("choppy, broken-up lines")
        if (repeatedLineRatio(lines) > REPEATED_LINE_RATIO_THRESHOLD) reasons.add("repeated headers/footers")
        if (reasons.isEmpty()) return null
        return ImportQualityReport(
            ImportQualityVerdict.LIKELY_STRUCTURED_BADLY,
            "This document's layout (${reasons.joinToString(", ")}) may read a little oddly - we've " +
                "cleaned up what we can automatically, but some artifacts of the original layout can remain.",
        )
    }

    private fun digitOnlyRatio(lines: List<String>): Double =
        lines.count { pageNumberLine.matches(it) }.toDouble() / lines.size

    private fun shortLineRatio(lines: List<String>): Double =
        lines.count { it.split(Regex("\\s+")).size <= SHORT_LINE_MAX_WORDS }.toDouble() / lines.size

    private fun repeatedLineRatio(lines: List<String>): Double {
        val counts = lines.groupingBy { it }.eachCount()
        val repeated = lines.count { line -> (counts[line] ?: 0) >= REPEATED_LINE_MIN_OCCURRENCES }
        return repeated.toDouble() / lines.size
    }
}
