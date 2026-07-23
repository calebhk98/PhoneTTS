package com.phonetts.core.metrics

/**
 * A snapshot of generation progress, computed entirely from MEASURED audio output and
 * MEASURED wall-clock time - never a hardcoded or guessed number (this is the metrics seam's
 * whole point). [audioSecondsProduced] and [wallClockElapsedSeconds] are facts: they come from
 * an actual accumulated sample count and actual `now()` deltas, taken by [trackGeneration] (or
 * [RtfEstimator] for a standalone calibration) as chunks really arrive.
 *
 * Every derived property below is a pure function of those facts, plus - optionally - the
 * caller-supplied [totalChunks]/[totalWords] targets. Each one degrades to a safe default
 * (`0.0` or `null`) rather than fabricating a number when there isn't yet enough signal to
 * compute it: dividing by zero elapsed time, or extrapolating a total nobody told us.
 *
 * [totalChunks] is the number of upstream `Flow<FloatArray>` emissions the caller expects in
 * total - e.g. the size of the [com.phonetts.core.text.TextChunker] sentence list it is driving
 * through `synthesize()`. It is what lets [progressFraction] (and therefore [wordsPerSecond] and
 * [estimatedRemainingSeconds]) be computed without this class needing to know how many words
 * live inside any individual audio chunk, which a pure `Flow<FloatArray>` cannot tell it.
 */
data class GenerationStats(
    val audioSecondsProduced: Double,
    val wallClockElapsedSeconds: Double,
    val chunksDone: Int,
    val totalChunks: Int? = null,
    val totalWords: Int? = null,
) {
    init {
        require(audioSecondsProduced >= 0.0) { "audioSecondsProduced must not be negative" }
        require(wallClockElapsedSeconds >= 0.0) { "wallClockElapsedSeconds must not be negative" }
        require(chunksDone >= 0) { "chunksDone must not be negative" }
    }

    /**
     * Real-time factor: measured wall-clock seconds per second of audio produced. `0.0`
     * (undefined, not "infinitely fast") until any audio has actually been produced.
     */
    val realTimeFactor: Double
        get() = if (audioSecondsProduced <= 0.0) 0.0 else wallClockElapsedSeconds / audioSecondsProduced

    /** Fraction of [totalChunks] completed so far, or `null` when the total isn't known. */
    val progressFraction: Double?
        get() {
            val total = totalChunks ?: return null
            if (total <= 0) return null
            return (chunksDone.toDouble() / total).coerceIn(0.0, 1.0)
        }

    /**
     * Measured throughput of the source text, in words per wall-clock second - [totalWords]
     * scaled by how far through [totalChunks] generation has progressed. Requires both totals;
     * without them there is nothing to scale words by, so this safely reports `0.0`.
     */
    val wordsPerSecond: Double
        get() {
            val words = totalWords ?: return 0.0
            val fraction = progressFraction ?: return 0.0
            if (wallClockElapsedSeconds <= 0.0) return 0.0
            return (words * fraction) / wallClockElapsedSeconds
        }

    /**
     * Estimated remaining wall-clock time, extrapolated from the elapsed-time-per-chunk
     * observed so far (i.e. `elapsed / progressFraction` gives a projected total, minus what has
     * already elapsed). `null` until at least one chunk has completed and [totalChunks] is
     * known - zero chunks done gives no rate to extrapolate from.
     */
    val estimatedRemainingSeconds: Double?
        get() {
            val fraction = progressFraction ?: return null
            if (chunksDone <= 0 || fraction <= 0.0) return null
            val projectedTotalSeconds = wallClockElapsedSeconds / fraction
            return (projectedTotalSeconds - wallClockElapsedSeconds).coerceAtLeast(0.0)
        }
}
