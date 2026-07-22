package com.phonetts.app.hf

import com.phonetts.core.download.hf.HfDownloadProgress
import com.phonetts.core.download.hf.HfModelSummary
import com.phonetts.core.download.hf.HfSizeEstimate
import com.phonetts.core.download.hf.HfSortOption
import com.phonetts.core.download.hf.HfTreeEntry
import com.phonetts.core.download.hf.QuantizationVariant

/**
 * State for [HfBrowseViewModel]. Split into its own file so the view model itself stays focused on
 * behavior, not data shapes (detekt LargeClass).
 */
data class HfBrowseUiState(
    val query: String = "",
    val results: List<HfModelSummary> = emptyList(),
    val loading: Boolean = false,
    // The most recent error, for a quick inline banner; every error (this one included) is also
    // retained in [errorLog] for the session so it can be read/copied later (issue #3).
    val error: String? = null,
    // Every model already in the catalog (this session's downloads AND anything imported in a
    // prior session) keyed by the SAME sanitized id every engine's descriptor.modelId uses
    // (ModelStorage.sanitize) — so "is this row already installed" survives leaving/reopening
    // this screen, not just the moment right after a fresh download completes.
    val installedIds: Set<String> = emptySet(),
    // Set when a chosen repo ships more than one weight precision and the user must pick one
    // (a budget-device win: fetch only fp16/q8 instead of every variant). Null otherwise.
    val variantChoice: VariantChoice? = null,
    // One entry per repo id currently being fetched — a map, not a single slot, so more than one
    // download can run at once (issue #2).
    val downloads: Map<String, HfDownloadProgress> = emptyMap(),
    // Download-size facts fetched on demand per repo id (issue #7) — a repo's size isn't known
    // until its file tree has been listed, so this starts empty and fills in as the user asks.
    val sizeEstimates: Map<String, HfSizeEstimate> = emptyMap(),
    val sizeLoading: Set<String> = emptySet(),
    // Retained for the whole session (not just a transient toast) so the user can scroll back and
    // copy an error that already scrolled off screen (issue #3).
    val errorLog: List<HfBrowseError> = emptyList(),
    // Sort/filter controls (issue #6) — the *choices* for tagFilter come from the current results
    // (see HfResultsView.availableTags), never a hardcoded list.
    val sort: HfSortOption = HfSortOption.MOST_DOWNLOADS,
    val tagFilter: String? = null,
) {
    /** True while [modelId] has an in-flight download (issue #2 — any number of these can be true
     * at once, one per repo). */
    fun isDownloading(modelId: String): Boolean = modelId in downloads
}

/**
 * A pending precision choice: the repo's files plus the distinct KNOWN precisions it offers.
 * [QuantizationVariant.UNKNOWN] is never offered here (issue #9) — see
 * [com.phonetts.core.download.hf.QuantizationFilter.knownVariants].
 */
data class VariantChoice(
    val modelId: String,
    val files: List<HfTreeEntry>,
    val variants: List<QuantizationVariant>,
)

/** One retained, copyable browse/download error (issue #3). [modelId] is null for a search error
 * (not tied to any one repo). */
data class HfBrowseError(
    val atMs: Long,
    val modelId: String?,
    val message: String,
)

/** How many recent errors the session keeps — old enough to be useful for a bug report, bounded so
 * the list can't grow unbounded across a long browsing session. */
const val MAX_HF_ERROR_LOG = 25
