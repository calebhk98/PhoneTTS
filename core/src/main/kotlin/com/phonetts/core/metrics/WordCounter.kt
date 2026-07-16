package com.phonetts.core.metrics

/**
 * Exact word counting for whatever text is about to be handed to `synthesize()`. This is a
 * real count of the actual input string — never an estimate or an assumed reading speed —
 * so anything downstream that treats the result as a "measurement" (e.g. [RtfEstimator],
 * or a caller wiring up [trackGeneration]'s `totalWords`) stays honest.
 */
object WordCounter {
    private val whitespace = Regex("\\s+")

    /** Number of whitespace-delimited words in [text]. Empty/blank input counts as zero. */
    fun count(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        return trimmed.split(whitespace).count { it.isNotBlank() }
    }
}
