package com.phonetts.app.benchmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.core.download.hf.ModelSpeedEstimate
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.metrics.BenchmarkEta
import com.phonetts.core.metrics.BenchmarkMetrics
import com.phonetts.core.metrics.BenchmarkProgress
import com.phonetts.core.metrics.BenchmarkRecord
import com.phonetts.core.metrics.RtfEstimator
import com.phonetts.core.metrics.RtfResult
import com.phonetts.core.metrics.ThermalRegressionDetector
import com.phonetts.core.metrics.WordCounter
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.registry.ModelUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * Drives the Benchmarks screen: measure every downloaded model's real generation speed on the same
 * phrase and lay the numbers side by side. Reuses the metrics seam ([RtfEstimator]) that measures a
 * real `synthesize()` run - never a guessed RTF - and the one-engine-at-a-time [EngineManager], so a
 * benchmark run is just the sample loop timed. Model facts (voice, params, sample rate, and now the
 * peak-RAM estimate) all come from each [ModelDescriptor]; nothing here is hardcoded per model.
 *
 * Two power-user features ride along:
 *  - issue #38: each row shows the estimated peak RAM, and a real observed peak is recorded after the
 *    run to refine future estimates.
 *  - issue #39: each run is appended to a persisted history, and an off-by-default toggle
 *    ([toggleHistory]) reveals a thermal-regression note ("~Nx slower than last time"). It is OFF by
 *    default so casual users aren't confused by normal run-to-run variance.
 *
 * Issue #14 extends each row with: time to first audio (TTFA), the model-load time paid before
 * synthesis even starts (timed around [com.phonetts.core.registry.EngineManager.switchTo]), and a
 * snapshot of this process's RAM footprint against the device's available RAM at run time - all
 * carried in [BenchmarkMetrics] (:core, so the derived math is unit-tested, not re-derived here).
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
        /**
         * TTFA / model-load time / RAM footprint for this run (issue #14), or null on a failed run
         * where nothing beyond the failure itself was measured. All the honesty logic (what counts as
         * "unknown" rather than a guess) lives in [BenchmarkMetrics] - this only carries the result.
         */
        val metrics: BenchmarkMetrics? = null,
    )

    /** How the results table is ordered (issue #115). Each key maps to a comparable Row field. */
    enum class SortKey(val label: String) {
        NAME("Name"),
        RTF("Speed (RTF)"),
        RAM("Est. RAM"),
    }

    /** The chosen sort key and direction (issue #115). */
    data class Sort(val key: SortKey, val ascending: Boolean) {
        companion object {
            val DEFAULT = Sort(SortKey.NAME, ascending = true)
        }
    }

    data class UiState(
        val running: Boolean = false,
        val status: String? = null,
        val rows: List<Row> = emptyList(),
        /** OFF by default: the power-user history/regression view is hidden until the user opts in. */
        val showHistory: Boolean = false,
        /** Live "elapsed / estimated-total, remaining left" for the current run (issue #116); null when idle. */
        val progress: BenchmarkProgress? = null,
        /** Sort key/direction for the results table (issue #115). */
        val sort: Sort = Sort.DEFAULT,
        /** Case-insensitive name filter for the results table (issue #115); blank shows everything. */
        val query: String = "",
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    /** Reveal or hide the persisted-history / thermal-regression view (issue #39). Off by default. */
    fun toggleHistory() {
        mutableState.update { it.copy(showHistory = !it.showHistory) }
    }

    /** Choose which column the results table is sorted by (issue #115). */
    fun setSortKey(key: SortKey) {
        mutableState.update { it.copy(sort = it.sort.copy(key = key)) }
    }

    /** Flip the sort between ascending and descending (issue #115). */
    fun toggleSortDirection() {
        mutableState.update { it.copy(sort = it.sort.copy(ascending = !it.sort.ascending)) }
    }

    /** Update the name filter applied to the results table (issue #115). */
    fun setQuery(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    /**
     * Benchmark every downloaded model in turn, updating the table as each finishes. Loads one
     * engine at a time (EngineManager.switchTo unloads the previous - one engine in memory), times a
     * real synthesis of [BENCH_PHRASE], and records the measured RTF. Best-effort: a model that fails
     * to load/generate becomes a failed row, not a fatal error.
     */
    fun run() {
        if (mutableState.value.running) return
        val models = graph.modelManager.usage()
        if (models.isEmpty()) {
            mutableState.update { it.copy(status = "No models to benchmark - download one first.") }
            return
        }
        // Issue #116: estimate each model's run time up front (prompt word count + its estimated RTF
        // + a load allowance) so the screen can show elapsed / estimated-total / predicted-remaining
        // from the very first tick. All the timing math is the pure, unit-tested [BenchmarkEta] seam.
        val wordCount = WordCounter.count(BENCH_PHRASE)
        val perModelSeconds = models.map { estimatedSeconds(wordCount, it) }
        val startMillis = System.currentTimeMillis()
        mutableState.update {
            it.copy(
                running = true,
                rows = emptyList(),
                status = "Benchmarking…",
                progress = BenchmarkEta.progress(startMillis, startMillis, perModelSeconds, completedModels = 0),
            )
        }
        viewModelScope.launch { runBenchmarks(models, perModelSeconds, startMillis) }
    }

    private suspend fun runBenchmarks(
        models: List<ModelUsage>,
        perModelSeconds: List<Double>,
        startMillis: Long,
    ) {
        // A 1s ticker advances the "elapsed / … left" readout between model completions so the time
        // keeps moving even while one long model is mid-synthesis. Cancelled the moment the run ends.
        val ticker =
            viewModelScope.launch {
                while (isActive) {
                    publishProgress(perModelSeconds, startMillis)
                    delay(PROGRESS_TICK_MILLIS)
                }
            }
        val rows = mutableListOf<Row>()
        models.forEachIndexed { index, model ->
            val descriptor = model.descriptor
            mutableState.update {
                it.copy(status = "Benchmarking ${index + 1}/${models.size}: ${descriptor.displayName}…")
            }
            rows += benchmarkOne(descriptor)
            mutableState.update { it.copy(rows = rows.toList()) }
            publishProgress(perModelSeconds, startMillis)
        }
        ticker.cancel()
        val succeeded = rows.count { it.ok }
        mutableState.update { it.copy(running = false, status = "Done - $succeeded/${models.size} succeeded") }
    }

    // Up-front estimate for one model: its estimated real-time multiple (from the existing
    // ModelSpeedEstimator, keyed off on-disk size + the model's own asset file names as precision
    // hints, the same signal InstalledModelFacts uses - no per-model literal) fed into [BenchmarkEta].
    private fun estimatedSeconds(
        wordCount: Int,
        model: ModelUsage,
    ): Double {
        val speed = ModelSpeedEstimate.from(model.sizeBytes, model.descriptor.assetPaths.keys.toList())
        return BenchmarkEta.estimateModelSeconds(wordCount, speed.realtimeMultiple)
    }

    // Recompute the live progress from the current finished-model count and the wall clock, and push
    // it into state. The clock is read here and handed to the pure helper (seam style); [BenchmarkEta]
    // never reads it itself.
    private fun publishProgress(
        perModelSeconds: List<Double>,
        startMillis: Long,
    ) {
        val completed = mutableState.value.rows.size
        val progress = BenchmarkEta.progress(startMillis, System.currentTimeMillis(), perModelSeconds, completed)
        mutableState.update { it.copy(progress = progress) }
    }

    private suspend fun benchmarkOne(descriptor: ModelDescriptor): Row {
        val estimatedRam = graph.resourceUsageStore.peakRamEstimate(descriptor)
        return runCatching {
            // Weight load (switchTo) + the synthesis drain in RtfEstimator both block; keep the whole
            // per-model benchmark off the main thread (issue #18-4b).
            withContext(Dispatchers.IO) {
                val loadStartNanos = System.nanoTime()
                graph.engineManager.switchTo(descriptor.engineId, descriptor)
                val modelLoadSeconds = (System.nanoTime() - loadStartNanos) / NANOS_PER_SECOND
                val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                val params = SynthesisParams(descriptor.parameters.associate { it.id to it.default })
                val rtf =
                    RtfEstimator.estimate(engine, descriptor.defaultVoiceId, params, BENCH_PHRASE, descriptor.sampleRate)
                modelLoadSeconds to rtf
            }
        }.fold(
            onSuccess = { (modelLoadSeconds, result) ->
                // A model can "succeed" (throw nothing) yet emit almost no audio - e.g. an Arabic
                // model handed the English bench phrase produced 0.06s of sound. That is broken
                // output, not a fast result, and at RTF near 0 it would even sort to the TOP as the
                // "fastest" model. Treat implausibly short output as a failure so it sinks to the
                // bottom with a clear reason instead of masquerading as the best result.
                if (result.isPlausibleSpeech) {
                    successRow(descriptor, modelLoadSeconds, result)
                } else {
                    implausibleOutputRow(descriptor, estimatedRam, result)
                }
            },
            onFailure = { e -> failureRow(descriptor, estimatedRam, e) },
        )
    }

    // A run that completed without error but produced far too little audio to be real speech (see
    // RtfResult.isPlausibleSpeech). Recorded as a failed row - ok=false sinks it below real results -
    // with a reason the user can act on (likely a wrong-language or otherwise mismatched model).
    private fun implausibleOutputRow(
        descriptor: ModelDescriptor,
        estimatedRam: Long?,
        result: RtfResult,
    ): Row {
        val reason =
            "produced only %.2fs of audio for %d words - likely wrong-language or broken output"
                .format(result.audioSecondsProduced, result.calibrationWordCount)
        return Row(displayName = descriptor.displayName, ok = false, peakRamBytes = estimatedRam, error = reason)
    }

    private fun successRow(
        descriptor: ModelDescriptor,
        modelLoadSeconds: Double,
        result: RtfResult,
    ): Row {
        // Snapshot process/available RAM right after this run: refines the peak-RAM estimate (issue
        // #38) and doubles as the honest "unknown unless actually read" RAM figures for issue #14.
        // DeviceInfo returns <=0 when a reading isn't available; null out rather than show a lie.
        val observedProcessBytes = graph.processMemoryBytes().takeIf { it > 0L }
        observedProcessBytes?.let { graph.resourceUsageStore.recordPeakRam(descriptor.modelId, it) }
        val availableBytes = graph.availableRamBytes().takeIf { it > 0L }

        val metrics =
            BenchmarkMetrics(
                rtf = result,
                modelLoadSeconds = modelLoadSeconds,
                processMemoryBytes = observedProcessBytes,
                availableRamBytes = availableBytes,
            )

        return Row(
            displayName = descriptor.displayName,
            ok = true,
            realTimeFactor = result.realTimeFactor,
            audioSeconds = result.audioSecondsProduced,
            wallSeconds = result.wallClockElapsedSeconds,
            peakRamBytes = graph.resourceUsageStore.peakRamEstimate(descriptor),
            regressionNote = recordAndDetect(descriptor, result.realTimeFactor),
            metrics = metrics,
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
        return "~%.1fx slower than your last benchmark - likely thermal throttling".format(regression.slowdownRatio)
    }

    /** The results as a Markdown table, for the "Copy results" button (paste into an issue/notes). */
    fun resultsAsMarkdown(): String {
        val header =
            "| Model | RTF (×real-time) | Audio (s) | Generation (s) | TTFA (s) | Load (s) | RAM used | Est. RAM |\n" +
                "|---|---|---|---|---|---|---|---|"
        val body = mutableState.value.rows.joinToString("\n", transform = ::markdownRow)
        return "$header\n$body\n\n_Lower RTF is faster; below 1.0× means faster than real-time. Phrase: \"$BENCH_PHRASE\"._"
    }

    private fun markdownRow(row: Row): String {
        val ramUsed = ramText(row.metrics?.processMemoryBytes)
        val estRam = ramText(row.peakRamBytes)
        // Surface the captured failure reason (crash message, or the implausible-output note) instead
        // of a bare "failed", so the user can tell a crash from an OOM from a wrong-language model.
        if (!row.ok) {
            val why = row.error?.let { "failed: $it" } ?: "failed"
            return "| ${row.displayName} | $why | - | - | - | - | $ramUsed | $estRam |"
        }
        val speed = "${fmt(row.realTimeFactor)} | ${fmt(row.audioSeconds)} | ${fmt(row.wallSeconds)}"
        val ttfa = optionalFmt(row.metrics?.timeToFirstAudioSeconds)
        val load = optionalFmt(row.metrics?.modelLoadSeconds)
        return "| ${row.displayName} | $speed | $ttfa | $load | $ramUsed | $estRam |"
    }

    private fun fmt(value: Double): String = "%.2f".format(value)

    private fun optionalFmt(value: Double?): String = value?.let { fmt(it) } ?: "unknown"

    private fun ramText(bytes: Long?): String = bytes?.let { "~${it / BYTES_PER_MEBIBYTE} MB" } ?: "unknown"

    companion object {
        // Multiple sentences on purpose (issue: TTFA == total Gen time): AbstractVoiceEngine emits
        // one Flow<FloatArray> chunk per TextChunker sentence (spec §8), and RtfEstimator measures
        // TTFA at the FIRST emitted chunk vs total elapsed at the LAST - both correct (see
        // RtfEstimatorTest). But a single-sentence phrase can only ever produce one chunk, so TTFA
        // and total generation time are mathematically forced to be identical no matter how correct
        // the measurement code is. Three sentences exercise the actual streaming/chunking path so the
        // benchmark table shows a real TTFA distinct from total Gen time, as issue #14 intends.
        const val BENCH_PHRASE =
            "The quick brown fox jumps over the lazy dog. " +
                "Pack my box with five dozen liquor jugs. " +
                "The five boxing wizards jump quickly."
        private const val BYTES_PER_MEBIBYTE = 1024L * 1024L
        private const val PROGRESS_TICK_MILLIS = 1_000L

        /**
         * The rows to actually show, filtered by [query] (case-insensitive name match) and ordered by
         * [sort] (issue #115). Failed rows always sink to the bottom regardless of direction - they
         * have no measured figures to rank meaningfully - so the sort only reorders successful rows.
         * Pure, so it can be called straight from the composable on the collected state.
         */
        fun visibleRows(
            rows: List<Row>,
            sort: Sort,
            query: String,
        ): List<Row> {
            val needle = query.trim()
            val filtered = rows.filter { needle.isEmpty() || it.displayName.contains(needle, ignoreCase = true) }
            val (ok, failed) = filtered.partition { it.ok }
            val sorted = ok.sortedWith(rowComparator(sort.key))
            val ordered = if (sort.ascending) sorted else sorted.reversed()
            return ordered + failed
        }

        private fun rowComparator(key: SortKey): Comparator<Row> =
            when (key) {
                SortKey.NAME -> compareBy { it.displayName.lowercase() }
                SortKey.RTF -> compareBy { it.realTimeFactor }
                SortKey.RAM -> compareBy { it.peakRamBytes ?: Long.MAX_VALUE }
            }
    }
}
