package com.phonetts.core.prefs

import com.phonetts.core.store.DurableStore
import com.phonetts.core.store.JsonListStore
import kotlinx.serialization.Serializable

/**
 * Everything the reading-library LIST needs about one saved document WITHOUT reading its (possibly
 * book-length) [text][LibraryDocument.text]: a stable content-derived [id] (see
 * [com.phonetts.core.text.DocumentId]), a display [title], [savedAtMillis] for ordering, and
 * [length] (character count) so a size can be shown without paying to load the text. See
 * [DocumentLibrary] for why this is stored separately from the text (issue #139).
 */
@Serializable
data class DocumentMeta(
    val id: String,
    val title: String,
    val savedAtMillis: Long,
    val length: Int,
)

/**
 * One saved document's metadata AND full text - what's needed to actually open it back into the
 * reader ([DocumentLibrary.get]). Deliberately the same shape the pre-#139 single-StringSet design
 * used, so app code that already expects a full document (e.g. opening one from the library screen)
 * is unaffected by the storage change underneath.
 */
data class LibraryDocument(
    val id: String,
    val title: String,
    val text: String,
    val savedAtMillis: Long,
)

/** How [DocumentLibrary.list] / [DocumentLibrary.search] order their results (issue #139). */
enum class LibrarySort {
    /** Most recently saved first (the previous, and still the default, order). */
    RECENT,

    /** Least recently saved first. */
    OLDEST,

    /** Title, A-Z, case-insensitive. */
    TITLE,

    /** Longest document (by character count) first. */
    LONGEST,
}

/**
 * Persists the user's saved documents over an injected [DurableStore] (issue #139), mirroring
 * [com.phonetts.core.store.FavoritesStore] / [com.phonetts.core.store.DurableErrorLog]'s pattern:
 * `:core` holds the pure logic, `:app` supplies the `filesDir`-backed store.
 *
 * The old design packed EVERY document's full text into one SharedPreferences `StringSet` - loaded
 * wholly into memory at first touch and rewritten in full on every single mutation, a real
 * startup/OOM risk once a few book-length documents are saved. This splits each document into two
 * separately-stored parts:
 *  - a small, [maxEntries]-bounded metadata index ([DocumentMeta] - id/title/timestamp/length) that
 *    every listing/search/rename/delete operates on, never touching any document's text;
 *  - the text itself, one [DurableStore] document per id, read only by [get] (open a specific
 *    document) or [add] (save/replace one) - so listing the library never loads every book into
 *    memory, and mutating one document never rewrites another's.
 *
 * Past [maxEntries] documents, the oldest (by save order) is evicted from the index AND its text
 * document is deleted, so the library can never grow unbounded (issue #139's "no size cap").
 *
 * Migrating data saved under the old StringSet format (and the old, collidable 32-bit-hash ids -
 * issue #140) is [DocumentLibraryMigration]'s job; this class only understands the current format.
 */
