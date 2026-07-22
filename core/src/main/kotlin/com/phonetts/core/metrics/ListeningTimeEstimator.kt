package com.phonetts.core.metrics

/**
 * Estimates how long it will take to *listen* to a block of text — the "~6 min" figure shown next
 * to Play — from a real [WordCounter] word count and the chosen speed. This is deliberately NOT a
 * measurement of the engine (that is [RtfEstimator]'s job, which times actual synthesis): it is a
 * cheap, synthesis-free estimate of playback duration so the UI can update the moment the user
 * edits the text or drags the speed slider.
 *
 * [WORDS_PER_MINUTE] is a listening-rate constant, NOT a model fact (CLAUDE.md rule 1 governs model
 * facts — sample rate, voices, speed bounds — none of which appear here). Speed, however, IS a model
 * fact: it comes from the model's speed [com.phonetts.core.model.ModelParameter] (SSOT, rule 2), and
 * a higher speed proportionally shortens the estimate.
 */
object ListeningTimeEstimator {
    /**
     * Baseline narration rate at 1.0x speed, in words per minute. A common audiobook/narration
     * pace; the estimate scales inversely with the chosen speed.
     */
    const val WORDS_PER_MINUTE: Double = 150.0

    /**
     * Estimated listening time, in seconds, for [wordCount] words spoken at [speed] (1.0 = normal).
     * Returns 0 for empty text or a non-positive speed — the caller shows nothing in that case.
     */
    fun estimateSeconds(
        wordCount: Int,
        speed: Float,
    ): Double {
        if (wordCount <= 0 || speed <= 0f) return 0.0
        val baseSeconds = wordCount / WORDS_PER_MINUTE * SECONDS_PER_MINUTE
        return baseSeconds / speed
    }

    private const val SECONDS_PER_MINUTE = 60.0
}
