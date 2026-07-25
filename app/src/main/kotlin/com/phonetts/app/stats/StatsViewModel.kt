package com.phonetts.app.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.core.download.hf.DiagnosticsEntry
import com.phonetts.core.download.hf.DiagnosticsKind
import com.phonetts.core.download.hf.DownloadDiagnosticsLog
import com.phonetts.core.metrics.BenchmarkHistory
import com.phonetts.core.metrics.BenchmarkRecord
import com.phonetts.core.prefs.ResourceUsageStore
import com.phonetts.core.registry.ModelManager
import com.phonetts.core.store.FavoritesStore
import com.phonetts.core.metrics.UsageStatsSnapshot
import com.phonetts.core.metrics.UsageStatsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the "most-played models" list - a model id paired with the best display name we have
 * for it right now, and how many times it has been played back. [displayName] falls back to the raw
 * [modelId] when the model is no longer installed (never fabricated - it is exactly what the catalog
 * would show, or the id itself when there is nothing better to show). */
data class ModelPlayRow(
    val modelId: String,
    val displayName: String,
    val playCount: Long,
)

/** One installed model's on-disk footprint and, if it has ever actually been measured on THIS
 * device, its observed peak RAM (issue #38) - never the a-priori estimate dressed up as a
 * measurement; [observedPeakRamBytes] is null when nothing has been observed yet. */
data class ModelStorageRow(
    val modelId: String,
    val displayName: String,
    val sizeBytes: Long,
    val observedPeakRamBytes: Long?,
)

/** One engine's persisted real-time-factor history on this device (issue #39) - oldest and newest
 * sample so the screen can show a compact trend without re-deriving [BenchmarkHistory]'s math. Only
 * built for engines that actually have at least one recorded sample - an engine with none simply
 * doesn't get a row, rather than a row full of fabricated zeros. */
data class BenchmarkTrendRow(
    val engineId: String,
    val displayName: String,
    val sampleCount: Int,
    val firstRealTimeFactor: Double,
    val latestRealTimeFactor: Double,
)

/** A compact summary of the persisted download-diagnostics log (issue: "download health") - counts
 * by kind plus the most recent entries, never the full unbounded log on one screen. */
data class DownloadHealthState(
    val failureCount: Int,
    val noEngineYetCount: Int,
    val recent: List<DiagnosticsEntry>,
)

/**
 * Drives the read-only Stats page (issue #130): a durable-usage dashboard combining brand new
 * cumulative counters ([UsageStatsStore] - total listening time, total words spoken, per-model play
 * counts, generation count - nothing accumulated these before) with data several other seams already
 * persist: benchmark speed history ([BenchmarkHistory]), observed peak RAM ([ResourceUsageStore]),
 * installed-model/storage totals ([ModelManager]), favorite/flagged counts ([FavoritesStore]), and
 * download failure history ([DownloadDiagnosticsLog]).
 *
 * Every section degrades to an honest empty state when nothing has been recorded yet - this class
 * never invents a number, and a genuine zero (e.g. "0 generations so far") is only ever shown when
 * the underlying store actually reports zero, never as a stand-in for "not measured".
 *
 * Constructor is intentionally narrow (each dependency named explicitly, not a whole app-wide
 * graph) so this view model stays constructible from a plain JVM test with fakes for every seam.
 */
