package com.phonetts.app.hf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.ModelStorage
import com.phonetts.core.download.builtin.BuiltInCatalog
import com.phonetts.core.download.builtin.BuiltInModel
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.download.hf.HfDownloadPlan
import com.phonetts.core.download.hf.HfModelSummary
import com.phonetts.core.download.hf.HfQuantizedDownloadPlan
import com.phonetts.core.download.hf.HfTreeEntry
import com.phonetts.core.download.hf.QuantizationFilter
import com.phonetts.core.download.hf.QuantizationVariant
import com.phonetts.core.registry.ModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val modelCatalog: ModelCatalog,
    // True if the Runtime with the given id is available on this build. Used to hide a recommended
    // model whose runtime isn't present (e.g. CosyVoice3 in a non-native build), so a one-tap
    // download never lands a model that can't load. Defaults to "not available" — fail-closed.
    private val isRuntimeAvailable: (String) -> Boolean = { false },
) : ViewModel() {
    data class UiState(
        val query: String = "",
        val results: List<HfModelSummary> = emptyList(),
        val loading: Boolean = false,
        val downloadingId: String? = null,
        val progress: Pair<Int, Int>? = null,
        val error: String? = null,
        // Every model already in the catalog (this session's downloads AND anything imported in a
        // prior session) keyed by the SAME sanitized id every engine's descriptor.modelId uses
        // (ModelStorage.sanitize) — so "is this row already installed" survives leaving/reopening
        // this screen, not just the moment right after a fresh download completes.
        val installedIds: Set<String> = emptySet(),
        // Set when a chosen repo ships more than one weight precision and the user must pick one
        // (a budget-device win: fetch only fp16/q8 instead of every variant). Null otherwise.
        val variantChoice: VariantChoice? = null,
    )

    /** A pending precision choice: the repo's files plus the distinct precisions it offers. */
    data class VariantChoice(
        val modelId: String,
        val files: List<HfTreeEntry>,
        val variants: List<QuantizationVariant>,
    )

    private val mutableState = MutableStateFlow(UiState(installedIds = installedIdsSnapshot()))
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    // The in-flight download coroutine (file listing + fetch + import), tracked so the user can
    // cancel a long download. Cancelling leaves any partial file on disk so a later retry resumes.
    private var downloadJob: Job? = null

    /**
     * Curated one-tap models (proven working; see docs/MODEL-VERIFICATION.md), minus any whose
     * required runtime isn't available on this build — so a standard APK shows the four ONNX models
     * and a native (-PwithCosyVoice) build also shows CosyVoice3.
     */
    val recommended: List<BuiltInModel> =
        BuiltInCatalog.ALL.filter { model -> model.requiresRuntimeId?.let(isRuntimeAvailable) ?: true }

    // Populate the list immediately so the screen isn't just the handful of recommended models until
    // the user types — a blank query lists the top TTS models (HfEndpoints.searchModelsUrl). Fail-
    // closed: no network just leaves results empty with an error line, the recommended list still shows.
    init {
        search()
    }

    /** True if [rawId] (an [HfModelSummary.id] or [BuiltInModel.id]) is already in the catalog. */
    fun isInstalled(rawId: String): Boolean = ModelStorage.sanitize(rawId) in mutableState.value.installedIds

    private fun installedIdsSnapshot(): Set<String> = modelCatalog.list().map { it.modelId }.toSet()

    /** Download a curated model directly — no search, no webpage — using its known file list. */
    fun downloadBuiltIn(model: BuiltInModel) = runDownload(model.id, model.downloadItems())

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
        downloadJob =
            viewModelScope.launch {
                runCatching { withContext(Dispatchers.IO) { catalog.listFiles(model.id) } }
                    .onSuccess { files -> onFilesListed(model.id, files) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        mutableState.update { it.copy(downloadingId = null, progress = null, error = e.message) }
                    }
            }
    }

    /**
     * Cancel the in-flight download. The coroutine is cancelled cooperatively (the fetch loop checks
     * for it each buffer) and any partially-downloaded file is left on disk, so re-tapping Download
     * resumes from where it stopped rather than starting the whole weight file over.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        mutableState.update { it.copy(downloadingId = null, progress = null, variantChoice = null) }
    }

    // If the repo ships one precision (or none classifiable), download it straight away; if it
    // ships several, surface a picker so the user fetches only the precision they want.
    private fun onFilesListed(
        modelId: String,
        files: List<HfTreeEntry>,
    ) {
        val variants = QuantizationFilter.availableVariants(files).toList()
        if (variants.size <= 1) {
            runDownload(modelId, HfDownloadPlan.forFiles(modelId, files))
            return
        }
        mutableState.update {
            it.copy(downloadingId = null, variantChoice = VariantChoice(modelId, files, variants))
        }
    }

    /** Download only the chosen precision's weights (plus the shared config/tokenizer files). */
    fun chooseVariant(variant: QuantizationVariant) {
        val choice = mutableState.value.variantChoice ?: return
        mutableState.update { it.copy(variantChoice = null, downloadingId = choice.modelId, progress = null) }
        runDownload(choice.modelId, HfQuantizedDownloadPlan.forVariant(choice.modelId, choice.files, variant))
    }

    fun cancelVariantChoice() = mutableState.update { it.copy(variantChoice = null, downloadingId = null) }

    private fun runDownload(
        modelId: String,
        items: List<com.phonetts.core.download.hf.HfDownloadItem>,
    ) {
        mutableState.update { it.copy(downloadingId = modelId, progress = null, error = null) }
        downloadJob =
            viewModelScope.launch {
                runCatching {
                    downloader.downloadAndImport(modelId, items) { done, total ->
                        mutableState.update { it.copy(progress = done to total) }
                    }
                }.onSuccess { descriptor ->
                    mutableState.update {
                        it.copy(
                            downloadingId = null,
                            progress = null,
                            installedIds = it.installedIds + descriptor.modelId,
                        )
                    }
                }.onFailure { e ->
                    // Cancellation is a user action, not a failure — let it propagate; cancelDownload
                    // already reset the UI. Only real errors surface an error line.
                    if (e is CancellationException) throw e
                    mutableState.update { it.copy(downloadingId = null, progress = null, error = e.message) }
                }
            }
    }
}
