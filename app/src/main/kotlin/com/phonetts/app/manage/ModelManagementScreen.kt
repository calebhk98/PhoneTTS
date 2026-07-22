package com.phonetts.app.manage

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonetts.core.model.Origin
import com.phonetts.core.registry.ModelUsage
import com.phonetts.core.registry.UnresolvedModelUsage
import kotlin.math.ln
import kotlin.math.pow

/**
 * Lists every model currently in the catalog with its on-disk footprint and a Delete button
 * (spec §1.1.6, "removable models" finished with a UI). Deletion is fully derived from the
 * catalog too: no model is named here, so a newly registered model just shows up on next
 * [ModelManagementViewModel.refresh] with no code change (same SSOT discipline as the model
 * dropdown).
 *
 * Re-reads the catalog every time this screen is (re)entered, not just once at first creation
 * (issue #10 — the [ModelManagementViewModel] instance otherwise lives for the whole Activity and
 * would keep showing whatever was downloaded the FIRST time this screen was ever opened).
 *
 * Also lists downloaded-but-unidentified bundles (issue #8) and a storage-location picker so
 * models can be kept somewhere that survives an uninstall (issue #4/#5).
 */
@Composable
fun ModelManagementScreen(viewModel: ModelManagementViewModel) {
    val state by viewModel.state.collectAsState()

    // Fixes issue #10: this composable re-enters every time the user navigates to this screen (the
    // caller un-mounts it on the way out), so LaunchedEffect(Unit) re-fires here even though the
    // ViewModel itself is a single long-lived instance whose init{} only ran once.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Storage used: ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Device free RAM: ${formatBytes(state.availableRamBytes)}",
            style = MaterialTheme.typography.bodyMedium,
        )

        state.error?.let { Text("Error: $it") }

        StorageLocationSection(
            description = state.storageDescription,
            message = state.storageMessage,
            onFolderPicked = viewModel::chooseFolder,
            onResetToAppStorage = viewModel::resetStorageLocation,
        )

        if (state.usage.isEmpty() && state.unresolved.isEmpty()) {
            Text("No models downloaded yet.")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.usage, key = { it.descriptor.modelId }) { usage ->
                ModelUsageRow(
                    usage = usage,
                    peakRamBytes = state.peakRamByModelId[usage.descriptor.modelId],
                    freeRamBytes = state.availableRamBytes,
                    isDeleting = state.deletingId == usage.descriptor.modelId,
                    onDelete = { viewModel.delete(usage.descriptor.modelId) },
                )
                HorizontalDivider()
            }
            items(state.unresolved, key = { it.bundleId }) { unresolved ->
                UnresolvedUsageRow(
                    unresolved = unresolved,
                    isDeleting = state.deletingUnresolvedId == unresolved.bundleId,
                    onDelete = { viewModel.deleteUnresolved(unresolved.bundleId) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ModelUsageRow(
    usage: ModelUsage,
    peakRamBytes: Long?,
    freeRamBytes: Long,
    isDeleting: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(usage.descriptor.displayName, fontWeight = FontWeight.Bold)
            Text(originLabel(usage.descriptor.origin) + " · " + formatBytes(usage.sizeBytes))
            Text(ramHint(peakRamBytes, freeRamBytes), style = MaterialTheme.typography.bodySmall)
        }
        DeleteControl(isDeleting, onDelete)
    }
}

// Issue #8/bug #6: a downloaded bundle no engine claimed — shown honestly instead of vanishing as
// if it were never fetched, and worded to head off the natural (wrong) assumption that a bundle
// with no engine must mean a failed/incomplete download that needs redoing. It's already fully on
// disk (that's what [unresolved.sizeBytes] is showing); redownloading the same bytes changes
// nothing without a matching engine. Deliberately offers no "use it anyway" affordance (that would
// fake an engine, breaking rule 4's fail-closed guarantee) — Delete is the only action, so there is
// no dead-end button pretending this row can be selected.
@Composable
private fun UnresolvedUsageRow(
    unresolved: UnresolvedModelUsage,
    isDeleting: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(unresolved.bundleId, fontWeight = FontWeight.Bold)
            Text("Already downloaded (" + formatBytes(unresolved.sizeBytes) + ") · no engine can use it yet")
            Text("Redownloading won't help — " + unresolved.reason, style = MaterialTheme.typography.bodySmall)
        }
        DeleteControl(isDeleting, onDelete)
    }
}

// Inline, non-blocking hint (issue #38): shows the estimated peak RAM and, when it exceeds free RAM,
// a gentle "may not fit" note — the user can still attempt it. "unknown" when no estimate exists.
private fun ramHint(peakRamBytes: Long?, freeRamBytes: Long): String {
    if (peakRamBytes == null) return "Est. RAM: unknown"
    val base = "Est. RAM: ~${formatBytes(peakRamBytes)}"
    if (freeRamBytes in 1 until peakRamBytes) return "$base · may not fit — you can still try"
    return base
}

@Composable
private fun DeleteControl(isDeleting: Boolean, onDelete: () -> Unit) {
    if (isDeleting) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        return
    }
    OutlinedButton(onClick = onDelete) { Text("Delete") }
}

private fun originLabel(origin: Origin): String =
    when (origin) {
        Origin.BUILT_IN -> "Built-in"
        Origin.SIDELOADED -> "Sideloaded"
    }

/**
 * Storage-location picker (issue #4/#5): shows where models live now, a button to pick a folder via
 * the Storage Access Framework, and — when this device hasn't granted "All files access" yet — a
 * button to request it (needed to read/write the picked folder as a plain file, not just through
 * SAF). A folder pick that can't be used as a plain directory is refused with [message] rather than
 * silently accepted; nothing here decides that itself, all of it is [ModelManagementViewModel]'s.
 */
@Composable
private fun StorageLocationSection(
    description: String,
    message: String?,
    onFolderPicked: (Uri) -> Unit,
    onResetToAppStorage: () -> Unit,
) {
    val context = LocalContext.current
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            onFolderPicked(uri)
        }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Storage location", style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose folder…") }
            TextButton(onClick = onResetToAppStorage) { Text("Use app storage") }
        }
        if (!hasAllFilesAccess()) {
            TextButton(onClick = { context.startActivity(allFilesAccessIntent(context.packageName)) }) {
                Text("Grant \"All files access\" (needed for a picked folder)")
            }
        }
    }
    HorizontalDivider()
}

// MANAGE_EXTERNAL_STORAGE only exists from API 30 — below that there is no such gate to check, so
// a picked folder either lives in app-private/media-store-reachable space or the write test in
// StorageLocation.resolve() will already fail closed with a clear reason.
private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun allFilesAccessIntent(packageName: String): Intent =
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))

/** "1.5 GB" / "320 KB" / "512 B" style formatting for a storage-usage display. */
private fun formatBytes(bytes: Long): String {
    if (bytes < UNIT) return "$bytes B"
    val exponent = (ln(bytes.toDouble()) / ln(UNIT.toDouble())).toInt().coerceIn(1, UNIT_PREFIXES.size)
    val value = bytes / UNIT.toDouble().pow(exponent)
    val rounded = Math.round(value * ROUNDING_FACTOR) / ROUNDING_FACTOR
    return "$rounded ${UNIT_PREFIXES[exponent - 1]}B"
}

private const val UNIT = 1024L
private const val ROUNDING_FACTOR = 10.0
private val UNIT_PREFIXES = charArrayOf('K', 'M', 'G', 'T')
