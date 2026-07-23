package com.phonetts.core.text

/**
 * The default [Chunker]: splits text into sentence-sized chunks so an engine can synthesize
 * sequentially and start emitting audio before the whole input is processed (spec §8). Purely
 * mechanical and model-agnostic: it knows nothing about any model, only about sentence terminators,
 * a handful of common abbreviations, and decimal numbers.
 *
 * Three rules beyond "split on `.!?\n…;`":
 *
 *  - **Abbreviations** ("Mr.", "Dr.", "etc.", "e.g.", ...): a `.` is not a sentence end when the
 *    word immediately before it is a known abbreviation, or when the `.` is immediately followed by
 *    another letter with no intervening space (the pattern inside multi-dot abbreviations like
 *    "e.g." and "i.e." - a genuine sentence-ending `.` is always followed by whitespace or the end
 *    of the text, never glued straight onto the next word).
 *  - **Decimals** ("3.14"): a `.` between two digits is never a sentence end.
 *  - **Max chunk length**: terminator-free (or terminator-sparse) text is still bounded. Once a
 *    chunk crosses [MAX_CHUNK_LENGTH] characters, the next whitespace ends it (a sensible word
 *    boundary, not a mid-word chop); if no whitespace shows up before [HARD_CAP_LENGTH], it is cut
 *    there anyway so a single pathological "word" can't grow a chunk without bound.
 */
object DefaultChunker : Chunker {
    private val terminators = charArrayOf('.', '!', '?', '\n', '…', ';')

    // Abbreviations matched as a whole word immediately before a '.' (case-insensitive). Kept small
    // and common on purpose-a wrong guess here just merges two sentences, it never drops text.
    private val abbreviations =
        setOf(
            "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "gen", "rev", "capt", "sgt", "col",
            "lt", "maj", "vs", "etc", "inc", "ltd", "co", "no", "e.g", "i.e", "u.s", "u.k", "a.m", "p.m",
        )

    // Multi-dot abbreviations matched as a literal suffix ending exactly at the '.' under test -
    // the letters-only word scan above can't see across their embedded dots (e.g. the "g" before the
    // final '.' in "e.g." isn't a word on its own), so these are checked directly against the text.
    private val dottedAbbreviations = listOf("e.g.", "i.e.", "u.s.", "u.k.", "a.m.", "p.m.")

    private const val MAX_CHUNK_LENGTH = 400
    private const val HARD_CAP_LENGTH = 480

    override fun intoSentences(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (i in trimmed.indices) {
            val ch = trimmed[i]
            current.append(ch)
            if (isChunkBoundary(trimmed, current, i, ch)) flush(current, chunks)
        }
        flush(current, chunks)
        return chunks
    }

    // Whether the character just appended ends the current chunk. Folds the three boundary rules
    // (safety-valve length, terminator, protected period) into one decision so the scan loop itself
    // stays jump-free.
    private fun isChunkBoundary(
        text: String,
        current: StringBuilder,
        index: Int,
        ch: Char,
    ): Boolean {
        if (isForcedBoundary(current, ch)) return true
        if (ch !in terminators) return false
        return !isProtectedPeriod(text, index)
    }

    // A safety valve, independent of terminators: once a chunk is long enough, end it at the next
    // whitespace; if whitespace never comes, end it anyway once it's clearly run away.
    private fun isForcedBoundary(
        current: StringBuilder,
        ch: Char,
    ): Boolean {
        if (current.length < MAX_CHUNK_LENGTH) return false
        if (ch.isWhitespace()) return true
        return current.length >= HARD_CAP_LENGTH
    }

    private fun isProtectedPeriod(
        text: String,
        index: Int,
    ): Boolean {
        if (text[index] != '.') return false
        return isDecimalPoint(text, index) || isGluedToNextLetter(text, index) || isAfterAbbreviation(text, index)
    }

    private fun isDecimalPoint(
        text: String,
        index: Int,
    ): Boolean {
        val prev = text.getOrNull(index - 1)
        val next = text.getOrNull(index + 1)
        return prev?.isDigit() == true && next?.isDigit() == true
    }

    // A real sentence-ending '.' is always followed by whitespace or end-of-text. One glued directly
    // to another letter is either an embedded abbreviation dot ("e.g.", "i.e.") or something
    // domain/identifier-like-either way, not a sentence break.
    private fun isGluedToNextLetter(
        text: String,
        index: Int,
    ): Boolean = text.getOrNull(index + 1)?.isLetter() == true

    private fun isAfterAbbreviation(
        text: String,
        index: Int,
    ): Boolean {
        val wordStart = letterWordStartBefore(text, index)
        val word = text.substring(wordStart, index).lowercase()
        if (word.isNotEmpty() && word in abbreviations) return true
        return dottedAbbreviations.any { endsWithIgnoreCase(text, index, it) }
    }

    private fun letterWordStartBefore(
        text: String,
        index: Int,
    ): Int {
        var start = index
        while (start > 0 && text[start - 1].isLetter()) start--
        return start
    }

    private fun endsWithIgnoreCase(
        text: String,
        index: Int,
        suffix: String,
    ): Boolean {
        val start = index - suffix.length + 1
        if (start < 0) return false
        return text.regionMatches(start, suffix, 0, suffix.length, ignoreCase = true)
    }

    private fun flush(
        buffer: StringBuilder,
        out: MutableList<String>,
    ) {
        val piece = buffer.toString().trim()
        buffer.setLength(0)
        if (piece.isEmpty()) return
        out.add(piece)
    }
}