class StatsViewModel(
    private val usageStats: UsageStatsStore,
    private val modelManager: ModelManager,
    private val resourceUsageStore: ResourceUsageStore,
    private val benchmarkHistory: BenchmarkHistory,
    private val favoritesStore: FavoritesStore,
    private val downloadDiagnosticsLog: DownloadDiagnosticsLog,
    private val deviceName: String,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        // --- Listening (issue #130 - the new durable counters) -------------------------------------
        val usage: UsageStatsSnapshot = UsageStatsSnapshot.EMPTY,
        val topPlayedModels: List<ModelPlayRow> = emptyList(),
        // --- Models & storage ------------------------------------------------------------------
        val modelCount: Int = 0,
        val totalStorageBytes: Long = 0L,
        val modelStorageRows: List<ModelStorageRow> = emptyList(),
        val favoriteVoiceCount: Int = 0,
        val favoriteModelCount: Int = 0,
        val flaggedModelCount: Int = 0,
        // --- Benchmarks --------------------------------------------------------------------------
        val benchmarkTrends: List<BenchmarkTrendRow> = emptyList(),
        // --- Downloads ---------------------------------------------------------------------------
        val downloadHealth: DownloadHealthState = DownloadHealthState(0, 0, emptyList()),
    ) {
        /** True once at least ONE generation or playback has ever been recorded - the whole
         * Listening section shows its empty-state copy instead of a wall of zeros until then. */
        val hasAnyListeningData: Boolean
            get() = usage.generationCount > 0L || usage.totalListeningMillis > 0L
    }

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads every backing seam. Call when the screen is shown - stats can change from other
     * screens (a generation, a playback, a new download) while this one isn't visible. */
    fun refresh() {
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { load() }
            mutableState.value = next.copy(loading = false)
        }
    }

    private fun load(): UiState {
        val usage = usageStats.snapshot()
        val modelUsage = modelManager.usage()
        val nameForModelId = modelUsage.associate { it.descriptor.modelId to it.descriptor.displayName }

        val topPlayed =
            usage.playCountsDescending.take(MAX_TOP_PLAYED_ROWS).map { (modelId, count) ->
                ModelPlayRow(modelId, nameForModelId[modelId] ?: modelId, count)
            }

        val storageRows =
            modelUsage.map { row ->
                ModelStorageRow(
                    modelId = row.descriptor.modelId,
                    displayName = row.descriptor.displayName,
                    sizeBytes = row.sizeBytes,
                    observedPeakRamBytes = resourceUsageStore.observedPeakRam(row.descriptor.modelId),
                )
            }

        val engineDisplayNames = modelUsage.associate { it.descriptor.engineId to it.descriptor.displayName }
        val trends =
            modelUsage.map { it.descriptor.engineId }.distinct().mapNotNull { engineId ->
                trendRowFor(engineId, engineDisplayNames[engineId] ?: engineId)
            }

        val diagnostics = downloadDiagnosticsLog.entries()
        val downloadHealth =
            DownloadHealthState(
                failureCount = diagnostics.count { it.kind == DiagnosticsKind.FAILURE },
                noEngineYetCount = diagnostics.count { it.kind == DiagnosticsKind.NO_ENGINE_YET },
                recent = diagnostics.take(MAX_RECENT_DOWNLOAD_ISSUES),
            )

        return UiState(
            loading = false,
            usage = usage,
            topPlayedModels = topPlayed,
            modelCount = modelUsage.size,
            totalStorageBytes = modelManager.totalBytes(),
            modelStorageRows = storageRows,
            favoriteVoiceCount = favoritesStore.favoriteVoices().size,
            favoriteModelCount = favoritesStore.favoriteModels().size,
            flaggedModelCount = favoritesStore.flaggedModels().size,
            benchmarkTrends = trends,
            downloadHealth = downloadHealth,
        )
    }

    // A compact first-vs-latest trend for one engine on THIS device only - benchmark numbers from a
    // different phone are not comparable (BenchmarkHistory itself keys samples by device for exactly
    // this reason). Null when this engine has no recorded sample here yet - no row, not a fake one.
    private fun trendRowFor(
        engineId: String,
        displayName: String,
    ): BenchmarkTrendRow? {
        if (deviceName.isBlank()) return null
        val history: List<BenchmarkRecord> = benchmarkHistory.history(engineId, deviceName)
        if (history.isEmpty()) return null
        return BenchmarkTrendRow(
            engineId = engineId,
            displayName = displayName,
            sampleCount = history.size,
            firstRealTimeFactor = history.first().realTimeFactor,
            latestRealTimeFactor = history.last().realTimeFactor,
        )
    }

    companion object {
        private const val MAX_TOP_PLAYED_ROWS = 10
        private const val MAX_RECENT_DOWNLOAD_ISSUES = 5
    }
}
