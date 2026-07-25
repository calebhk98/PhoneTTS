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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import com.phonetts.core.format.ByteFormat
import com.phonetts.core.format.CountFormat
import com.phonetts.core.model.DeviceRamFit
import com.phonetts.core.model.ExportableModel
import com.phonetts.core.model.ManageModelFacts
import com.phonetts.core.model.ModelListExport
import com.phonetts.core.model.Origin
import com.phonetts.core.registry.ManagedEngineFilter
import com.phonetts.core.registry.ManagedModelEntry
import com.phonetts.core.registry.NO_ENGINE_YET_LABEL
import com.phonetts.core.registry.ModelUsage
import com.phonetts.core.registry.UnresolvedModelUsage
import com.phonetts.core.resolver.SelectableEngine

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
 *
 * "Add model" (issue: "Import model from folder" in the global drawer was bad UI - it's an action,
 * not a destination) lives here as an `ExtendedFloatingActionButton`: this is the one screen where
 * wanting to add a model is the obvious next step. [onBrowseHuggingFace] and [onImportFolder] are
 * callbacks rather than this screen owning navigation/a picker itself - [onImportFolder] is only
 * handed the picked [Uri], the caller decides what "import" means (sideloading into the catalog).
 */
@Composable
fun ModelManagementScreen(
    viewModel: ModelManagementViewModel,
    onBrowseHuggingFace: () -> Unit = {},
    onImportFolder: (Uri) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    // Fixes issue #10: this composable re-enters every time the user navigates to this screen (the
    // caller un-mounts it on the way out), so LaunchedEffect(Unit) re-fires here even though the
    // ViewModel itself is a single long-lived instance whose init{} only ran once.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val clipboard = LocalClipboardManager.current
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Storage used: ${ByteFormat.format(state.totalBytes)}", style = MaterialTheme.typography.bodyLarge)
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
                "Device RAM: ${ByteFormat.format(state.availableRamBytes)} free of " +
                    "${ByteFormat.format(state.totalRamBytes)} total",
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

            if (state.usage.isNotEmpty() || state.unresolved.isNotEmpty()) {
                ManageControls(
                    sort = state.sort,
                    query = state.query,
                    onSortKey = viewModel::setSortKey,
                    onToggleDirection = viewModel::toggleSortDirection,
                    onQuery = viewModel::setQuery,
                    filterState =
                        ManageFilterState(
                            origin = state.originFilter,
                            engine = state.engineFilter,
                            minSizeBytes = state.minSizeBytes,
                            maxSizeBytes = state.maxSizeBytes,
                            availableEngineIds = state.availableEngineIds,
                            hasUnresolved = state.unresolved.isNotEmpty(),
                        ),
                    filterCallbacks =
                        ManageFilterCallbacks(
                            onOrigin = viewModel::setOriginFilter,
                            onEngine = viewModel::setEngineFilter,
                            onSize = viewModel::setSizeFilter,
                            onClear = viewModel::clearAdvancedFilters,
                        ),
                )
            }

            // Issue: sort/filter ignoring engine-less models - a resolved model row AND an unclaimed
            // bundle row now go through the exact SAME sort/filter pass ([ModelManagementViewModel.
            // visibleEntries]/[ManagedModelListView]), instead of the unresolved list being appended
            // afterward raw, unsorted, and unfiltered.
            val visibleEntries = ModelManagementViewModel.visibleEntries(state)

            // Re-sorting keeps stable item keys (by modelId), so a LazyColumn would otherwise preserve the
            // scroll anchor and "follow" whatever row was on top - the user changed the order to see the new
            // top, so snap back to it whenever the sort changes (issue #115). Keyed on the whole Sort so both
            // a new sort-key and a direction flip trigger it.
            val listState = rememberLazyListState()
            LaunchedEffect(state.sort) { listState.animateScrollToItem(0) }

            // Display names collide (several "KittenTTS", two "Kokoro"); the rows sharing a name get their
            // unique folder id shown underneath to tell them apart (issue #123). Computed once over the
            // whole visible set so a name is "duplicated" consistently regardless of scroll position. Only
            // resolved rows carry a real descriptor display name to disambiguate; an unresolved bundle's
            // id is already unique by construction.
            val duplicateNames =
                visibleEntries
                    .filterIsInstance<ManagedModelEntry.Resolved>()
                    .groupingBy { it.usage.descriptor.displayName }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys

            if ((state.usage.isNotEmpty() || state.unresolved.isNotEmpty()) && visibleEntries.isEmpty()) {
                Text("No downloaded models match the current filters.", style = MaterialTheme.typography.bodyMedium)
            }

            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visibleEntries, key = { it.entryKey() }) { entry ->
                    when (entry) {
                        is ManagedModelEntry.Resolved ->
                            ModelUsageRow(
                                usage = entry.usage,
                                peakRamBytes = state.peakRamByModelId[entry.usage.descriptor.modelId],
                                totalRamBytes = state.totalRamBytes,
                                facts = state.factsByModelId[entry.usage.descriptor.modelId],
                                disambiguate = entry.usage.descriptor.displayName in duplicateNames,
                                isDeleting = state.deletingId == entry.usage.descriptor.modelId,
                                onDelete = { viewModel.delete(entry.usage.descriptor.modelId) },
                            )
                        is ManagedModelEntry.Unresolved ->
                            UnresolvedUsageRow(
                                unresolved = entry.usage,
                                selectableEngines = state.selectableEngines,
                                isDeleting = state.deletingUnresolvedId == entry.usage.bundleId,
                                isAssigning = state.assigningUnresolvedId == entry.usage.bundleId,
                                onDelete = { viewModel.deleteUnresolved(entry.usage.bundleId) },
                                onAssignEngine = { engineId -> viewModel.assignEngine(entry.usage.bundleId, engineId) },
                            )
                    }
                    HorizontalDivider()
                }
            }
        }
        AddModelFab(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            onBrowseHuggingFace = onBrowseHuggingFace,
            onImportFolder = onImportFolder,
        )
    }
}

