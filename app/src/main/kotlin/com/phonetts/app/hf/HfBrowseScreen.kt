package com.phonetts.app.hf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.phonetts.core.download.hf.DiagnosticsEntry
import com.phonetts.core.download.hf.DiagnosticsKind
import com.phonetts.core.download.hf.HfDownloadProgress
import com.phonetts.core.download.hf.HfEndpoints
import com.phonetts.core.download.hf.HfModelSummary
import com.phonetts.core.download.hf.HfSizeEstimate
import com.phonetts.core.download.hf.HfSizeEstimator
import com.phonetts.core.download.hf.HfSizeParamFilter
import com.phonetts.core.download.hf.HfSortOption
import com.phonetts.core.download.hf.ModelSpeedEstimate
import com.phonetts.core.download.hf.QuantizationFilter
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToLong

/**
 * Browse Hugging Face text-to-speech models and download one. The whole screen is derived from the
 * catalog + the resolver — it hardcodes no model. A downloaded repo the resolver can't identify
 * still lands in the catalog via the user-pick fallback (spec §6.2), so "browse everything" degrades
 * gracefully instead of failing.
 *
 * Layout: search box at the very top, then sort/filter controls (issue #6, choices derived from the
 * current results), then the live results, then the curated one-tap "Recommended" models — all in
 * one scrolling list. More than one row can download at once (issue #2): each row tracks its own
 * progress instead of the whole screen being locked to a single in-flight download. Errors — search
 * or download — are retained for the session and readable/copyable from the "Errors" button rather
 * than only flashing by (issue #3), and the inline banner itself is dismissible (bug #2) without
 * losing that retained log.
 *
 * Sort/filter recompute (bug #3): [displayedResults]/[availableTags] are `remember`-cached keyed on
 * exactly the state fields they read (`results`/`sort`/`tagFilter`), not recomputed on every
 * recomposition. Without that, any unrelated state change — most commonly a download's
 * bytes/files-progress tick, which can fire many times a second per in-flight download (issue #2
 * allows several at once) — replaces the whole [HfBrowseUiState] and forces `collectAsState` to
 * recompose this screen, re-sorting/re-filtering and re-flattening tags from scratch every time.
 * That alone is wasted work; it got worse because [SortAndFilterRow]'s callbacks were passed as bare
 * `viewModel::method` references, which Kotlin allocates fresh on every call — an unstable lambda
 * that defeats Compose's skip check, so the dropdown's own composable (and, if it happened to be
 * open, its full item list) re-composed on every one of those ticks too. On the target budget
 * hardware (4 GB RAM, no NPU) that steady drip of small-but-frequent allocations and recompositions
 * is enough GC/main-thread pressure that a tap's input event sits behind a backlog of pending frames
 * instead of being handled promptly — which reads as the dropdown "doing nothing" until a click
 * happens to land in a gap. The fix here is the `remember` scoping below plus `remember`-wrapped
 * stable callbacks — the dropdown's own open/close state was always local and cheap; it only *looked*
 * unresponsive because the main thread was busy with unrelated work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HfBrowseScreen(viewModel: HfBrowseViewModel) {
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    var showErrorLog by remember { mutableStateOf(false) }
    var showDiagnosticsLog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Search Hugging Face TTS models") },
                // So the keyboard's Search/Enter key runs the query — not only the Search button.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
            )
            Button(onClick = viewModel::search, enabled = !state.loading) { Text("Search") }
        }

        // Wrapped in `remember(viewModel)` so the SAME lambda instance is passed on every
        // recomposition (a bare `viewModel::onSortChange` reference allocates a new one each time,
        // which is exactly the unstable-lambda half of the bug #3 root cause described above).
        val onSortChange = remember(viewModel) { viewModel::onSortChange }
        val onTagFilterChange = remember(viewModel) { viewModel::onTagFilterChange }
        val onSizeFilterChange = remember(viewModel) { viewModel::onSizeFilterChange }
        // Both keyed on only the fields they actually depend on — NOT recomputed on every
        // recomposition (e.g. a download-progress tick), which was the other half of bug #3.
        val availableTags = remember(state.results) { viewModel.availableTags(state) }
        SortAndFilterRow(
            sort = state.sort,
            onSortChange = onSortChange,
            tagFilter = state.tagFilter,
            availableTags = availableTags,
            onTagFilterChange = onTagFilterChange,
        )
        // Size/param-count filter (issue: sort+filter by size/params) — a separate row from the
        // sort/tag dropdowns above since it takes numeric bounds, not a single choice from a menu.
        SizeParamFilterRow(filter = state.sizeFilter, onFilterChange = onSizeFilterChange)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            state.error?.let { message ->
                Text("Error: $message", modifier = Modifier.weight(1f))
                // Bug #2: the inline banner is now dismissible — this only hides the banner, the
                // full session log below is untouched (HfBrowseViewModel.dismissError).
                IconButton(onClick = viewModel::dismissError) { Text("✕") }
            }
            if (state.errorLog.isNotEmpty()) {
                TextButton(onClick = { showErrorLog = true }) { Text("Errors (${state.errorLog.size})") }
            }
            // Persistent across app restarts (unlike the two logs above, which are session-only) —
            // see DownloadDiagnosticsLog. Only shown once something has actually been recorded.
            if (state.diagnostics.isNotEmpty()) {
                TextButton(onClick = { showDiagnosticsLog = true }) { Text("Download log (${state.diagnostics.size})") }
            }
        }
        if (state.loading) CircularProgressIndicator()

        // Also keyed on sizeEstimates/sizeFilter (unlike the sort/tag-only keys before size/param
        // sort+filter existed) so a size arriving after this composition — or the user setting a
        // size/param bound — actually reorders/refilters the list. Safe perf-wise despite bug #3's
        // "don't recompute on every tick" lesson: sizeEstimates changes at most once per row (each
        // repo's size resolves once and is cached), never the many-times-a-second cadence a
        // download's byte progress produces.
        val displayedResults =
            remember(state.results, state.sort, state.tagFilter, state.sizeEstimates, state.sizeFilter) {
                viewModel.displayedResults(state)
            }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Recommended one-tap models come first — they're the curated, known-good downloads most
            // users want; the broader Hugging Face results follow under their own header.
            if (viewModel.recommended.isNotEmpty()) {
                item(key = "recommended-header") {
                    Text("Recommended (one-tap)", fontWeight = FontWeight.Bold)
                }
                items(viewModel.recommended, key = { "rec:${it.id}" }) { model ->
                    RecommendedRow(
                        model = model,
                        progress = state.downloads[model.id],
                        isInstalled = viewModel.isInstalled(model.id),
                        actions =
                            RowActions(
                                onDownload = { viewModel.downloadBuiltIn(model) },
                                onCancel = { viewModel.cancelDownload(model.id) },
                                onOpenPage = { uriHandler.openUri(HfEndpoints.modelPageUrl(model.repoId)) },
                            ),
                    )
                }
            }

            if (displayedResults.isNotEmpty()) {
                item(key = "results-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        Text("More from Hugging Face", fontWeight = FontWeight.Bold)
                    }
                }
            }
            items(displayedResults, key = { "hf:${it.id}" }) { model ->
                ModelRow(
                    model = model,
                    progress = state.downloads[model.id],
                    isInstalled = viewModel.isInstalled(model.id),
                    notYetSupported = model.id in state.notYetSupportedIds,
                    sizeState =
                        SizeState(
                            estimate = state.sizeEstimates[model.id],
                            loading = model.id in state.sizeLoading,
                            onLoad = { viewModel.loadSize(model.id) },
                        ),
                    actions =
                        RowActions(
                            onDownload = { viewModel.download(model) },
                            onCancel = { viewModel.cancelDownload(model.id) },
                            onOpenPage = { uriHandler.openUri(HfEndpoints.modelPageUrl(model.id)) },
                        ),
                )
            }
            // Pagination (issue: Browse "Load more"): appears once the last-fetched page came back
            // full (state.canLoadMore — see HfBrowseViewModel.search/loadMore) and disappears once a
            // short page proves the Hub has nothing further for this query. Its own row so it always
            // sits after every fetched result, never interleaved mid-list.
            if (state.canLoadMore || state.loadingMore) {
                item(key = "load-more") {
                    LoadMoreRow(loading = state.loadingMore, onLoadMore = viewModel::loadMore)
                }
            }
        }
    }

    state.variantChoice?.let { choice ->
        VariantPicker(
            choice = choice,
            onPick = viewModel::chooseVariant,
            onDismiss = viewModel::cancelVariantChoice,
        )
    }

    if (showErrorLog) {
        ErrorLogDialog(errors = state.errorLog, onDismiss = { showErrorLog = false })
    }

    if (showDiagnosticsLog) {
        DiagnosticsLogDialog(
            entries = state.diagnostics,
            onClear = viewModel::clearDiagnostics,
            onDismiss = { showDiagnosticsLog = false },
        )
    }
}

/** Sort order + tag filter, both derived from the current result set (issue #6) — no hardcoded
 * model list or fixed tag vocabulary; a tag menu with nothing to filter by just stays empty. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortAndFilterRow(
    sort: HfSortOption,
    onSortChange: (HfSortOption) -> Unit,
    tagFilter: String?,
    availableTags: List<String>,
    onTagFilterChange: (String?) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var sortExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = sortExpanded,
            onExpandedChange = { sortExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            TextField(
                value = sortLabel(sort),
                onValueChange = {},
                readOnly = true,
                label = { Text("Sort by") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                HfSortOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(sortLabel(option)) },
                        onClick = { onSortChange(option); sortExpanded = false },
                    )
                }
            }
        }

        if (availableTags.isEmpty()) return@Row
        var tagExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = tagExpanded,
            onExpandedChange = { tagExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            TextField(
                value = tagFilter ?: "All tags",
                onValueChange = {},
                readOnly = true,
                label = { Text("Filter by tag") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tagExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = tagExpanded, onDismissRequest = { tagExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("All tags") },
                    onClick = { onTagFilterChange(null); tagExpanded = false },
                )
                availableTags.forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(tag) },
                        onClick = { onTagFilterChange(tag); tagExpanded = false },
                    )
                }
            }
        }
    }
}

private fun sortLabel(option: HfSortOption): String =
    when (option) {
        HfSortOption.RELEVANCE -> "Relevance"
        HfSortOption.MOST_DOWNLOADS -> "Most downloads"
        HfSortOption.MOST_LIKES -> "Most likes"
        HfSortOption.NAME_ASC -> "Name (A-Z)"
        HfSortOption.LARGEST_SIZE -> "Largest size"
        HfSortOption.SMALLEST_SIZE -> "Smallest size"
        HfSortOption.MOST_PARAMS -> "Most params (est.)"
        HfSortOption.FEWEST_PARAMS -> "Fewest params (est.)"
    }

/**
 * Numeric min/max bounds for the size and estimated-parameter-count filter (issue: sort+filter by
 * size/params — [HfSizeParamFilter]). Collapsed by default so the common case (no filter) doesn't
 * add four text fields to the screen; expands once tapped, or if a filter from a previous session
 * is already active. Values are entered in the same human units the rest of this screen already
 * shows (MB, M params) and converted to raw bytes/count only on Apply — see [mbTextToBytes]/
 * [mTextToParams] — so a blank field means "no bound", never a fabricated zero.
 */
