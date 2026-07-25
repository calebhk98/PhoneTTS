package com.phonetts.app.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonetts.core.prefs.DocumentBookmark
import com.phonetts.core.prefs.DocumentMeta
import com.phonetts.core.prefs.LibraryDocument
import com.phonetts.core.prefs.LibrarySort
import java.text.DateFormat
import java.util.Date

/**
 * The reading library (issue #19-5): every document saved via [ReadingLibraryViewModel.saveCurrentDocument]
 * (from here or the main screen), openable back into the reader. Opening a document that has a saved
 * resume position ([ReadingLibraryViewModel.Row.hasResumePoint]) asks "resume, or start over?" first
 * - [onOpen]'s second parameter carries the answer, and the caller (AppNav) turns it into the SAME
 * [com.phonetts.app.ui.TtsViewModel.resumeFromSaved] the main screen's own resume button uses; no
 * second resume mechanism is introduced here.
 *
 * Issue #139: a search field and sort control (mirroring the pattern
 * [com.phonetts.app.manage.ModelManagementScreen] already uses for its own name filter/sort dropdown)
 * wire the existing [ReadingLibraryViewModel.setQuery]/[ReadingLibraryViewModel.setSort] - previously
 * built in the ViewModel with nothing in this screen to trigger them. Rows render from
 * [DocumentMeta] alone (title/saved-at/length - no text read); only tapping Open loads one document's
 * full text via [ReadingLibraryViewModel.open], so listing/searching/sorting never pays to read
 * every saved book.
 *
 * Issue #150 (named bookmarks): each row's "Bookmarks" button opens [BookmarksDialog] to add/rename/
 * delete bookmarks and jump straight to one. [onJumpToBookmark] defaults to a no-op because wiring a
 * real jump requires [com.phonetts.app.ui.TtsViewModel.seekToSentence], which is only reachable from
 * `MainActivity.kt` - outside this session's owned files (see the mission report for the exact
 * one-line patch: load the document via [ReadingLibraryViewModel.open] then call `setText` +
 * `seekToSentence(bookmark.sentenceIndex)` on the shared `TtsViewModel`, exactly like [onOpen]
 * already does for a plain open). The default keeps this screen compiling and testable in isolation
 * until that patch lands, rather than requiring every existing caller to change at once.
 */
@Composable
fun ReadingLibraryScreen(
    viewModel: ReadingLibraryViewModel,
    onOpen: (LibraryDocument, resume: Boolean) -> Unit,
    onJumpToBookmark: (LibraryDocument, DocumentBookmark) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsState()
    var pendingResumeId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = viewModel::saveCurrentDocument,
            enabled = viewModel.canSaveCurrent,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save current document") }
        state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        LibrarySearchControls(
            query = state.query,
            onQuery = viewModel::setQuery,
            sort = state.sort,
            onSort = viewModel::setSort,
        )

        if (state.rows.isEmpty()) {
            Text(
                if (state.query.isBlank()) {
                    "No saved documents yet. Write or import some text, then \"Save current document\"."
                } else {
                    "No saved documents match \"${state.query}\"."
                },
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.rows, key = { it.meta.id }) { row ->
                DocumentRow(
                    row = row,
                    onOpen = {
                        if (row.hasResumePoint) {
                            pendingResumeId = row.meta.id
                        } else {
                            viewModel.open(row.meta.id)?.let { onOpen(it, false) }
                        }
                    },
                    onRename = { viewModel.startRename(row.meta.id) },
                    onDelete = { viewModel.delete(row.meta.id) },
                    onBookmarks = { viewModel.openBookmarks(row.meta.id) },
                )
                HorizontalDivider()
            }
        }
    }

    state.renamingId?.let { id ->
        val current = state.rows.firstOrNull { it.meta.id == id }?.meta
        if (current != null) {
            RenameDialog(current, onRename = { viewModel.rename(id, it) }, onDismiss = viewModel::cancelRename)
        }
    }

    val pendingResumeMeta = state.rows.firstOrNull { it.meta.id == pendingResumeId }?.meta
    pendingResumeMeta?.let { meta ->
        ResumeChoiceDialog(
            documentTitle = meta.title,
            onResume = { viewModel.open(meta.id)?.let { onOpen(it, true) }; pendingResumeId = null },
            onStartOver = { viewModel.open(meta.id)?.let { onOpen(it, false) }; pendingResumeId = null },
            onDismiss = { pendingResumeId = null },
        )
    }

    state.bookmarksOpenFor?.let { documentId ->
        val meta = state.rows.firstOrNull { it.meta.id == documentId }?.meta
        if (meta != null) {
            BookmarksDialog(
                documentTitle = meta.title,
                bookmarks = state.bookmarks,
                onAdd = { label, sentenceIndex -> viewModel.addBookmark(documentId, label, sentenceIndex) },
                onRename = { id, newLabel -> viewModel.renameBookmark(documentId, id, newLabel) },
                onDelete = { id -> viewModel.deleteBookmark(documentId, id) },
                onJump = { bookmark -> viewModel.open(documentId)?.let { onJumpToBookmark(it, bookmark) } },
                onDismiss = viewModel::closeBookmarks,
            )
        }
    }
}

