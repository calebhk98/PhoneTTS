package com.phonetts.core.store

/**
 * Minimal named-document persistence seam for durable app state that must survive process death
 * and relaunch (tournament results, benchmark history, error/diagnostics logs, favorites - issue
 * #114). Mirrors [com.phonetts.core.prefs.PreferenceStore] / [com.phonetts.core.resolver.OverrideStore]:
 * `:core` defines the interface (plus a plain in-memory double for tests) and `:app` supplies a
 * thin `filesDir`-backed implementation.
 *
 * A "document" is one small, self-contained blob of UTF-8 text addressed by [name] (the concrete
 * store maps that to one file). This interface intentionally knows nothing about JSON, bounding, or
 * the shape of the data - those are the job of the pure helpers layered on top ([JsonListStore],
 * [FavoritesStore], [DurableErrorLog]), which is what keeps them unit-testable on a plain JVM with
 * an [InMemoryDurableStore] and no Android at all.
 *
 * Every method fails closed: a read that can't find or can't parse its backing file returns `null`,
 * and a write/delete that hits an IO error is swallowed rather than thrown, exactly like
 * [com.phonetts.core.download.hf.DownloadDiagnosticsLog]. Implementations are expected to be safe to
 * call from a background dispatcher.
 */
interface DurableStore {
    /** The stored text for [name], or `null` when nothing is stored (or it could not be read). */
    fun read(name: String): String?

    /** Persists [contents] under [name], replacing any previous value. Best-effort; never throws. */
    fun write(
        name: String,
        contents: String,
    )

    /** Forgets whatever was stored under [name]. A no-op when nothing was stored. */
    fun delete(name: String)
}

/**
 * A non-persistent [DurableStore] backed by an in-memory map - the plain-JVM test double that lets
 * every store layered on top ([JsonListStore], [FavoritesStore], [DurableErrorLog]) be exercised
 * without touching the filesystem or Android.
 */
class InMemoryDurableStore : DurableStore {
    private val documents = mutableMapOf<String, String>()

    override fun read(name: String): String? = documents[name]

    override fun write(
        name: String,
        contents: String,
    ) {
        documents[name] = contents
    }

    override fun delete(name: String) {
        documents.remove(name)
    }
}