/** The "Add model" FAB (issue: "Import model from folder" in the global drawer was bad UI - it's an
 * action, not a destination). A small menu offers the two ways to add a model: browse Hugging Face,
 * or import a folder already on the phone via the system folder picker. */
@Composable
private fun AddModelFab(
    modifier: Modifier,
    onBrowseHuggingFace: () -> Unit,
    onImportFolder: (Uri) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(onImportFolder)
        }
    Box(modifier = modifier) {
        ExtendedFloatingActionButton(
            text = { Text("Add model") },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Browse Hugging Face") },
                onClick = {
                    expanded = false
                    onBrowseHuggingFace()
                },
            )
            DropdownMenuItem(
                text = { Text("Import from folder") },
                onClick = {
                    expanded = false
                    importLauncher.launch(null)
                },
            )
        }
    }
}

// A unique LazyColumn key across BOTH entry kinds (issue: a duplicate-key crash already bit this
// app once on the Browse screen's list - see v0.1.12). A resolved model's modelId and an unresolved
// bundle's bundleId live in different id spaces already, but this prefixes them anyway so the two
// can never collide even in the (unexpected) event they ever shared a raw id.
private fun ManagedModelEntry.entryKey(): String =
    when (this) {
        is ManagedModelEntry.Resolved -> "m:${usage.descriptor.modelId}"
        is ManagedModelEntry.Unresolved -> "u:${usage.bundleId}"
    }

/** The Origin/Engine/size-range filter values (issue: downloaded models has only a name filter),
 * bundled so [ManageControls] stays under detekt's parameter-count limit. */
