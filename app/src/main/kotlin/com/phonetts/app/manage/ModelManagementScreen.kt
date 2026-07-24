package com.phonetts.app.manage

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.phonetts.core.model.DeviceRamFit
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
 * (issue #10 - the [ModelManagementViewModel] instance otherwise lives for the whole Activity and
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
        // Both numbers shown so neither reads as the one that decides whether a model "fits" - that
        // decision is against total RAM only (see [ramHint]), free RAM is shown purely as context.
        Text(
            "Device RAM: ${formatBytes(state.availableRamBytes)} free of ${formatBytes(state.totalRamBytes)} total",
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

        if (state.usage.isNotEmpty()) {
            ManageControls(
                sort = state.sort,
                query = state.query,
                onSortKey = viewModel::setSortKey,
                onToggleDirection = viewModel::toggleSortDirection,
                onQuery = viewModel::setQuery,
            )
        }

        val visibleUsage =
            ModelManagementViewModel.visibleUsage(state.usage, state.factsByModelId, state.sort, state.query)

        // Re-sorting keeps stable item keys (by modelId), so a LazyColumn would otherwise preserve the
        // scroll anchor and "follow" whatever row was on top - the user changed the order to see the new
        // top, so snap back to it whenever the sort changes (issue #115). Keyed on the whole Sort so both
        // a new sort-key and a direction flip trigger it.
        val listState = rememberLazyListState()
        LaunchedEffect(state.sort) { listState.animateScrollToItem(0) }

        // Display names collide (several "KittenTTS", two "Kokoro"); the rows sharing a name get their
        // unique folder id shown underneath to tell them apart (issue #123). Computed once over the
        // whole visible set so a name is "duplicated" consistently regardless of scroll position.
        val duplicateNames =
            visibleUsage.groupingBy { it.descriptor.displayName }.eachCount().filterValues { it > 1 }.keys

        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(visibleUsage, key = { it.descriptor.modelId }) { usage ->
                ModelUsageRow(
                    usage = usage,
                    peakRamBytes = state.peakRamByModelId[usage.descriptor.modelId],
                    totalRamBytes = state.totalRamBytes,
                    facts = state.factsByModelId[usage.descriptor.modelId],
                    disambiguate = usage.descriptor.displayName in duplicateNames,
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

// Sort/filter controls for the downloaded-models list (issue #115): a name filter, a sort-key
// dropdown, and an ascending/descending toggle. Compact so it stays usable on a phone. The ordering
// itself is the pure [ModelManagementViewModel.visibleUsage]; this only reports the user's choices.
@Composable
private fun ManageControls(
    sort: ModelManagementViewModel.Sort,
    query: String,
    onSortKey: (ModelManagementViewModel.SortKey) -> Unit,
    onToggleDirection: () -> Unit,
    onQuery: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Filter by name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Both the sort key and the direction use the same outlined-button style (issue #123: they
        // were mismatched - an outlined button next to bare text).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ManageSortKeyMenu(sort.key, onSortKey)
            OutlinedButton(onClick = onToggleDirection) {
                Text(if (sort.ascending) "Ascending" else "Descending")
            }
        }
    }
}

@Composable
private fun ManageSortKeyMenu(
    selected: ModelManagementViewModel.SortKey,
    onSelect: (ModelManagementViewModel.SortKey) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Sort: ${selected.label}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ModelManagementViewModel.SortKey.values().forEach { key ->
                DropdownMenuItem(
                    text = { Text(key.label) },
                    onClick = {
                        expanded = false
                        onSelect(key)
                    },
                )
            }
        }
    }
}

/**
 * One downloaded model, as a two-line row: name, then "Origin - size - ~RAM" (issue #123 - four
 * lines of metadata per row meant only ~3 models fit a screen). A "won't fit this device" warning
 * stays always-visible (it is safety info, not a detail). Everything else - parameter count, RTF,
 * the Open-on-Hugging-Face link - moves behind the row's overflow menu / an expandable Details
 * section, and Delete (the one destructive action) lives in that menu in the error color behind a
 * confirmation dialog rather than as a same-colored button that is the easiest thing to hit.
 */
@Composable
private fun ModelUsageRow(
    usage: ModelUsage,
    peakRamBytes: Long?,
    totalRamBytes: Long,
    facts: ManageModelFacts?,
    disambiguate: Boolean,
    isDeleting: Boolean,
    onDelete: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(usage.descriptor.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    metadataLine(usage, peakRamBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Only shown for a name that collides with another row: the unique folder id tells them apart.
                if (disambiguate) {
                    Text(
                        usage.descriptor.modelId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isDeleting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                ModelRowOverflow(
                    repoId = facts?.hfRepoId,
                    detailsShown = showDetails,
                    onToggleDetails = { showDetails = !showDetails },
                    onOpenHf = { facts?.hfRepoId?.let { uriHandler.openUri(HfEndpoints.modelPageUrl(it)) } },
                    onDelete = { confirmDelete = true },
                )
            }
        }
        wontFitWarning(peakRamBytes, totalRamBytes)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (showDetails) ModelFactsBlock(facts)
    }

    if (confirmDelete) {
        DeleteConfirmDialog(
            name = usage.descriptor.displayName,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

// The compact second line: "Origin - size" plus the estimated RAM when known. RAM sits here so the
// row stays two lines; the won't-fit warning is separate (and always shown) because it is safety info.
private fun metadataLine(
    usage: ModelUsage,
    peakRamBytes: Long?,
): String =
    listOfNotNull(
        originLabel(usage.descriptor.origin),
        formatBytes(usage.sizeBytes),
        // Always an estimate now (the a-priori per-engine figure), so it says so - the previous number
        // was silently a whole-process measurement for benchmarked models (issue #123).
        peakRamBytes?.let { "~${formatBytes(it)} RAM est." },
    ).joinToString(" · ")

/** The row's overflow menu: Details toggle, Open on Hugging Face (when a repo id is known), Delete. */
@Composable
private fun ModelRowOverflow(
    repoId: String?,
    detailsShown: Boolean,
    onToggleDetails: () -> Unit,
    onOpenHf: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (detailsShown) "Hide details" else "Details") },
                onClick = {
                    expanded = false
                    onToggleDetails()
                },
            )
            if (repoId != null) {
                DropdownMenuItem(
                    text = { Text("Open on Hugging Face") },
                    onClick = {
                        expanded = false
                        onOpenHf()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/** Confirm before deleting - the destructive action should not fire on a single stray tap (issue #123). */
@Composable
private fun DeleteConfirmDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete model?") },
        text = { Text("Remove \"$name\" and its files from this device? You can download it again later.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The expandable per-model detail block: RTF and parameter-count lines, each labeled "measured" or
 * "estimated" so the user never mistakes a formula guess for a real benchmark. Nothing here is a
 * per-model literal - every value comes from [ManageModelFacts]
 * ([com.phonetts.core.model.InstalledModelFacts], CLAUDE.md rule 1). Renders nothing when [facts]
 * isn't available yet (e.g. mid-refresh). Open-on-Hugging-Face moved to the row overflow menu.
 */
@Composable
private fun ModelFactsBlock(facts: ManageModelFacts?) {
    if (facts == null) return
    paramCountLine(facts)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    realtimeLine(facts)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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

/** A compact "82M"/"1.2B" parameter-count label - mirrors the Browse screen's own formatter
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

// Issue #8/bug #6: a downloaded bundle no engine claimed - shown honestly instead of vanishing as
// if it were never fetched, and worded to head off the natural (wrong) assumption that a bundle
// with no engine must mean a failed/incomplete download that needs redoing. It's already fully on
// disk (that's what [unresolved.sizeBytes] is showing); redownloading the same bytes changes
// nothing without a matching engine.
//
// Bug #1: this used to offer no way to actually pick an engine - the resolver's documented
// fail-closed fallback ("ask the user") had nothing on this screen that could drive it, so a user
// who saw "pick an engine" had no button to press. [selectableEngines] (the SAME registered set
// autodetection already checked - SSOT, nothing named here) now backs a real picker: choosing one
// runs [onAssignEngine], which hands the bundle to that engine's forcedMatch. This is still not
// "guessing" (rule 4 stands) - it is the user, not the app, making the call.
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
                Text("Redownloading won't help - " + unresolved.reason, style = MaterialTheme.typography.bodySmall)
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

// A warning shown ONLY when the model genuinely can't physically fit this device - its estimated peak
// exceeds TOTAL RAM, not merely whatever happens to be free right now (a tight-but-possible 3.5 GB
// model on a 4 GB phone never warns). [DeviceRamFit] is the single source of truth for that call, no
// "tight"/tier language here. null (no warning) when it fits or no estimate exists. The estimate
// itself is shown compactly in the row's metadata line.
private fun wontFitWarning(peakRamBytes: Long?, totalRamBytes: Long): String? {
    if (peakRamBytes == null) return null
    if (!DeviceRamFit.modelExceedsDeviceRam(peakRamBytes, totalRamBytes)) return null
    return "May not fit this device - needs ~${formatBytes(peakRamBytes)} RAM"
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
 * the Storage Access Framework, and - when this device hasn't granted "All files access" yet - a
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

// MANAGE_EXTERNAL_STORAGE only exists from API 30 - below that there is no such gate to check, so
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
 * RTF), and each downloaded-but-unclaimed bundle carries a best-effort HF link too - the whole
 * point of listing it is letting the user click through to what didn't resolve. Headed by the
 * count and grand total size. The actual formatting is pure logic in [ModelListExport] (`:core`,
 * no Android deps) so it's unit-testable on a plain JVM; this function only maps the screen's own
 * state into that call - no model fact is re-hardcoded here (CLAUDE.md rule 1).
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
