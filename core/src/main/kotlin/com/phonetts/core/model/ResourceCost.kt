package com.phonetts.core.model

/**
 * Approximate resource footprint of a model — the descriptor-level fact (issue #38) that lets the
 * UI *warn* (never block) a user before they download or load something a small phone can't hold.
 *
 * Peak RAM is the fact that isn't otherwise known: on-disk size is measured directly from the
 * files, but how much resident memory a model needs while loaded + generating is an ENGINE-declared
 * a-priori estimate, discovered when the engine inspects the model (same SSOT discipline as sample
 * rate or the speed knob — a model fact lives here, never as a literal in the UI). It is refined at
 * runtime by whatever peak RAM previous loads actually cost (see
 * [com.phonetts.core.prefs.ResourceUsageStore]).
 *
 * Honest-closed: [approxPeakRamBytes] is null when the engine has no basis for an estimate — the UI
 * then shows "unknown" rather than a fabricated number.
 */
data class ResourceCost(
    /** Approximate peak resident RAM while the model is loaded and generating, in bytes; null if unknown. */
    val approxPeakRamBytes: Long? = null,
) {
    init {
        require(approxPeakRamBytes == null || approxPeakRamBytes > 0) {
            "approxPeakRamBytes must be positive when known, was $approxPeakRamBytes"
        }
    }

    companion object {
        /** The engine could not estimate a footprint — the UI shows "unknown", not a guess. */
        val UNKNOWN = ResourceCost()

        private const val BYTES_PER_MEBIBYTE = 1024L * 1024L

        /** Convenience: an approximate peak-RAM estimate expressed in whole mebibytes. */
        fun peakRamMebibytes(mebibytes: Long): ResourceCost =
            ResourceCost(approxPeakRamBytes = mebibytes * BYTES_PER_MEBIBYTE)
    }
}
