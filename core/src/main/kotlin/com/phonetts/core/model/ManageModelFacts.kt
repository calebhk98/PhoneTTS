package com.phonetts.core.model

import com.phonetts.core.download.builtin.BuiltInCatalog
import com.phonetts.core.download.builtin.PiperVoicesIndex
import com.phonetts.core.download.hf.ParameterCountEstimator
import com.phonetts.core.download.hf.SpeedPredictor

/**
 * Everything the "Manage models" screen shows about an ALREADY-DOWNLOADED model beyond name/size,
 * derived purely from facts the app already has on hand - never a per-model literal (CLAUDE.md
 * rule 1). Each field is honest-closed: null/false means "we don't know", never a fabricated
 * number or link.
 */
data class ManageModelFacts(
    /** This model's Hugging Face repo page, or null if no repo id could be recovered for it. */
    val hfRepoId: String?,
    /** Estimated parameter count from on-disk size, or null when the size is unusable (<= 0). */
    val paramCount: Long?,
    /** Peak RAM in bytes - a real observed peak if one exists, else the engine's a-priori estimate. */
    val peakRamBytes: Long?,
    /** True if [peakRamBytes] came from an actual observed load rather than the engine's guess. */
    val ramIsMeasured: Boolean,
    /** Speed as a multiple of real-time (e.g. 4.0 = ~4x faster than real-time), or null if unknown. */
    val realtimeMultiple: Double?,
    /** True if [realtimeMultiple] came from this device's own benchmark history rather than a formula. */
    val realtimeIsMeasured: Boolean,
)

/**
 * Builds [ManageModelFacts] for one installed model. Pure and deterministic - no clock, no
 * randomness, no I/O - so it is fully unit-testable on a plain JVM (spec §9).
 */
object InstalledModelFacts {
    /**
     * The HF repo id for [modelId], recovered from the curated catalog ([BuiltInCatalog]) that
     * already records a repo id per curated model - the same SSOT those download flows use to
     * build their own "Open on Hugging Face" links. Any Piper voice (browsed dynamically via
     * [PiperVoicesIndex], not stored as static data) is recognized by its `piper-` id prefix and
     * resolves to the one Piper voices repo they're all published from, rather than needing the
     * full voice list refetched just to answer this. A model that's neither (a repo browsed and
     * downloaded directly, or a sideloaded bundle) has no repo id retained anywhere, so this
     * returns null rather than guessing one from the sanitized bundle/folder name.
     */
    fun hfRepoId(modelId: String): String? =
        BuiltInCatalog.ALL.firstOrNull { it.id == modelId }?.repoId
            ?: PiperVoicesIndex.REPO_ID.takeIf { PiperVoicesIndex.isPiperVoiceId(modelId) }

    /**
     * Combines everything the Manage screen needs for [descriptor] into one honestly-labeled bundle.
     *
     * @param sizeBytes this model's on-disk size (already measured elsewhere - [ModelUsage.sizeBytes]).
     * @param observedPeakRamBytes a real measured peak RAM for this model, if one has been recorded
     *   (see `ResourceUsageStore.observedPeakRam`); null falls back to the descriptor's a-priori estimate.
     * @param measuredRealTimeFactors this device's benchmark-history RTF samples (wall-clock seconds
     *   per second of audio - lower is faster) for this model's engine, oldest-to-newest or any order;
     *   empty falls back to the parameter-count-based prediction.
     */
    fun of(
        descriptor: ModelDescriptor,
        sizeBytes: Long,
        observedPeakRamBytes: Long?,
        measuredRealTimeFactors: List<Double> = emptyList(),
    ): ManageModelFacts {
        val paramCount = estimatedParamCount(descriptor, sizeBytes)
        val measuredMultiple = averageRealtimeMultiple(measuredRealTimeFactors)
        return ManageModelFacts(
            hfRepoId = hfRepoId(descriptor.modelId),
            paramCount = paramCount,
            peakRamBytes = observedPeakRamBytes ?: descriptor.resourceCost.approxPeakRamBytes,
            ramIsMeasured = observedPeakRamBytes != null,
            realtimeMultiple = measuredMultiple ?: predictedRealtimeMultiple(paramCount),
            realtimeIsMeasured = measuredMultiple != null,
        )
    }

    // A model's own asset file names (config.json, model.onnx, ...) are the same precision hints
    // the browse screen already derives a param estimate from - no separate literal here.
    private fun estimatedParamCount(
        descriptor: ModelDescriptor,
        sizeBytes: Long,
    ): Long? {
        if (sizeBytes <= 0L) return null
        return ParameterCountEstimator.estimate(sizeBytes, descriptor.assetPaths.keys.toList())
    }

    // SpeedPredictor always returns a positive number, even for an unknown (zero) param count
    // (its own reference-point fallback) - that fallback is only honest when a param count is
    // genuinely known, so this stays null when [paramCount] itself couldn't be estimated.
    private fun predictedRealtimeMultiple(paramCount: Long?): Double? {
        if (paramCount == null || paramCount <= 0L) return null
        return SpeedPredictor.predictRealtimeMultiple(paramCount)
    }

    // Benchmark history stores wall-clock-per-audio-second RTF (lower = faster); the UI shows
    // speed the other way round ("Nx real-time", higher = faster) to match the predicted-speed
    // hint elsewhere in the app, so this inverts the averaged measured RTF once, here.
    private fun averageRealtimeMultiple(realTimeFactors: List<Double>): Double? {
        val positive = realTimeFactors.filter { it > 0.0 }
        if (positive.isEmpty()) return null
        return 1.0 / positive.average()
    }
}
