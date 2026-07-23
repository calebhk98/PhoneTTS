package com.phonetts.core.metrics

import kotlin.math.roundToLong

/**
 * A live snapshot of a benchmark run's timing (issue #116): how long it has actually taken so far,
 * the up-front estimate of the whole run, and a live prediction of how much is left. Every field is
 * a plain number a caller can format however it likes; [label] renders the "4:30 / 3:00, 2:45 left"
 * form the screen shows. [remainingSeconds] is deliberately independent of
 * [estimatedTotalSeconds] - the up-front total can be wrong (a model ran slower than predicted), so
 * elapsed may exceed it while the run still honestly predicts time remaining (see [BenchmarkEta]).
 */
data class BenchmarkProgress(
    val elapsedSeconds: Double,
    val estimatedTotalSeconds: Double,
    val remainingSeconds: Double,
) {
    /** "elapsed / estimated-total, remaining left", e.g. "4:30 / 3:00, 2:45 left". */
    val label: String
        get() =
            "${BenchmarkEta.formatClock(elapsedSeconds)} / " +
                "${BenchmarkEta.formatClock(estimatedTotalSeconds)}, " +
                "${BenchmarkEta.formatClock(remainingSeconds)} left"
}

/**
 * Pure ETA math for the benchmark run (issue #116). Deliberately clock-free - the caller passes in
 * `System.currentTimeMillis()` timestamps and the per-model estimates, so every function here is a
 * deterministic pure function unit-tested on a plain JVM (spec §9, matching [ListeningTimeEstimator]
 * / [GenerationStats]).
 *
 * The up-front estimate for one model is `load time + how long its share of audio takes to
 * generate`: [ListeningTimeEstimator] turns the prompt's word count into seconds of audio, and the
 * model's estimated real-time multiple (from
 * [com.phonetts.core.download.hf.ModelSpeedEstimate], the existing `ModelSpeedEstimator`) says how
 * many times faster than real time it renders, so generation seconds = audio / multiple. Summed
 * over the models, that is the whole-run estimate.
 *
 * The live remaining prediction rescales the estimate for the not-yet-run models by how the
 * finished ones actually fared (`elapsed / estimate-of-finished`), so a run that is going slower
 * than predicted reports a proportionally larger remaining time rather than a stale one that runs
 * out. [LOAD_SECONDS_ESTIMATE] is a coarse per-model load allowance, NOT a per-model literal (it is
 * the same single constant for every model, the way [ListeningTimeEstimator.WORDS_PER_MINUTE] is a
 * rate constant, not a model fact - CLAUDE.md rule 1 governs model facts, none of which appear here).
 */
object BenchmarkEta {
    /**
     * Coarse per-model load-time allowance, in seconds, added to each model's generation estimate.
     * A single run-agnostic constant (never a per-model figure): loading weights costs a few seconds
     * on the target budget hardware regardless of which model it is, and the live prediction below
     * corrects the total from real elapsed time anyway.
     */
    const val LOAD_SECONDS_ESTIMATE: Double = 3.0

    /**
     * Up-front estimate, in seconds, of benchmarking one model: [loadSeconds] to load its weights
     * plus the time to generate [wordCount] words of audio at [realtimeMultiple] times real time.
     * A non-positive [realtimeMultiple] (no speed signal) contributes only the load allowance rather
     * than dividing by zero.
     */
    fun estimateModelSeconds(
        wordCount: Int,
        realtimeMultiple: Double,
        loadSeconds: Double = LOAD_SECONDS_ESTIMATE,
    ): Double {
        val audioSeconds = ListeningTimeEstimator.estimateSeconds(wordCount, 1.0f)
        val generationSeconds = if (realtimeMultiple > 0.0) audioSeconds / realtimeMultiple else 0.0
        return (loadSeconds.coerceAtLeast(0.0) + generationSeconds).coerceAtLeast(0.0)
    }

    /** Whole-run up-front estimate: the sum of the per-model estimates from [estimateModelSeconds]. */
    fun estimateTotalSeconds(perModelSeconds: List<Double>): Double = perModelSeconds.sum()

    /**
     * A live [BenchmarkProgress] at [nowMillis], given the run's [startMillis], the per-model
     * up-front estimates [perModelSeconds] (in run order) and how many models have [completedModels]
     * finished. Remaining time rescales the estimate for the unfinished models by how the finished
     * ones actually fared, so it stays honest even once elapsed passes the original total. Before any
     * model finishes there is no measured rate yet, so the estimate is used as-is.
     */
    fun progress(
        startMillis: Long,
        nowMillis: Long,
        perModelSeconds: List<Double>,
        completedModels: Int,
    ): BenchmarkProgress {
        val elapsedSeconds = ((nowMillis - startMillis) / MILLIS_PER_SECOND).coerceAtLeast(0.0)
        val estimatedTotalSeconds = perModelSeconds.sum()
        val done = completedModels.coerceIn(0, perModelSeconds.size)
        val finishedEstimate = perModelSeconds.take(done).sum()
        val remainingEstimate = perModelSeconds.drop(done).sum()
        val scale = if (finishedEstimate > 0.0) elapsedSeconds / finishedEstimate else 1.0
        val remainingSeconds = (remainingEstimate * scale).coerceAtLeast(0.0)
        return BenchmarkProgress(elapsedSeconds, estimatedTotalSeconds, remainingSeconds)
    }

    /** [totalSeconds] as "M:SS" (seconds zero-padded), rounded to the nearest second, never negative. */
    fun formatClock(totalSeconds: Double): String {
        val whole = totalSeconds.coerceAtLeast(0.0).roundToLong()
        val minutes = whole / SECONDS_PER_MINUTE
        val seconds = whole % SECONDS_PER_MINUTE
        return "%d:%02d".format(minutes, seconds)
    }

    private const val MILLIS_PER_SECOND = 1000.0
    private const val SECONDS_PER_MINUTE = 60L
}
