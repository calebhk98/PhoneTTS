package com.phonetts.app.manage

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.StorageLocation
import com.phonetts.core.metrics.BenchmarkHistory
import com.phonetts.core.model.InstalledModelFacts
import com.phonetts.core.model.ManageModelFacts
import com.phonetts.core.prefs.ResourceUsageStore
import com.phonetts.core.registry.ModelManager
import com.phonetts.core.registry.ModelUsage
import com.phonetts.core.registry.UnresolvedModelUsage
import com.phonetts.core.resolver.SelectableEngine
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
 * It also surfaces the resource-cost hint (issue #38): each row shows an estimated peak RAM (the
 * engine's a-priori estimate from the descriptor, refined by observed peaks in [resourceUsage]), and
 * the screen shows the device's total RAM at the top. A "won't fit" warning is only ever shown when
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
 * (`MainActivity.kt`'s `ModelManagementViewModel(...)` initializer) should pass
 * `graph.benchmarkHistory` and `graph.deviceName` - both already exist on `AppGraph` for the
 * Benchmark screen, so no `AppGraph` change is needed, only that one call site.
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
) : ViewModel() {
    /** Which downloaded-model field the list is ordered by (issue #115). */
    enum class SortKey(val label: String) {
        NAME("Name"),
        SIZE("Size"),
        RAM("Est. RAM"),
        SPEED("Speed"),
        ENGINE("Engine"),
        ORIGIN("Origin"),
    }

    /** The chosen sort key and direction (issue #115). */
    data class Sort(val key: SortKey, val ascending: Boolean) {
        companion object {
            val DEFAULT = Sort(SortKey.NAME, ascending = true)
        }
    }

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
        val deletingId: String? = null,
        val error: String? = null,
        /** Downloaded bundles no engine claimed (issue #8) - each offers a manual engine picker. */
        val unresolved: List<UnresolvedModelUsage> = emptyList(),
        val deletingUnresolvedId: String? = null,
        /** Engines a manual pick can offer for an unresolved bundle - the same registered set the
         * resolver's auto-detection already checks (SSOT: no engine name is hardcoded in the UI). */
        val selectableEngines: List<SelectableEngine> = emptyList(),
        /** bundleId currently being assigned an engine, so its row shows a spinner, not the picker. */
        val assigningUnresolvedId: String? = null,
        /** Where models are stored right now (issue #4/#5) - the app-private default or a picked folder. */
        val storageDescription: String = "",
        /** Feedback from the last storage-location action (folder picked / rejected / reset). */
        val storageMessage: String? = null,
        /** Sort key/direction for the downloaded-models list (issue #115). */
        val sort: Sort = Sort.DEFAULT,
        /** Case-insensitive name filter for the downloaded-models list (issue #115); blank shows all. */
        val query: String = "",
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    /** Re-read the catalog + sizes + RAM estimates - call after a delete, or when the screen shows. */
    fun refresh() {
        val usage = modelManager.usage()
        val estimates = usage.associate { it.descriptor.modelId to resourceUsage.peakRamEstimate(it.descriptor) }
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
        mutableState.update { it.copy(deletingId = modelId, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { modelManager.remove(modelId) } }
                .onSuccess { mutableState.update { it.copy(deletingId = null) } }
                .onFailure { e -> mutableState.update { it.copy(deletingId = null, error = e.message) } }
            refresh()
        }
    }

    /** Delete an unidentified bundle's files (issue #8) - the user reclaiming space on a dead download. */
    fun deleteUnresolved(bundleId: String) {
        mutableState.update { it.copy(deletingUnresolvedId = bundleId, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { modelManager.removeUnresolved(bundleId) } }
                .onSuccess { mutableState.update { it.copy(deletingUnresolvedId = null) } }
                .onFailure { e -> mutableState.update { it.copy(deletingUnresolvedId = null, error = e.message) } }
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
        mutableState.update { it.copy(assigningUnresolvedId = bundleId, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { modelManager.assignEngine(bundleId, engineId) } }
                .onSuccess { mutableState.update { it.copy(assigningUnresolvedId = null) } }
                .onFailure { e ->
                    mutableState.update {
                        it.copy(assigningUnresolvedId = null, error = e.message ?: "Couldn't use that engine.")
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
    }

    /** Flip the sort between ascending and descending (issue #115). */
    fun toggleSortDirection() {
        mutableState.update { it.copy(sort = it.sort.copy(ascending = !it.sort.ascending)) }
    }

    /** Update the name filter applied to the downloaded-models list (issue #115). */
    fun setQuery(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    companion object {
        /**
         * The downloaded models to actually show, filtered by [query] (case-insensitive name match)
         * and ordered by [sort] (issue #115). RAM/Speed keys read the derived [facts]; a model with no
         * such fact sorts last in ascending order rather than being dropped. Pure, so the composable
         * can call it straight on the collected state.
         */
        fun visibleUsage(
            usage: List<ModelUsage>,
            facts: Map<String, ManageModelFacts>,
            sort: Sort,
            query: String,
        ): List<ModelUsage> {
            val needle = query.trim()
            val filtered =
                usage.filter { needle.isEmpty() || it.descriptor.displayName.contains(needle, ignoreCase = true) }
            val sorted = filtered.sortedWith(usageComparator(sort.key, facts))
            return if (sort.ascending) sorted else sorted.reversed()
        }

        private fun usageComparator(
            key: SortKey,
            facts: Map<String, ManageModelFacts>,
        ): Comparator<ModelUsage> =
            when (key) {
                SortKey.NAME -> compareBy { it.descriptor.displayName.lowercase() }
                SortKey.SIZE -> compareBy { it.sizeBytes }
                SortKey.RAM -> compareBy { facts[it.descriptor.modelId]?.peakRamBytes ?: Long.MAX_VALUE }
                SortKey.SPEED -> compareBy { facts[it.descriptor.modelId]?.realtimeMultiple ?: Double.MAX_VALUE }
                SortKey.ENGINE -> compareBy { it.descriptor.engineId.lowercase() }
                SortKey.ORIGIN -> compareBy { it.descriptor.origin.name }
            }
    }
}