/** Search field + sort dropdown (issue #139) - same shape as [com.phonetts.app.manage.ModelManagementScreen]'s
 * `ManageControls`: a full-width [OutlinedTextField] for the query, an [OutlinedButton]-backed
 * [DropdownMenu] for the sort choice, so the two library-ish screens in this app feel the same. */
@Composable
private fun LibrarySearchControls(
    query: String,
    onQuery: (String) -> Unit,
    sort: LibrarySort,
    onSort: (LibrarySort) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Search title or text") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LibrarySortMenu(sort, onSort)
    }
}

@Composable
private fun LibrarySortMenu(
    selected: LibrarySort,
    onSelect: (LibrarySort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Sort: ${sortLabel(selected)}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(sortLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

private fun sortLabel(sort: LibrarySort): String =
    when (sort) {
        LibrarySort.RECENT -> "Most recent"
        LibrarySort.OLDEST -> "Oldest first"
        LibrarySort.TITLE -> "Title (A-Z)"
        LibrarySort.LONGEST -> "Longest first"
    }

@Composable
private fun DocumentRow(
    row: ReadingLibraryViewModel.Row,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onBookmarks: () -> Unit,
) {
    val meta = row.meta
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // mergeDescendants = true (issue #141 rule 3): the title/preview/saved-at/resume-point lines
        // announce as one TalkBack stop; Open/Rename/Delete/Bookmarks stay separate, individually
        // reachable nodes. Issue #139: rendered from DocumentMeta alone (title/saved-at/length) - no
        // document's text is read just to show this row, so a snippet-of-content preview is a length
        // count instead.
        Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(meta.title, fontWeight = FontWeight.Bold)
            Text(lengthLabel(meta.length), style = MaterialTheme.typography.bodySmall)
            Text(formatSavedAt(meta.savedAtMillis), style = MaterialTheme.typography.bodySmall)
            if (row.hasResumePoint) {
                Text("Has a saved resume position", style = MaterialTheme.typography.bodySmall)
            }
            if (row.bookmarkCount > 0) {
                Text(bookmarkCountLabel(row.bookmarkCount), style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onOpen) { Text("Open") }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onRename) { Text("Rename") }
                OutlinedButton(onClick = onDelete) { Text("Delete") }
            }
            OutlinedButton(onClick = onBookmarks) { Text("Bookmarks") }
        }
    }
}

/** issue #150: "1 bookmark" / "N bookmarks", singular-aware like [lengthLabel]'s neighbors elsewhere. */
private fun bookmarkCountLabel(count: Int): String = if (count == 1) "1 bookmark" else "$count bookmarks"

