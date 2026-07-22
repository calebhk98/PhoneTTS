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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
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
import com.phonetts.core.download.hf.HfDownloadProgress
import com.phonetts.core.download.hf.HfEndpoints
import com.phonetts.core.download.hf.HfModelSummary
import com.phonetts.core.download.hf.HfSizeEstimate
import com.phonetts.core.download.hf.HfSizeEstimator
import com.phonetts.core.download.hf.HfSortOption
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
 * than only flashing by (issue #3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HfBrowseScreen(viewModel: HfBrowseViewModel) {
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    var showErrorLog by remember { mutableStateOf(false) }

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

        SortAndFilterRow(
            sort = state.sort,
            onSortChange = viewModel::onSortChange,
            tagFilter = state.tagFilter,
            availableTags = viewModel.availableTags(),
            onTagFilterChange = viewModel::onTagFilterChange,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            state.error?.let { Text("Error: $it", modifier = Modifier.weight(1f)) }
            if (state.errorLog.isNotEmpty()) {
                TextButton(onClick = { showErrorLog = true }) { Text("Errors (${state.errorLog.size})") }
            }
        }
        if (state.loading) CircularProgressIndicator()

        val displayedResults = viewModel.displayedResults()
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
    }

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
 * in flight, and how to trigger one — bundled into one parameter for the same reason as [RowActions]. */
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
    sizeState: SizeState,
    actions: RowActions,
) {
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
                SizeLine(sizeState)
            }
            DownloadControl(progress != null, isInstalled, actions.onDownload, actions.onCancel)
        }
        DownloadProgress(progress)
        OpenPageLink(actions.onOpenPage)
    }
}

/** Issue #7: the repo's download size isn't known until its file tree is fetched, so this shows a
 * lazy "Size" action until then rather than a guess, then the computed (possibly partial) total. */
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
    TextButton(onClick = sizeState.onLoad) { Text("Show download size") }
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

private const val MAX_TAGS_SHOWN = 4
private const val MB_TO_GB_THRESHOLD = 1024.0
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
