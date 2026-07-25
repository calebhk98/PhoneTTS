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
 */
@Composable
fun ReadingLibraryScreen(
    viewModel: ReadingLibraryViewModel,
    onOpen: (LibraryDocument, resume: Boolean) -> Unit,
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
) {
    val meta = row.meta
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // mergeDescendants = true (issue #141 rule 3): the title/preview/saved-at/resume-point lines
        // announce as one TalkBack stop; Open/Rename/Delete stay separate, individually reachable nodes.
        // Issue #139: rendered from DocumentMeta alone (title/saved-at/length) - no document's text
        // is read just to show this row, so a snippet-of-content preview is a length count instead.
        Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(meta.title, fontWeight = FontWeight.Bold)
            Text(lengthLabel(meta.length), style = MaterialTheme.typography.bodySmall)
            Text(formatSavedAt(meta.savedAtMillis), style = MaterialTheme.typography.bodySmall)
            if (row.hasResumePoint) {
                Text("Has a saved resume position", style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onOpen) { Text("Open") }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onRename) { Text("Rename") }
                OutlinedButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

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