@Composable
private fun SizeParamFilterRow(
    filter: HfSizeParamFilter,
    onFilterChange: (HfSizeParamFilter) -> Unit,
) {
    var expanded by remember { mutableStateOf(filter.isActive) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (filter.isActive) "Size / params filter (active) ▾" else "Size / params filter ▾")
        }
        if (!expanded) return@Column
        var minSizeMb by remember { mutableStateOf(bytesToMbText(filter.minBytes)) }
        var maxSizeMb by remember { mutableStateOf(bytesToMbText(filter.maxBytes)) }
        var minParamsM by remember { mutableStateOf(paramsToMText(filter.minParams)) }
        var maxParamsM by remember { mutableStateOf(paramsToMText(filter.maxParams)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Min size (MB)", minSizeMb, { minSizeMb = it }, Modifier.weight(1f))
            NumberField("Max size (MB)", maxSizeMb, { maxSizeMb = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Min params (M)", minParamsM, { minParamsM = it }, Modifier.weight(1f))
            NumberField("Max params (M)", maxParamsM, { maxParamsM = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onFilterChange(
                    HfSizeParamFilter(
                        minBytes = mbTextToBytes(minSizeMb),
                        maxBytes = mbTextToBytes(maxSizeMb),
                        minParams = mTextToParams(minParamsM),
                        maxParams = mTextToParams(maxParamsM),
                    ),
                )
            }) { Text("Apply") }
            OutlinedButton(onClick = {
                minSizeMb = ""
                maxSizeMb = ""
                minParamsM = ""
                maxParamsM = ""
                onFilterChange(HfSizeParamFilter())
            }) { Text("Clear") }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

/** Pagination's "fetch the next page" control (issue: Browse "Load more") — a button while idle, a
 * spinner while [loading] a page is in flight, centered so it reads as a list footer rather than
 * another result row. */
@Composable
private fun LoadMoreRow(
    loading: Boolean,
    onLoadMore: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        if (loading) {
            CircularProgressIndicator()
        } else {
            OutlinedButton(onClick = onLoadMore) { Text("Load more") }
        }
    }
}

private fun bytesToMbText(bytes: Long?): String = bytes?.let { "%.0f".format(it / BYTES_PER_MB.toDouble()) } ?: ""

private fun paramsToMText(params: Long?): String = params?.let { "%.0f".format(it / PARAMS_PER_M) } ?: ""

private fun mbTextToBytes(text: String): Long? =
    text.trim().toDoubleOrNull()?.takeIf { it > 0.0 }?.let { (it * BYTES_PER_MB).roundToLong() }

private fun mTextToParams(text: String): Long? =
    text.trim().toDoubleOrNull()?.takeIf { it > 0.0 }?.let { (it * PARAMS_PER_M).roundToLong() }

/** Every retained browse/download error this session (issue #3): selectable text plus a "Copy all"
 * button so the user can paste a full report rather than only screenshotting a toast. */
@Composable
private fun ErrorLogDialog(
    errors: List<HfBrowseError>,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val formatter = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val fullText = remember(errors) { errors.joinToString("\n\n") { formatErrorLine(it, formatter) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(fullText)) }) { Text("Copy all") }
        },
        title = { Text("Recent errors") },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    errors.forEach { error ->
                        Text(formatErrorLine(error, formatter), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

private fun formatErrorLine(
    error: HfBrowseError,
    formatter: DateFormat,
): String {
    val time = formatter.format(Date(error.atMs))
    val prefix = error.modelId?.let { "$it — " } ?: ""
    return "[$time] $prefix${error.message}"
}

/** Persistent (survives an app restart — see [DownloadDiagnosticsLog]) record of Browse download
 * failures and "downloaded, but no engine claims it yet" imports, so the user can track which
 * engines are worth adding next — not just a session-only toast. */
@Composable
private fun DiagnosticsLogDialog(
    entries: List<DiagnosticsEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val fullText = remember(entries) { entries.joinToString("\n\n") { formatDiagnosticsLine(it, formatter) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(fullText)) }) { Text("Copy all") }
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
        },
        title = { Text("Download diagnostics") },
        text = { DiagnosticsLogBody(entries, formatter) },
    )
}

@Composable
private fun DiagnosticsLogBody(
    entries: List<DiagnosticsEntry>,
    formatter: DateFormat,
) {
    if (entries.isEmpty()) {
        Text("No download issues recorded yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    SelectionContainer {
        Column(
            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEach { entry ->
                Text(formatDiagnosticsLine(entry, formatter), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatDiagnosticsLine(
    entry: DiagnosticsEntry,
    formatter: DateFormat,
): String {
    val time = formatter.format(Date(entry.atMs))
    val kind = if (entry.kind == DiagnosticsKind.FAILURE) "FAILED" else "downloaded, no engine yet"
    return "[$time] ${entry.modelId} — $kind: ${entry.detail}"
}

/** Lets the user pick which weight precision to download when a repo ships more than one KNOWN
 * precision (issue #9 — an ambiguous, unlabeled auxiliary weight file never reaches this picker;
 * see [com.phonetts.core.download.hf.QuantizationFilter.requiresChoice]). Each option shows its own
 * download size where the repo's file tree reports one (issue #7). */
@Composable
private fun VariantPicker(
    choice: VariantChoice,
    onPick: (com.phonetts.core.download.hf.QuantizationVariant) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Choose precision") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${choice.modelId} offers several precisions. Smaller = faster/less RAM.")
                choice.variants.forEach { variant ->
                    val estimate = HfSizeEstimator.estimate(QuantizationFilter.filesForVariant(choice.files, variant))
                    Button(onClick = { onPick(variant) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${variant.name} — ${formatSize(estimate)}")
                    }
                }
            }
        },
    )
}

/** A row's Download/Cancel/"View on Hugging Face" callbacks, bundled into one parameter so
 * [RecommendedRow]/[ModelRow] stay under detekt's LongParameterList limit. */
private data class RowActions(
    val onDownload: () -> Unit,
    val onCancel: () -> Unit,
    val onOpenPage: () -> Unit,
)

/** A result row's on-demand size lookup (issue #7): the estimate once fetched, whether a fetch is
 * in flight, and how to trigger one — bundled into one parameter for the same reason as [RowActions].
 * The fetch is now triggered automatically (bug #4 — no more "Show download size" tap gate); [onLoad]
 * is idempotent (see [HfBrowseViewModel.loadSize]) so calling it once per row composition is safe. */
private data class SizeState(
    val estimate: HfSizeEstimate?,
    val loading: Boolean,
    val onLoad: () -> Unit,
)

@Composable
private fun RecommendedRow(
    model: com.phonetts.core.download.builtin.BuiltInModel,
    progress: HfDownloadProgress?,
    isInstalled: Boolean,
    actions: RowActions,
) {
    ModelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.displayName, fontWeight = FontWeight.Bold)
                Text("~${model.approxSizeMb} MB", style = MaterialTheme.typography.bodyMedium)
                model.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                // Bug #5: a curated model's approximate size is already known up front (no network
                // round trip needed), so its param/speed hint can be derived immediately too.
                val fileNames = remember(model.id) { model.files.map { it.localName } }
                SpeedHintLine(totalBytes = model.approxSizeMb.toLong() * BYTES_PER_MB, precisionHints = fileNames)
            }
            DownloadControl(progress != null, isInstalled, actions.onDownload, actions.onCancel)
        }
        DownloadProgress(progress)
        OpenPageLink(actions.onOpenPage)
    }
}

