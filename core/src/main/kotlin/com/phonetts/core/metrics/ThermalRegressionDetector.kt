package com.phonetts.core.metrics

/**
 * A detected slowdown between two benchmark runs of the same engine+device: the newest run is
 * [slowdownRatio]× slower (by RTF) than the [baseline] it is compared against.
 */
data class ThermalRegression(
    val current: BenchmarkRecord,
    val baseline: BenchmarkRecord,
    val slowdownRatio: Double,
)

/**
 * Reads a [BenchmarkHistory] series and flags when the newest run is much slower than the previous
 * one for the same engine+device (issue #39). RTF is wall-clock per second of audio, so a *larger*
 * RTF means *slower* - a ratio of ~2 is the classic signature of thermal throttling on a passively
 * cooled, no-NPU phone that has been synthesizing for a while.
 *
 * Deliberately conservative: it compares only the two most recent samples and stays silent unless
 * the slowdown clears [DEFAULT_SLOWDOWN_THRESHOLD], so ordinary run-to-run variance ("within noise")
 * never gets reported as a regression. This is why the whole view is OFF by default - it is a
 * power-user diagnostic, not an always-on warning.
 */
object ThermalRegressionDetector {
    /** Ratio at/above which the newest run counts as a regression. 1.5 flags a 2× slowdown, ignores noise. */
    const val DEFAULT_SLOWDOWN_THRESHOLD = 1.5

    fun detect(
        history: List<BenchmarkRecord>,
        threshold: Double = DEFAULT_SLOWDOWN_THRESHOLD,
    ): ThermalRegression? {
        if (history.size < MIN_SAMPLES_FOR_TREND) return null
        val sorted = history.sortedBy { it.timestampMillis }
        val current = sorted.last()
        val baseline = sorted[sorted.size - 2]
        if (baseline.realTimeFactor <= 0.0) return null
        val ratio = current.realTimeFactor / baseline.realTimeFactor
        if (ratio < threshold) return null
        return ThermalRegression(current, baseline, ratio)
    }

    private const val MIN_SAMPLES_FOR_TREND = 2
}
