package com.phonetts.app.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonetts.core.prefs.LibraryDocument
import java.text.DateFormat
import java.util.Date

/**
 * The reading library (issue #19-5): every document saved via [ReadingLibraryViewModel.saveCurrentDocument]
 * (from here or the main screen), openable back into the reader. Opening a document that has a saved
 * resume position ([ReadingLibraryViewModel.Row.hasResumePoint]) asks "resume, or start over?" first
 * - [onOpen]'s second parameter carries the answer, and the caller (AppNav) turns it into the SAME
 * [com.phonetts.app.ui.TtsViewModel.resumeFromSaved] the main screen's own resume button uses; no
 * second resume mechanism is introduced here.
 */
@Composable
fun ReadingLibraryScreen(
    viewModel: ReadingLibraryViewModel,
    onOpen: (LibraryDocument, resume: Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var pendingResume by remember { mutableStateOf<LibraryDocument?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = viewModel::saveCurrentDocument,
            enabled = viewModel.canSaveCurrent,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save current document") }
        state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (state.rows.isEmpty()) {
            Text("No saved documents yet. Write or import some text, then \"Save current document\".")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.rows, key = { it.document.id }) { row ->
                DocumentRow(
                    row = row,
                    onOpen = { if (row.hasResumePoint) pendingResume = row.document else onOpen(row.document, false) },
                    onRename = { viewModel.startRename(row.document.id) },
                    onDelete = { viewModel.delete(row.document.id) },
                )
                HorizontalDivider()
            }
        }
    }

    state.renamingId?.let { id ->
        val current = state.rows.firstOrNull { it.document.id == id }?.document
        if (current != null) {
            RenameDialog(current, onRename = { viewModel.rename(id, it) }, onDismiss = viewModel::cancelRename)
        }
    }

    pendingResume?.let { document ->
        ResumeChoiceDialog(
            documentTitle = document.title,
            onResume = { onOpen(document, true); pendingResume = null },
            onStartOver = { onOpen(document, false); pendingResume = null },
            onDismiss = { pendingResume = null },
        )
    }
}

@Composable
private fun DocumentRow(
    row: ReadingLibraryViewModel.Row,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val document = row.document
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(document.title, fontWeight = FontWeight.Bold)
            Text(preview(document.text), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Text(formatSavedAt(document.savedAtMillis), style = MaterialTheme.typography.bodySmall)
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
    document: LibraryDocument,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(document.id) { mutableStateOf(document.title) }
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

private fun preview(text: String): String {
    val singleLine = text.replace('\n', ' ').trim()
    if (singleLine.length <= PREVIEW_MAX_CHARS) return singleLine
    return singleLine.take(PREVIEW_MAX_CHARS).trimEnd() + "…"
}

private fun formatSavedAt(savedAtMillis: Long): String = DateFormat.getDateTimeInstance().format(Date(savedAtMillis))

private const val PREVIEW_MAX_CHARS = 120
