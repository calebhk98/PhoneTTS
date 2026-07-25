package com.phonetts.core.prefs

import com.phonetts.core.store.DurableStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One named bookmark within a document (issue #150): a sentence-index position the user tagged
 * with their own [label]. [createdAtMillis] is caller-supplied, never read from the wall clock
 * here - matching every other `:core` seam ([com.phonetts.core.store.FlaggedModel.atMs],
 * [com.phonetts.core.store.DurableErrorLog.atMs]). [id] is caller-supplied too (e.g. a UUID minted
 * in `:app`) rather than generated here, so this store stays a pure function of its inputs and is
 * trivially unit-testable without a random source.
 */
@Serializable
data class DocumentBookmark(
    val id: String,
    val documentId: String,
    val label: String,
    val sentenceIndex: Int,
    val createdAtMillis: Long,
)

/**
 * Outcome of [DocumentBookmarksStore.add]. Hitting the per-document bound does NOT silently evict
 * an existing bookmark to make room - that would be a surprising, invisible loss of a user's own
 * named marker, exactly the "no silent no-op on a control path" rule this repo already learned the
 * hard way. [BoundReached] lets the caller tell the user to delete one first instead.
 */
sealed interface BookmarkAddResult {
    data class Added(val bookmark: DocumentBookmark) : BookmarkAddResult

    data object BoundReached : BookmarkAddResult
}

/** The whole persisted bookmarks document. One small JSON blob; see [DocumentBookmarksStore]. */
@Serializable
private data class BookmarksState(val entries: List<DocumentBookmark> = emptyList())

/**
 * Multiple named bookmarks per document (issue #150), over an injected [DurableStore] - mirrors
 * [com.phonetts.core.store.FavoritesStore]'s pattern (one small JSON document, loaded once at
 * construction, every mutation writes the whole thing back) rather than a new SharedPreferences
 * namespace. Keyed by the SHA-256 [com.phonetts.core.text.DocumentId] the caller supplies - like
 * [DocumentMemory], this class never computes or validates a document id itself.
 *
 * Bounded PER DOCUMENT at [maxPerDocument], not globally: a single book-length library entry with
 * many bookmarks must never crowd out every OTHER document's allowance. Fails closed: an
 * unreadable/corrupt document loads as empty rather than throwing (same as [FavoritesStore]).
 */
class DocumentBookmarksStore(
    private val store: DurableStore,
    private val documentName: String = DOCUMENT_NAME,
    private val maxPerDocument: Int = DEFAULT_MAX_PER_DOCUMENT,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var state: BookmarksState = load()

    /**
     * Every bookmark saved for [documentId], in DOCUMENT order - by [DocumentBookmark.sentenceIndex]
     * ascending (ties broken by creation order) - the order a reader would actually encounter them
     * in, rather than the order they happened to be created in.
     */
    fun bookmarksFor(documentId: String): List<DocumentBookmark> =
        state.entries
            .filter { it.documentId == documentId }
            .sortedWith(compareBy(DocumentBookmark::sentenceIndex, DocumentBookmark::createdAtMillis))

    /**
     * Adds a new bookmark at [sentenceIndex] labeled [label] for [documentId], identified by the
     * caller-supplied [id] and timestamped [createdAtMillis] (see class kdoc for why neither is
     * generated here). Returns [BookmarkAddResult.BoundReached] without writing anything once
     * [documentId] already holds [maxPerDocument] bookmarks - see [BookmarkAddResult]'s kdoc for why
     * that is a returned outcome rather than a silent eviction.
     */
    fun add(
        documentId: String,
        label: String,
        sentenceIndex: Int,
        createdAtMillis: Long,
        id: String,
    ): BookmarkAddResult {
        if (state.entries.count { it.documentId == documentId } >= maxPerDocument) {
            return BookmarkAddResult.BoundReached
        }
        val bookmark = DocumentBookmark(id, documentId, label, sentenceIndex, createdAtMillis)
        persist(state.copy(entries = state.entries + bookmark))
        return BookmarkAddResult.Added(bookmark)
    }

    /** Renames the bookmark [id] to [newLabel]. A no-op if [id] isn't found. */
    fun rename(
        id: String,
        newLabel: String,
    ) {
        persist(state.copy(entries = state.entries.map { if (it.id == id) it.copy(label = newLabel) else it }))
    }

    /** Deletes the bookmark [id]. A no-op if it wasn't there. */
    fun delete(id: String) {
        persist(state.copy(entries = state.entries.filterNot { it.id == id }))
    }

    /** Forgets every bookmark saved for [documentId] - call when the document itself is deleted
     * from the library, mirroring [DocumentMemory.forget]'s cleanup. A no-op if it had none. */
    fun forgetDocument(documentId: String) {
        persist(state.copy(entries = state.entries.filterNot { it.documentId == documentId }))
    }

    private fun persist(next: BookmarksState) {
        state = next
        runCatching { store.write(documentName, json.encodeToString(BookmarksState.serializer(), next)) }
    }

    private fun load(): BookmarksState {
        val raw = store.read(documentName) ?: return BookmarksState()
        return runCatching { json.decodeFromString(BookmarksState.serializer(), raw) }.getOrDefault(BookmarksState())
    }

    companion object {
        /** The [DurableStore] document name (one file) bookmarks are persisted under. */
        const val DOCUMENT_NAME = "document_bookmarks"

        /** Per-document cap so one long book's bookmarks can never crowd out another's. */
        const val DEFAULT_MAX_PER_DOCUMENT = 50
    }
}