@Composable
private fun ModelRow(
    model: HfModelSummary,
    progress: HfDownloadProgress?,
    isInstalled: Boolean,
    notYetSupported: Boolean,
    sizeState: SizeState,
    actions: RowActions,
) {
    // Bug #4: the download size is no longer gated behind a "Show download size" tap — fetch it as
    // soon as this row is shown. Runs once per model id (LaunchedEffect restarts only when the key
    // changes) and loadSize() is a no-op if a fetch already ran/is running for this id. The same
    // file-tree fetch also determines [notYetSupported] (see HfBrowseViewModel.loadSize).
    LaunchedEffect(model.id) { sizeState.onLoad() }
    ModelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.id, fontWeight = FontWeight.Bold)
                Text("▼ ${model.downloads}   ♥ ${model.likes}", style = MaterialTheme.typography.bodyMedium)
                // pipelineTag + tags come straight from the HF API response — no extra network call,
                // just fields the parser already kept that the UI wasn't showing.
                val tags = listOfNotNull(model.pipelineTag) + model.tags.take(MAX_TAGS_SHOWN)
                val subtitle = tags.joinToString(" · ")
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
                if (notYetSupported) NotYetSupportedBadge()
                SizeLine(sizeState)
                SpeedHintLine(totalBytes = sizeState.estimate?.knownBytes, precisionHints = model.tags)
            }
            DownloadControl(progress != null, isInstalled, actions.onDownload, actions.onCancel)
        }
        DownloadProgress(progress)
        OpenPageLink(actions.onOpenPage)
    }
}

