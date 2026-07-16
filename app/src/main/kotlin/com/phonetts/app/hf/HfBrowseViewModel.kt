package com.phonetts.app.hf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.download.hf.HfDownloadPlan
import com.phonetts.core.download.hf.HfModelSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the HF browse screen: live search of text-to-speech models, and download → import of a
 * chosen repo. All model detection is delegated to the core resolver via [HfDownloader]; this class
 * holds UI state only and names no model family.
 */
class HfBrowseViewModel(
    private val catalog: HfCatalog,
    private val downloader: HfDownloader,
) : ViewModel() {
    data class UiState(
        val query: String = "",
        val results: List<HfModelSummary> = emptyList(),
        val loading: Boolean = false,
        val downloadingId: String? = null,
        val progress: Pair<Int, Int>? = null,
        val importedModelId: String? = null,
        val error: String? = null,
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    fun onQueryChange(query: String) = mutableState.update { it.copy(query = query) }

    fun search() {
        val query = mutableState.value.query
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { catalog.search(query) } }
                .onSuccess { results -> mutableState.update { it.copy(loading = false, results = results) } }
                .onFailure { e -> mutableState.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun download(model: HfModelSummary) {
        mutableState.update { it.copy(downloadingId = model.id, progress = null, error = null) }
        viewModelScope.launch {
            runCatching {
                val files = withContext(Dispatchers.IO) { catalog.listFiles(model.id) }
                val plan = HfDownloadPlan.forFiles(model.id, files)
                downloader.downloadAndImport(model.id, plan) { done, total ->
                    mutableState.update { it.copy(progress = done to total) }
                }
            }.onSuccess { descriptor ->
                mutableState.update {
                    it.copy(downloadingId = null, progress = null, importedModelId = descriptor.modelId)
                }
            }.onFailure { e ->
                mutableState.update { it.copy(downloadingId = null, progress = null, error = e.message) }
            }
        }
    }
}