@Composable
private fun RenameDialog(
    meta: DocumentMeta,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(meta.id) { mutableStateOf(meta.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename document") },
        text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
        confirmButton = {
            Button(onClick = { onRename(title) }, enabled = title.isNotBlank()) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Resume, or start over?" - asked once, right when a document with a saved position is opened. */
@Composable
private fun ResumeChoiceDialog(
    documentTitle: String,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resume \"$documentTitle\"?") },
        text = { Text("This document has a saved position from a previous session.") },
        confirmButton = { Button(onClick = onResume) { Text("Resume") } },
        dismissButton = { OutlinedButton(onClick = onStartOver) { Text("Start over") } },
    )
}

/** issue #139: metadata-only rows have no text to snippet-preview, so [DocumentMeta.length] (already
 * computed and stored when the document was saved) stands in for it. */
private fun lengthLabel(characters: Int): String = "$characters characters"

private fun formatSavedAt(savedAtMillis: Long): String = DateFormat.getDateTimeInstance().format(Date(savedAtMillis))

/**
 * Manage one document's named bookmarks (issue #150): list existing ones (jump/rename/delete) and
 * add a new one. Rename opens its own small dialog ([RenameBookmarkDialog]), mirroring how the
 * document-level [RenameDialog] already works, so the two rename flows in this screen feel the same.
 */
@Composable
private fun BookmarksDialog(
    documentTitle: String,
    bookmarks: List<DocumentBookmark>,
    onAdd: (label: String, sentenceIndex: Int) -> Unit,
    onRename: (id: String, newLabel: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onJump: (DocumentBookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    var renamingId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks - $documentTitle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BookmarkList(bookmarks, onJump, onRename = { renamingId = it }, onDelete)
                HorizontalDivider()
                AddBookmarkForm(onAdd)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
    )

    val renaming = bookmarks.firstOrNull { it.id == renamingId }
    if (renaming != null) {
        RenameBookmarkDialog(
            current = renaming,
            onRename = { onRename(renaming.id, it); renamingId = null },
            onDismiss = { renamingId = null },
        )
    }
}

@Composable
private fun BookmarkList(
    bookmarks: List<DocumentBookmark>,
    onJump: (DocumentBookmark) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        Text("No bookmarks yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    bookmarks.forEach { bookmark ->
        BookmarkRow(
            bookmark = bookmark,
            onJump = { onJump(bookmark) },
            onRename = { onRename(bookmark.id) },
            onDelete = { onDelete(bookmark.id) },
        )
    }
}

@Composable
private fun BookmarkRow(
    bookmark: DocumentBookmark,
    onJump: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(bookmark.label, fontWeight = FontWeight.Bold)
            // Shown 1-based (issue #150): a reader thinks in "sentence 1, 2, 3...", never index 0.
            Text("Sentence ${bookmark.sentenceIndex + ONE_BASED}", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onJump) { Text("Jump") }
        OutlinedButton(onClick = onRename) { Text("Rename") }
        OutlinedButton(onClick = onDelete) { Text("Delete") }
    }
}

/** The "add a new bookmark" form at the bottom of [BookmarksDialog]. The sentence number field is
 * entered 1-based (matching [BookmarkRow]'s display) and converted back to the 0-based
 * [DocumentBookmark.sentenceIndex] the store expects before calling [onAdd]. */
@Composable
private fun AddBookmarkForm(onAdd: (label: String, sentenceIndex: Int) -> Unit) {
    var label by remember { mutableStateOf("") }
    var sentenceNumber by remember { mutableStateOf("") }

    Text("Add bookmark", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text("Label") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = sentenceNumber,
        onValueChange = { input -> if (input.all(Char::isDigit)) sentenceNumber = input },
        label = { Text("Sentence #") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            val sentenceIndex = (sentenceNumber.toIntOrNull() ?: ONE_BASED) - ONE_BASED
            onAdd(label, sentenceIndex.coerceAtLeast(0))
            label = ""
            sentenceNumber = ""
        },
        enabled = label.isNotBlank(),
    ) { Text("Add") }
}

@Composable
private fun RenameBookmarkDialog(
    current: DocumentBookmark,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember(current.id) { mutableStateOf(current.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename bookmark") },
        text = { OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true) },
        confirmButton = { Button(onClick = { onRename(label) }, enabled = label.isNotBlank()) { Text("Save") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Sentence numbers are shown/entered 1-based in the bookmarks UI; [DocumentBookmark.sentenceIndex]
 * itself stays 0-based (the same unit [com.phonetts.core.text.TextChunker] uses everywhere else). */
private const val ONE_BASED = 1
