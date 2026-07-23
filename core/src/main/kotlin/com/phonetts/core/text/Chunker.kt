package com.phonetts.core.text

/**
 * A sentence-splitting strategy: text in, sentence-sized pieces out, in reading order. This is the
 * seam issue #18 asks for - a model-agnostic, swappable abstraction so the app is never pinned to
 * one splitting algorithm.
 *
 * [TextChunker] is the default implementation the app actually uses (it wraps [DefaultChunker], see
 * that object's docs for the splitting rules). To try an alternative:
 *
 *  1. Write an `object`/`class` implementing this interface - the only contract is
 *     `intoSentences(text) -> List<String>`, sentences in order, no assumptions about how you split.
 *  2. Point production at it by changing the one-line `chunker = ...` default inside [TextChunker].
 *  3. Or, to A/B two implementations without touching [TextChunker] at all, just call
 *     `implementationA.intoSentences(text)` and `implementationB.intoSentences(text)` side by side in
 *     a test - see `ChunkerSwapTest` for a worked example. Both implementations only need to satisfy
 *     this interface, so the comparison is a two-line diff, not a rewrite.
 */
fun interface Chunker {
    /** Splits [text] into sentence-sized, trimmed, non-empty pieces, in reading order. */
    fun intoSentences(text: String): List<String>
}