class DocumentLibrary(
    private val store: DurableStore,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val index = JsonListStore(store, INDEX_DOCUMENT, DocumentMeta.serializer(), maxEntries)

    /** Every saved document's metadata (no text read), in [sort] order. */
    fun list(sort: LibrarySort = LibrarySort.RECENT): List<DocumentMeta> = sort(index.all(), sort)

    /**
     * Metadata for documents whose title OR full text contains [query] (case-insensitive), in [sort]
     * order. A blank [query] matches everything, same as [list]. Unlike [list], a query that isn't
     * satisfied by any title has to read candidate documents' text to check content - an explicit,
     * occasional action the user asked for, not something [list]/[add]/[rename]/[delete] ever do.
     */
    fun search(
        query: String,
        sort: LibrarySort = LibrarySort.RECENT,
    ): List<DocumentMeta> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return list(sort)
        val matches =
            index.all().filter { meta ->
                meta.title.contains(trimmed, ignoreCase = true) ||
                    textFor(meta.id)?.contains(trimmed, ignoreCase = true) == true
            }
        return sort(matches, sort)
    }

    /** One saved document's metadata by [id] (no text read), or null if it was never saved. */
    fun metaFor(id: String): DocumentMeta? = index.all().firstOrNull { it.id == id }

    /** One saved document's metadata AND full text by [id], or null if it was never saved (or is missing its text). */
    fun get(id: String): LibraryDocument? {
        val meta = metaFor(id) ?: return null
        val text = textFor(id) ?: return null
        return LibraryDocument(meta.id, meta.title, text, meta.savedAtMillis)
    }

    /**
     * Save [text] under [id] (adding it, or replacing any existing document with the same id - so
     * re-saving the same document, e.g. via [com.phonetts.core.text.DocumentId], updates it in
     * place rather than duplicating it). [title] defaults to a title derived from [text] when the
     * caller doesn't supply one (blank/null). Past [maxEntries] saved documents, the oldest is
     * evicted (metadata AND text).
     */
    fun add(
        id: String,
        text: String,
        savedAtMillis: Long,
        title: String? = null,
    ): LibraryDocument {
        val resolvedTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: deriveTitle(text)
        val meta = DocumentMeta(id, resolvedTitle, savedAtMillis, text.length)
        persistIndex(listOf(meta) + index.all().filterNot { it.id == id })
        store.write(textDocumentName(id), text)
        return LibraryDocument(id, resolvedTitle, text, savedAtMillis)
    }

    /** Rename a saved document. Returns its updated metadata, or null if [id] isn't saved or [newTitle] is blank. */
    fun rename(
        id: String,
        newTitle: String,
    ): DocumentMeta? {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return null
        val existing = metaFor(id) ?: return null
        val renamed = existing.copy(title = trimmed)
        index.replaceAll(index.all().map { if (it.id == id) renamed else it })
        return renamed
    }

    /** Removes the document with [id] (metadata AND text), if present. A no-op if it was never saved. */
    fun delete(id: String) {
        index.replaceAll(index.all().filterNot { it.id == id })
        store.delete(textDocumentName(id))
    }

    // Bounds the index to maxEntries, deleting the text document for anything the cap evicts so a
    // full library never leaks orphaned text blobs nobody's metadata points at anymore.
    private fun persistIndex(next: List<DocumentMeta>) {
        index.replaceAll(next.take(maxEntries))
        next.drop(maxEntries).forEach { store.delete(textDocumentName(it.id)) }
    }

    private fun textFor(id: String): String? = store.read(textDocumentName(id))

    private fun textDocumentName(id: String): String = "$TEXT_DOCUMENT_PREFIX$id"

    private fun sort(
        metas: List<DocumentMeta>,
        sort: LibrarySort,
    ): List<DocumentMeta> =
        when (sort) {
            LibrarySort.RECENT -> metas.sortedByDescending { it.savedAtMillis }
            LibrarySort.OLDEST -> metas.sortedBy { it.savedAtMillis }
            LibrarySort.TITLE -> metas.sortedBy { it.title.lowercase() }
            LibrarySort.LONGEST -> metas.sortedByDescending { it.length }
        }

    companion object {
        /** Default title when the user doesn't name a document: its first non-blank line, truncated. */
        fun deriveTitle(text: String): String {
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (firstLine.isEmpty()) return UNTITLED
            if (firstLine.length <= TITLE_MAX_CHARS) return firstLine
            return firstLine.take(TITLE_MAX_CHARS).trimEnd() + "…"
        }

        private const val UNTITLED = "Untitled"
        private const val TITLE_MAX_CHARS = 60

        /** The [DurableStore] document name (one small file) the bounded metadata index lives under. */
        const val INDEX_DOCUMENT = "library_index"

        /** Prefix for each document's own text [DurableStore] document (`"$TEXT_DOCUMENT_PREFIX$id"`). */
        const val TEXT_DOCUMENT_PREFIX = "library_text_"

        /** Hard cap on saved documents - past this, the oldest is evicted (issue #139's "no size cap"). */
        const val DEFAULT_MAX_ENTRIES = 200
    }
}
