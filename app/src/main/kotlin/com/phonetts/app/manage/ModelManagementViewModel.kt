package com.phonetts.app.manage

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.StorageLocation
import com.phonetts.core.diagnostics.FailureContext
import com.phonetts.core.diagnostics.FailureExplainer
import com.phonetts.core.metrics.BenchmarkHistory
import com.phonetts.core.model.InstalledModelFacts
import com.phonetts.core.model.ManageModelFacts
import com.phonetts.core.model.Origin
import com.phonetts.core.prefs.ResourceUsageStore
import com.phonetts.core.registry.ManagedEngineFilter
import com.phonetts.core.registry.ManagedModelEntry
import com.phonetts.core.registry.ManagedModelFilter
import com.phonetts.core.registry.ManagedModelListView
import com.phonetts.core.registry.ManagedSort
import com.phonetts.core.registry.ManagedSortKey
import com.phonetts.core.registry.ModelManager
import com.phonetts.core.registry.ModelUsage
import com.phonetts.core.registry.UnresolvedModelUsage
import com.phonetts.core.resolver.SelectableEngine
import com.phonetts.core.session.SessionStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the "manage models" screen: lists every cataloged model with its on-disk size and lets
 * the user delete one. All the actual removal logic (catalog, files, saved override, live unload)
 * is [ModelManager]'s job (spec §1.1.6, "removable models"); this class only holds UI state and
 * keeps the (potentially slow, recursive-delete) work off the main thread.
 *
 * It also surfaces the resource-cost hint (issue #38): each row shows the engine's a-priori estimated
 * peak RAM from the descriptor (issue #123: deliberately NOT the observed whole-process PSS, which
 * isn't model-attributable; that stays on the Benchmark screen), and the screen shows the device's
 * total RAM at the top. A "won't fit" warning is only ever shown when
 * the model's estimated peak genuinely exceeds this device's TOTAL RAM
 * ([com.phonetts.core.model.DeviceRamFit]) - never merely because free RAM is currently low, which
 * on a 4 GB phone is often true even for models that fit fine (a per-maintainer fix: a 3.5 GB model
 * on a 4 GB phone should never warn). This is an INLINE hint, never a blocking pop-up - the user can
 * still attempt a heavy model regardless.
 *
 * Also lists bundles no engine could identify ([UnresolvedModelUsage], issue #8) so a download that
 * detection declined is shown honestly ("downloaded, no engine available") instead of silently
 * vanishing as if nothing were ever fetched, and drives the storage-location picker (issue #4/#5):
 * [chooseFolder] resolves a SAF tree URI to a real folder and, if usable, hands it straight to
 * [ModelManager.changeStorageLocation] - the same seam a future non-SAF caller could use too.
 *
 * Each unresolved bundle also gets a working manual "pick an engine" fallback (bug #1: this used to
 * be described but unreachable from the UI). [assignEngine] hands the bundle to the chosen engine's
 * `forcedMatch` via [ModelManager.assignEngine] and re-[refresh]es, so on success the row moves from
 * "unresolved" into a normal, usable model row with no further action needed.
 *
 * Also surfaces, per downloaded model, an "Open on Hugging Face" link plus RAM/RTF/parameter-count
 * facts ([ManageModelFacts], derived by [InstalledModelFacts] - never a hardcoded per-model literal).
 * [benchmarkHistory]/[deviceName] are optional: without them (the current wiring - see NOTE below)
 * every model still shows a formula-predicted RTF, just never a measured one.
 *
 * NOTE for whoever wires this next: [benchmarkHistory] and [deviceName] default to "not available"
 * so this class stays constructible exactly as it was before this feature (no other file needed to
 * change). To actually surface MEASURED (not just predicted) RTF here, the call site
 * (`VoicesHubScreen.kt`'s `DownloadedTabContent`, which builds this class) should pass
 * `graph.benchmarkHistory` and `graph.deviceName` - both already exist on `AppGraph` for the
 * Benchmark screen, so no `AppGraph` change is needed, only that one call site.
 *
 * Issue #131 follow-up: [sessionState] (null by default, same fail-open-to-"not wired" rationale as
 * [benchmarkHistory] above) is where this screen's sort key/direction, name query, and
 * origin/engine/size filters are seeded from at construction ([initialUiState]) and written back to
 * on every change (see each setter's [persistManage] call). Before this, [SessionStateStore.manage]
 * existed and was faithfully unit-tested in isolation, but nothing in `:app` ever called it or
 * [SessionStateStore.updateManage] - the exact "built but never wired" defect this issue tracks (see
 * [SessionStateStore]'s own kdoc). A restored value this build no longer understands (a sort key
 * that was renamed, an engine that was uninstalled) is DROPPED in favor of the default rather than
 * applied blind - see [restoreSortKey]/[restoreEngineFilter] - so a stale document can never leave
 * the screen crashed or filtered down to nothing with no visible way out.
 *
 * EXTERNAL WIRING REQUIRED: [sessionState] defaults to `null` so every existing call site (including
 * plain unit tests constructing this class directly) keeps compiling unchanged. The one real call
 * site, `VoicesHubScreen.kt`'s `DownloadedTabContent` (the `app/ui/nav` package, owned by another
 * in-flight change), needs one added line to actually persist anything on device:
 * `sessionState = graph.sessionState` in its `ModelManagementViewModel(...)` initializer.
 */
class ModelManagementViewModel(
    private val modelManager: ModelManager,
    private val resourceUsage: ResourceUsageStore,
    private val availableRamBytes: () -> Long,
    // Defaults to [availableRamBytes] only so existing call sites that haven't been updated yet
    // still compile; a real call site should always pass the device's actual TOTAL ram (issue:
    // "RAM warning fires against free ram" fix) since free RAM is the wrong number to warn against.
    private val totalRamBytes: () -> Long = availableRamBytes,
    private val benchmarkHistory: BenchmarkHistory? = null,
    private val deviceName: String = "",
    // Issue #131: persists sort/query/filters across process death. Null (the default) means "not
    // wired at this call site" - the screen behaves exactly as it did before this feature, same
    // fail-open rationale as [benchmarkHistory]/[deviceName] above. See the class kdoc NOTE.
    private val sessionState: SessionStateStore? = null,
) : ViewModel() {
    data class UiState(
        val usage: List<ModelUsage> = emptyList(),
        val totalBytes: Long = 0L,
        /** modelId → estimated peak RAM in bytes (null = unknown), shown inline next to each model. */
        val peakRamByModelId: Map<String, Long?> = emptyMap(),
        /** modelId → derived link/RAM/RTF/param-count facts (issue: Manage screen model info). */
        val factsByModelId: Map<String, ManageModelFacts> = emptyMap(),
        /** Device free RAM right now - informational only, no longer what the fit warning checks. */
        val availableRamBytes: Long = 0L,
        /** This device's TOTAL RAM - the only figure [com.phonetts.core.model.DeviceRamFit] checks. */
        val totalRamBytes: Long = 0L,
        /** modelIds currently being deleted (issue: single-slot progress state) - a Set, not a single
         * nullable slot, so deleting two rows in quick succession shows a spinner on BOTH until each
         * one's own delete finishes, instead of the second overwriting the first and whichever
         * finishes first clearing the spinner for the other still-mid-delete row. Mirrors
         * [com.phonetts.app.hf.HfBrowseViewModel]'s `queuedDownloadIds`, which already models
         * concurrent per-id state correctly. */
        val deletingIds: Set<String> = emptySet(),
        val error: String? = null,
        /** Downloaded bundles no engine claimed (issue #8) - each offers a manual engine picker. */
        val unresolved: List<UnresolvedModelUsage> = emptyList(),
        /** bundleIds of unresolved bundles currently being deleted - see [deletingIds]'s kdoc. */
        val deletingUnresolvedIds: Set<String> = emptySet(),
        /** Engines a manual pick can offer for an unresolved bundle - the same registered set the
         * resolver's auto-detection already checks (SSOT: no engine name is hardcoded in the UI). */
        val selectableEngines: List<SelectableEngine> = emptyList(),
        /** bundleIds currently being assigned an engine, so their rows show a spinner, not the picker -
         * see [deletingIds]'s kdoc. */
        val assigningUnresolvedIds: Set<String> = emptySet(),
        /** Where models are stored right now (issue #4/#5) - the app-private default or a picked folder. */
        val storageDescription: String = "",
        /** Feedback from the last storage-location action (folder picked / rejected / reset). */
        val storageMessage: String? = null,
        /** Sort key/direction for the downloaded-models list (issue #115). */
        val sort: Sort = Sort.DEFAULT,
        /** Case-insensitive name filter for the downloaded-models list (issue #115); blank shows all. */
        val query: String = "",
        /** Origin restriction (issue: downloaded models has only a name filter). Null = no restriction;
         * [Origin.entries] is the only source of the choices offered, never a hardcoded pair. */
        val originFilter: Origin? = null,
        /** Engine restriction - [ManagedEngineFilter.All] by default. The choices a UI offers for
         * this come from [availableEngineIds] (plus "No engine" when [unresolved] is non-empty),
         * never a hardcoded engine list. */
        val engineFilter: ManagedEngineFilter = ManagedEngineFilter.All,
        val minSizeBytes: Long? = null,
        val maxSizeBytes: Long? = null,
    ) {
        /** Every distinct engine id an installed model actually maps to right now (issue: downloaded
         * models has only a name filter) - the live SSOT for the Engine filter's choices, derived
         * fresh from [usage] rather than any hardcoded roster of known engines. */
        val availableEngineIds: List<String>
            get() = usage.map { it.descriptor.engineId }.distinct().sorted()
    }

    private val mutableState = MutableStateFlow(initialUiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    /**
     * The sort/query/filters this screen starts with (issue #131) - restored from
     * [SessionStateStore.manage] when [sessionState] is wired, validated against the models actually
     * on disk RIGHT NOW so a stale document can never resurrect a choice that no longer makes sense
     * (rule 4's fail-closed spirit applied to persistence, not just model detection). [sessionState]
     * being `null` (no call site wired yet) or the document being empty (first-ever launch) both
     * fall through to [UiState]'s ordinary defaults.
     */
    private fun initialUiState(): UiState {
        val saved = sessionState?.manage() ?: return UiState()
        val knownEngineIds = modelManager.usage().map { it.descriptor.engineId }.toSet()
        return UiState(
            sort = ManagedSort(key = restoreSortKey(saved.sortKey), ascending = saved.ascending),
            query = saved.query,
            originFilter = restoreOrigin(saved.filters[FILTER_ORIGIN]),
            engineFilter = restoreEngineFilter(saved.filters[FILTER_ENGINE], knownEngineIds),
            minSizeBytes = saved.filters[FILTER_MIN_SIZE]?.toLongOrNull(),
            maxSizeBytes = saved.filters[FILTER_MAX_SIZE]?.toLongOrNull(),
        )
    }

    // A sort key this build no longer has (an enum value renamed/removed) falls back to the default
    // rather than crashing ManagedSortKey.valueOf or silently sorting by a null key.
    private fun restoreSortKey(raw: String?): SortKey = SortKey.entries.find { it.name == raw } ?: Sort.DEFAULT.key

    // Same fail-closed rule for Origin - a persisted name this build's Origin enum no longer has
    // (or nothing ever saved) just means "no restriction", never a crash.
    private fun restoreOrigin(raw: String?): Origin? = raw?.let { name -> Origin.entries.find { it.name == name } }

    // The one filter that can genuinely go stale in a way that traps the user: a specific engine id
    // saved from a model that has since been deleted/uninstalled. Falls back to [ManagedEngineFilter.All]
    // rather than leaving the list showing zero rows with no visible way to reset it.
    private fun restoreEngineFilter(
        raw: String?,
        knownEngineIds: Set<String>,
    ): ManagedEngineFilter {
        if (raw == null) return ManagedEngineFilter.All
        if (raw == FILTER_ENGINE_NO_ENGINE) return ManagedEngineFilter.NoEngine
        val id = raw.removePrefix(FILTER_ENGINE_SPECIFIC_PREFIX)
        val isSpecific = raw.startsWith(FILTER_ENGINE_SPECIFIC_PREFIX) && id in knownEngineIds
        return if (isSpecific) ManagedEngineFilter.Specific(id) else ManagedEngineFilter.All
    }

    // Mirrors the just-updated [mutableState] into [sessionState] (issue #131) - called from every
    // setter below so a process death never has to catch up on more than the one change just made.
    // A no-op (besides the read) when [sessionState] is null, matching every other optional wiring
    // in this class.
    private fun persistManage() {
        val current = mutableState.value
        sessionState?.updateManage {
            it.copy(
                sortKey = current.sort.key.name,
                ascending = current.sort.ascending,
                query = current.query,
                filters = manageFilters(current),
            )
        }
    }

    // Encodes the origin/engine/size filters as the plain string map ListViewSession carries (issue
    // #131) - a key is simply omitted when that filter is at its default, so an old persisted
    // document with fewer keys than a newer build's UiState still round-trips cleanly.
    private fun manageFilters(state: UiState): Map<String, String> {
        val map = mutableMapOf<String, String>()
        state.originFilter?.let { map[FILTER_ORIGIN] = it.name }
        when (val engine = state.engineFilter) {
            is ManagedEngineFilter.All -> Unit
            is ManagedEngineFilter.NoEngine -> map[FILTER_ENGINE] = FILTER_ENGINE_NO_ENGINE
            is ManagedEngineFilter.Specific -> map[FILTER_ENGINE] = FILTER_ENGINE_SPECIFIC_PREFIX + engine.engineId
        }
        state.minSizeBytes?.let { map[FILTER_MIN_SIZE] = it.toString() }
        state.maxSizeBytes?.let { map[FILTER_MAX_SIZE] = it.toString() }
        return map
    }

    /** Re-read the catalog + sizes + RAM estimates - call after a delete, or when the screen shows. */
    fun refresh() {
        val usage = modelManager.usage()
        // Show the engine's a-priori peak-RAM ESTIMATE, not the observed peak. The observed value is a
        // whole-process PSS snapshot taken after a benchmark run (it includes the entire app, not just
        // this model), so it isn't model-attributable and produced contradictory numbers between models
        // - a 22.8 MB model reading 354 MB while a 57.3 MB one read 80 MB (issue #123). The honest
        // measured process-memory still lives on the Benchmark screen, labeled as process memory there.
        val estimates = usage.associate { it.descriptor.modelId to it.descriptor.resourceCost.approxPeakRamBytes }
        val facts = usage.associate { it.descriptor.modelId to factsFor(it) }
        mutableState.update {
            it.copy(
                usage = usage,
                // Bug #7: this used to sum only `usage` (identified models), so a downloaded-but-
                // unresolved bundle's very real disk space (issue #8/bug #6) never counted toward
                // "storage used" - [ModelManager.totalBytes] is the SSOT for the total, covering both.
                totalBytes = modelManager.totalBytes(),
                peakRamByModelId = estimates,
                factsByModelId = facts,
                availableRamBytes = availableRamBytes(),
                totalRamBytes = totalRamBytes(),
                error = null,
                unresolved = modelManager.unresolvedUsage(),
                storageDescription = modelManager.currentStorageDescription(),
                selectableEngines = modelManager.selectableEngines(),
            )
        }
    }

    // Combines this model's on-disk size, any REAL observed peak RAM (beats the engine's a-priori
    // guess), and this device's own benchmark history for its engine (beats the formula-predicted
    // RTF) into one derived, honestly-labeled fact bundle - see [InstalledModelFacts].
    private fun factsFor(usage: ModelUsage): ManageModelFacts =
        InstalledModelFacts.of(
            descriptor = usage.descriptor,
            sizeBytes = usage.sizeBytes,
            observedPeakRamBytes = resourceUsage.observedPeakRam(usage.descriptor.modelId),
            measuredRealTimeFactors = measuredRealTimeFactors(usage.descriptor.engineId),
        )

    // Empty (never a fabricated sample) when history isn't wired in yet (see the class kdoc NOTE)
    // or this device has no recorded runs for this engine.
    private fun measuredRealTimeFactors(engineId: String): List<Double> {
        val history = benchmarkHistory ?: return emptyList()
        if (deviceName.isBlank()) return emptyList()
        return history.history(engineId, deviceName).map { it.realTimeFactor }
    }

    fun delete(modelId: String) {
        mutableState.update { it.copy(deletingIds = it.deletingIds + modelId, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { modelManager.remove(modelId) } }
                .onSuccess { mutableState.update { it.copy(deletingIds = it.deletingIds - modelId) } }
                .onFailure { e ->
                    val error = FailureExplainer.explain(e, FailureContext.MANAGING_MODEL)
                    mutableState.update { it.copy(deletingIds = it.deletingIds - modelId, error = error) }
                }
            refresh()
        }
    }

    /** Delete an unidentified bundle's files (issue #8) - the user reclaiming space on a dead download. */
    fun deleteUnresolved(bundleId: String) {
        mutableState.update { it.copy(deletingUnresolvedIds = it.deletingUnresolvedIds + bundleId, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { modelManager.removeUnresolved(bundleId) } }
                .onSuccess { mutableState.update { it.copy(deletingUnresolvedIds = it.deletingUnresolvedIds - bundleId) } }
                .onFailure { e ->
                    val error = FailureExplainer.explain(e, FailureContext.MANAGING_MODEL)
                    mutableState.update {
                        it.copy(deletingUnresolvedIds = it.deletingUnresolvedIds - bundleId, error = error)
                    }
                }
            refresh()
        }
    }

    /**
     * Manually assign an unresolved bundle to [engineId] (bug #1 - the picker the resolver's fallback
     * promised had nothing on the UI side that could actually drive it). Runs
     * [ModelManager.assignEngine] off the main thread; on success the bundle disappears from
     * [UiState.unresolved] and reappears as a normal [UiState.usage] entry on the [refresh] this
     * always triggers. A rejection (e.g. the chosen engine's forcedMatch declining the bundle, most
     * likely because it's missing a file that family structurally needs) is surfaced as
     * [UiState.error] rather than crashing - the bundle stays unresolved and can be tried again.
     */
    fun assignEngine(
        bundleId: String,
        engineId: String,
    ) {
        mutableState.update { it.copy(assigningUnresolvedIds = it.assigningUnresolvedIds + bundleId, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { modelManager.assignEngine(bundleId, engineId) } }
                .onSuccess { mutableState.update { it.copy(assigningUnresolvedIds = it.assigningUnresolvedIds - bundleId) } }
                .onFailure { e ->
                    val error = FailureExplainer.explain(e, FailureContext.MANAGING_MODEL)
                    mutableState.update {
                        it.copy(assigningUnresolvedIds = it.assigningUnresolvedIds - bundleId, error = error)
                    }
                }
            refresh()
        }
    }

    /**
     * Handle a folder picked via `ActivityResultContracts.OpenDocumentTree()` (issue #4/#5). The
     * caller has already taken a persistable permission on [treeUri]; this resolves it to a real
     * filesystem path ([StorageLocation.resolve]) and, only if that path is genuinely readable and
     * writable, switches the models base dir to it. A folder that can't be resolved to a plain
     * `java.io.File` (or isn't actually writable - e.g. `MANAGE_EXTERNAL_STORAGE` wasn't granted)
     * is refused with a message rather than silently left half-applied.
     *
     * [ModelManager.changeStorageLocation] MIGRATES any already-downloaded models from the previous
     * location to this one and returns a warning message if any of them couldn't be moved (left
     * safely in place at the old location, never lost) - that warning, when present, replaces the
     * generic "now storing models in…" confirmation so the user actually sees it.
     */
    fun chooseFolder(treeUri: Uri) {
        when (val resolution = StorageLocation.resolve(treeUri)) {
            is StorageLocation.Resolution.Usable -> {
                val warning = modelManager.changeStorageLocation(resolution.path)
                val message = warning ?: "Now storing models in ${resolution.path}"
                mutableState.update { it.copy(storageMessage = message) }
            }
            is StorageLocation.Resolution.Unusable -> {
                mutableState.update { it.copy(storageMessage = "Can't use that folder: ${resolution.reason}") }
            }
        }
        refresh()
    }

    /**
     * Revert to app-private storage (issue #4/#5). Like [chooseFolder], this now MIGRATES any
     * models that were sitting under the custom folder back into app-private storage rather than
     * abandoning them there.
     */
    fun resetStorageLocation() {
        val warning = modelManager.changeStorageLocation(null)
        mutableState.update { it.copy(storageMessage = warning ?: "Back to app-private storage") }
        refresh()
    }

    /** Dismiss the last storage-location feedback message. */
    fun dismissStorageMessage() = mutableState.update { it.copy(storageMessage = null) }

    /** Choose which field the downloaded-models list is sorted by (issue #115). */
    fun setSortKey(key: SortKey) {
        mutableState.update { it.copy(sort = it.sort.copy(key = key)) }
        persistManage()
    }

    /** Flip the sort between ascending and descending (issue #115). */
    fun toggleSortDirection() {
        mutableState.update { it.copy(sort = it.sort.copy(ascending = !it.sort.ascending)) }
        persistManage()
    }

    /** Update the name filter applied to the downloaded-models list (issue #115). */
    fun setQuery(query: String) {
        mutableState.update { it.copy(query = query) }
        persistManage()
    }

    /** Restrict the downloaded-models list to one [Origin], or null to clear (issue: downloaded
     * models has only a name filter). */
    fun setOriginFilter(origin: Origin?) {
        mutableState.update { it.copy(originFilter = origin) }
        persistManage()
    }

    /** Restrict the downloaded-models list to one engine, "No engine", or [ManagedEngineFilter.All]
     * (issue: downloaded models has only a name filter). */
    fun setEngineFilter(filter: ManagedEngineFilter) {
        mutableState.update { it.copy(engineFilter = filter) }
        persistManage()
    }

    /** Apply a min/max on-disk size bound (bytes; either may be null) to the downloaded-models list
     * (issue: downloaded models has only a name filter). */
    fun setSizeFilter(
        minSizeBytes: Long?,
        maxSizeBytes: Long?,
    ) {
        mutableState.update { it.copy(minSizeBytes = minSizeBytes, maxSizeBytes = maxSizeBytes) }
        persistManage()
    }

    /** Clear the origin/engine/size filters (name filter and sort are untouched). */
    fun clearAdvancedFilters() {
        mutableState.update {
            it.copy(originFilter = null, engineFilter = ManagedEngineFilter.All, minSizeBytes = null, maxSizeBytes = null)
        }
        persistManage()
    }

    companion object {
        /**
         * The unified, sorted, filtered downloaded-models list - a resolved [ModelUsage] AND an
         * unclaimed [UnresolvedModelUsage] bundle go through the exact same pass (issue: sort/filter
         * ignoring engine-less models; before this, [unresolved] was appended afterward raw, never
         * sorted or filtered). Pure, so the composable can call it straight on the collected state.
         */
        fun visibleEntries(state: UiState): List<ManagedModelEntry> {
            val entries =
                state.usage.map { ManagedModelEntry.Resolved(it) } +
                    state.unresolved.map { ManagedModelEntry.Unresolved(it) }
            val filter =
                ManagedModelFilter(
                    query = state.query,
                    origin = state.originFilter,
                    engine = state.engineFilter,
                    minSizeBytes = state.minSizeBytes,
                    maxSizeBytes = state.maxSizeBytes,
                )
            return ManagedModelListView.visible(entries, state.factsByModelId, state.sort, filter)
        }

        // Persisted-filter map keys (issue #131) - see [manageFilters]/[initialUiState]. Plain string
        // keys into the session store's ListViewSession.filters map, matching the "screen-specific
        // filters need no schema change" contract that map already documents.
        private const val FILTER_ORIGIN = "origin"
        private const val FILTER_ENGINE = "engine"
        private const val FILTER_MIN_SIZE = "minSize"
        private const val FILTER_MAX_SIZE = "maxSize"
        private const val FILTER_ENGINE_NO_ENGINE = "no-engine"
        private const val FILTER_ENGINE_SPECIFIC_PREFIX = "specific:"
    }
}

/** Which downloaded-model field the list is ordered by (issue #115) - an alias for the pure
 * `:core` type so [ManagedModelListView] does the actual comparing (issue: sort/filter ignoring
 * engine-less models) while this screen's call sites keep their existing short names. */
typealias SortKey = ManagedSortKey

/** The chosen sort key and direction (issue #115) - see [SortKey]. */
typealias Sort = ManagedSort
