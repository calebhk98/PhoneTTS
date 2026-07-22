package com.phonetts.app.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * vanishing as if nothing were ever fetched.
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
                totalBytes = usage.sumOf { row -> row.sizeBytes },
                peakRamByModelId = estimates,
                availableRamBytes = availableRamBytes(),
                error = null,
                unresolved = modelManager.unresolvedUsage(),
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
}
