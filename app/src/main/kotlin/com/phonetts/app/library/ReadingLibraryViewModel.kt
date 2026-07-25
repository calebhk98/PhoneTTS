package com.phonetts.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.app.store.FilesDurableStore
import com.phonetts.core.prefs.BookmarkAddResult
import com.phonetts.core.prefs.DocumentBookmark
import com.phonetts.core.prefs.DocumentBookmarksStore
import com.phonetts.core.prefs.DocumentMeta
import com.phonetts.core.prefs.LibraryDocument
import com.phonetts.core.prefs.LibrarySort
import com.phonetts.core.text.DocumentId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Drives the reading library screen (issue #19-5): list every saved document, open one back into
 * the main reader, rename/delete, and save the main screen's current text as a new entry. All the
 * actual persistence is [AppGraph.documentLibrary]'s job (`:core`, pure/testable) - this class only
 * holds UI state and reads the loaded-once [initialText] snapshot to save (mirrors
 * [com.phonetts.app.ui.MixVoicesViewModel] taking a snapshot [com.phonetts.core.model.ModelDescriptor]
 * rather than a live reference).
 *
 * "Offer to resume" (the task's ask) is NOT a separate mechanism here: opening a document just hands
 * its text to [com.phonetts.app.ui.TtsViewModel.setText], which already looks up
 * [AppGraph.documentMemory] by the SAME content-derived id ([DocumentId]) this library saves under -
 * so [Row.hasResumePoint] simply reports whether that existing lookup would find something, letting
 * the screen ask "resume, or start over?" before opening.
 *
 * [AppGraph.documentLibrary] (issue #139) lists/searches over small METADATA only - and this class
 * now takes advantage of that: [refresh] renders every row straight from [DocumentMeta] (id, title,
 * saved-at, length), never reading a single document's full text just to list it. Only [open] pays
 * to load text, and only for the ONE document the user actually taps - opening a book-length saved
 * document no longer means every OTHER saved document's text was read first.
 *
 * **Issue #150 (named bookmarks) and [bookmarksStore]:** the natural home for this store is
 * `AppGraph.documentBookmarks = DocumentBookmarksStore(durableStore)`, mirroring how
 * [AppGraph.favoritesStore]/`documentLibrary` are already built over the SAME shared `durableStore`.
 * That one-line addition to `AppGraph.kt` is outside this session's owned files (see the mission
 * report for the exact patch), so [bookmarksStore] defaults to a store built directly over the SAME
 * on-disk location `AppGraph`'s own `durableStore` uses (`filesDir/durable/document_bookmarks.json`
 * - see [FilesDurableStore.DIRECTORY_NAME] and [DocumentBookmarksStore.DOCUMENT_NAME]). Bookmarks
 * saved today already sit exactly where `graph.documentBookmarks` will read them from once that
 * patch lands - nothing to migrate, nothing silently lost either way.
 */
class ReadingLibraryViewModel(
    private val graph: AppGraph,
    private val initialText: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newBookmarkId: () -> String = { UUID.randomUUID().toString() },
    private val bookmarksStore: DocumentBookmarksStore =
        DocumentBookmarksStore(FilesDurableStore(File(graph.appContext.filesDir, FilesDurableStore.DIRECTORY_NAME))),
) : ViewModel() {
    /** One row in the list: a saved document's metadata (no text - see class kdoc) plus whether a
     * resume position and/or bookmarks exist for it. */
    data class Row(val meta: DocumentMeta, val hasResumePoint: Boolean, val bookmarkCount: Int)

    data class UiState(
        val rows: List<Row> = emptyList(),
        val status: String? = null,
        /** Non-null while the rename dialog is open for this document id. */
        val renamingId: String? = null,
        /** Non-null while the bookmarks sheet (issue #150) is open for this document id. */
        val bookmarksOpenFor: String? = null,
        /** The open document's bookmarks, in document order - see [DocumentBookmarksStore.bookmarksFor]. */
        val bookmarks: List<DocumentBookmark> = emptyList(),
        /** Current title/content search filter; blank means "show everything". */
        val query: String = "",
        /** Current list order. */
        val sort: LibrarySort = LibrarySort.RECENT,
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    /** Whether the main screen had any text to save when this screen was opened. */
    val canSaveCurrent: Boolean = initialText.isNotBlank()

    init {
        refresh()
    }

    /** Re-read the saved documents (honoring the current [UiState.query]/[UiState.sort]) - call after any mutation.
     * Metadata-only (see class kdoc): no document's text is read just to list it. */
    fun refresh() {
        val query = mutableState.value.query
        val sort = mutableState.value.sort
        val metas =
            if (query.isBlank()) graph.documentLibrary.list(sort) else graph.documentLibrary.search(query, sort)
        val rows =
            metas.map { meta ->
                Row(meta, graph.documentMemory.resume(meta.id) != null, bookmarksStore.bookmarksFor(meta.id).size)
            }
        mutableState.update { it.copy(rows = rows) }
    }

    /**
     * Loads one document's full text by id, for actually opening it (the one place this screen
     * still needs more than metadata). A `null` result (id vanished, or its text document is
     * missing) is surfaced via [UiState.status] rather than a silent no-op on the Open action (rule:
     * no silent no-ops on a control path) - the caller (the screen) simply doesn't navigate when
     * this returns null.
     */
    fun open(id: String): LibraryDocument? {
        val document = graph.documentLibrary.get(id)
        if (document == null) {
            mutableState.update { it.copy(status = "Couldn't open that document - it may have been removed.") }
        }
        return document
    }

    /** Updates the title/content search filter and re-runs [refresh] against it. */
    fun setQuery(query: String) {
        mutableState.update { it.copy(query = query) }
        refresh()
    }

    /** Changes the list order and re-runs [refresh] against it. */
    fun setSort(sort: LibrarySort) {
        mutableState.update { it.copy(sort = sort) }
        refresh()
    }

    /**
     * Save the main screen's current text as a new library entry (or update it in place, if it was
     * already saved - [DocumentId] is content-derived, so re-saving unchanged text is idempotent).
     * A no-op when there was no text to save.
     */
    fun saveCurrentDocument() {
        if (!canSaveCurrent) return
        viewModelScope.launch {
            graph.documentLibrary.add(id = DocumentId.of(initialText), text = initialText, savedAtMillis = nowMillis())
            refresh()
            mutableState.update { it.copy(status = "Saved to library") }
        }
    }

    fun startRename(id: String) = mutableState.update { it.copy(renamingId = id) }

    fun cancelRename() = mutableState.update { it.copy(renamingId = null) }

    fun rename(
        id: String,
        newTitle: String,
    ) {
        graph.documentLibrary.rename(id, newTitle)
        mutableState.update { it.copy(renamingId = null) }
        refresh()
    }

    /** Deletes the document and forgets any resume position/bookmarks saved under the same id (tidy
     * cleanup - mirrors [com.phonetts.core.prefs.DocumentMemory.forget]). */
    fun delete(id: String) {
        graph.documentLibrary.delete(id)
        graph.documentMemory.forget(id)
        bookmarksStore.forgetDocument(id)
        refresh()
    }

    fun dismissStatus() = mutableState.update { it.copy(status = null) }

    // --- Named bookmarks (issue #150) ---------------------------------------------------------

    /** Opens the bookmarks sheet for [documentId], loading its current bookmarks. */
    fun openBookmarks(documentId: String) =
        mutableState.update { it.copy(bookmarksOpenFor = documentId, bookmarks = bookmarksStore.bookmarksFor(documentId)) }

    /** Closes whichever document's bookmarks sheet is open. A no-op if none is. */
    fun closeBookmarks() = mutableState.update { it.copy(bookmarksOpenFor = null, bookmarks = emptyList()) }

    /**
     * Adds a bookmark labeled [label] at [sentenceIndex] within [documentId]. Blank labels and
     * negative indices are rejected outright (never silently stored malformed); hitting the
     * per-document bound ([BookmarkAddResult.BoundReached]) is surfaced via [UiState.status] instead
     * of silently discarding the request or evicting an older bookmark - rule: no silent no-op on a
     * control path.
     */
    fun addBookmark(
        documentId: String,
        label: String,
        sentenceIndex: Int,
    ) {
        val trimmed = label.trim()
        if (trimmed.isBlank() || sentenceIndex < 0) return
        when (bookmarksStore.add(documentId, trimmed, sentenceIndex, nowMillis(), newBookmarkId())) {
            is BookmarkAddResult.Added -> {
                refreshBookmarks(documentId)
                refresh()
            }
            BookmarkAddResult.BoundReached ->
                mutableState.update {
                    it.copy(status = "Bookmark limit reached for this document - delete one first.")
                }
        }
    }

    /** Renames bookmark [id] (within [documentId], for refreshing the right sheet) to [newLabel].
     * A no-op on a blank label. */
    fun renameBookmark(
        documentId: String,
        id: String,
        newLabel: String,
    ) {
        val trimmed = newLabel.trim()
        if (trimmed.isBlank()) return
        bookmarksStore.rename(id, trimmed)
        refreshBookmarks(documentId)
    }

    /** Deletes bookmark [id] from [documentId]. */
    fun deleteBookmark(
        documentId: String,
        id: String,
    ) {
        bookmarksStore.delete(id)
        refreshBookmarks(documentId)
        refresh()
    }

    private fun refreshBookmarks(documentId: String) =
        mutableState.update { it.copy(bookmarks = bookmarksStore.bookmarksFor(documentId)) }
}
