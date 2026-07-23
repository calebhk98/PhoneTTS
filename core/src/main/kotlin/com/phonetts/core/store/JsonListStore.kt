package com.phonetts.core.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The reusable, pure foundation the durable features consume (issue #114): a **bounded**,
 * newest-first, JSON-backed list of [T] persisted through a [DurableStore]. It generalises exactly
 * what [com.phonetts.core.download.hf.DownloadDiagnosticsLog] does by hand (one small JSON file,
 * capped entry count, fail-closed reads) so tournament results, benchmark samples, and the durable
 * error log ([DurableErrorLog]) do not each reinvent it.
 *
 * Storage is injected as a [DurableStore], so this class stays Android-free and unit-testable on a
 * plain JVM with an [InMemoryDurableStore]; `:app` wires the same instances over a `filesDir`-backed
 * store. The compiler `kotlinx.serialization` plugin lives in `:core` (not `:app`), which is why
 * every `@Serializable` payload this generic serialises must be declared here in `:core`.
 *
 * @param store where the JSON document lives.
 * @param documentName the [DurableStore] key (one file) this list is persisted under.
 * @param serializer the element serializer for [T].
 * @param maxEntries hard cap on retained rows; a [record] past the cap drops the oldest. Must be > 0.
 */
class JsonListStore<T>(
    private val store: DurableStore,
    private val documentName: String,
    private val serializer: KSerializer<T>,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(serializer)

    /** Every retained row, newest first. Empty when nothing is stored or the document can't be parsed. */
    fun all(): List<T> {
        val raw = store.read(documentName) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    /** Prepends [entry] (making it newest), then trims to [maxEntries] by dropping the oldest rows. */
    fun record(entry: T) {
        replaceAll(listOf(entry) + all())
    }

    /** Replaces the whole list with [entries] (kept in the given order), trimmed to [maxEntries]. */
    fun replaceAll(entries: List<T>) {
        val bounded = entries.take(maxEntries)
        runCatching { store.write(documentName, json.encodeToString(listSerializer, bounded)) }
    }

    /** Forgets every row. */
    fun clear() {
        store.delete(documentName)
    }

    companion object {
        /** The same bound [com.phonetts.core.download.hf.DownloadDiagnosticsLog] uses. */
        const val DEFAULT_MAX_ENTRIES = 100
    }
}
