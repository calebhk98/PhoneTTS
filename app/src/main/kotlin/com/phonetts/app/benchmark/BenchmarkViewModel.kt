package com.phonetts.app.benchmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.metrics.RtfEstimator
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
 * benchmark run is just the sample loop timed. Model facts (voice, params, sample rate) all come
 * from each [ModelDescriptor]; nothing here is hardcoded per model.
 */
class BenchmarkViewModel(private val graph: AppGraph) : ViewModel() {
    /** One model's measured result (or a failure), ready to render in the table and copy out. */
    data class Row(
        val displayName: String,
        val ok: Boolean,
        val realTimeFactor: Double = 0.0,
        val audioSeconds: Double = 0.0,
        val wallSeconds: Double = 0.0,
        val error: String? = null,
    )

    data class UiState(
        val running: Boolean = false,
        val status: String? = null,
        val rows: List<Row> = emptyList(),
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

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

    private suspend fun benchmarkOne(descriptor: ModelDescriptor): Row =
        runCatching {
            graph.engineManager.switchTo(descriptor.engineId, descriptor)
            val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
            val params = SynthesisParams(descriptor.parameters.associate { it.id to it.default })
            RtfEstimator.estimate(engine, descriptor.defaultVoiceId, params, BENCH_PHRASE, descriptor.sampleRate)
        }.fold(
            onSuccess = { result ->
                Row(
                    displayName = descriptor.displayName,
                    ok = true,
                    realTimeFactor = result.realTimeFactor,
                    audioSeconds = result.audioSecondsProduced,
                    wallSeconds = result.wallClockElapsedSeconds,
                )
            },
            onFailure = { e -> Row(displayName = descriptor.displayName, ok = false, error = e.message) },
        )

    /** The results as a Markdown table, for the "Copy results" button (paste into an issue/notes). */
    fun resultsAsMarkdown(): String {
        val header =
            "| Model | RTF (×real-time) | Audio (s) | Wall (s) |\n" +
                "|---|---|---|---|"
        val body =
            mutableState.value.rows.joinToString("\n") { row ->
                if (!row.ok) return@joinToString "| ${row.displayName} | failed | — | — |"
                "| ${row.displayName} | ${fmt(row.realTimeFactor)} | ${fmt(row.audioSeconds)} | ${fmt(row.wallSeconds)} |"
            }
        return "$header\n$body\n\n_Lower RTF is faster; below 1.0× means faster than real-time. Phrase: \"$BENCH_PHRASE\"._"
    }

    private fun fmt(value: Double): String = "%.2f".format(value)

    companion object {
        const val BENCH_PHRASE = "The quick brown fox jumps over the lazy dog."
    }
}
