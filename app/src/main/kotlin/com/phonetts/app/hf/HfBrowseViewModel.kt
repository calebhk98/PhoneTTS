package com.phonetts.app.hf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.ModelStorage
import com.phonetts.core.download.builtin.BuiltInCatalog
import com.phonetts.core.download.builtin.BuiltInModel
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.download.hf.HfDownloadItem
import com.phonetts.core.download.hf.HfDownloadPlan
import com.phonetts.core.download.hf.HfDownloadProgress
import com.phonetts.core.download.hf.HfModelSummary
import com.phonetts.core.download.hf.HfQuantizedDownloadPlan
import com.phonetts.core.download.hf.HfResultsView
import com.phonetts.core.download.hf.HfSizeEstimator
import com.phonetts.core.download.hf.HfSortOption
import com.phonetts.core.download.hf.HfTreeEntry
import com.phonetts.core.download.hf.QuantizationFilter
import com.phonetts.core.download.hf.QuantizationVariant
import com.phonetts.core.model.ModelDescriptor
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
 * Drives the HF browse screen: live search of text-to-speech models, sort/filter over the results
 * (issue #6), download → import of a chosen repo with **multiple downloads running at once**
 * (issue #2), on-demand size lookups (issue #7), and a retained/copyable error log (issue #3). All
 * model detection is delegated to the core resolver via [HfDownloader]; this class holds UI state
 * only and names no model family.
 */
class HfBrowseViewModel(
    private val catalog: HfCatalog,
    private val downloader: HfDownloader,
    private val modelCatalog: ModelCatalog,
    // True if the Runtime with the given id is available on this build. Used to hide a recommended
    // model whose runtime isn't present (e.g. CosyVoice3 in a non-native build), so a one-tap
    // download never lands a model that can't load. Defaults to "not available" — fail-closed.
    private val isRuntimeAvailable: (String) -> Boolean = { false },
    // Injectable wall-clock so progress/ETA math (issue #7) is deterministic to test; defaults to
    // the real clock on device.
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HfBrowseUiState(installedIds = installedIdsSnapshot()))
    val state: StateFlow<HfBrowseUiState> = mutableState.asStateFlow()

    // One coroutine per repo id currently in flight (listing files, or fetching them) — a map, not
    // a single slot, so the user can start a second download while the first is still running and
    // still cancel each independently (issue #2).
    private val downloadJobs = mutableMapOf<String, Job>()

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

    /** The current results after [HfBrowseUiState.sort]/[HfBrowseUiState.tagFilter] are applied — a
     * pure recompute over already-fetched data (issue #6), never a second network call. Takes
     * [state] explicitly (rather than reading [mutableState] internally) so the Compose call site
     * can `remember` this keyed on exactly the fields it depends on — see [HfBrowseScreen] (issue
     * #3): recomputing this on every unrelated state change, e.g. a download-progress tick, is
     * what made the sort/filter dropdown janky.
     */
    fun displayedResults(state: HfBrowseUiState): List<HfModelSummary> =
        HfResultsView.apply(state.results, state.sort, state.tagFilter)

    /** Every tag present in the current result set, for the filter menu's choices — derived from
     * data, never a hardcoded list (issue #6 SSOT rule). Same [state]-parameter rationale as
     * [displayedResults]. */
    fun availableTags(state: HfBrowseUiState): List<String> = HfResultsView.availableTags(state.results)

    fun onSortChange(sort: HfSortOption) = mutableState.update { it.copy(sort = sort) }

    fun onTagFilterChange(tag: String?) = mutableState.update { it.copy(tagFilter = tag) }

    /** Clears the current inline error banner (issue #2). The full session log in [HfBrowseError]
     * is untouched — dismissing only hides the one-line banner, it doesn't forget the error. */
    fun dismissError() = mutableState.update { it.copy(error = null) }

    /** Download a curated model directly — no search, no webpage — using its known file list. */
    fun downloadBuiltIn(model: BuiltInModel) = runDownload(model.id, model.downloadItems())

    fun onQueryChange(query: String) = mutableState.update { it.copy(query = query) }

    fun search() {
        val query = mutableState.value.query
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { catalog.search(query) } }
                .onSuccess { results -> mutableState.update { it.copy(loading = false, results = results) } }
                .onFailure { e ->
                    // logError does its own atomic state update, so it must NOT be called from
                    // inside this update {} block below — MutableStateFlow.update retries its
                    // transform on a compare-and-set conflict, and a nested update() call here would
                    // re-run (and re-log) on every retry.
                    val message = logError(null, e)
                    mutableState.update { it.copy(loading = false, error = message) }
                }
        }
    }

    fun download(model: HfModelSummary) {
        if (mutableState.value.isDownloading(model.id)) return // already fetching this repo
        beginTracking(model.id)
        downloadJobs[model.id] =
            viewModelScope.launch {
                runCatching { withContext(Dispatchers.IO) { catalog.listFiles(model.id) } }
                    .onSuccess { files -> onFilesListed(model.id, files) }
                    .onFailure { e -> failDownload(model.id, e) }
            }
    }

    /**
     * Fetch just the repo's total download size, without downloading it (issue #7). Cached in
     * state once known so re-opening the screen or scrolling doesn't refetch. A model whose size
     * can't be determined (network hiccup) simply stays unknown — never a guessed number.
     */
    fun loadSize(modelId: String) {
        val current = mutableState.value
        if (modelId in current.sizeEstimates || modelId in current.sizeLoading) return
        mutableState.update { it.copy(sizeLoading = it.sizeLoading + modelId) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { catalog.listFiles(modelId) } }
                .onSuccess { files ->
                    val estimate = HfSizeEstimator.estimate(files)
                    mutableState.update {
                        it.copy(
                            sizeLoading = it.sizeLoading - modelId,
                            sizeEstimates = it.sizeEstimates + (modelId to estimate),
                        )
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    mutableState.update { it.copy(sizeLoading = it.sizeLoading - modelId) }
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
        endTracking(modelId)
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

    private fun runDownload(
        modelId: String,
        items: List<HfDownloadItem>,
    ) {
        val sizeEstimate = HfSizeEstimator.estimateItems(items)
        val exactBytesTotal = sizeEstimate.knownBytes.takeIf { sizeEstimate.isExact }
        beginTracking(modelId, filesTotal = items.size, bytesTotal = exactBytesTotal)
        downloadJobs[modelId] =
            viewModelScope.launch {
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
                        },
                    )
                }.onSuccess { descriptor -> completeDownload(modelId, descriptor) }
                    .onFailure { e -> failDownload(modelId, e) }
            }
    }

    private fun beginTracking(
        modelId: String,
        filesTotal: Int = 0,
        bytesTotal: Long? = null,
    ) {
        val progress = HfDownloadProgress(filesTotal = filesTotal, bytesTotal = bytesTotal, startedAtMs = clock())
        mutableState.update { it.copy(downloads = it.downloads + (modelId to progress), error = null) }
    }

    private fun endTracking(modelId: String) = mutableState.update { it.copy(downloads = it.downloads - modelId) }

    private fun updateProgress(
        modelId: String,
        transform: (HfDownloadProgress) -> HfDownloadProgress,
    ) {
        mutableState.update { current ->
            val progress = current.downloads[modelId] ?: return@update current
            current.copy(downloads = current.downloads + (modelId to transform(progress)))
        }
    }

    private fun completeDownload(
        modelId: String,
        descriptor: ModelDescriptor,
    ) {
        downloadJobs.remove(modelId)
        mutableState.update {
            it.copy(downloads = it.downloads - modelId, installedIds = it.installedIds + descriptor.modelId)
        }
    }

    // Cancellation is a user action, not a failure — let it propagate; cancelDownload already reset
    // the UI. Only real errors surface an error line AND join the retained/copyable log (issue #3).
    private fun failDownload(
        modelId: String,
        e: Throwable,
    ) {
        if (e is CancellationException) throw e
        downloadJobs.remove(modelId)
        val message = logError(modelId, e)
        mutableState.update { it.copy(downloads = it.downloads - modelId, error = message) }
    }

    /** Appends [e] to the retained error log (issue #3), bounded to [MAX_HF_ERROR_LOG] entries, and
     * returns a display message for the caller's own inline banner. */
    private fun logError(
        modelId: String?,
        e: Throwable,
    ): String {
        val message = e.message ?: e::class.simpleName ?: "Unknown error"
        mutableState.update { current ->
            val entry = HfBrowseError(atMs = clock(), modelId = modelId, message = message)
            current.copy(errorLog = (listOf(entry) + current.errorLog).take(MAX_HF_ERROR_LOG))
        }
        return message
    }
}
