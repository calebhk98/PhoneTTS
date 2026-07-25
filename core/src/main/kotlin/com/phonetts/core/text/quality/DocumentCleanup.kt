package com.phonetts.core.text.quality

/**
 * Which of [DocumentCleanup]'s transforms to apply, and in what's otherwise a fixed, dependency
 * order (issue #138). Every flag defaults to on; each is independently testable and independently
 * switchable off, since each is a heuristic with its own (documented) false-positive risk.
 *
 * Order matters and is NOT reconfigurable: repeated headers/footers and page-number lines are
 * dropped first so they can't get stitched into the middle of a sentence by the later joins;
 * hyphenation is repaired before wrapped-line joining so a word broken as `"inter-\nnational"`
 * becomes one word (`"international"`) rather than two ("inter- national"); citation markers are
 * stripped last, after lines are already in their final shape.
 */
data class CleanupOptions(
    val stripRepeatedLines: Boolean = true,
    val dropPageNumbers: Boolean = true,
    val repairHyphenation: Boolean = true,
    val joinWrappedLines: Boolean = true,
    val stripCitationMarkers: Boolean = true,
)

/**
 * Cheap, pure text transforms (issue #138) that turn a naive per-line PDF/DOCX text dump into
 * something closer to real prose, without touching layout the source didn't actually have. Every
 * function here is a plain `String -> String`, so each is directly unit-testable on hand-built
 * fixtures - no PDF/DOCX binary involved (that lives one layer up, in the extractors).
 *
 * These are best-effort heuristics, not a layout engine: [DocumentCleanup] never has real page
 * boundaries to work with (the extractors it runs on top of - `PdfTextExtractor` et al. - hand
 * back a flat string, not per-page text), so "recurs at the same relative position on every page"
 * is approximated as "a short line that appears verbatim several times across the whole
 * document," which is the same signal in practice for running headers/footers/watermarks.
 */
object DocumentCleanup {
    /** Applies the enabled steps of [options], in the fixed order documented on [CleanupOptions]. */
    fun clean(
        text: String,
        options: CleanupOptions = CleanupOptions(),
    ): String {
        var result = text
        if (options.stripRepeatedLines) result = stripRepeatedLines(result)
        if (options.dropPageNumbers) result = dropPageNumberLines(result)
        if (options.repairHyphenation) result = repairHyphenation(result)
        if (options.joinWrappedLines) result = joinWrappedLines(result)
        if (options.stripCitationMarkers) result = stripCitationMarkers(result)
        return result.trim()
    }

    // --- repeated headers/footers ---

    private const val DEFAULT_MIN_OCCURRENCES = 3
    private const val DEFAULT_MAX_WORDS = 8

    /**
     * Drops any non-blank line that (a) is short enough to plausibly be a running header/footer/
     * watermark ([maxWords] words or fewer) and (b) recurs verbatim at least [minOccurrences] times
     * across the whole document. Deliberately conservative on both knobs - a real book's short,
     * repeated dialogue lines ("Yes." / "No.") are the known false-positive risk, which is exactly
     * why this step is independently skippable via [CleanupOptions.stripRepeatedLines].
     */
    fun stripRepeatedLines(
        text: String,
        minOccurrences: Int = DEFAULT_MIN_OCCURRENCES,
        maxWords: Int = DEFAULT_MAX_WORDS,
    ): String {
        val lines = text.split("\n")
        val counts = lines.map { it.trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
        val headerFooterLines =
            counts.filterKeys { it.split(Regex("\\s+")).size <= maxWords }
                .filterValues { it >= minOccurrences }
                .keys
        return lines.filter { it.trim() !in headerFooterLines }.joinToString("\n")
    }

    // --- page numbers ---

    /** Drops a line that is only a page number: bare digits, "Page N", "Page N of M", or "N of M". */
    fun dropPageNumberLines(text: String): String =
        text.split("\n").filterNot { pageNumberLinePattern.matches(it.trim()) }.joinToString("\n")

    // --- hyphenation repair ---

    /**
     * Joins a word hyphenated across a line break ("inter-\nnational" -> "international"): a line
     * ending in a letter immediately followed by `-`, whose next non-blank line starts with a
     * lowercase letter, is merged with the hyphen removed. The lowercase-start check is what tells
     * a genuine word-break from a real trailing dash (list bullet, em-dash-as-punctuation) - not
     * foolproof (a dash before a lowercase continuation of a clause reads the same way), but a
     * favorable trade given how much more common actual hyphenation breaks are in extracted PDF/DOCX
     * text.
     */
    fun repairHyphenation(text: String): String {
        val lines = text.split("\n")
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1)
            if (next != null && endsWithHyphenatedWord(line) && startsLowercase(next)) {
                out.add(line.trimEnd().dropLast(1) + next.trimStart())
                i += 2
                continue
            }
            out.add(line)
            i += 1
        }
        return out.joinToString("\n")
    }

    private fun endsWithHyphenatedWord(line: String): Boolean {
        val trimmed = line.trimEnd()
        if (trimmed.length < 2 || trimmed.last() != '-') return false
        return trimmed[trimmed.length - 2].isLetter()
    }

    private fun startsLowercase(line: String): Boolean {
        val firstLetter = line.trimStart().firstOrNull { it.isLetter() } ?: return false
        return firstLetter.isLowerCase()
    }

    // --- wrapped-line joining ---

    private val terminalPunctuation = setOf('.', '!', '?', ':', ';', '"', '\'', '”', '’', ')', ']', '…')

    /**
     * The single highest-impact transform after the quality signal itself: joins a line that does
     * NOT end in terminal punctuation to the next non-blank line when that next line starts with a
     * lowercase letter - the exact shape a PDF/DOCX text dump leaves a wrapped (not actually ended)
     * sentence in. This is what lets [com.phonetts.core.text.Chunker] see one real sentence instead
     * of one chunk per visual line. Blank lines (paragraph breaks) are never crossed, so intentional
     * paragraph structure survives untouched.
     */
    fun joinWrappedLines(text: String): String {
        val lines = text.split("\n")
        val out = mutableListOf<String>()
        for (line in lines) {
            if (out.isNotEmpty() && shouldJoin(out.last(), line)) {
                out[out.lastIndex] = out.last().trimEnd() + " " + line.trim()
                continue
            }
            out.add(line)
        }
        return out.joinToString("\n")
    }

    private fun shouldJoin(
        previous: String,
        current: String,
    ): Boolean {
        val prevTrimmed = previous.trim()
        val currTrimmed = current.trim()
        if (prevTrimmed.isEmpty() || currTrimmed.isEmpty()) return false
        if (prevTrimmed.last() in terminalPunctuation) return false
        val firstLetter = currTrimmed.firstOrNull { it.isLetter() } ?: return false
        return firstLetter.isLowerCase()
    }

    // --- citation markers ---

    private val citationMarker = Regex("""\[\d+(?:\s*[-–,]\s*\d+)*\]""")
    private val spaceBeforePunctuation = Regex("""\s+([.,;:!?])""")
    private val runsOfSpaces = Regex(" {2,}")

    /** Strips inline citation markers like "[12]" or "[3, 4]" that TTS would otherwise read aloud. */
    fun stripCitationMarkers(text: String): String =
        text.split("\n").joinToString("\n") { line ->
            line.replace(citationMarker, "")
                .replace(spaceBeforePunctuation, "$1")
                .replace(runsOfSpaces, " ")
                .trim()
        }
}
