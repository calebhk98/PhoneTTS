package com.phonetts.app.store

import com.phonetts.core.store.DurableStore
import java.io.File

/**
 * The `:app` `filesDir`-backed [DurableStore] (issue #114): each named document is one small
 * `<name>.json` file inside a dedicated [baseDir] directory, so the durable stores layered on top
 * ([com.phonetts.core.store.FavoritesStore], [com.phonetts.core.store.DurableErrorLog], and the
 * tournament/benchmark persistence to come) all share one consistent, bounded, on-disk home under
 * app-private storage. This mirrors how [com.phonetts.core.download.hf.DownloadDiagnosticsLog]
 * already persists — one JSON file under `filesDir` — just generalised behind the [DurableStore]
 * seam so `:core` stays Android-free.
 *
 * Living under `filesDir` (app-private internal storage) it is unaffected by the model-storage
 * relocation the user can do on the Manage screen (issue #74/#88 note): only downloaded *weights*
 * move, never this metadata. This class deals only in [String] + [File] — no `kotlinx.serialization`
 * — because the `:app` module does not carry the serializer compiler plugin (the JSON encoding all
 * happens in the `:core` helpers).
 *
 * Every method is best-effort and fails closed, matching the [DurableStore] contract: a read error
 * yields `null`, a write/delete error is swallowed. Callers should invoke it off the main thread.
 */
class FilesDurableStore(private val baseDir: File) : DurableStore {
    override fun read(name: String): String? {
        val file = fileFor(name)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    override fun write(
        name: String,
        contents: String,
    ) {
        runCatching {
            baseDir.mkdirs()
            fileFor(name).writeText(contents)
        }
    }

    override fun delete(name: String) {
        runCatching { fileFor(name).delete() }
    }

    // Confine the document to [baseDir]: only the file's own name is used, never any path the caller
    // might have smuggled into [name], so a document name can never escape the store's directory.
    private fun fileFor(name: String): File = File(baseDir, "${File(name).name}.json")

    companion object {
        /** Conventional sub-directory of `filesDir` these durable documents live in. */
        const val DIRECTORY_NAME = "durable"
    }
}