/** A file-tree-derived, honest "may not run yet" label (see [com.phonetts.core.download.hf.
 * HfCompatibility]) — greyed out, but never disables the Download button below it: the user may
 * still want the weights on disk ahead of a future engine (spec rule 1: no hardcoded model list). */
@Composable
private fun NotYetSupportedBadge() {
    Text(
        "Not yet supported — may not run on this app yet",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = NOT_SUPPORTED_ALPHA),
    )
}

/** Issue #7 + bug #4: the repo's download size isn't known until its file tree is fetched, so this
 * shows "Checking size…" briefly (the fetch now starts automatically — see [ModelRow]'s
 * `LaunchedEffect` — never behind a tap) and then the computed (possibly partial) total inline,
 * with no gating button. */
@Composable
private fun SizeLine(sizeState: SizeState) {
    val estimate = sizeState.estimate
    if (estimate != null) {
        val suffix = if (estimate.isExact) "" else " (partial — some file sizes unknown)"
        Text("Download size: ${formatSize(estimate)}$suffix", style = MaterialTheme.typography.bodySmall)
        return
    }
    if (sizeState.loading) {
        Text("Checking size…", style = MaterialTheme.typography.bodySmall)
        return
    }
    // Neither loading nor known: the automatic fetch hasn't started yet this frame, or it failed
    // (see HfBrowseViewModel.loadSize's onFailure — a network hiccup just clears the loading flag,
    // never fabricates a number). Either way, no size to show and no button to gate it behind.
    Text("Download size unavailable", style = MaterialTheme.typography.bodySmall)
}