private data class ManageFilterState(
    val origin: Origin?,
    val engine: ManagedEngineFilter,
    val minSizeBytes: Long?,
    val maxSizeBytes: Long?,
    /** Every engine id an installed model actually maps to right now - live, never hardcoded. */
    val availableEngineIds: List<String>,
    /** Whether the "No engine" bucket is worth offering at all (issue: never fabricate a filter
     * option for a bucket that's currently empty). */
    val hasUnresolved: Boolean,
)

/** The filter callbacks, bundled for the same reason as [ManageFilterState]. */
private data class ManageFilterCallbacks(
    val onOrigin: (Origin?) -> Unit,
    val onEngine: (ManagedEngineFilter) -> Unit,
    val onSize: (min: Long?, max: Long?) -> Unit,
    val onClear: () -> Unit,
)

// Sort/filter controls for the downloaded-models list (issue #115, extended by issue: downloaded
// models has only a name filter): a name filter, a sort-key dropdown, an ascending/descending
// toggle, and - behind "More filters" (mirroring HfBrowseScreen's collapsible pattern, issue #106)
// - Origin/Engine dropdowns plus a min/max size range. The ordering/filtering itself is the pure
// [ModelManagementViewModel.visibleEntries]/[com.phonetts.core.registry.ManagedModelListView];
// this only reports the user's choices.
@Composable
private fun ManageControls(
    sort: Sort,
    query: String,
    onSortKey: (SortKey) -> Unit,
    onToggleDirection: () -> Unit,
    onQuery: (String) -> Unit,
    filterState: ManageFilterState,
    filterCallbacks: ManageFilterCallbacks,
) {
    var expanded by remember { mutableStateOf(false) }
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
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Fewer filters ▴" else "More filters ▾")
        }
        if (expanded) ManageMoreFilters(filterState, filterCallbacks)
    }
}

/** The collapsed-away Origin/Engine/size filters (mirrors HfBrowseScreen's `MoreFilters`, issue
 * #106) - kept in its own composable so the common case (no filter) doesn't pay for laying these
 * out at all. */
@Composable
private fun ManageMoreFilters(
    filterState: ManageFilterState,
    callbacks: ManageFilterCallbacks,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OriginFilterMenu(filterState.origin, callbacks.onOrigin, Modifier.weight(1f))
            EngineFilterMenu(
                engine = filterState.engine,
                availableEngineIds = filterState.availableEngineIds,
                hasUnresolved = filterState.hasUnresolved,
                onSelect = callbacks.onEngine,
                modifier = Modifier.weight(1f),
            )
        }
        SizeRangeFilterRow(filterState.minSizeBytes, filterState.maxSizeBytes, callbacks.onSize)
        if (isAdvancedFilterActive(filterState)) {
            TextButton(onClick = callbacks.onClear) { Text("Clear filters") }
        }
    }
}

private fun isAdvancedFilterActive(filterState: ManageFilterState): Boolean =
    filterState.origin != null ||
        filterState.engine != ManagedEngineFilter.All ||
        filterState.minSizeBytes != null ||
        filterState.maxSizeBytes != null

/** Origin filter (issue: downloaded models has only a name filter) - choices come from
 * [Origin.entries], the real enum, never a hardcoded pair of strings. */
@Composable
private fun OriginFilterMenu(
    selected: Origin?,
    onSelect: (Origin?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Origin: ${selected?.let(::originLabel) ?: "All"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All origins") }, onClick = { expanded = false; onSelect(null) })
            Origin.entries.forEach { origin ->
                DropdownMenuItem(
                    text = { Text(originLabel(origin)) },
                    onClick = {
                        expanded = false
                        onSelect(origin)
                    },
                )
            }
        }
    }
}

/** Engine filter (issue: downloaded models has only a name filter) - choices are the installed
 * models' OWN engine ids ([availableEngineIds], derived live from the catalog, never a hardcoded
 * engine roster) plus a "No engine" bucket offered only while [hasUnresolved] bundles exist. */
