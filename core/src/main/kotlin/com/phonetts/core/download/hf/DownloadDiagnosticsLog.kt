package com.phonetts.core.download.hf

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** What kind of thing one [DiagnosticsEntry] records. */
enum class DiagnosticsKind {
    /** A real network/IO download failure - the bytes never landed. */
    FAILURE,

    /** The bytes landed and were imported, but no registered engine could identify the bundle
     * (spec rule 4 - a fail-closed `null`, not a guess). Not a failure: the model is on disk and
     * usable once a matching engine is registered. */
    NO_ENGINE_YET,
}

/**
 * One row in the persistent Browse download diagnostics log. [atMs] is supplied by the caller -
 * this class does not read the wall clock itself, so it stays trivially testable with a fake time
 * source, matching every other :core seam.
 */
@Serializable
data class DiagnosticsEntry(
    val atMs: Long,
    val modelId: String,
    val kind: DiagnosticsKind,
    val detail: String,
)

/**
 * A small **persistent** (survives process death / app restart - unlike a session-only error
 * banner) log of Hugging Face download/import outcomes worth tracking over time: real failures
 * (naming the file + reason - never a bare URL) and "downloaded, but no engine claims it yet"
 * imports, so the user can see which engine is worth adding next.
 *
 * Backed by one small JSON file, bounded to [MAX_ENTRIES] rows. Lives in `:core` (not `:app`) even
 * though only `:app`'s `HfDownloader`/Browse screen use it today, because it needs nothing beyond a
 * plain [File] - no Android `Context` - and this module already carries the `kotlinx.serialization`
 * compiler plugin `:app` does not. Every method does its own (small, bounded) file I/O and is meant
 * to be called from a background dispatcher, same as the download work itself.
 */
class DownloadDiagnosticsLog(private val storeFile: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun record(entry: DiagnosticsEntry) {
        val updated = (listOf(entry) + readAll()).take(MAX_ENTRIES)
        writeAll(updated)
    }

    @Synchronized
    fun entries(): List<DiagnosticsEntry> = readAll()

    @Synchronized
    fun clear() {
        runCatching { storeFile.delete() }
    }

    private fun readAll(): List<DiagnosticsEntry> {
        if (!storeFile.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(DiagnosticsEntry.serializer()), storeFile.readText())
        }.getOrDefault(emptyList())
    }

    private fun writeAll(entries: List<DiagnosticsEntry>) {
        runCatching {
            storeFile.parentFile?.mkdirs()
            storeFile.writeText(json.encodeToString(ListSerializer(DiagnosticsEntry.serializer()), entries))
        }
    }

    companion object {
        private const val MAX_ENTRIES = 100
    }
}
