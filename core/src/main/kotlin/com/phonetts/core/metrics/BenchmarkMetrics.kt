package com.phonetts.core.metrics

/**
 * The full set of measured figures for one benchmark run (issue #14): everything [RtfEstimator]
 * already measures from draining a real `synthesize()` call — audio produced, wall-clock elapsed,
 * time to first audio — plus the model-load cost that happens *before* synthesis and the device's
 * RAM footprint at that moment. Every field here is either a genuine measurement or `null`
 * ("unknown/unavailable") — never a fabricated number (CLAUDE.md: label unknown rather than guess).
 *
 * [modelLoadSeconds], [processMemoryBytes], and [availableRamBytes] are measured by the caller
 * (the app's [BenchmarkViewModel][com.phonetts.app.benchmark.BenchmarkViewModel], which has the
 * `EngineManager`/device-memory access this pure module deliberately does not) and simply carried
 * here so the display-derived properties below have one place to live and be tested.
 */
data class BenchmarkMetrics(
    val rtf: RtfResult,
    /** Wall-clock seconds to load the engine before synthesis started, or `null` if not measured. */
    val modelLoadSeconds: Double? = null,
    /** This process's memory footprint at the moment the run finished, or `null` if unavailable. */
    val processMemoryBytes: Long? = null,
    /** Free RAM on the device at that same moment, or `null` if unavailable. */
    val availableRamBytes: Long? = null,
) {
    init {
        modelLoadSeconds?.let { require(it >= 0.0) { "modelLoadSeconds must not be negative" } }
        processMemoryBytes?.let { require(it >= 0L) { "processMemoryBytes must not be negative" } }
        availableRamBytes?.let { require(it >= 0L) { "availableRamBytes must not be negative" } }
    }

    /**
     * Actual synthesis wall-clock time once the model is already loaded — distinct from [RtfResult
     * .realTimeFactor], which is a *ratio* (wall/audio), not a duration.
     */
    val generationSeconds: Double get() = rtf.wallClockElapsedSeconds

    /** Wall time from generation start to the first emitted audio chunk; `null` if none arrived. */
    val timeToFirstAudioSeconds: Double? get() = rtf.timeToFirstAudioSeconds

    /**
     * Load time plus generation time, end to end. `null` when load time wasn't measured — reporting
     * a partial total as if it were the whole thing would be dishonest, so this abstains instead.
     */
    val totalWallSeconds: Double?
        get() = modelLoadSeconds?.let { it + generationSeconds }

    /**
     * This process's memory footprint as a fraction of the device's available RAM at run time,
     * clamped to `[0, 1]` — a process footprint and a device-available reading are sampled at
     * slightly different instants, so a naive ratio can exceed 1.0. `null` when either figure is
     * unknown, or when [availableRamBytes] is zero (nothing to divide by).
     */
    val ramUsageFraction: Double?
        get() {
            val used = processMemoryBytes ?: return null
            val available = availableRamBytes ?: return null
            if (available <= 0L) return null
            return (used.toDouble() / available.toDouble()).coerceIn(0.0, 1.0)
        }
}
