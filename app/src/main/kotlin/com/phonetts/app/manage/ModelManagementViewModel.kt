package com.phonetts.app.manage

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.StorageLocation
import com.phonetts.core.prefs.ResourceUsageStore
import com.phonetts.core.registry.ModelManager
import com.phonetts.core.registry.ModelUsage
import com.phonetts.core.registry.UnresolvedModelUsage
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
 * the screen shows the device's current free RAM at the top. This is an INLINE hint, never a
 * blocking pop-up — the user can still attempt a heavy model on a small phone.
 *
 * Also lists bundles no engine could identify ([UnresolvedModelUsage], issue #8) so a download that
 * detection declined is shown honestly ("downloaded, no engine available") instead of silently
 * vanishing as if nothing were ever fetched, and drives the storage-location picker (issue #4/#5):
 * [chooseFolder] resolves a SAF tree URI to a real folder and, if usable, hands it straight to
 * [ModelManager.changeStorageLocation] — the same seam a future non-SAF caller could use too.
 */
class ModelManagementViewModel(
    private val modelManager: ModelManager,
    private val resourceUsage: ResourceUsageStore,
    private val availableRamBytes: () -> Long,
) : ViewModel() {
    data class UiState(
        val usage: List<ModelUsage> = emptyList(),
        val totalBytes: Long = 0L,
        /** modelId → estimated peak RAM in bytes (null = unknown), shown inline next to each model. */
        val peakRamByModelId: Map<String, Long?> = emptyMap(),
        /** Device free RAM right now, shown at the top so the estimates have a reference point. */
        val availableRamBytes: Long = 0L,
        val deletingId: String? = null,
        val error: String? = null,
        /** Downloaded bundles no engine claimed (issue #8) — shown, but never selectable. */
        val unresolved: List<UnresolvedModelUsage> = emptyList(),
        val deletingUnresolvedId: String? = null,
        /** Where models are stored right now (issue #4/#5) — the app-private default or a picked folder. */
        val storageDescription: String = "",
        /** Feedback from the last storage-location action (folder picked / rejected / reset). */
        val storageMessage: String? = null,
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    /** Re-read the catalog + sizes + RAM estimates — call after a delete, or when the screen shows. */
    fun refresh() {
        val usage = modelManager.usage()
        val estimates = usage.associate { it.descriptor.modelId to resourceUsage.peakRamEstimate(it.descriptor) }
        mutableState.update {
            it.copy(
                usage = usage,
                // Bug #7: this used to sum only `usage` (identified models), so a downloaded-but-
                // unresolved bundle's very real disk space (issue #8/bug #6) never counted toward
                // "storage used" — [ModelManager.totalBytes] is the SSOT for the total, covering both.
                totalBytes = modelManager.totalBytes(),
                peakRamByModelId = estimates,
                availableRamBytes = availableRamBytes(),
                error = null,
                unresolved = modelManager.unresolvedUsage(),
                storageDescription = modelManager.currentStorageDescription(),
            )
        }
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

    /** Delete an unidentified bundle's files (issue #8) — the user reclaiming space on a dead download. */
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
     * Handle a folder picked via `ActivityResultContracts.OpenDocumentTree()` (issue #4/#5). The
     * caller has already taken a persistable permission on [treeUri]; this resolves it to a real
     * filesystem path ([StorageLocation.resolve]) and, only if that path is genuinely readable and
     * writable, switches the models base dir to it. A folder that can't be resolved to a plain
     * `java.io.File` (or isn't actually writable — e.g. `MANAGE_EXTERNAL_STORAGE` wasn't granted)
     * is refused with a message rather than silently left half-applied.
     *
     * [ModelManager.changeStorageLocation] MIGRATES any already-downloaded models from the previous
     * location to this one and returns a warning message if any of them couldn't be moved (left
     * safely in place at the old location, never lost) — that warning, when present, replaces the
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
}
