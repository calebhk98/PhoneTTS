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
