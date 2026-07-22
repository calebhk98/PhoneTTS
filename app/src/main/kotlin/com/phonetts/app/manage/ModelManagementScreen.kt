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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonetts.core.download.hf.HfEndpoints
import com.phonetts.core.model.ExportableModel
import com.phonetts.core.model.ManageModelFacts
import com.phonetts.core.model.ModelListExport
import com.phonetts.core.model.Origin
import com.phonetts.core.registry.ModelUsage
import com.phonetts.core.registry.UnresolvedModelUsage
import com.phonetts.core.resolver.SelectableEngine
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

    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Storage used: ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodyLarge)
            // Issue: no way to copy the list of downloaded models. Copies a plain-text listing
            // (name, origin, size, plus the downloaded-but-unclaimed bundles) for pasting into a
            // note or bug report. Disabled until there's actually something to copy.
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(buildModelListText(state))) },
                enabled = state.usage.isNotEmpty() || state.unresolved.isNotEmpty(),
            ) { Text("Copy list") }
        }
        Text(
            "Device free RAM: ${formatBytes(state.availableRamBytes)}",
            style = MaterialTheme.typography.bodyMedium,
        )

        // Bounded so a long error can't grow to cover the screen; full text is still selectable
        // where it's shown untruncated elsewhere.
        state.error?.let {
            Text("Error: $it", maxLines = ERROR_MAX_LINES, overflow = TextOverflow.Ellipsis)
        }

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
                    facts = state.factsByModelId[usage.descriptor.modelId],
                    isDeleting = state.deletingId == usage.descriptor.modelId,
                    onDelete = { viewModel.delete(usage.descriptor.modelId) },
                )
                HorizontalDivider()
            }
            items(state.unresolved, key = { it.bundleId }) { unresolved ->
                UnresolvedUsageRow(
                    unresolved = unresolved,
                    selectableEngines = state.selectableEngines,
                    isDeleting = state.deletingUnresolvedId == unresolved.bundleId,
                    isAssigning = state.assigningUnresolvedId == unresolved.bundleId,
                    onDelete = { viewModel.deleteUnresolved(unresolved.bundleId) },
                    onAssignEngine = { engineId -> viewModel.assignEngine(unresolved.bundleId, engineId) },
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
    facts: ManageModelFacts?,
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
            ModelFactsBlock(facts)
        }
        DeleteControl(isDeleting, onDelete)
    }
}

/**
 * The per-downloaded-model info block: an "Open on Hugging Face" link (only when a repo id could
 * be recovered — fail-closed, never a guessed URL), plus RTF and parameter-count lines, each
 * labeled "measured" or "estimated" so the user never mistakes a formula guess for a real
 * benchmark. Nothing here is a per-model literal — every value comes from [ManageModelFacts]
 * ([com.phonetts.core.model.InstalledModelFacts], CLAUDE.md rule 1). Renders nothing extra when
 * [facts] isn't available yet (e.g. mid-refresh).
 */
@Composable
private fun ModelFactsBlock(facts: ManageModelFacts?) {
    if (facts == null) return
    val uriHandler = LocalUriHandler.current

    paramCountLine(facts)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    realtimeLine(facts)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    facts.hfRepoId?.let { repoId ->
        TextButton(onClick = { uriHandler.openUri(HfEndpoints.modelPageUrl(repoId)) }) {
            Text("Open on Hugging Face ↗")
        }
    }
}

private fun paramCountLine(facts: ManageModelFacts): String? {
    val count = facts.paramCount ?: return null
    return "~${formatParamCount(count)} params (estimated)"
}

private fun realtimeLine(facts: ManageModelFacts): String? {
    val multiple = facts.realtimeMultiple ?: return null
    val label = if (facts.realtimeIsMeasured) "measured" else "estimated"
    return "~${formatRealtimeMultiple(multiple)}x real-time ($label)"
}

/** A compact "82M"/"1.2B" parameter-count label — mirrors the Browse screen's own formatter
 * ([com.phonetts.app.hf.HfBrowseScreen]'s `formatParamCount`), duplicated here rather than shared
 * since it is pure display formatting, not a model fact. */
private fun formatParamCount(count: Long): String {
    if (count <= 0L) return "?"
    val millions = count / 1_000_000.0
    if (millions >= THOUSAND) return "%.1fB".format(millions / THOUSAND)
    if (millions >= 1.0) return "%.0fM".format(millions)
    val thousands = count / 1_000.0
    if (thousands >= 1.0) return "%.0fK".format(thousands)
    return count.toString()
}

