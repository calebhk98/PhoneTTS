package com.phonetts.app.hf

import com.phonetts.core.download.builtin.BuiltInModel
import com.phonetts.core.download.hf.DiagnosticsEntry
import com.phonetts.core.download.hf.HfDownloadProgress
import com.phonetts.core.download.hf.HfFileFormat
import com.phonetts.core.download.hf.HfInstalledFilter
import com.phonetts.core.download.hf.HfModelSummary
import com.phonetts.core.download.hf.HfSizeEstimate
import com.phonetts.core.download.hf.HfSizeParamFilter
import com.phonetts.core.download.hf.HfSortOption
import com.phonetts.core.download.hf.HfSupportedFilter
import com.phonetts.core.download.hf.HfTreeEntry
import com.phonetts.core.download.hf.QuantizationVariant
import com.phonetts.core.download.hf.RunCompatibility

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
    // Set when a chosen repo ships more than one weight precision and the user must pick one
    // (a budget-device win: fetch only fp16/q8 instead of every variant). Null otherwise.
    val variantChoice: VariantChoice? = null,
    // One entry per repo id currently being fetched - a map, not a single slot, so more than one
    // download can run at once (issue #2).
    val downloads: Map<String, HfDownloadProgress> = emptyMap(),
    // Download-size facts fetched on demand per repo id (issue #7) - a repo's size isn't known
    // until its file tree has been listed, so this starts empty and fills in as the user asks.
    val sizeEstimates: Map<String, HfSizeEstimate> = emptyMap(),
    val sizeLoading: Set<String> = emptySet(),
    // How runnable each repo's listed file tree is (see HfCompatibility.classify) - drives the badge
    // (issue #108: distinguish "cannot run here" from "needs conversion" from runnable) and the
    // Supported filter (issue #107). Filled alongside [sizeEstimates] since both need the same
    // file-tree fetch; a repo not yet checked is simply absent (no badge shown), never guessed.
    val compatibility: Map<String, RunCompatibility> = emptyMap(),
    // The weight/model formats each repo's file tree carries (issue #107 Format filter) - derived
    // from file extensions/tokens (HfFileFormats.formatsOf), filled from the same fetch as above.
    val fileFormats: Map<String, Set<HfFileFormat>> = emptyMap(),
    // Retained for the whole session (not just a transient toast) so the user can scroll back and
    // copy an error that already scrolled off screen (issue #3).
    val errorLog: List<HfBrowseError> = emptyList(),
    // Persistent (not just this session - see DownloadDiagnosticsLog) record of download failures
    // and "downloaded, no engine yet" imports, mirrored into state so the Browse screen's dialog is
    // driven by the same collectAsState() flow as everything else.
    val diagnostics: List<DiagnosticsEntry> = emptyList(),
    // Sort/filter controls (issue #6) - the *choices* for tagFilter come from the current results
    // (see HfResultsView.availableTags), never a hardcoded list.
    val sort: HfSortOption = HfSortOption.MOST_DOWNLOADS,
    val tagFilter: String? = null,
    // Language filter (issue: many models are multilingual, user mostly wants English) - like the
    // tag filter, the *choices* come from the current results (HfLanguages.availableLanguages),
    // never a hardcoded language list. Null = all languages.
    val languageFilter: String? = null,
    // Repo/model ids whose most recent download attempt failed with a real error (not a cancel, not
    // a "downloaded, no engine yet"). Drives the "Retry failed (N)" control (issue: a network drop
    // failed a whole batch with no way to see/retry which ones). Each is still resumable from its
    // partial file on disk - retry just re-runs its download. Cleared on success/cancel/retry.
    val failedDownloadIds: Set<String> = emptySet(),
    // Size/param-count filter (issue: sort+filter by size/params) - see HfSizeParamFilter. Applying
    // any bound here (or picking a size/param HfSortOption) is what triggers eagerly fetching sizes
    // for the whole current result set - see HfBrowseViewModel.ensureSizesLoaded.
    val sizeFilter: HfSizeParamFilter = HfSizeParamFilter(),
    // Installed / not-installed filter (issue: installed filter) - "installed" is read live from the
    // local model catalog (HfBrowseViewModel.isInstalled), never cached here, so this only carries
    // the user's ALL/INSTALLED_ONLY/NOT_INSTALLED_ONLY *choice*.
    val installedFilter: HfInstalledFilter = HfInstalledFilter.ALL,
    // Advanced filters (issue #107) - each carries only the user's CHOICE; the choices themselves
    // (available formats/engines) are derived from data (see HfBrowseViewModel.availableFormats /
    // availableEngines), never a hardcoded list. Setting a Supported/Format/RTF filter triggers the
    // same eager file-tree fetch an active size filter does, so a filtered-out row still resolves.
    val supportedFilter: HfSupportedFilter = HfSupportedFilter.ALL,
    val formatFilter: HfFileFormat? = null,
    val minRealtimeMultiple: Double? = null,
    val engineFilter: String? = null,
    // Concurrency cap + queue (issue #101): repo ids that are tracked (in [downloads]) but WAITING on
    // a free download slot rather than actively transferring - shown with a "Queued" state so the
    // user sees the full set instead of the overflow silently vanishing. Cleared as each starts.
    val queuedDownloadIds: Set<String> = emptySet(),
    // Hugging Face 429 cooldown (issue #103): the wall-clock time until the list/search path may be
    // hit again. 0 = not rate-limited. [rateLimitConsecutive] counts back-to-back 429s to drive the
    // backoff and stop auto-retrying past HfRateLimitBackoff.MAX_AUTO_RETRIES.
    val rateLimitedUntilMs: Long = 0,
    val rateLimitConsecutive: Int = 0,
    // Pagination (issue: Browse "Load more") - the Hub's /api/models list is paged with limit+skip
    // (HfEndpoints.searchModelsUrl); [results] above only ever holds the pages fetched SO far,
    // appended in order. [canLoadMore] is false once a page came back shorter than the page size
    // (or before any search has run), so the button/control disappears rather than firing a request
    // that's known to return nothing new.
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    // "Piper voices" section (issue #71): collapsed by default - 166+ voices is too many to show
    // unfiltered - with its own search text, kept here (not local Composable state) so both
    // survive the same recomposition/navigation lifecycle as every other Browse control.
    val piperVoicesExpanded: Boolean = false,
    val piperVoiceQuery: String = "",
    // The Piper voice list itself is fetched at runtime from upstream rhasspy/piper-voices'
    // `voices.json` the first time the section is expanded (never a checked-in snapshot - see
    // PiperVoicesIndex) and cached here for the rest of the session: empty + not loading + no
    // error means "not fetched yet", so the section starts collapsed with nothing to lay out.
    val piperVoices: List<BuiltInModel> = emptyList(),
    val piperVoicesLoading: Boolean = false,
    // Fail-closed (CLAUDE.md rule 4's spirit applied to data, not just model detection): a fetch
    // failure surfaces this message rather than falling back to a stale/guessed list.
    val piperVoicesError: String? = null,
) {
    /** True while [modelId] has an in-flight download (issue #2 - any number of these can be true
     * at once, one per repo). Includes queued downloads (tracked but waiting for a slot). */
    fun isDownloading(modelId: String): Boolean = modelId in downloads

    /** True while a Hugging Face 429 cooldown is still in effect at [nowMs] (issue #103) - the
     * structural backstop that keeps [HfBrowseViewModel.search]/[loadMore] from re-hammering the
     * same throttled bucket, and the signal the UI uses to disable Search/"Load more". */
    fun isRateLimited(nowMs: Long): Boolean = nowMs < rateLimitedUntilMs
}

/**
 * A pending precision choice: the repo's files plus the distinct KNOWN precisions it offers.
 * [QuantizationVariant.UNKNOWN] is never offered here (issue #9) - see
 * [com.phonetts.core.download.hf.QuantizationFilter.knownVariants].
 */
data class VariantChoice(
    val modelId: String,
    val files: List<HfTreeEntry>,
    val variants: List<QuantizationVariant>,
)

/** One retained, copyable browse/download error (issue #3). [modelId] is null for a search error
 * (not tied to any one repo). [count] collapses repeat-identical entries into a single row so a
 * throttle/transient loop can't flood the bounded 25-slot log (issue #103) - shown as "(xN)" when
 * more than one. */
data class HfBrowseError(
    val atMs: Long,
    val modelId: String?,
    val message: String,
    val count: Int = 1,
)

/** How many recent errors the session keeps - old enough to be useful for a bug report, bounded so
 * the list can't grow unbounded across a long browsing session. */
const val MAX_HF_ERROR_LOG = 25
