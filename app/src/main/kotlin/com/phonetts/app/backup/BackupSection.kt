package com.phonetts.app.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonetts.core.backup.BackupDecodeResult
import com.phonetts.core.backup.BackupDocument
import com.phonetts.core.backup.RestoreMode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A file just decoded successfully, awaiting the user's merge-vs-replace choice before anything is written. */
private data class PendingImport(val document: BackupDocument, val preview: String)

/**
 * Settings' "Backup" section (issue #149): fully local, cloud-free export/import of everything this
 * app persists EXCEPT model weights - the honest substitute for sync in an app whose whole
 * differentiator is "nothing leaves your phone, no account, no cloud". Until this, an uninstall or a
 * phone swap destroyed the reading library/favorites/settings/stats with no recovery path at all
 * (`allowBackup="false"` in the manifest, by design, since Android's own auto-backup would be a
 * silent, unreviewed exception to that promise).
 *
 * Self-contained: builds its own stores via [BackupStoreFactory] straight from [LocalContext] rather
 * than taking a dependency-graph parameter, so adding this section needed no change to how
 * `MainActivity` wires `SettingsScreen` (see [BackupStoreFactory]'s kdoc for the one thing a future
 * wiring change could simplify). A replace-style restore always confirms first via
 * [RestoreModeDialog], which also states plainly that model weights are never included.
 */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch { statusMessage = BackupIo.exportTo(context, uri) }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                when (val outcome = BackupIo.readAndDecode(context, uri)) {
                    is BackupDecodeResult.Success -> pendingImport = PendingImport(outcome.document, describe(outcome.document))
                    is BackupDecodeResult.Corrupt -> statusMessage = "Couldn't read that file: ${outcome.reason}"
                    is BackupDecodeResult.UnsupportedVersion -> statusMessage = versionRefusedMessage(outcome.fileVersion)
                }
            }
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Backup", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Save your reading library, favorites, blended voices, and settings to a file you " +
                    "control, or restore from one - fully local, nothing is uploaded. " +
                    BackupDocument.WEIGHTS_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch(defaultBackupFileName()) }) { Text("Export") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("Import") }
            }
            statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    pendingImport?.let { pending ->
        RestoreModeDialog(
            preview = pending.preview,
            onDismiss = { pendingImport = null },
            onConfirm = { mode ->
                pendingImport = null
                scope.launch { statusMessage = BackupIo.applyImport(context, pending.document, mode) }
            },
        )
    }
}

@Composable
private fun RestoreModeDialog(
    preview: String,
    onDismiss: () -> Unit,
    onConfirm: (RestoreMode) -> Unit,
) {
    var mode by remember { mutableStateOf(RestoreMode.MERGE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore backup?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(preview, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                RestoreModeOption(
                    "Merge (safer)",
                    "Add what's in the file; keep everything already on this device.",
                    selected = mode == RestoreMode.MERGE,
                    onSelect = { mode = RestoreMode.MERGE },
                )
                RestoreModeOption(
                    "Replace",
                    "Make this device match the file exactly - anything not in the file is deleted.",
                    selected = mode == RestoreMode.REPLACE,
                    onSelect = { mode = RestoreMode.REPLACE },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(mode) }) { Text(if (mode == RestoreMode.REPLACE) "Replace" else "Merge") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RestoreModeOption(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(label)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun describe(document: BackupDocument): String {
    val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(document.exportedAtMillis))
    val favoriteCount = document.favorites.voices.size + document.favorites.models.size
    return "Exported $exportedAt - ${document.library.size} document(s), $favoriteCount favorite(s), " +
        "${document.blendedVoices.size} blended voice(s), ${document.benchmarkHistory.size} benchmark sample(s). " +
        document.notice
}

private fun versionRefusedMessage(fileVersion: Int): String =
    "That backup was made by a different app version (schema $fileVersion) this build doesn't support - " +
        "nothing was imported."

private fun defaultBackupFileName(): String =
    "phonetts-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())}.json"
