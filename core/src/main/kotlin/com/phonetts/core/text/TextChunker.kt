package com.phonetts.core.text

/**
 * The app's sentence chunker. This is a thin facade over a swappable [Chunker] (see that
 * interface's docs for how to drop in an alternative implementation) plus the paragraph/offset
 * utilities built on top of it. It is itself a valid [Chunker]—`TextChunker.intoSentences(text)`
 * is exactly `chunker.intoSentences(text)` for the active [chunker], which defaults to
 * [DefaultChunker]. Swapping the whole app onto a different splitting strategy is a one-line change
 * to that default; every method below (paragraph indices, offset mapping) stays correct because
 * they're derived from whatever [chunker] actually returns, not from a second copy of its rules.
 */
object TextChunker : Chunker {
    // The single point of truth for "how do we split a sentence"—change this to try an
    // alternative Chunker app-wide. See [Chunker]'s docs for how to A/B two implementations instead.
    private val chunker: Chunker = DefaultChunker

    // A paragraph break: a blank line (one newline, optional whitespace, another newline). Because
    // '\n' is itself a sentence terminator, splitting on this never falls mid-sentence — the sentences
    // of each paragraph are exactly a contiguous slice of [intoSentences]'s flat result, so their
    // counts sum back to it. That alignment is what lets [paragraphStartSentenceIndices] map paragraph
    // boundaries onto sentence indices the one generation path already understands.
    private val paragraphBreak = Regex("\\n\\s*\\n")

    override fun intoSentences(text: String): List<String> = chunker.intoSentences(text)

    /**
     * The sentence index at which each paragraph begins (issue #26), for jumping playback ±1
     * paragraph. Always non-empty and starts at 0. A paragraph that contributes no sentences (a run
     * of blank lines) is skipped, so no two entries collide. Indices line up with [intoSentences], so
     * a returned value feeds straight into the same slice-and-resynthesize path "Read from here" uses.
     */
    fun paragraphStartSentenceIndices(text: String): List<Int> {
        val starts = mutableListOf<Int>()
        var sentenceIndex = 0
        for (paragraph in text.split(paragraphBreak)) {
            val count = intoSentences(paragraph).size
            if (count == 0) continue
            starts.add(sentenceIndex)
            sentenceIndex += count
        }
        return starts.ifEmpty { listOf(0) }
    }

    /**
     * The sentence index to jump to for "next paragraph" from [currentSentenceIndex]: the first
     * paragraph start strictly after it, or the last paragraph's start if already in the final one
     * (so a forward skip at the end is a harmless no-op rather than running off the text).
     */
    fun nextParagraphStart(
        text: String,
        currentSentenceIndex: Int,
    ): Int {
        val starts = paragraphStartSentenceIndices(text)
        return starts.firstOrNull { it > currentSentenceIndex } ?: starts.last()
    }

    /**
     * The sentence index to jump to for "previous paragraph" from [currentSentenceIndex]: the last
     * paragraph start strictly before it (so mid-paragraph this restarts the current paragraph, and
     * at a paragraph's start it steps to the previous one), or 0 when already at/near the top.
     */
    fun previousParagraphStart(
        text: String,
        currentSentenceIndex: Int,
    ): Int {
        val starts = paragraphStartSentenceIndices(text)
        return starts.lastOrNull { it < currentSentenceIndex } ?: 0
    }

    /**
     * The index into [intoSentences]'s result of the sentence that contains character [charOffset]
     * of the original [text] — the mapping "Read from here" needs to turn a tap position into a
     * starting sentence. Fails soft: an offset before the first sentence maps to 0, and an offset
     * past the last one (or in trailing whitespace) maps to the last sentence; empty text maps to 0.
     *
     * The chunk boundaries are located by re-finding each of [intoSentences]'s sentences inside the
     * original (untrimmed) [text], in order, rather than by re-running the splitting rule — so this
     * always agrees with [intoSentences] no matter which [Chunker] is active, by construction.
     */
    fun sentenceIndexAt(
        text: String,
        charOffset: Int,
    ): Int {
        val sentences = intoSentences(text)
        if (sentences.isEmpty()) return 0
        val offset = charOffset.coerceIn(0, text.length)
        // The first sentence whose last character is at/after the offset owns it. Offsets past every
        // sentence's end fall in the trailing sentence, i.e. the last one.
        val index = chunkEndOffsets(text, sentences).indexOfFirst { offset <= it }
        return if (index < 0) sentences.lastIndex else index
    }

    // The index (in [text]'s coordinates) of the last character of each sentence, found by locating
    // each of [sentences] in turn starting from where the previous one ended. Kept jump-free (no
    // break/continue) so it reads as a straight scan.
    private fun chunkEndOffsets(
        text: String,
        sentences: List<String>,
    ): List<Int> {
        val ends = mutableListOf<Int>()
        var searchFrom = 0
        for (sentence in sentences) {
            val start = text.indexOf(sentence, searchFrom).coerceAtLeast(searchFrom)
            val end = start + sentence.length
            ends.add((end - 1).coerceAtLeast(searchFrom))
            searchFrom = end
        }
        return ends
    }
}