private fun formatRealtimeMultiple(multiple: Double): String = "%.1f".format(multiple)

// Issue #8/bug #6: a downloaded bundle no engine claimed — shown honestly instead of vanishing as
// if it were never fetched, and worded to head off the natural (wrong) assumption that a bundle
// with no engine must mean a failed/incomplete download that needs redoing. It's already fully on
// disk (that's what [unresolved.sizeBytes] is showing); redownloading the same bytes changes
// nothing without a matching engine.
//
// Bug #1: this used to offer no way to actually pick an engine — the resolver's documented
// fail-closed fallback ("ask the user") had nothing on this screen that could drive it, so a user
// who saw "pick an engine" had no button to press. [selectableEngines] (the SAME registered set
// autodetection already checked — SSOT, nothing named here) now backs a real picker: choosing one
// runs [onAssignEngine], which hands the bundle to that engine's forcedMatch. This is still not
// "guessing" (rule 4 stands) — it is the user, not the app, making the call.
@Composable
private fun UnresolvedUsageRow(
    unresolved: UnresolvedModelUsage,
    selectableEngines: List<SelectableEngine>,
    isDeleting: Boolean,
    isAssigning: Boolean,
    onDelete: () -> Unit,
    onAssignEngine: (engineId: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
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
        EngineAssignmentControl(selectableEngines, isAssigning, onAssignEngine)
    }
}

/**
 * The manual "pick an engine" affordance for an unresolved row (bug #1). Renders nothing when there
 * are no [selectableEngines] to offer (e.g. no engines registered at all) rather than a dead-end
 * button. Shows a spinner in place of the picker button while [isAssigning] this row.
 */
@Composable
private fun EngineAssignmentControl(
    selectableEngines: List<SelectableEngine>,
    isAssigning: Boolean,
    onAssignEngine: (engineId: String) -> Unit,
) {
    if (selectableEngines.isEmpty()) return
    if (isAssigning) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
            Text("Assigning engine…", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    var menuExpanded by remember { mutableStateOf(false) }
    Row {
        TextButton(onClick = { menuExpanded = true }) { Text("Pick an engine for it…") }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            selectableEngines.forEach { engine ->
                DropdownMenuItem(
                    text = { Text(engine.displayName) },
                    onClick = {
                        menuExpanded = false
                        onAssignEngine(engine.id)
                    },
                )
            }
        }
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

/**
 * A plain-text listing of the downloaded models for the "Copy list" button (issue #98, extending
 * the original "no way to copy the list of downloaded models"). Each resolved model's line carries
 * whatever [ManageModelFacts] knows for it (HF link, est. RAM, param count, measured/estimated
 * RTF), and each downloaded-but-unclaimed bundle carries a best-effort HF link too — the whole
 * point of listing it is letting the user click through to what didn't resolve. Headed by the
 * count and grand total size. The actual formatting is pure logic in [ModelListExport] (`:core`,
 * no Android deps) so it's unit-testable on a plain JVM; this function only maps the screen's own
 * state into that call — no model fact is re-hardcoded here (CLAUDE.md rule 1).
 */
private fun buildModelListText(state: ModelManagementViewModel.UiState): String {
    val resolved = state.usage.map { ExportableModel.from(it, state.factsByModelId[it.descriptor.modelId]) }
    return ModelListExport.build(resolved, state.unresolved)
}

/** "1.5 GB" / "320 KB" / "512 B" style formatting for a storage-usage display. */
private fun formatBytes(bytes: Long): String {
    if (bytes < UNIT) return "$bytes B"
    val exponent = (ln(bytes.toDouble()) / ln(UNIT.toDouble())).toInt().coerceIn(1, UNIT_PREFIXES.size)
    val value = bytes / UNIT.toDouble().pow(exponent)
    val rounded = Math.round(value * ROUNDING_FACTOR) / ROUNDING_FACTOR
    return "$rounded ${UNIT_PREFIXES[exponent - 1]}B"
}

private const val ERROR_MAX_LINES = 3
private const val UNIT = 1024L
private const val ROUNDING_FACTOR = 10.0
private val UNIT_PREFIXES = charArrayOf('K', 'M', 'G', 'T')
private const val THOUSAND = 1000.0
