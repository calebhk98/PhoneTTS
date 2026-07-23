package com.phonetts.core.metrics

import com.phonetts.core.prefs.PreferenceStore

/** One persisted benchmark sample (issue #39): which engine, on which device, when, and the measured RTF. */
data class BenchmarkRecord(
    val engineId: String,
    val device: String,
    val timestampMillis: Long,
    val realTimeFactor: Double,
) {
    init {
        require(engineId.isNotBlank()) { "engineId must not be blank" }
        require(device.isNotBlank()) { "device must not be blank" }
        require(timestampMillis >= 0L) { "timestampMillis must not be negative" }
        require(realTimeFactor >= 0.0) { "realTimeFactor must not be negative" }
    }
}

/**
 * Persists [RtfEstimator] measurements over time (issue #39), keyed by engine+device, via an
 * injected [PreferenceStore] - mirrors [com.phonetts.core.prefs.DocumentMemory]. It does NOT change
 * how [RtfEstimator] measures; it only stores the number the estimator already produced, so a later
 * run can be compared against earlier ones ([ThermalRegressionDetector]) to spot "this engine is now
 * much slower than last time" - the tell-tale of thermal throttling on a no-NPU phone.
 *
 * This whole feature is opt-in / power-user (kept OFF by default in the UI): storing history is
 * pointless unless the user turned the view on, and always-on history would confuse casual users
 * with "running 2x slower" noise. Fails closed: a corrupt/absent record reads back as an empty
 * history, and malformed lines are skipped rather than crashing the parse.
 */
class BenchmarkHistory(private val store: PreferenceStore) {
    /** Appends [record] to the saved history for its engine+device, trimming to the most recent [MAX_SAMPLES]. */
    fun record(record: BenchmarkRecord) {
        val kept = (history(record.engineId, record.device) + record).takeLast(MAX_SAMPLES)
        val serialized = kept.joinToString(RECORD_SEPARATOR, transform = ::encode)
        store.putString(key(record.engineId, record.device), serialized)
    }

    /** The saved samples for [engineId] on [device], oldest first; empty when nothing valid is stored. */
    fun history(
        engineId: String,
        device: String,
    ): List<BenchmarkRecord> {
        val raw = store.getString(key(engineId, device)) ?: return emptyList()
        return raw.split(RECORD_SEPARATOR)
            .mapNotNull { line -> decode(engineId, device, line) }
            .sortedBy { it.timestampMillis }
    }

    /** Forgets the saved history for [engineId] on [device]. A no-op if none was recorded. */
    fun clear(
        engineId: String,
        device: String,
    ) {
        store.remove(key(engineId, device))
    }

    private fun encode(record: BenchmarkRecord): String =
        "${record.timestampMillis}$FIELD_SEPARATOR${record.realTimeFactor}"

    private fun decode(
        engineId: String,
        device: String,
        line: String,
    ): BenchmarkRecord? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != FIELD_COUNT) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        val rtf = parts[1].toDoubleOrNull() ?: return null
        if (timestamp < 0L || rtf < 0.0) return null
        return BenchmarkRecord(engineId, device, timestamp, rtf)
    }

    private fun key(
        engineId: String,
        device: String,
    ) = "$KEY_PREFIX$engineId$FIELD_SEPARATOR$device"

    companion object {
        private const val KEY_PREFIX = "benchmark_history."
        private const val RECORD_SEPARATOR = "\n"
        private const val FIELD_SEPARATOR = "|"
        private const val FIELD_COUNT = 2
        private const val MAX_SAMPLES = 50
    }
}