@Composable
private fun EngineFilterMenu(
    engine: ManagedEngineFilter,
    availableEngineIds: List<String>,
    hasUnresolved: Boolean,
    onSelect: (ManagedEngineFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Engine: ${engineFilterLabel(engine)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All engines") },
                onClick = { expanded = false; onSelect(ManagedEngineFilter.All) },
            )
            availableEngineIds.forEach { engineId ->
                DropdownMenuItem(
                    text = { Text(engineId) },
                    onClick = {
                        expanded = false
                        onSelect(ManagedEngineFilter.Specific(engineId))
                    },
                )
            }
            if (hasUnresolved) {
                DropdownMenuItem(
                    text = { Text("No engine") },
                    onClick = { expanded = false; onSelect(ManagedEngineFilter.NoEngine) },
                )
            }
        }
    }
}

private fun engineFilterLabel(engine: ManagedEngineFilter): String =
    when (engine) {
        is ManagedEngineFilter.All -> "All"
        is ManagedEngineFilter.NoEngine -> "No engine"
        is ManagedEngineFilter.Specific -> engine.engineId
    }

/** Min/max on-disk size filter, entered in MB (mirrors HfBrowseScreen's `SizeParamFilterRow`, issue
 * #107) and converted to raw bytes only on Apply, so a blank field means "no bound" rather than a
 * fabricated zero. Collapsed by default; starts open if a bound from a previous visit is active. */
@Composable
private fun SizeRangeFilterRow(
    minSizeBytes: Long?,
    maxSizeBytes: Long?,
    onApply: (min: Long?, max: Long?) -> Unit,
) {
    var minMb by remember(minSizeBytes) { mutableStateOf(bytesToMbText(minSizeBytes)) }
    var maxMb by remember(maxSizeBytes) { mutableStateOf(bytesToMbText(maxSizeBytes)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = minMb,
                onValueChange = { minMb = it },
                label = { Text("Min size (MB)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = maxMb,
                onValueChange = { maxMb = it },
                label = { Text("Max size (MB)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Button(onClick = { onApply(mbTextToBytes(minMb), mbTextToBytes(maxMb)) }) { Text("Apply size filter") }
    }
}

private fun bytesToMbText(bytes: Long?): String = bytes?.let { "%.0f".format(it / BYTES_PER_MB.toDouble()) } ?: ""

private fun mbTextToBytes(text: String): Long? =
    text.trim().toDoubleOrNull()?.takeIf { it > 0.0 }?.let { (it * BYTES_PER_MB).toLong() }

@Composable
private fun ManageSortKeyMenu(
    selected: SortKey,
    onSelect: (SortKey) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Sort: ${selected.label}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortKey.entries.forEach { key ->
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
        ByteFormat.format(usage.sizeBytes),
        // Always an estimate now (the a-priori per-engine figure), so it says so - the previous number
        // was silently a whole-process measurement for benchmarked models (issue #123).
        peakRamBytes?.let { "~${ByteFormat.format(it)} RAM est." },
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
    return "~${CountFormat.formatParamCount(count)} params (estimated)"
}

private fun realtimeLine(facts: ManageModelFacts): String? {
    val multiple = facts.realtimeMultiple ?: return null
    val label = if (facts.realtimeIsMeasured) "measured" else "estimated"
    return "~${formatRealtimeMultiple(multiple)}x real-time ($label)"
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
                Text("Already downloaded (" + ByteFormat.format(unresolved.sizeBytes) + ") · $NO_ENGINE_YET_LABEL")
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
    return "May not fit this device - needs ~${ByteFormat.format(peakRamBytes)} RAM"
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

private const val ERROR_MAX_LINES = 3

// The size-range filter is entered/shown in MB, same as HfBrowseScreen's own size filter, and
// converted to raw bytes only on Apply (issue: downloaded models has only a name filter).
private const val BYTES_PER_MB = 1024L * 1024L
