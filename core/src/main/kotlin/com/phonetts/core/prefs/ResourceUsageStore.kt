package com.phonetts.core.prefs

import com.phonetts.core.model.ModelDescriptor

/**
 * Persists the peak RAM previous loads of a model actually cost, so the resource-cost hint the UI
 * shows can be refined from real observations rather than only the engine's a-priori estimate
 * (issue #38: "it can even use previous loads if we save that RAM info"). Mirrors [DocumentMemory]:
 * `:core` owns the logic over an injected [PreferenceStore]; `:app` supplies the
 * SharedPreferences-backed store and feeds it a measured peak after each load.
 *
 * Keeps the MAX observed peak per model — a ceiling is the safe thing to warn against. Fails closed:
 * an unrecorded or corrupt value reads back as null and the caller falls back to the descriptor's
 * declared estimate.
 */
class ResourceUsageStore(private val store: PreferenceStore) {
    /** Records an observed [peakRamBytes] for [modelId], keeping the larger of it and any prior value. */
    fun recordPeakRam(
        modelId: String,
        peakRamBytes: Long,
    ) {
        require(peakRamBytes > 0) { "peakRamBytes must be positive, was $peakRamBytes" }
        val previous = observedPeakRam(modelId) ?: 0L
        store.putString(key(modelId), maxOf(previous, peakRamBytes).toString())
    }

    /** The max peak RAM ever observed for [modelId], or null if nothing valid was ever recorded. */
    fun observedPeakRam(modelId: String): Long? {
        val value = store.getString(key(modelId))?.toLongOrNull() ?: return null
        return value.takeIf { it > 0 }
    }

    /**
     * The peak-RAM figure to display for [descriptor]: a real observed peak when we have one (it
     * beats any a-priori guess), else the engine's declared estimate, else null ("unknown"). This is
     * the single place the two sources are combined, so both the model list and the benchmark chart
     * report the same number.
     */
    fun peakRamEstimate(descriptor: ModelDescriptor): Long? =
        observedPeakRam(descriptor.modelId) ?: descriptor.resourceCost.approxPeakRamBytes

    /** Forgets any observed peak for [modelId]. A no-op if none was recorded. */
    fun forget(modelId: String) {
        store.remove(key(modelId))
    }

    private fun key(modelId: String) = "$KEY_PREFIX$modelId"

    companion object {
        private const val KEY_PREFIX = "resource_peak_ram."
    }
}
