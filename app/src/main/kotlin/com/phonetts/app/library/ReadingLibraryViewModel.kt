package com.phonetts.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.core.prefs.LibraryDocument
import com.phonetts.core.text.DocumentId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 */
class ReadingLibraryViewModel(
    private val graph: AppGraph,
    private val initialText: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** One row in the list: the saved document plus whether a resume position exists for it. */
    data class Row(val document: LibraryDocument, val hasResumePoint: Boolean)

    data class UiState(
        val rows: List<Row> = emptyList(),
        val status: String? = null,
        /** Non-null while the rename dialog is open for this document id. */
        val renamingId: String? = null,
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    /** Whether the main screen had any text to save when this screen was opened. */
    val canSaveCurrent: Boolean = initialText.isNotBlank()

    init {
        refresh()
    }

    /** Re-read the saved documents - call after a save/rename/delete, or when the screen shows. */
    fun refresh() {
        val rows =
            graph.documentLibrary.list().map { document ->
                Row(document, graph.documentMemory.resume(document.id) != null)
            }
        mutableState.update { it.copy(rows = rows) }
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

    /** Deletes the document and forgets any resume position saved under the same id (tidy cleanup). */
    fun delete(id: String) {
        graph.documentLibrary.delete(id)
        graph.documentMemory.forget(id)
        refresh()
    }

    fun dismissStatus() = mutableState.update { it.copy(status = null) }
}
