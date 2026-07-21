package com.phonetts.app.benchmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.metrics.BenchmarkRecord
import com.phonetts.core.metrics.RtfEstimator
import com.phonetts.core.metrics.RtfResult
import com.phonetts.core.metrics.ThermalRegressionDetector
import com.phonetts.core.model.ModelDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Benchmarks screen: measure every downloaded model's real generation speed on the same
 * phrase and lay the numbers side by side. Reuses the metrics seam ([RtfEstimator]) that measures a
 * real `synthesize()` run — never a guessed RTF — and the one-engine-at-a-time [EngineManager], so a
 * benchmark run is just the sample loop timed. Model facts (voice, params, sample rate, and now the
 * peak-RAM estimate) all come from each [ModelDescriptor]; nothing here is hardcoded per model.
 *
 * Two power-user features ride along:
 *  - issue #38: each row shows the estimated peak RAM, and a real observed peak is recorded after the
 *    run to refine future estimates.
 *  - issue #39: each run is appended to a persisted history, and an off-by-default toggle
 *    ([toggleHistory]) reveals a thermal-regression note ("~Nx slower than last time"). It is OFF by
 *    default so casual users aren't confused by normal run-to-run variance.
 */
class BenchmarkViewModel(private val graph: AppGraph) : ViewModel() {
    /** One model's measured result (or a failure), ready to render in the table and copy out. */
    data class Row(
        val displayName: String,
        val ok: Boolean,
        val realTimeFactor: Double = 0.0,
        val audioSeconds: Double = 0.0,
        val wallSeconds: Double = 0.0,
        /** Estimated peak RAM in bytes (null = unknown), shown alongside speed (issue #38). */
        val peakRamBytes: Long? = null,
        /** A thermal-regression note vs the last benchmark, shown only when history is on (issue #39). */
        val regressionNote: String? = null,
        val error: String? = null,
    )

    data class UiState(
        val running: Boolean = false,
        val status: String? = null,
        val rows: List<Row> = emptyList(),
        /** OFF by default: the power-user history/regression view is hidden until the user opts in. */
        val showHistory: Boolean = false,
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    /** Reveal or hide the persisted-history / thermal-regression view (issue #39). Off by default. */
    fun toggleHistory() {
        mutableState.update { it.copy(showHistory = !it.showHistory) }
    }

    /**
     * Benchmark every downloaded model in turn, updating the table as each finishes. Loads one
     * engine at a time (EngineManager.switchTo unloads the previous — one engine in memory), times a
     * real synthesis of [BENCH_PHRASE], and records the measured RTF. Best-effort: a model that fails
     * to load/generate becomes a failed row, not a fatal error.
     */
    fun run() {
        if (mutableState.value.running) return
        val models = graph.catalog.list()
        if (models.isEmpty()) {
            mutableState.update { it.copy(status = "No models to benchmark — download one first.") }
            return
        }
        mutableState.update { it.copy(running = true, rows = emptyList(), status = "Benchmarking…") }
        viewModelScope.launch {
            val rows = mutableListOf<Row>()
            models.forEachIndexed { index, descriptor ->
                mutableState.update {
                    it.copy(status = "Benchmarking ${index + 1}/${models.size}: ${descriptor.displayName}…")
                }
                rows += benchmarkOne(descriptor)
                mutableState.update { it.copy(rows = rows.toList()) }
            }
            val succeeded = rows.count { it.ok }
            mutableState.update { it.copy(running = false, status = "Done — $succeeded/${models.size} succeeded") }
        }
    }

    private suspend fun benchmarkOne(descriptor: ModelDescriptor): Row {
        val estimatedRam = graph.resourceUsageStore.peakRamEstimate(descriptor)
        return runCatching {
            graph.engineManager.switchTo(descriptor.engineId, descriptor)
            val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
            val params = SynthesisParams(descriptor.parameters.associate { it.id to it.default })
            RtfEstimator.estimate(engine, descriptor.defaultVoiceId, params, BENCH_PHRASE, descriptor.sampleRate)
        }.fold(
            onSuccess = { result -> successRow(descriptor, result) },
            onFailure = { e -> failureRow(descriptor, estimatedRam, e) },
        )
    }

    private fun successRow(
        descriptor: ModelDescriptor,
        result: RtfResult,
    ): Row {
        // Refine the RAM estimate from a real observed footprint now that the model has loaded + run.
        val observed = graph.processMemoryBytes()
        if (observed > 0L) graph.resourceUsageStore.recordPeakRam(descriptor.modelId, observed)

        return Row(
            displayName = descriptor.displayName,
            ok = true,
            realTimeFactor = result.realTimeFactor,
            audioSeconds = result.audioSecondsProduced,
            wallSeconds = result.wallClockElapsedSeconds,
            peakRamBytes = graph.resourceUsageStore.peakRamEstimate(descriptor),
            regressionNote = recordAndDetect(descriptor, result.realTimeFactor),
        )
    }

    private fun failureRow(
        descriptor: ModelDescriptor,
        estimatedRam: Long?,
        error: Throwable,
    ): Row = Row(displayName = descriptor.displayName, ok = false, peakRamBytes = estimatedRam, error = error.message)

    // Append this run to the persisted history and flag if it is much slower than the last (issue #39).
    private fun recordAndDetect(
        descriptor: ModelDescriptor,
        rtf: Double,
    ): String? {
        val device = graph.deviceName
        graph.benchmarkHistory.record(BenchmarkRecord(descriptor.engineId, device, System.currentTimeMillis(), rtf))
        val history = graph.benchmarkHistory.history(descriptor.engineId, device)
        val regression = ThermalRegressionDetector.detect(history) ?: return null
        return "~%.1fx slower than your last benchmark — likely thermal throttling".format(regression.slowdownRatio)
    }

    /** The results as a Markdown table, for the "Copy results" button (paste into an issue/notes). */
    fun resultsAsMarkdown(): String {
        val header =
            "| Model | RTF (×real-time) | Audio (s) | Wall (s) | Est. RAM |\n" +
                "|---|---|---|---|---|"
        val body = mutableState.value.rows.joinToString("\n", transform = ::markdownRow)
        return "$header\n$body\n\n_Lower RTF is faster; below 1.0× means faster than real-time. Phrase: \"$BENCH_PHRASE\"._"
    }

    private fun markdownRow(row: Row): String {
        if (!row.ok) return "| ${row.displayName} | failed | — | — | ${ramText(row.peakRamBytes)} |"
        val speed = "${fmt(row.realTimeFactor)} | ${fmt(row.audioSeconds)} | ${fmt(row.wallSeconds)}"
        return "| ${row.displayName} | $speed | ${ramText(row.peakRamBytes)} |"
    }

    private fun fmt(value: Double): String = "%.2f".format(value)

    private fun ramText(bytes: Long?): String = bytes?.let { "~${it / BYTES_PER_MEBIBYTE} MB" } ?: "unknown"

    companion object {
        const val BENCH_PHRASE = "The quick brown fox jumps over the lazy dog."
        private const val BYTES_PER_MEBIBYTE = 1024L * 1024L
    }
}
