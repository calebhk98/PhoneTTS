package com.phonetts.core.download.hf

/**
 * The "Supported" browse filter (issue #107): keep only results whose fetched file tree classifies
 * to a chosen [RunCompatibility] (via [HfCompatibility.classify]). "Supported" means [RUNNABLE] -
 * something a registered engine loads today - reconciled with the badge so a bare `.gguf` reads as
 * needs-conversion, not runnable (issue #108). Each value carries the [RunCompatibility] it keeps,
 * derived from the enum, never a hardcoded model list.
 */
enum class HfSupportedFilter(val required: RunCompatibility?) {
    ALL(null),
    RUNNABLE(RunCompatibility.RUNNABLE),
    NEEDS_CONVERSION(RunCompatibility.NEEDS_CONVERSION),
    IMPOSSIBLE(RunCompatibility.IMPOSSIBLE),
}

object HfSupportedFilters {
    /** Keeps only results whose [compatibility] equals [filter]'s target; [HfSupportedFilter.ALL] is
     * a no-op. A result whose tree isn't classified yet (absent) is dropped once a target is set,
     * mirroring [HfResultsView.filterBySize]'s unknown-excluded rule. */
    fun filter(
        results: List<HfModelSummary>,
        compatibility: Map<String, RunCompatibility>,
        filter: HfSupportedFilter,
    ): List<HfModelSummary> {
        val required = filter.required ?: return results
        return results.filter { compatibility[it.id] == required }
    }
}

/**
 * The RTF slider filter (issue #107): keep only results whose ESTIMATED real-time multiple (from
 * [ModelSpeedEstimate], derived from the fetched download size + precision hints) meets a minimum.
 * This is the size-derived estimate, distinct from the measured-benchmark RTF the RTF *sort* uses -
 * the issue explicitly allows an estimate here. A result whose size isn't fetched yet is dropped
 * once the filter is active, same unknown-excluded rule as the size filter.
 */
object HfSpeedFilter {
    fun filterByMinRealtime(
        results: List<HfModelSummary>,
        sizeEstimates: Map<String, HfSizeEstimate>,
        minMultiple: Double?,
    ): List<HfModelSummary> {
        if (minMultiple == null) return results
        return results.filter { model ->
            val bytes = sizeEstimates[model.id]?.knownBytes ?: return@filter false
            ModelSpeedEstimate.from(bytes, model.tags).realtimeMultiple >= minMultiple
        }
    }
}

/**
 * Applies the issue-#107 advanced filters (Supported, Format, RTF slider, Engine type) in one place
 * so [HfResultsView.apply] stays small (detekt LongMethod) and every filter composes in a fixed,
 * order-preserving sequence - each only removes entries, so none disturbs a prior sort. Every input
 * lives on [HfResultFilters] and defaults to "no filter", so a caller that wants none passes nothing.
 */
object HfBrowseFilters {
    fun applyAdvanced(
        results: List<HfModelSummary>,
        filters: HfResultFilters,
    ): List<HfModelSummary> {
        val supported = HfSupportedFilters.filter(results, filters.compatibility, filters.supportedFilter)
        val formatted = HfFileFormats.filterByFormat(supported, filters.formats, filters.formatFilter)
        val fast = HfSpeedFilter.filterByMinRealtime(formatted, filters.sizeEstimates, filters.minRealtimeMultiple)
        return HfEngineClassifier.filterByEngine(fast, filters.engineLabels, filters.engineFilter)
    }

    /** True when any advanced filter that needs a fetched file tree (Supported/Format/RTF) is active
     * - the browse layer's signal to eagerly fetch trees for every result, exactly like an active
     * size filter does (a filtered-out row is never composed, so its per-row fetch would never run). */
    fun needsFileData(filters: HfResultFilters): Boolean =
        filters.supportedFilter != HfSupportedFilter.ALL ||
            filters.formatFilter != null ||
            filters.minRealtimeMultiple != null
}
