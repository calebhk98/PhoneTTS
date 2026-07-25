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
        store.putStringSet(
            INDEX_KEY,
            allKeys().map {
                    (e, d) ->
                indexEntry(e, d)
            }.toSet() + indexEntry(record.engineId, record.device),
        )
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

    /**
     * Every `(engineId, device)` pair that currently has at least one saved sample, in no particular
     * order. Backed by a small separate index (updated alongside [record]/[replaceHistory]/[clear])
     * so a caller that doesn't already know which engine+device pairs to ask about - e.g. a full
     * backup export (issue #149) - can enumerate every saved history without [PreferenceStore]
     * gaining Android-style key-listing.
     */
    fun allKeys(): List<Pair<String, String>> =
        store.getStringSet(INDEX_KEY).mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size != FIELD_COUNT) return@mapNotNull null
            parts[0] to parts[1]
        }

    /**
     * Wholesale-replaces the saved history for [engineId] on [device] with [records] (only the ones
     * that actually match [engineId]/[device] are kept), sorted oldest-first and trimmed to the most
     * recent [MAX_SAMPLES] - the same invariant [record] maintains one append at a time. Used by
     * backup restore (issue #149), where a whole saved history is being written back at once rather
     * than incrementally measured; every other caller should keep using [record]. An empty [records]
     * behaves like [clear].
     */
    fun replaceHistory(
        engineId: String,
        device: String,
        records: List<BenchmarkRecord>,
    ) {
        val matching = records.filter { it.engineId == engineId && it.device == device }
        if (matching.isEmpty()) {
            clear(engineId, device)
            return
        }
        val serialized =
            matching.sortedBy {
                it.timestampMillis
            }.takeLast(MAX_SAMPLES).joinToString(RECORD_SEPARATOR, transform = ::encode)
        store.putString(key(engineId, device), serialized)
        store.putStringSet(
            INDEX_KEY,
            allKeys().map {
                    (e, d) ->
                indexEntry(e, d)
            }.toSet() + indexEntry(engineId, device),
        )
    }

    /** Forgets the saved history for [engineId] on [device]. A no-op if none was recorded. */
    fun clear(
        engineId: String,
        device: String,
    ) {
        store.remove(key(engineId, device))
        store.putStringSet(
            INDEX_KEY,
            allKeys().filterNot { it == engineId to device }.map {
                    (e, d) ->
                indexEntry(e, d)
            }.toSet(),
        )
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
        // `rtf < 0.0` looks like it rejects a negative value, but NaN fails EVERY relational
        // comparison (NaN < 0.0 is false, same as NaN >= 0.0), so a stored "NaN" - which
        // toDoubleOrNull happily parses - used to slip past this guard and reach
        // BenchmarkRecord's `require(realTimeFactor >= 0.0)`, which throws instead of failing
        // closed. Mirroring that exact require() condition here (rather than its negation) rejects
        // NaN the same way the constructor would, restoring this function's own contract: a
        // malformed/corrupt line is skipped, never a thrown exception (bug: Browse RTF sort crash -
        // history() feeds HfBrowseViewModel.rtfEstimates, called on every Browse recompute).
        if (timestamp < 0L || !(rtf >= 0.0)) return null
        return BenchmarkRecord(engineId, device, timestamp, rtf)
    }

    private fun key(
        engineId: String,
        device: String,
    ) = "$KEY_PREFIX$engineId$FIELD_SEPARATOR$device"

    private fun indexEntry(
        engineId: String,
        device: String,
    ) = "$engineId$FIELD_SEPARATOR$device"

    companion object {
        private const val KEY_PREFIX = "benchmark_history."
        private const val RECORD_SEPARATOR = "\n"
        private const val FIELD_SEPARATOR = "|"
        private const val FIELD_COUNT = 2
        private const val MAX_SAMPLES = 50

        // Index of every "engineId|device" pair ever recorded, so allKeys() can enumerate without
        // PreferenceStore exposing key-listing.
        private const val INDEX_KEY = "benchmark_history_index"
    }
}
