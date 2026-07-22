package com.phonetts.core.text

/**
 * Splits text into sentence-sized chunks so an engine can synthesize sequentially and start
 * emitting audio before the whole input is processed (spec §8). Purely mechanical and
 * model-agnostic: it knows nothing about any model, only about sentence terminators.
 */
object TextChunker {
    private val terminators = charArrayOf('.', '!', '?', '\n', '…', ';')

    // A paragraph break: a blank line (one newline, optional whitespace, another newline). Because
    // '\n' is itself a sentence terminator, splitting on this never falls mid-sentence — the sentences
    // of each paragraph are exactly a contiguous slice of [intoSentences]'s flat result, so their
    // counts sum back to it. That alignment is what lets [paragraphStartSentenceIndices] map paragraph
    // boundaries onto sentence indices the one generation path already understands.
    private val paragraphBreak = Regex("\\n\\s*\\n")

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

    fun intoSentences(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in trimmed) {
            current.append(ch)
            if (ch !in terminators) continue
            flush(current, chunks)
        }
        flush(current, chunks)
        return chunks
    }

    /**
     * The index into [intoSentences]'s result of the sentence that contains character [charOffset]
     * of the original [text] — the mapping "Read from here" needs to turn a tap position into a
     * starting sentence. Fails soft: an offset before the first sentence maps to 0, and an offset
     * past the last one (or in trailing whitespace) maps to the last sentence; empty text maps to 0.
     *
     * The chunk boundaries are recomputed over the original (untrimmed) text so the returned index
     * lines up exactly with [intoSentences] — the same terminator rule, the same dropping of empty
     * pieces — while staying in the caller's character coordinates.
     */
    fun sentenceIndexAt(
        text: String,
        charOffset: Int,
    ): Int {
        val sentences = intoSentences(text)
        if (sentences.isEmpty()) return 0
        val offset = charOffset.coerceIn(0, text.length)
        // The first chunk whose terminator is at/after the offset owns it. Offsets past every
        // terminator fall in the trailing (terminator-less) chunk, i.e. the last sentence.
        val index = chunkEndOffsets(text).indexOfFirst { offset <= it }
        return if (index < 0) sentences.lastIndex else index
    }

    // The terminator position that ends each non-empty emitted chunk, in the original text's
    // coordinates — one entry per sentence [intoSentences] would emit, minus any trailing chunk that
    // has no terminator. Kept jump-free (no break/continue) so it reads as a straight scan.
    private fun chunkEndOffsets(text: String): List<Int> {
        val ends = mutableListOf<Int>()
        val current = StringBuilder()
        for (i in text.indices) {
            current.append(text[i])
            val isTerminator = text[i] in terminators
            if (isTerminator && current.isNotBlank()) ends.add(i)
            if (isTerminator) current.setLength(0)
        }
        return ends
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
