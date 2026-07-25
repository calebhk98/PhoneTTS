package com.phonetts.app.hf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.ModelStorage
import com.phonetts.app.UserPickRequiredException
import com.phonetts.app.device.DeviceInfo
import com.phonetts.core.download.builtin.BuiltInCatalog
import com.phonetts.core.download.builtin.BuiltInModel
import com.phonetts.core.download.builtin.PiperVoicesIndex
import com.phonetts.core.download.hf.DiagnosticsEntry
import com.phonetts.core.download.hf.DiagnosticsKind
import com.phonetts.core.download.hf.HfBrowseFilters
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.download.hf.HfCompatibility
import com.phonetts.core.download.hf.HfDownloadItem
import com.phonetts.core.download.hf.HfDownloadPlan
import com.phonetts.core.download.hf.HfDownloadProgress
import com.phonetts.core.download.hf.HfEndpoints
import com.phonetts.core.download.hf.HfEngineClassifier
import com.phonetts.core.download.hf.HfFileFormat
import com.phonetts.core.download.hf.HfFileFormats
import com.phonetts.core.download.hf.HfInstalledFilter
import com.phonetts.core.download.hf.HfLanguages
import com.phonetts.core.download.hf.HfLoadMorePolicy
import com.phonetts.core.download.hf.HfModelSummary
import com.phonetts.core.download.hf.HfQuantizedDownloadPlan
import com.phonetts.core.download.hf.HfRateLimitBackoff
import com.phonetts.core.download.hf.HfRateLimitedException
import com.phonetts.core.download.hf.HfResultFilters
import com.phonetts.core.download.hf.HfResultsView
import com.phonetts.core.download.hf.HfSupportedFilter
import com.phonetts.core.download.hf.OfflineErrorHint
import com.phonetts.core.download.hf.HfSizeEstimator
import com.phonetts.core.download.hf.HfSizeParamFilter
import com.phonetts.core.download.hf.HfSortOption
import com.phonetts.core.download.hf.HfTreeEntry
import com.phonetts.core.download.hf.HttpClient
import com.phonetts.core.download.hf.QuantizationFilter
import com.phonetts.core.download.hf.QuantizationVariant
import com.phonetts.core.download.hf.needsEagerSizeFetch
import com.phonetts.core.metrics.BenchmarkHistory
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.store.DurableErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Drives the HF browse screen: live search of text-to-speech models, sort/filter over the results
 * (issue #6), download → import of a chosen repo with **multiple downloads running at once**
 * (issue #2), on-demand size lookups (issue #7), and a retained/copyable error log (issue #3) that
 * also mirrors into a durable, cross-restart store (issue #88, see [durableErrorLog]). All model
 * detection is delegated to the core resolver via [HfDownloader]; this class holds UI state only and
 * names no model family.
 */
class HfBrowseViewModel(
    private val catalog: HfCatalog,
    private val downloader: HfDownloader,
    private val modelCatalog: ModelCatalog,
    // True if the Runtime with the given id is available on this build. Used to hide a recommended
    // model whose runtime isn't present (e.g. CosyVoice3 in a non-native build), so a one-tap
    // download never lands a model that can't load. Defaults to "not available" - fail-closed.
    private val isRuntimeAvailable: (String) -> Boolean = { false },
    // Injectable wall-clock so progress/ETA math (issue #7) is deterministic to test; defaults to
    // the real clock on device.
    private val clock: () -> Long = System::currentTimeMillis,
    // Live system-notification mirror of each download's progress (issue: download progress
    // notification) - null by default so this class (and any test constructing it directly) never
    // needs a real Android Context; the caller that DOES have one (MainActivity/AppGraph, outside
    // this file's ownership) passes a real DownloadNotifier(context) to turn it on. See
    // DownloadNotifier's kdoc.
    private val notifier: DownloadNotifier? = null,
    // Issue #88: the session error log below (`HfBrowseUiState.errorLog`) is in-memory only and dies
    // with the process - a user who hits a search/size/download error, backgrounds the app, and comes
    // back has nothing left to copy. [logError] mirrors every entry into this durable, bounded sink
    // too (see [DurableErrorLog], already the shared home [MainActivity]'s Help screen reads for
    // crash rows) so the same error survives a restart. Null by default, exactly like [notifier]
    // above - this class (and any test constructing it directly) needs no real Android storage; the
    // caller wiring up the real dependency (AppGraph/MainActivity, outside this file's ownership)
    // passes `graph.durableErrorLog` to turn it on.
    // WIRED: VoicesHubScreen's BrowseTabContent passes `durableErrorLog = graph.durableErrorLog`.
    // Stated as a fact rather than a to-do on purpose - a comment saying "still needs wiring" that
    // outlives the wiring is how this repo ended up with several features that were built, tested,
    // and never actually connected.
    private val durableErrorLog: DurableErrorLog? = null,
    // The transport used ONLY to fetch upstream rhasspy/piper-voices' `voices.json` (issue: don't
    // check in a static Piper voice snapshot - see PiperVoicesIndex). Defaults to the same real
    // HTTP client [catalog] itself is built on elsewhere in the graph, so the caller wiring this up
    // (AppGraph/MainActivity) needs no change; a test can still inject a fake.
    private val piperHttp: HttpClient = HttpUrlConnectionClient(),
    // Read-only source for the RTF sort/filter's per-model speed numbers (issue: real-time-factor
    // sort/filter) - the SAME persisted history the Benchmarks screen writes to
    // (BenchmarkViewModel.recordAndDetect), never a second measurement path. Null by default (a test
    // constructing this class directly, or a build wiring nothing up) simply means the RTF sort has
    // nothing to show yet - fail-closed, exactly like [notifier] above.
    private val benchmarkHistory: BenchmarkHistory? = null,
    // Injectable device name so RTF lookups are deterministic to test; defaults to the real device's
    // name, matching how the Benchmarks screen keys history (BenchmarkViewModel/DeviceInfo.name).
    private val deviceName: String = DeviceInfo.name,
    // The ids of every registered engine (issue #107 "Engine type" filter) - the SSOT for which
    // engine a browsed repo maps to (see HfEngineClassifier), derived from the live registry, never
    // a hardcoded model list. Empty by default (fail-closed: a build wiring nothing up just shows no
    // engine choices), so a test constructing this class directly needs nothing. AppGraph passes
    // engineRegistry.list().map { it.id } - the one small external wire-up this filter needs.
    private val knownEngineIds: List<String> = emptyList(),
    // This device's TOTAL RAM (issue #137: the "may not fit this device" warning existed only on
    // the Manage screen, AFTER download - too late to help the decision this screen is actually
    // for). Same figure/provider shape as ModelManagementViewModel's totalRamBytes: a 0-arg
    // provider (not a raw Long) so it's read once lazily rather than forcing every call site,
    // including tests that construct this class directly, to know a real device's RAM up front.
    // Defaults to "unknown" (0L), which DeviceRamFit.modelExceedsDeviceRam treats as "can't say" -
    // fail-closed, never a false warning from an absent reading.
    private val totalRamBytes: () -> Long = { 0L },
) : ViewModel() {
    private val mutableState = MutableStateFlow(HfBrowseUiState())
    val state: StateFlow<HfBrowseUiState> = mutableState.asStateFlow()

    /** This device's TOTAL RAM in bytes, read once (it never changes for the life of the process) -
     * the one figure [com.phonetts.core.model.DeviceRamFit] checks to decide whether a browsed
     * model's estimated size can't possibly fit. Exposed as a plain property (not app state) since
     * it never changes and the UI needs no recomposition trigger for it.
     *
     * Bug fix (Browse crash on open): like [recommended]/[isRecommendedAvailable] just above, this
     * runs as part of CONSTRUCTING this class - before the screen has anything to show - by calling
     * an injected probe ([totalRamBytes], wired to `DeviceInfo.totalRamBytes(context)` in production:
     * `ActivityManager.getMemoryInfo()` under the hood, a real Binder call that can throw on a
     * misbehaving OEM framework or a dead system-server binder). [totalRamBytes]'s own kdoc already
     * promises "0L, fail-closed" for an ordinary failed reading; that guarantee did not extend to an
     * exception, so a throwing probe here used to take the whole Browse screen down before it could
     * render anything - the exact bug class [isRecommendedAvailable] exists to prevent, just left
     * unguarded on this one property. runCatching extends the same fail-closed contract: a
     * misbehaving RAM probe now yields "unknown" (0L, DeviceRamFit's own "can't say" value) instead
     * of crashing construction.
     */
    val deviceTotalRamBytes: Long = runCatching { totalRamBytes() }.getOrDefault(0L)

    // One coroutine per repo id currently in flight (listing files, or fetching them) - a map, not
    // a single slot, so the user can start a second download while the first is still running and
    // still cancel each independently (issue #2).
    private val downloadJobs = mutableMapOf<String, Job>()

    // Caps how many downloads actually TRANSFER at once (issue #101): queueing ~25 at once used to
    // fire them all concurrently, saturating the phone's connection into a batch of socket failures
    // that read as "No internet". Each download coroutine acquires a permit before transferring and
    // shows a "Queued" state while it waits; overflow is never dropped, and a freed slot starts the
    // next queued item automatically. Gates the transfer (runDownload), not the tiny file-tree
    // listing or a user variant choice, so a permit is never held during user think-time.
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    // Caps how many per-row size/file-tree lookups (loadSize) run at once (issue: unbounded
    // per-row size fetches on Browse). Unlike downloads, these fire from a LaunchedEffect on EVERY
    // visible row with no user action gating them - fast-scrolling a long result list used to fire
    // one file-tree request per row with no limit at all, which can flood the HF file-tree endpoint
    // and get the whole session rate-limited (degrading the Format/Supported/RTF filters that also
    // depend on this same fetch). Mirrors [downloadSemaphore]'s pattern at a smaller size, since a
    // size lookup is far cheaper than a download transfer but still a real network round trip.
    private val sizeLookupSemaphore = Semaphore(MAX_CONCURRENT_SIZE_LOOKUPS)

    // How to re-run each download that has failed, keyed by repo id (issue: a network drop failed a
    // whole batch with no way to retry them). Held here rather than in UI state because it's a
    // callback, not display data - the UI only needs the failed *ids* (state.failedDownloadIds) to
    // show the "Retry failed (N)" count. Registered when a download starts, invoked by
    // [resumeFailedDownloads], and dropped once a download completes or is cancelled.
    private val retryActions = mutableMapOf<String, () -> Unit>()

    /**
     * Curated one-tap models (proven working; see docs/MODEL-VERIFICATION.md), minus any whose
     * required runtime isn't available on this build - so a standard APK shows the four ONNX models
     * and a native (-PwithCosyVoice) build also shows CosyVoice3.
     *
     * Bug fix (Browse crash on open): this runs synchronously as part of constructing this class -
     * BEFORE the screen has anything to show - so a runtime-availability probe that throws (rather
     * than returning false) used to take the whole Browse screen down before it could even open,
     * with no way to search/scroll/retry around it. [isRuntimeAvailable] already documents itself as
     * "fail-closed: defaults to not available" for a normal false answer; [isRecommendedAvailable]
     * extends that same fail-closed guarantee to an exception, so a misbehaving probe just hides the
     * one model it's asked about instead of crashing construction.
     */
    val recommended: List<BuiltInModel> = BuiltInCatalog.ALL.filter(::isRecommendedAvailable)

    private fun isRecommendedAvailable(model: BuiltInModel): Boolean {
        val runtimeId = model.requiresRuntimeId ?: return true
        return runCatching { isRuntimeAvailable(runtimeId) }.getOrDefault(false)
    }

    // Populate the list immediately so the screen isn't just the handful of recommended models until
    // the user types - a blank query lists the top TTS models (HfEndpoints.searchModelsUrl). Fail-
    // closed: no network just leaves results empty with an error line, the recommended list still shows.
    // Both calls are themselves internally fail-closed for their async work; wrapped here too (bug fix:
    // Browse crash on open) so nothing added to either in the future can throw OUT of init{} and take
    // the whole screen down before it has rendered anything at all.
    init {
        runCatching { search() }
        runCatching { loadDiagnostics() }
    }

    /** True if [rawId] (an [HfModelSummary.id] or [BuiltInModel.id]) is already on disk - resolved
     * OR unresolved (bug #6: [ModelCatalog.isKnown], not [ModelCatalog.list] alone, so a
     * downloaded-but-unidentified bundle shows "Installed" rather than inviting a redownload). Read
     * straight from the catalog (the SSOT) on every call rather than a locally cached copy, so it
     * can never drift stale. */
    fun isInstalled(rawId: String): Boolean = modelCatalog.isKnown(ModelStorage.sanitize(rawId))

    /** The current results after [HfBrowseUiState.sort]/[HfBrowseUiState.tagFilter] are applied - a
     * pure recompute over already-fetched data (issue #6), never a second network call. Takes
     * [state] explicitly (rather than reading [mutableState] internally) so the Compose call site
     * can `remember` this keyed on exactly the fields it depends on - see [HfBrowseScreen] (issue
     * #3): recomputing this on every unrelated state change, e.g. a download-progress tick, is
     * what made the sort/filter dropdown janky.
     *
     * Bug fix (Browse crash on open): called directly from a Compose `remember` block with no
     * surrounding try/catch of its own, and it runs the instant real Hugging Face results first land
     * - exactly the moment "opening Browse" turns from the empty/loading state into real data. It
     * also touches on-device state this class doesn't otherwise validate (e.g. [rtfEstimates] reads
     * the real, SharedPreferences-backed [benchmarkHistory] for every installed+benchmarked result).
     * A Browse screen must never crash outright (spec rule 4's fail-closed spirit, applied to
     * rendering, not just model detection): on any unexpected exception, fall back to the raw
     * fetched results - unsorted/unfiltered is still a working screen, a crash is not.
     */
    fun displayedResults(state: HfBrowseUiState): List<HfModelSummary> =
        runCatching { applyFilters(state, state.results) }.getOrDefault(state.results)

    // Shared by displayedResults (the full current result set) and loadMore's "how many pages do I
    // still need?" check (a growing prefix of it) - one filter pipeline, so the count loadMore reads
    // is always exactly what the screen would show for the same [results]. Installed-ness is applied
    // AFTER HfResultsView.apply's own sort+filter chain - filtering only removes entries, so it never
    // disturbs the order apply() already computed (see HfResultsView.apply's kdoc).
    private fun applyFilters(
        state: HfBrowseUiState,
        results: List<HfModelSummary>,
    ): List<HfModelSummary> {
        val filtered =
            HfResultsView.apply(
                results,
                state.sort,
                state.tagFilter,
                HfResultFilters(
                    sizeEstimates = state.sizeEstimates,
                    sizeFilter = state.sizeFilter,
                    language = state.languageFilter,
                    rtfEstimates = rtfEstimates(results),
                    compatibility = state.compatibility,
                    supportedFilter = state.supportedFilter,
                    formats = state.fileFormats,
                    formatFilter = state.formatFilter,
                    minRealtimeMultiple = state.minRealtimeMultiple,
                    engineLabels = HfEngineClassifier.engineLabels(results, knownEngineIds),
                    engineFilter = state.engineFilter,
                ),
            )
        return HfResultsView.filterByInstalled(filtered, installedIdsOf(results), state.installedFilter)
    }

    /** The Format filter menu's choices - every [HfFileFormat] present across the file trees fetched
     * so far (issue #107), derived from data via [HfFileFormats.availableFormats], never a hardcoded
     * list. Same [state]-parameter rationale as [displayedResults]; same fail-closed-to-empty
     * rationale too (Browse crash on open) - an empty menu is a minor UX loss, a crash is not. */
    fun availableFormats(state: HfBrowseUiState): List<HfFileFormat> =
        runCatching { HfFileFormats.availableFormats(state.fileFormats) }.getOrDefault(emptyList())

    /** The Engine-type filter menu's choices - each registered engine a current result maps to, plus
     * [HfEngineClassifier.UNKNOWN] when any result matches none (issue #107). Derived from the live
     * registry ids + the current results, never a hardcoded model list. Fail-closed to empty, same
     * rationale as [availableFormats]. */
    fun availableEngines(state: HfBrowseUiState): List<String> =
        runCatching {
            HfEngineClassifier.availableEngines(HfEngineClassifier.engineLabels(state.results, knownEngineIds))
        }.getOrDefault(emptyList())

    /** [results]' ids that already have a downloaded/resolved bundle on this device - the input
     * [HfResultsView.filterByInstalled] needs (issue: installed filter). Read live off
     * [modelCatalog] (via [isInstalled]) on every call, same freshness guarantee as [isInstalled]
     * itself. */
    private fun installedIdsOf(results: List<HfModelSummary>): Set<String> =
        results.asSequence().map { it.id }.filter(::isInstalled).toSet()

    /**
     * The most recent measured real-time factor for each of [results] that is BOTH installed and has
     * at least one benchmark run recorded (issue: RTF sort/filter) - keyed by the HF repo id so it
     * slots into [HfResultsView] exactly like [HfBrowseUiState.sizeEstimates] does for size. A model
     * that was never downloaded, or was downloaded but never benchmarked, is simply absent - never a
     * guessed number - so [HfSortOption.FASTEST_RTF]/[SLOWEST_RTF] sort it last (the same
     * unknown-last rule size sorting already uses). Purely local reads (the installed catalog + the
     * on-device [BenchmarkHistory] the Benchmarks screen already writes to) - no network call, unlike
     * size estimates.
     */
    private fun rtfEstimates(results: List<HfModelSummary>): Map<String, Double> {
        val history = benchmarkHistory ?: return emptyMap()
        val descriptorsByBundleId = modelCatalog.list().associateBy { it.modelId }
        return results.mapNotNull { model ->
            val descriptor = descriptorsByBundleId[ModelStorage.sanitize(model.id)] ?: return@mapNotNull null
            val latest = history.history(descriptor.engineId, deviceName).lastOrNull() ?: return@mapNotNull null
            model.id to latest.realTimeFactor
        }.toMap()
    }

    /** The tag filter menu's choices - the most common, *useful* tags across the current results
     * (issue: the tag filter is slow with hundreds of raw tags). Boilerplate and language codes are
     * stripped and the list is capped - see [HfResultsView.frequentTags]. Derived from data, never a
     * hardcoded list (issue #6 SSOT rule); same [state]-parameter and fail-closed-to-empty rationale
     * as [displayedResults]/[availableFormats] (Browse crash on open). */
    fun availableTags(state: HfBrowseUiState): List<String> =
        runCatching { HfResultsView.frequentTags(state.results) }.getOrDefault(emptyList())

    /** The language filter menu's choices - every language present in the current results (issue:
     * add a language filter). Derived from the Hub's own language tags, never a hardcoded list - see
     * [HfLanguages.availableLanguages]. Same [state]-parameter and fail-closed rationale as
     * [displayedResults]. */
    fun availableLanguages(state: HfBrowseUiState): List<String> =
        runCatching { HfLanguages.availableLanguages(state.results) }.getOrDefault(emptyList())

    fun onLanguageFilterChange(code: String?) = mutableState.update { it.copy(languageFilter = code) }

    /** Changing sort order to a size/param-derived one (a "needs size" [HfSortOption] - see
     * [needsEagerSizeFetch]) eagerly fetches every currently-listed result's size - see
     * [ensureSizesLoadedIfNeeded] - so the new order isn't just the whole page dumped into "unknown,
     * sorts last" until the user happens to scroll each row into view (bug: sort/filter by
     * size+params needs sizes available up front, not one row at a time). */
    fun onSortChange(sort: HfSortOption) {
        mutableState.update { it.copy(sort = sort) }
        ensureSizesLoadedIfNeeded(mutableState.value.results)
    }

    fun onTagFilterChange(tag: String?) = mutableState.update { it.copy(tagFilter = tag) }

    /** Same eager-fetch rationale as [onSortChange]: setting any size/param bound needs every
     * result's size to know whether it passes the filter, not just the ones already scrolled past. */
    fun onSizeFilterChange(filter: HfSizeParamFilter) {
        mutableState.update { it.copy(sizeFilter = filter) }
        ensureSizesLoadedIfNeeded(mutableState.value.results)
    }

    /** Show all results, only installed ones, or only not-yet-installed ones (issue: installed
     * filter). No fetch to trigger here - install state is already local (see [installedIdsOf]). */
    fun onInstalledFilterChange(filter: HfInstalledFilter) = mutableState.update { it.copy(installedFilter = filter) }

    /** Supported filter (issue #107) - needs each result's file tree classified, so it triggers the
     * same eager fetch a size filter does (a filtered-out row is never composed, so its per-row
     * fetch would never run). */
    fun onSupportedFilterChange(filter: HfSupportedFilter) {
        mutableState.update { it.copy(supportedFilter = filter) }
        ensureSizesLoadedIfNeeded(mutableState.value.results)
    }

    /** Format filter (issue #107) - same eager-fetch rationale as [onSupportedFilterChange]. */
    fun onFormatFilterChange(format: HfFileFormat?) {
        mutableState.update { it.copy(formatFilter = format) }
        ensureSizesLoadedIfNeeded(mutableState.value.results)
    }

    /** RTF slider (issue #107) - a minimum estimated real-time multiple; null clears it. Needs each
     * result's size to estimate speed, so it eagerly fetches like the size filter. */
    fun onMinRealtimeMultipleChange(minMultiple: Double?) {
        mutableState.update { it.copy(minRealtimeMultiple = minMultiple) }
        ensureSizesLoadedIfNeeded(mutableState.value.results)
    }

    /** Engine-type filter (issue #107) - derived from the repo id/tags alone (no file tree needed),
     * so nothing to fetch. Null shows all engines. */
    fun onEngineFilterChange(engine: String?) = mutableState.update { it.copy(engineFilter = engine) }

    // loadSize() is already idempotent (a no-op once known or already loading - see its own kdoc),
    // so firing it for the whole current result set here is safe to call as often as sort/filter
    // changes fire; each row also still calls it individually as it scrolls into view (ModelRow's
    // LaunchedEffect), so nothing here is a new network *pattern* - just triggering it earlier.
    private fun ensureSizesLoaded(models: List<HfModelSummary>) = models.forEach { loadSize(it.id) }

    /**
     * Fires [ensureSizesLoaded] for [models] whenever [needsEagerSizeFetch] says the CURRENT
     * sort/filter needs sizes (issue: max-size filter regression). Previously the eager fetch only
     * ran from [onSortChange]/[onSizeFilterChange] - the two places the sort/filter itself changes -
     * but a size filter set BEFORE a fresh [search] or a later [loadMore] page never got the same
     * treatment: [HfResultsView.filterBySize] excludes an unknown-size result outright, and an
     * excluded row is never composed, so its own per-row [loadSize] (ModelRow's `LaunchedEffect`)
     * never even runs - those results would simply vanish forever instead of eventually resolving.
     * Calling this from every place new results land ([search], [loadMore]) as well as from a
     * sort/filter change closes that gap.
     */
    private fun ensureSizesLoadedIfNeeded(models: List<HfModelSummary>) {
        val current = mutableState.value
        val sizeNeeded = needsEagerSizeFetch(current.sort, current.sizeFilter)
        // The Supported/Format/RTF filters (issue #107) also exclude an unfetched row outright, so
        // they need the same up-front fetch - one file-tree fetch fills size, compatibility AND
        // formats (see loadSize). The Engine filter reads the summary only, so it never gets here.
        val advancedNeeded =
            HfBrowseFilters.needsFileData(
                HfResultFilters(
                    supportedFilter = current.supportedFilter,
                    formatFilter = current.formatFilter,
                    minRealtimeMultiple = current.minRealtimeMultiple,
                ),
            )
        if (sizeNeeded || advancedNeeded) ensureSizesLoaded(models)
    }

    /** Clears the current inline error banner (issue #2). The full session log in [HfBrowseError]
     * is untouched - dismissing only hides the one-line banner, it doesn't forget the error. */
    fun dismissError() = mutableState.update { it.copy(error = null) }

    /**
     * Download a curated model directly - no search, no webpage - using its known file list.
     *
     * Bug fix: a curated model's [BuiltInModel.downloadItems] carries no per-file size (it's a
     * static, hand-authored file list - unlike a browsed repo's file tree, nothing here ever called
     * the Hub for real sizes), so [HfDownloader]'s byte total was always unknown and the in-screen
     * progress bar rendered indeterminate for the whole download, then jumped straight to done -
     * unlike a browsed model, whose file-tree fetch ([download]) gives it real per-file sizes up
     * front. Fetching the SAME repo's file tree here (the exact endpoint browsed downloads already
     * use) before starting the download closes that gap: [sizedBuiltInItems] merges each file's real
     * tree size onto its download item, so [runDownload] gets an exact byte total and the bar fills
     * the same way a browsed model's does.
     */
    fun downloadBuiltIn(model: BuiltInModel) {
        if (mutableState.value.isDownloading(model.id)) return
        retryActions[model.id] = { downloadBuiltIn(model) }
        beginTracking(model.id)
        downloadJobs[model.id] =
            viewModelScope.launch {
                // [sizedBuiltInItems] moved inside this runCatching (not just the network call) so
                // ANY failure merging real tree sizes onto the curated file list - not only a network
                // one - takes the same best-effort fallback below, consistent with this function's
                // own "a failed size lookup must not block a known-good curated download" contract.
                runCatching {
                    val files = withContext(Dispatchers.IO) { catalog.listFiles(model.repoId, model.revision) }
                    sizedBuiltInItems(model, files)
                }
                    .onSuccess { items -> runDownload(model.id, items, model.displayName) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        // A failed size lookup is best-effort, not fatal - a known-good curated
                        // download must not be blocked by it. Falls back to the old size-less items
                        // (indeterminate bar, exactly this bug's pre-fix behavior) rather than losing
                        // the download entirely.
                        runDownload(model.id, model.downloadItems(), model.displayName)
                    }
            }
    }

    // Zips model.downloadItems() (the URLs/relative paths - unchanged) against model.files (the
    // repo paths that name each file's real tree size) - both lists are the exact same size/order
    // since downloadItems() maps 1:1 over files, so no repo-path matching by string is needed beyond
    // this simple positional pairing.
    private fun sizedBuiltInItems(
        model: BuiltInModel,
        files: List<HfTreeEntry>,
    ): List<HfDownloadItem> {
        val sizesByRepoPath = files.filter { it.isFile }.associate { it.path to it.size }
        return model.downloadItems().zip(model.files) { item, file ->
            item.copy(sizeBytes = sizesByRepoPath[file.repoPath])
        }
    }

    /** Toggles the collapsible "Piper voices" section (issue #71) - collapsed by default so
     * 166+ voices aren't dumped onto the screen unasked; nothing in the section is even
     * filtered/laid out until this flips true. Expanding it for the first time kicks off the
     * runtime fetch of upstream's voice list ([loadPiperVoices]); re-collapsing/re-expanding never
     * refetches (issue: don't check in a static Piper voice snapshot - see [PiperVoicesIndex]). */
    fun onPiperVoicesExpandedChange(expanded: Boolean) {
        mutableState.update { it.copy(piperVoicesExpanded = expanded) }
        if (expanded) loadPiperVoices()
    }

    fun onPiperVoiceQueryChange(query: String) = mutableState.update { it.copy(piperVoiceQuery = query) }

    /**
     * Fetches upstream rhasspy/piper-voices' `voices.json` and parses it via [PiperVoicesIndex] -
     * the SAME manifest upstream itself publishes, so the browsable list is always current instead
     * of a checked-in snapshot of it. A no-op if a fetch already succeeded this session
     * ([HfBrowseUiState.piperVoices] non-empty - the in-memory cache) or is already in flight.
     * Fails closed: a network hiccup sets [HfBrowseUiState.piperVoicesError] rather than showing a
     * stale or guessed list, and never crashes the screen (CLAUDE.md rule 4's spirit applied here
     * to data, not just model detection).
     */
    fun loadPiperVoices() {
        val current = mutableState.value
        if (current.piperVoices.isNotEmpty() || current.piperVoicesLoading) return
        mutableState.update { it.copy(piperVoicesLoading = true, piperVoicesError = null) }
        viewModelScope.launch {
            // Same "protect the whole success path, not just the network call" fix as
            // search()/loadSize()/download() above, applied here for consistency even though the
            // dedup below is already provably safe.
            runCatching {
                val voices = withContext(Dispatchers.IO) { fetchPiperVoices() }
                // Same duplicate-key guard as the search results: the Piper list is rendered with
                // key = "piper:${it.id}", so two upstream voices that derive to the same id would
                // crash the LazyColumn on expand. Dedupe by id before it reaches state.
                voices.distinctBy { it.id }
            }
                .onSuccess { unique -> mutableState.update { it.copy(piperVoicesLoading = false, piperVoices = unique) } }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    mutableState.update {
                        it.copy(
                            piperVoicesLoading = false,
                            piperVoicesError = "Couldn't load the Piper voice list - check your connection.",
                        )
                    }
                }
        }
    }

    private fun fetchPiperVoices(): List<BuiltInModel> {
        val url = HfEndpoints.resolveUrl(PiperVoicesIndex.REPO_ID, HfCatalog.DEFAULT_REVISION, "voices.json")
        val body = piperHttp.getText(url, HfCatalog.USER_AGENT)
        return PiperVoicesIndex.parse(body)
    }

    /**
     * [voices] narrowed to those whose display name matches [query] (issue #71) - a pure,
     * in-memory filter over the already-fetched list, never a network call of its own. The display
     * name already encodes the language (e.g. "Piper - Kareem (Arabic, Jordan, low)"), so one
     * substring match covers both a voice-name search and a language search without a second
     * field. A blank query matches every voice.
     */
    fun filterPiperVoices(
        voices: List<BuiltInModel>,
        query: String,
    ): List<BuiltInModel> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return voices
        return voices.filter { it.displayName.contains(trimmed, ignoreCase = true) }
    }

    fun onQueryChange(query: String) = mutableState.update { it.copy(query = query) }

    // A fresh search always starts a new first page (skip = 0, the default) - canLoadMore is set from
    // whether that page came back full (== PAGE_SIZE): a short/empty page means the Hub has nothing
    // more for this query, so "Load more" never even appears rather than firing a request known to
    // return nothing new.
    fun search() {
        // Structural backstop against re-hammering a throttled bucket (issue #103): while a 429
        // cooldown is active, Search is a no-op - the scheduled auto-retry (onRateLimited) is what
        // re-runs it once the cooldown lifts, and the UI disables the button meanwhile.
        if (mutableState.value.isRateLimited(clock())) return
        val query = mutableState.value.query
        mutableState.update { it.copy(loading = true, error = null, loadingMore = false) }
        viewModelScope.launch {
            // Bug fix (Browse crash on open): the success-path processing below used to run in a
            // separate `.onSuccess { }` NOT covered by this `runCatching` - only the network call
            // itself was protected. That meant any exception while turning a real HF response into
            // state (dedup, `ensureSizesLoadedIfNeeded` kicking off further fetches, ...) would
            // propagate uncaught out of this coroutine and crash the whole app, right at the exact
            // moment real results first replace the empty initial list. Moving it inside the
            // protected block routes ANY such failure through the same existing, well-tested
            // [onSearchFailure] error path instead (spec rule 4's fail-closed spirit, applied here).
            runCatching {
                val results = withContext(Dispatchers.IO) { catalog.search(query, limit = PAGE_SIZE) }
                // Dedupe by id BEFORE these become LazyColumn items: the list is rendered with
                // key = "hf:${it.id}", and the Hub can return the same repo id twice in one page
                // (its own ranking), which makes Compose throw a duplicate-key IllegalStateException
                // and crash the whole Browse screen. loadMore() already dedupes across page
                // boundaries for the same reason; the initial page needs the same guard. canLoadMore
                // still keys off the RAW page size so paging isn't cut short by within-page dupes.
                val unique = results.distinctBy { it.id }
                // A success clears any prior cooldown counter so the next 429 starts its backoff
                // from scratch rather than from an inflated consecutive count.
                mutableState.update {
                    it.copy(
                        loading = false,
                        results = unique,
                        canLoadMore = results.size >= PAGE_SIZE,
                        rateLimitConsecutive = 0,
                        rateLimitedUntilMs = 0,
                    )
                }
                ensureSizesLoadedIfNeeded(unique)
            }.onFailure { e -> onSearchFailure(e) }
        }
    }

    // A 429 is an expected, self-resolving cooldown, NOT a real error - route it to onRateLimited
    // (which shows a countdown and schedules one auto-retry) instead of the copyable error log, so a
    // throttle can't flood the log with identical lines (issue #103). Everything else logs as before.
    private fun onSearchFailure(e: Throwable) {
        if (e is CancellationException) throw e
        val rateLimit = e as? HfRateLimitedException
        if (rateLimit != null) {
            onRateLimited(rateLimit) { search() }
            return
        }
        // logError does its own atomic state update, so it must NOT be called from inside an update{}
        // block - MutableStateFlow.update retries its transform on a compare-and-set conflict, and a
        // nested update() would re-run (and re-log) on every retry.
        val message = logError(null, e)
        mutableState.update { it.copy(loading = false, error = message, canLoadMore = false) }
    }

    /**
     * Handles a Hugging Face 429 (issue #103): sets a cooldown [HfBrowseUiState.rateLimitedUntilMs]
     * from HF's own reset hint (or exponential backoff via [HfRateLimitBackoff]) so the UI shows a
     * countdown and disables Search/"Load more", then schedules ONE auto-[retry] once it lifts - up
     * to [HfRateLimitBackoff.MAX_AUTO_RETRIES] consecutive times, after which the user retries
     * manually. Never touches the error log (that is for real, user-actionable failures).
     */
    private fun onRateLimited(
        e: HfRateLimitedException,
        retry: () -> Unit,
    ) {
        val consecutive = mutableState.value.rateLimitConsecutive + 1
        val delayMs = HfRateLimitBackoff.nextDelayMs(consecutive, e.retryAfterMs)
        mutableState.update {
            it.copy(
                loading = false,
                loadingMore = false,
                rateLimitedUntilMs = clock() + delayMs,
                rateLimitConsecutive = consecutive,
            )
        }
        if (consecutive > HfRateLimitBackoff.MAX_AUTO_RETRIES) return
        viewModelScope.launch {
            delay(delayMs)
            // Clear the gate so the retry's own isRateLimited() guard passes, then re-run it.
            mutableState.update { it.copy(rateLimitedUntilMs = 0) }
            retry()
        }
    }

    /**
     * Fetches the next page(s) of the current query (issue: Browse pagination) and appends them to
     * [HfBrowseUiState.results] - the Hub's `/api/models` list endpoint pages with `limit`+`skip`
     * (verified against the live API; see [com.phonetts.core.download.hf.HfEndpoints.searchModelsUrl]'s
     * kdoc), so each page's `skip` is simply how many results are already loaded. A no-op while a
     * page is already loading, a fresh search is loading, or the previous page came back short
     * (nothing more to fetch) - [HfBrowseUiState.canLoadMore] gates all three.
     *
     * Bug: with a restrictive filter active, fetching just ONE page here used to leave "Load more"
     * looking broken - most (or all) of a 20-result page could get filtered away, so tapping the
     * button visibly added ~nothing. [fetchUntilEnoughVisible] instead keeps fetching subsequent
     * pages, off the main thread, until either enough NEW post-filter-visible results have appeared,
     * the Hub runs out of results, or a hard page cap is hit - see [HfLoadMorePolicy].
     */
    fun loadMore() {
        val current = mutableState.value
        if (current.loadingMore || current.loading || !current.canLoadMore) return
        // Same 429 backstop as search() (issue #103) - the same /api/models bucket throttles both.
        if (current.isRateLimited(clock())) return
        mutableState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            // Bug fix (Browse crash on open/scroll): unlike every sibling network-triggering method
            // in this class (search/download/downloadBuiltIn/loadSize all wrap their work in
            // runCatching), this body had NO error handling at all - any exception here, including
            // one from [fetchUntilEnoughVisible]'s calls into the SAME sort/filter pipeline
            // [displayedResults] uses for rendering (via `applyFilters`), propagated uncaught and
            // crashed the app. Routes any such failure into the existing copyable error log instead,
            // exactly like [onSearchFailure] does for [search].
            runCatching { withContext(Dispatchers.IO) { fetchUntilEnoughVisible(current) } }
                .onSuccess { outcome -> applyLoadMoreOutcome(outcome) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    val message = logError(null, e)
                    mutableState.update { it.copy(loadingMore = false, error = message) }
                }
        }
    }

    // Merges the (possibly partial) pages back into state - ALWAYS keeping what was merged - then
    // routes a mid-loop failure (issue #103): a 429 goes to the cooldown/auto-retry path, any other
    // error to the log, and a clean run resets the cooldown counter. Never discards fetched pages.
    private fun applyLoadMoreOutcome(outcome: LoadMoreOutcome) {
        mutableState.update {
            it.copy(loadingMore = false, results = outcome.results, canLoadMore = outcome.canLoadMore)
        }
        ensureSizesLoadedIfNeeded(outcome.results)
        val rateLimit = outcome.rateLimit
        if (rateLimit != null) {
            onRateLimited(rateLimit) { loadMore() }
            return
        }
        val error = outcome.error
        if (error != null) {
            val message = logError(null, error)
            mutableState.update { it.copy(error = message) }
            return
        }
        mutableState.update { it.copy(rateLimitConsecutive = 0, rateLimitedUntilMs = 0) }
    }

    /**
     * The network/looping half of [loadMore]: repeatedly fetches the next page of [snapshot]'s query
     * and merges it in, deciding whether to keep going via the pure [HfLoadMorePolicy] (unit-tested
     * in `:core` without a fake HTTP client). Visibility is recomputed against [snapshot] - the state
     * captured at the moment the user tapped "Load more" - so a sort/filter change arriving mid-fetch
     * can't produce an inconsistent count; the next [displayedResults] call picks up any such change
     * against the final merged list regardless.
     */
    private suspend fun fetchUntilEnoughVisible(snapshot: HfBrowseUiState): LoadMoreOutcome {
        var results = snapshot.results
        val visibleBefore = applyFilters(snapshot, results).size
        var lastPageSize = Int.MAX_VALUE
        var pagesFetched = 0
        while (
            HfLoadMorePolicy.shouldFetchAnotherPage(
                pagesFetchedSoFar = pagesFetched,
                lastPageSize = lastPageSize,
                pageSize = PAGE_SIZE,
                newlyVisibleCount = applyFilters(snapshot, results).size - visibleBefore,
                targetNewVisible = LOAD_MORE_TARGET_NEW_VISIBLE,
                maxPages = MAX_LOAD_MORE_PAGES,
            )
        ) {
            // Bug (issue #103): a throw here used to discard every page already merged this loop.
            // Catch it, keep the merged results, and hand the reason back so the caller can start a
            // cooldown (429) or log it (other) - the merged prefix is never lost.
            val page = fetchPageOrFail(snapshot.query, results.size).getOrElse { e ->
                if (e is CancellationException) throw e
                return partialOutcome(results, lastPageSize, e)
            }
            // distinctBy guards against a result the Hub's own ranking shuffled across the page
            // boundary mid-session (a download-count change reordering the list) from showing up
            // twice, rather than trusting pages never overlap.
            results = (results + page).distinctBy { it.id }
            lastPageSize = page.size
            pagesFetched++
        }
        return LoadMoreOutcome(results = results, canLoadMore = lastPageSize >= PAGE_SIZE)
    }

    // runCatching (not a try/catch in the loop) keeps fetchUntilEnoughVisible's while-body flat
    // (never-nesting). A blocking call, already off the main thread via loadMore's withContext(IO).
    private fun fetchPageOrFail(
        query: String,
        skip: Int,
    ): Result<List<HfModelSummary>> = runCatching { catalog.search(query, limit = PAGE_SIZE, skip = skip) }

    // Keeps whatever pages merged so far. A 429 mid-loop still leaves more to fetch once the cooldown
    // lifts (canLoadMore stays true and the exception rides along to start the cooldown); any other
    // error rides along to the log without pretending there's nothing more.
    private fun partialOutcome(
        results: List<HfModelSummary>,
        lastPageSize: Int,
        e: Throwable,
    ): LoadMoreOutcome {
        val rateLimit = e as? HfRateLimitedException
        return LoadMoreOutcome(
            results = results,
            canLoadMore = rateLimit != null || lastPageSize >= PAGE_SIZE,
            rateLimit = rateLimit,
            error = if (rateLimit == null) e else null,
        )
    }

    fun download(model: HfModelSummary) {
        if (mutableState.value.isDownloading(model.id)) return // already fetching this repo
        retryActions[model.id] = { download(model) }
        beginTracking(model.id)
        downloadJobs[model.id] =
            viewModelScope.launch {
                // Bug fix: [onFilesListed] (quantization classification + starting the plan) used to
                // run in a separate `.onSuccess { }` NOT covered by this runCatching - only the
                // network call itself was protected, so any exception while classifying a real repo's
                // file tree would crash the app instead of failing this one download. Moved inside so
                // it routes through the same [failDownload] path as a network failure would.
                runCatching {
                    val files = withContext(Dispatchers.IO) { catalog.listFiles(model.id) }
                    onFilesListed(model.id, files)
                }.onFailure { e -> failDownload(model.id, model.id, e) }
            }
    }

    /**
     * Fetch just the repo's total download size, without downloading it (issue #7) - and, from the
     * same file tree, whether this app has anything that can load it yet (the "Not yet supported"
     * grey badge, [HfCompatibility]). Cached in state once known so re-opening the screen or
     * scrolling doesn't refetch. A model whose size can't be determined (network hiccup) simply
     * stays unknown - never a guessed number, and never labeled unsupported on a failed fetch.
     *
     * Called from a `LaunchedEffect` on EVERY visible row (issue: unbounded per-row size fetches on
     * Browse) - the actual network fetch is gated behind [sizeLookupSemaphore] so fast-scrolling a
     * long result list queues extra rows instead of firing them all at once and flooding the HF
     * file-tree endpoint into a rate limit.
     */
    fun loadSize(modelId: String) {
        val current = mutableState.value
        if (modelId in current.sizeEstimates || modelId in current.sizeLoading) return
        mutableState.update { it.copy(sizeLoading = it.sizeLoading + modelId) }
        viewModelScope.launch {
            // Bug fix (Browse crash on open): the classification below (estimate/compatibility/
            // formats, all derived from a REAL repo's file tree this app doesn't control the shape
            // of) used to run in a separate `.onSuccess { }` NOT covered by this runCatching - only
            // the network call itself was protected. This fires automatically, unconditionally, for
            // every visible row the instant Browse shows real results (ModelRow's `LaunchedEffect`),
            // so any exception here used to crash the whole app right when the screen first has
            // something to show. Moved inside so any such failure routes through the SAME onFailure
            // handling a network hiccup already gets, rather than escaping uncaught.
            runCatching {
                val files =
                    sizeLookupSemaphore.withPermit {
                        withContext(Dispatchers.IO) { catalog.listFiles(modelId) }
                    }
                val estimate = HfSizeEstimator.estimate(files)
                // One fetch fills three facts the UI/filters need: the download size, how
                // runnable the repo is (badge #108 + Supported filter #107), and which formats it
                // ships (Format filter #107). [modelId] is the HF repo id, so it sharpens the
                // Apple-only detection HfCompatibility/HfFileFormats do from the namespace.
                val runnability = HfCompatibility.classify(files, modelId)
                val formats = HfFileFormats.formatsOf(files, modelId)
                mutableState.update {
                    it.copy(
                        sizeLoading = it.sizeLoading - modelId,
                        sizeEstimates = it.sizeEstimates + (modelId to estimate),
                        compatibility = it.compatibility + (modelId to runnability),
                        fileFormats = it.fileFormats + (modelId to formats),
                    )
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                mutableState.update { it.copy(sizeLoading = it.sizeLoading - modelId) }
                // A 429 here is an EXPECTED, self-resolving cooldown, exactly as it is on the
                // search path - not a real error. Routing it to logError put a raw red
                // "HTTP 429 for https://huggingface.co/api/models/.../tree/main" banner over the
                // screen, which is one of the ways Browse reads as "broken": scrolling a long
                // list fires one of these per visible row, so a throttled connection paints the
                // error banner while every row's size stays permanently unavailable.
                // Defer to the same cooldown the search path already shows and let the row retry
                // afterwards instead of failing forever.
                if (e is HfRateLimitedException) {
                    // Retry this row's lookup once the cooldown elapses - the size/format/
                    // compatibility facts the Format, Supported and RTF filters depend on all
                    // come from this one fetch, so abandoning it silently degrades those filters.
                    onRateLimited(e) { loadSize(modelId) }
                    return@onFailure
                }
                logError(modelId, e)
            }
        }
    }

    /**
     * Cancel one repo's in-flight download. The coroutine is cancelled cooperatively (the fetch
     * loop checks for it each buffer) and any partially-downloaded file is left on disk, so
     * re-tapping Download resumes from where it stopped rather than starting the whole thing over.
     * Other repos' downloads are untouched (issue #2).
     */
    fun cancelDownload(modelId: String) {
        downloadJobs.remove(modelId)?.cancel()
        retryActions.remove(modelId)
        mutableState.update { it.copy(failedDownloadIds = it.failedDownloadIds - modelId) }
        endTracking(modelId)
        // Drop any in-flight progress/failure notification too - a cancelled download shouldn't
        // leave a stale "downloading"/"failed" row sitting in the shade for something the user
        // chose to abandon (issue: download progress notification).
        notifier?.cancel(modelId)
        if (mutableState.value.variantChoice?.modelId == modelId) cancelVariantChoice()
    }

    // If the repo ships one precision (or none classifiable), download it straight away; if it
    // genuinely ships more than one KNOWN precision, surface a picker so the user fetches only the
    // one they want (issue #9: an ambiguously-named auxiliary weight file must not force a prompt).
    private fun onFilesListed(
        modelId: String,
        files: List<HfTreeEntry>,
    ) {
        if (!QuantizationFilter.requiresChoice(files)) {
            runDownload(modelId, HfDownloadPlan.forFiles(modelId, files))
            return
        }
        endTracking(modelId)
        val variants = QuantizationFilter.knownVariants(files).toList()
        mutableState.update { it.copy(variantChoice = VariantChoice(modelId, files, variants)) }
    }

    /** Download only the chosen precision's weights (plus the shared config/tokenizer files). */
    fun chooseVariant(variant: QuantizationVariant) {
        val choice = mutableState.value.variantChoice ?: return
        mutableState.update { it.copy(variantChoice = null) }
        runDownload(choice.modelId, HfQuantizedDownloadPlan.forVariant(choice.modelId, choice.files, variant))
    }

    fun cancelVariantChoice() = mutableState.update { it.copy(variantChoice = null) }

    // [displayName] defaults to [modelId] - right for a browsed HF repo, whose id (e.g.
    // "hexgrad/Kokoro-82M") is already the label shown in ModelRow; downloadBuiltIn passes the
    // curated model's friendlier BuiltInModel.displayName instead. Only used for the notification
    // (issue: download progress notification) - the in-screen row already has its own label.
    private fun runDownload(
        modelId: String,
        items: List<HfDownloadItem>,
        displayName: String = modelId,
    ) {
        val sizeEstimate = HfSizeEstimator.estimateItems(items)
        val exactBytesTotal = sizeEstimate.knownBytes.takeIf { sizeEstimate.isExact }
        beginTracking(modelId, filesTotal = items.size, bytesTotal = exactBytesTotal)
        // Show it as "Queued" until a transfer slot frees (issue #101). The coroutine is launched now
        // (so it stays cancellable and survives a config change), but blocks on the semaphore before
        // any bytes move - so 25 taps queue instead of firing 25 concurrent transfers.
        markQueued(modelId)
        downloadJobs[modelId] =
            viewModelScope.launch {
                downloadSemaphore.withPermit {
                    markRunning(modelId)
                    runCatching {
                        downloader.downloadAndImport(
                            modelId = modelId,
                            items = items,
                            onProgress = { done, total ->
                                updateProgress(modelId) { it.copy(filesDone = done, filesTotal = total) }
                            },
                            onBytesProgress = { bytesDone, bytesTotal ->
                                updateProgress(modelId) {
                                    it.copy(bytesDone = bytesDone, bytesTotal = bytesTotal ?: it.bytesTotal)
                                }
                                notifier?.updateProgress(modelId, displayName, bytesDone, bytesTotal)
                            },
                        )
                    }.onSuccess { completeDownload(modelId, displayName) }
                        .onFailure { e -> failDownload(modelId, displayName, e) }
                }
            }
    }

    // A tracked-but-waiting download (issue #101): visible in the list with a "Queued" state instead
    // of the overflow silently vanishing. Flipped to running the moment its semaphore permit lands.
    private fun markQueued(modelId: String) =
        mutableState.update { it.copy(queuedDownloadIds = it.queuedDownloadIds + modelId) }

    private fun markRunning(modelId: String) =
        mutableState.update { it.copy(queuedDownloadIds = it.queuedDownloadIds - modelId) }

    private fun beginTracking(
        modelId: String,
        filesTotal: Int = 0,
        bytesTotal: Long? = null,
    ) {
        val progress = HfDownloadProgress(filesTotal = filesTotal, bytesTotal = bytesTotal, startedAtMs = clock())
        // Starting (or restarting) a download clears any prior "failed" mark for it - so both a
        // manual re-tap of Download and a "Retry failed" run drop it out of the failed set.
        mutableState.update {
            it.copy(
                downloads = it.downloads + (modelId to progress),
                error = null,
                failedDownloadIds = it.failedDownloadIds - modelId,
            )
        }
    }

    private fun endTracking(modelId: String) =
        mutableState.update {
            it.copy(downloads = it.downloads - modelId, queuedDownloadIds = it.queuedDownloadIds - modelId)
        }

    private fun updateProgress(
        modelId: String,
        transform: (HfDownloadProgress) -> HfDownloadProgress,
    ) {
        mutableState.update { current ->
            val progress = current.downloads[modelId] ?: return@update current
            current.copy(downloads = current.downloads + (modelId to transform(progress)))
        }
    }

    // ModelCatalog already has [descriptor] cataloged by the time this runs (ModelImporter.import
    // added it before returning), so isInstalled()'s live modelCatalog.isKnown() check already
    // reflects it - nothing to update here beyond ending the progress tracking (and the notification
    // - issue: download progress notification).
    private fun completeDownload(
        modelId: String,
        displayName: String,
    ) {
        downloadJobs.remove(modelId)
        retryActions.remove(modelId)
        mutableState.update {
            it.copy(
                downloads = it.downloads - modelId,
                queuedDownloadIds = it.queuedDownloadIds - modelId,
                failedDownloadIds = it.failedDownloadIds - modelId,
            )
        }
        notifier?.complete(modelId, displayName)
    }

    // Bug #6: a resolve failure during a Browse download is NOT a network/IO failure - the bytes
    // landed fine and ModelImporter already recorded the bundle as an UnresolvedModel (issue #8,
    // ModelCatalog.markUnresolved) before rethrowing this. Treat it as "installed, no engine yet":
    // no error banner/errorLog entry (that's for real failures), just a diagnostics-log row so the
    // user can see which engine is worth adding next. isInstalled() already reflects it live via
    // ModelCatalog.isKnown(). The notification reads "downloaded" too - from the user's perspective
    // the bytes landed fine, which is what the notification is telling them about.
    private fun completeUnresolvedDownload(
        modelId: String,
        displayName: String,
        e: UserPickRequiredException,
    ) {
        downloadJobs.remove(modelId)
        retryActions.remove(modelId)
        recordDiagnostics(
            DiagnosticsEntry(
                atMs = clock(),
                modelId = modelId,
                kind = DiagnosticsKind.NO_ENGINE_YET,
                detail = e.explanation,
            ),
        )
        mutableState.update {
            it.copy(
                downloads = it.downloads - modelId,
                queuedDownloadIds = it.queuedDownloadIds - modelId,
                failedDownloadIds = it.failedDownloadIds - modelId,
            )
        }
        notifier?.complete(modelId, displayName)
    }

    // Cancellation is a user action, not a failure - let it propagate; cancelDownload already reset
    // the UI (and dropped the notification, see cancelDownload). Only real errors surface an error
    // line AND join the retained/copyable log (issue #3) AND the persistent diagnostics log (bug:
    // user-tracked download log) AND the failure notification. A resolve failure (bug #6,
    // "downloaded, no engine yet") is a distinct, non-error outcome - see [completeUnresolvedDownload].
    private fun failDownload(
        modelId: String,
        displayName: String,
        e: Throwable,
    ) {
        if (e is CancellationException) throw e
        if (e is UserPickRequiredException) {
            completeUnresolvedDownload(modelId, displayName, e)
            return
        }
        downloadJobs.remove(modelId)
        val message = logError(modelId, e)
        recordDiagnostics(
            DiagnosticsEntry(atMs = clock(), modelId = modelId, kind = DiagnosticsKind.FAILURE, detail = message),
        )
        // Mark it failed (its retry action stays registered) so the "Retry failed (N)" control can
        // re-run it; the partial file is left on disk, so the retry resumes rather than restarts.
        mutableState.update {
            it.copy(
                downloads = it.downloads - modelId,
                queuedDownloadIds = it.queuedDownloadIds - modelId,
                error = message,
                failedDownloadIds = it.failedDownloadIds + modelId,
            )
        }
        notifier?.failed(modelId, displayName, message)
    }

    /** Resume every download whose last attempt failed (issue: a network drop failed a batch).
     * Called "Resume", not "Retry" (issue #105): each continues from its partial file on disk via
     * HTTP Range, it does not restart. [beginTracking] clears the failed mark as each one restarts.
     * A no-op when nothing has failed. */
    fun resumeFailedDownloads() {
        mutableState.value.failedDownloadIds.toList().forEach { id -> retryActions[id]?.invoke() }
    }

    /**
     * Auto-resume interrupted downloads when connectivity returns (issue #105) - the internet drops
     * for minutes at a time while moving between stores, and the user should not have to tap Resume
     * on each failed download every time it recovers. Reuses the exact same partial-file HTTP Range
     * resume [resumeFailedDownloads] does. Idempotent and safe to call on every "network available"
     * callback: a no-op when nothing has failed, and [beginTracking]/`isDownloading` prevent
     * double-starting one already back in flight.
     *
     * WIRED: VoicesHubScreen's BrowseTabContent registers a `ConnectivityManager.NetworkCallback`
     * in a DisposableEffect whose `onAvailable` calls this. (This kdoc previously said nothing
     * registered one - it was stale, and a stale "not wired yet" note is worse than none, because
     * it invites someone to wire it a second time.)
     */
    fun onConnectivityAvailable() = resumeFailedDownloads()

    /** Appends [e] to the retained error log (issue #3), bounded to [MAX_HF_ERROR_LOG] entries, and
     * returns a display message for the caller's own inline banner. Every search/size/download
     * failure funnels through here (see the call sites above), so mirroring into [durableErrorLog]
     * here (issue #88) covers all three without a second logging path. */
    private fun logError(
        modelId: String?,
        e: Throwable,
    ): String {
        val raw = e.message ?: e::class.simpleName ?: "Unknown error"
        // A bare "Unable to resolve host …" (a no-network DNS failure on a search/size fetch) reads
        // as a plain connectivity line; a genuine, specific error is left untouched (OfflineErrorHint).
        val message = OfflineErrorHint.humanize(raw)
        mutableState.update { current ->
            current.copy(errorLog = appendError(current.errorLog, HfBrowseError(clock(), modelId, message)))
        }
        // Durable mirror (issue #88): a null durableErrorLog (no real store wired - see the
        // constructor param's kdoc) makes this a no-op, same fail-closed shape as [notifier] elsewhere
        // in this class. DurableErrorLog bounds/persists itself; nothing extra to do here.
        durableErrorLog?.record(atMs = clock(), source = DURABLE_ERROR_SOURCE, message = durableMessage(modelId, message))
        return message
    }

    // DurableErrorLog's entries carry a plain message string, no separate model-id field (unlike this
    // screen's own HfBrowseError) - fold the model id in as a prefix so the Help screen's log (which
    // renders "[source] message") still tells the two apart.
    private fun durableMessage(
        modelId: String?,
        message: String,
    ): String = if (modelId != null) "$modelId: $message" else message

    // Collapses a repeat-identical newest entry into a single "(xN)" row rather than prepending a
    // duplicate (issue #103) - a transient loop (or a 429 that slipped past the cooldown) can't flood
    // the bounded 25-slot log with the same line 25 times. A genuinely different error still prepends.
    private fun appendError(
        log: List<HfBrowseError>,
        entry: HfBrowseError,
    ): List<HfBrowseError> {
        val head = log.firstOrNull()
        if (head != null && head.modelId == entry.modelId && head.message == entry.message) {
            return listOf(head.copy(atMs = entry.atMs, count = head.count + 1)) + log.drop(1)
        }
        return (listOf(entry) + log).take(MAX_HF_ERROR_LOG)
    }

    /** Clear the persistent diagnostics log (mirrors [DownloadDiagnosticsLog.clear] into state). */
    fun clearDiagnostics() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { downloader.diagnosticsLog.clear() }
            mutableState.update { it.copy(diagnostics = emptyList()) }
        }
    }

    // Reads the persistent log off the main thread and mirrors it into [state] so the Browse
    // screen's dialog is driven by the same collectAsState() flow as everything else, rather than
    // reaching into HfDownloader/DownloadDiagnosticsLog directly from Compose.
    private fun loadDiagnostics() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { downloader.diagnosticsLog.entries() }
            mutableState.update { it.copy(diagnostics = entries) }
        }
    }

    // Persists [entry] then re-reads the log so [state] stays exactly what's on disk (rather than
    // hand-maintaining a parallel in-memory copy that could drift from it).
    private fun recordDiagnostics(entry: DiagnosticsEntry) {
        viewModelScope.launch {
            val entries =
                withContext(Dispatchers.IO) {
                    downloader.diagnosticsLog.record(entry)
                    downloader.diagnosticsLog.entries()
                }
            mutableState.update { it.copy(diagnostics = entries) }
        }
    }

    companion object {
        // One page's worth of Hub results (issue: Browse pagination) - deliberately the same value
        // HfCatalog.DEFAULT_LIMIT already used for the single page fetched before pagination existed,
        // so a first search's behavior/URL is unchanged; only loadMore()'s later pages are new.
        private const val PAGE_SIZE = HfCatalog.DEFAULT_LIMIT

        // loadMore() keeps fetching pages (see fetchUntilEnoughVisible/HfLoadMorePolicy) until AT
        // LEAST this many new post-filter results are visible - one page's worth, so a tap on "Load
        // more" reliably adds a meaningful amount whether or not a filter is active, rather than
        // sometimes 20 and sometimes ~0 (issue: load-more with filters shows almost nothing).
        private const val LOAD_MORE_TARGET_NEW_VISIBLE = PAGE_SIZE

        // Hard cap on pages fetched by one loadMore() call - bounds network use so a filter matching
        // almost nothing (or a query with few genuine results left) can't spin fetching pages forever.
        private const val MAX_LOAD_MORE_PAGES = 5

        // How many downloads may TRANSFER at once (issue #101). Small on purpose: a budget phone on a
        // mobile connection saturates past ~2-3 large parallel transfers, which surfaced as a batch of
        // socket failures misread as "No internet". Everything past this queues and starts as slots free.
        private const val MAX_CONCURRENT_DOWNLOADS = 3

        // How many per-row loadSize() file-tree fetches may run at once (issue: unbounded per-row
        // size fetches on Browse). A size lookup is a small GET, not a multi-file transfer, so this
        // can be a little more generous than [MAX_CONCURRENT_DOWNLOADS] while still keeping a fast
        // scroll from firing dozens of requests at the HF file-tree endpoint simultaneously.
        private const val MAX_CONCURRENT_SIZE_LOOKUPS = 3

        // Issue #88: the [DurableErrorLog.record] "source" tag for every error this screen mirrors
        // into the durable log, so the Help screen's shared log (which shows "[source] message") can
        // tell a Browse-originated row apart from a crash row or any other subsystem's.
        private const val DURABLE_ERROR_SOURCE = "browse"
    }
}

/**
 * The result of one [HfBrowseViewModel.loadMore] fetch loop (issue #103): the (possibly partial)
 * merged results and whether more remain, plus WHY the loop stopped early when it did - a 429
 * [rateLimit] that should start a cooldown, or any other [error] that should be logged. Carried out
 * of the loop as data instead of a thrown exception so the pages already merged are never discarded.
 */
private data class LoadMoreOutcome(
    val results: List<HfModelSummary>,
    val canLoadMore: Boolean,
    val rateLimit: HfRateLimitedException? = null,
    val error: Throwable? = null,
)
