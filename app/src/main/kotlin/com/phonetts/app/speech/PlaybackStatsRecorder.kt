package com.phonetts.app.speech

import com.phonetts.app.AppGraph
import com.phonetts.core.metrics.WordCounter

/**
 * The lifetime listening/generation counters behind the Stats screen (`graph.usageStatsStore`) -
 * split out of `TtsViewModel` purely so "record what just happened" is its own tiny, independently
 * testable unit rather than two more one-line methods buried in a 1500-line god object. Both
 * [GenerationStats][com.phonetts.core.metrics.GenerationStats] and
 * [ListeningTimeEstimator][com.phonetts.core.metrics.ListeningTimeEstimator] already compute a
 * per-run figure and then throw it away; this is the only place either is ever accumulated.
 */
class PlaybackStatsRecorder(private val graph: AppGraph) {
    /** Record a fully-successful generation of [text] - fed the Stats page's lifetime word count. */
    fun recordGeneration(text: String) = graph.usageStatsStore.recordGeneration(WordCounter.count(text))

    /**
     * Record [millis] of ACTUAL listening for [modelId] - measured audio delivered to the sink,
     * never [com.phonetts.core.metrics.ListeningTimeEstimator]'s prediction. Zero-or-negative runs
     * are not recorded, so the Stats page shows an honest empty state rather than a fabricated zero.
     */
    fun recordListening(
        modelId: String,
        millis: Long,
    ) {
        if (millis <= 0) return
        graph.usageStatsStore.recordPlayback(modelId, millis)
    }
}
