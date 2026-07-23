package com.phonetts.core.store

import kotlinx.serialization.Serializable

/**
 * One durable error-log row (issue #88). [atMs] is caller-supplied (this class never reads the wall
 * clock, matching every other seam), [source] names where the error came from (e.g. a screen or
 * subsystem tag) so unrelated logs can share one store, and [message] is the copyable text.
 */
@Serializable
data class ErrorLogEntry(
    val atMs: Long,
    val source: String,
    val message: String,
)

/**
 * The first concrete consumer of the [JsonListStore] foundation (issue #114) and the durable half of
 * issue #88: an app-wide, bounded, newest-first error log that survives process death, unlike the
 * in-memory `HfBrowseUiState.errorLog` that dies with the process today. A later change can route the
 * Browse search/size errors (and any other subsystem's errors) into this store by calling [record]
 * with a `source` tag — this foundation deliberately does NOT touch the Browse ViewModel; it only
 * provides the durable sink for a consumer to adopt.
 *
 * Pure `:core` over an injected [DurableStore] (storage in `:app`), fail-closed like every other
 * store here: an unreadable document reads back as an empty log rather than throwing.
 */
class DurableErrorLog(
    store: DurableStore,
    documentName: String = DOCUMENT_NAME,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val backing = JsonListStore(store, documentName, ErrorLogEntry.serializer(), maxEntries)

    /** Records [entry], newest first, dropping the oldest row once the bound is reached. */
    fun record(entry: ErrorLogEntry) {
        backing.record(entry)
    }

    /** Convenience over [record] that builds the [ErrorLogEntry] from its parts. */
    fun record(
        atMs: Long,
        source: String,
        message: String,
    ) {
        record(ErrorLogEntry(atMs, source, message))
    }

    /** Every retained error, newest first. Empty when nothing is stored. */
    fun entries(): List<ErrorLogEntry> = backing.all()

    /** Forgets every recorded error. */
    fun clear() {
        backing.clear()
    }

    companion object {
        /** The [DurableStore] document name (one file) the error log is persisted under. */
        const val DOCUMENT_NAME = "error_log"

        /** Bound on retained rows, matching DownloadDiagnosticsLog's 100. */
        const val DEFAULT_MAX_ENTRIES = 100
    }
}
