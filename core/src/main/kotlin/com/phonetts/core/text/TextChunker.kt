package com.phonetts.core.text

/**
 * Splits text into sentence-sized chunks so an engine can synthesize sequentially and start
 * emitting audio before the whole input is processed (spec §8). Purely mechanical and
 * model-agnostic: it knows nothing about any model, only about sentence terminators.
 */
object TextChunker {
    private val terminators = charArrayOf('.', '!', '?', '\n', '…', ';')

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