/**
 * Bug #5: a parameter-count + predicted-speed hint, derived purely from a total byte size (already
 * on hand once the download size is known — no extra network call) via the pure [ModelSpeedEstimate]
 * formula in `:core`. Both numbers are estimates and are labeled as such — the formula runs
 * identically for every model, curated or browsed, with no per-model fact hardcoded here (spec
 * rule 1). Renders nothing until [totalBytes] is known, since there's nothing honest to estimate
 * from yet.
 */
@Composable
private fun SpeedHintLine(
    totalBytes: Long?,
    precisionHints: List<String>,
) {
    if (totalBytes == null || totalBytes <= 0L) return
    val speed = remember(totalBytes, precisionHints) { ModelSpeedEstimate.from(totalBytes, precisionHints) }
    Text(
        "~${formatParamCount(speed.paramCount)} params · " +
            "~${formatRealtimeMultiple(speed.realtimeMultiple)}x real-time (both estimated)",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Opens the model's Hugging Face page (its README / model card / files) in the browser. */
@Composable
private fun OpenPageLink(onOpenPage: () -> Unit) {
    TextButton(onClick = onOpenPage) { Text("View on Hugging Face ↗") }
}

/** A model card's Delete/Download row is followed by a progress bar when a download is in flight.
 * Shows bytes/ETA once a total size and a measured throughput are both known (issue #7) — never a
 * fabricated rate; falls back to the file-count text otherwise. */
@Composable
private fun DownloadProgress(progress: HfDownloadProgress?) {
    if (progress == null) return
    val now = System.currentTimeMillis()
    val bytesTotal = progress.bytesTotal
    // Progress is file-count based when the plan's byte total isn't known (a repo file the tree
    // omitted a size for) — most of these repos are 1-2 large weight files, so a single-file repo
    // with no size would sit at an uninformative 0% the whole time; show it as indeterminate instead.
    if (bytesTotal == null) {
        if (progress.filesTotal <= 1) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        } else {
            LinearProgressIndicator(
                progress = { progress.filesDone.toFloat() / progress.filesTotal },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        return
    }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        LinearProgressIndicator(
            progress = { (progress.bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        val etaText = progress.etaSeconds(now)?.let { " · ≈${formatDuration(it)} left" } ?: ""
        Text(
            "${formatBytes(progress.bytesDone)} / ${formatBytes(bytesTotal)}$etaText",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Groups a model's info + controls into a visually distinct card instead of a bare list row. */
@Composable
private fun ModelCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun DownloadControl(
    isDownloading: Boolean,
    isInstalled: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    if (isInstalled) {
        Text("Installed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        return
    }
    if (isDownloading) {
        // Cancel stops the fetch and leaves any partial file on disk, so re-tapping Download resumes.
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
        return
    }
    Button(onClick = onDownload) { Text("Download") }
}

private fun formatSize(estimate: HfSizeEstimate): String {
    val prefix = if (estimate.isExact) "" else "≥"
    return "$prefix${formatBytes(estimate.knownBytes)}"
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= MB_TO_GB_THRESHOLD) return "%.2f GB".format(mb / 1024.0)
    return "%.1f MB".format(mb)
}

private fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds.roundToLong().coerceAtLeast(0L)
    if (totalSeconds < SECONDS_PER_MINUTE) return "${totalSeconds}s"
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    if (minutes < MINUTES_PER_HOUR) return "${minutes}m"
    val hours = minutes / MINUTES_PER_HOUR
    return "${hours}h ${minutes % MINUTES_PER_HOUR}m"
}

/** Bug #5 display helper: an estimated parameter count as a compact "82M"/"1.2B" label. */
private fun formatParamCount(count: Long): String {
    if (count <= 0L) return "?"
    val millions = count / 1_000_000.0
    if (millions >= THOUSAND) return "%.1fB".format(millions / THOUSAND)
    if (millions >= 1.0) return "%.0fM".format(millions)
    val thousands = count / 1_000.0
    if (thousands >= 1.0) return "%.0fK".format(thousands)
    return count.toString()
}

/** Bug #5 display helper: one decimal place is plenty of precision for a heuristic estimate. */
private fun formatRealtimeMultiple(multiple: Double): String = "%.1f".format(multiple)

private const val MAX_TAGS_SHOWN = 4
private const val BYTES_PER_MB = 1024L * 1024L
private const val PARAMS_PER_M = 1_000_000.0
private const val THOUSAND = 1000.0
private const val MB_TO_GB_THRESHOLD = 1024.0
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val NOT_SUPPORTED_ALPHA = 0.5f
