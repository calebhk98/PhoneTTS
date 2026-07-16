package com.phonetts.app.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.core.registry.ModelManager
import com.phonetts.core.registry.ModelUsage
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
 */
class ModelManagementViewModel(private val modelManager: ModelManager) : ViewModel() {
    data class UiState(
        val usage: List<ModelUsage> = emptyList(),
        val totalBytes: Long = 0L,
        val deletingId: String? = null,
        val error: String? = null,
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    /** Re-read the catalog + sizes — call after a delete, or whenever the screen becomes visible. */
    fun refresh() {
        val usage = modelManager.usage()
        mutableState.update { it.copy(usage = usage, totalBytes = usage.sumOf { row -> row.sizeBytes }, error = null) }
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
}
