package com.phonetts.app.hf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.phonetts.core.download.hf.HfEndpoints
import com.phonetts.core.download.hf.HfModelSummary

/**
 * Browse Hugging Face text-to-speech models and download one. The whole screen is derived from the
 * catalog + the resolver — it hardcodes no model. A downloaded repo the resolver can't identify
 * still lands in the catalog via the user-pick fallback (spec §6.2), so "browse everything" degrades
 * gracefully instead of failing.
 *
 * Layout: search box at the very top, then the live results directly beneath it, then the curated
 * one-tap "Recommended" models below — all in one scrolling list, so the recommended block never
 * wedges between the search box and its results. The list is pre-populated on open with the top TTS
 * models (see [HfBrowseViewModel]'s init) so there's more than a handful to see before you type.
 */
@Composable
fun HfBrowseScreen(viewModel: HfBrowseViewModel) {
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current

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

        state.error?.let { Text("Error: $it") }
        if (state.loading) CircularProgressIndicator()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.results, key = { "hf:${it.id}" }) { model ->
                ModelRow(
                    model = model,
                    isDownloading = state.downloadingId == model.id,
                    progress = state.progress.takeIf { state.downloadingId == model.id },
                    isInstalled = viewModel.isInstalled(model.id),
                    onDownload = { viewModel.download(model) },
                    onOpenPage = { uriHandler.openUri(HfEndpoints.modelPageUrl(model.id)) },
                )
            }

            if (viewModel.recommended.isNotEmpty()) {
                item(key = "recommended-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        Text("Recommended (one-tap)", fontWeight = FontWeight.Bold)
                    }
                }
                items(viewModel.recommended, key = { "rec:${it.id}" }) { model ->
                    RecommendedRow(
                        model = model,
                        isDownloading = state.downloadingId == model.id,
                        progress = state.progress.takeIf { state.downloadingId == model.id },
                        isInstalled = viewModel.isInstalled(model.id),
                        onDownload = { viewModel.downloadBuiltIn(model) },
                        onOpenPage = { uriHandler.openUri(HfEndpoints.modelPageUrl(model.repoId)) },
                    )
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
}

/** Lets the user pick which weight precision to download when a repo ships more than one. */
@Composable
private fun VariantPicker(
    choice: HfBrowseViewModel.VariantChoice,
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
                    Button(onClick = { onPick(variant) }, modifier = Modifier.fillMaxWidth()) {
                        Text(variant.name)
                    }
                }
            }
        },
    )
}

@Composable
private fun RecommendedRow(
    model: com.phonetts.core.download.builtin.BuiltInModel,
    isDownloading: Boolean,
    progress: Pair<Int, Int>?,
    isInstalled: Boolean,
    onDownload: () -> Unit,
    onOpenPage: () -> Unit,
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
            DownloadControl(isDownloading, progress, isInstalled, onDownload)
        }
        DownloadProgress(isDownloading, progress)
        OpenPageLink(onOpenPage)
    }
}

@Composable
private fun ModelRow(
    model: HfModelSummary,
    isDownloading: Boolean,
    progress: Pair<Int, Int>?,
    isInstalled: Boolean,
    onDownload: () -> Unit,
    onOpenPage: () -> Unit,
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
            }
            DownloadControl(isDownloading, progress, isInstalled, onDownload)
        }
        DownloadProgress(isDownloading, progress)
        OpenPageLink(onOpenPage)
    }
}

/** Opens the model's Hugging Face page (its README / model card / files) in the browser. */
@Composable
private fun OpenPageLink(onOpenPage: () -> Unit) {
    TextButton(onClick = onOpenPage) { Text("View on Hugging Face ↗") }
}

/** A model card's Delete/Download row is followed by a progress bar when a download is in flight. */
@Composable
private fun DownloadProgress(
    isDownloading: Boolean,
    progress: Pair<Int, Int>?,
) {
    if (!isDownloading) return
    val (done, total) = progress ?: (0 to 0)
    // Progress is file-count based (how many of this repo's files have finished), not byte-based —
    // most of these repos are 1-2 large weight files, so a single-file repo would sit at an
    // uninformative 0% the whole time; show it as indeterminate instead of a bar that never moves.
    if (total <= 1) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    } else {
        LinearProgressIndicator(
            progress = { done.toFloat() / total },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
    progress: Pair<Int, Int>?,
    isInstalled: Boolean,
    onDownload: () -> Unit,
) {
    if (isInstalled) {
        Text("Installed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        return
    }
    if (isDownloading) {
        Text(progress?.let { (done, total) -> "$done/$total files" } ?: "Starting…")
        return
    }
    Button(onClick = onDownload) { Text("Download") }
}

private const val MAX_TAGS_SHOWN = 4
