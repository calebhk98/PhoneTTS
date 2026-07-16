package com.phonetts.app.hf

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonetts.core.download.hf.HfModelSummary

/**
 * Browse Hugging Face text-to-speech models and download one. The whole screen is derived from the
 * catalog + the resolver — it hardcodes no model. A downloaded repo the resolver can't identify
 * still lands in the catalog via the user-pick fallback (spec §6.2), so "browse everything" degrades
 * gracefully instead of failing.
 */
@Composable
fun HfBrowseScreen(viewModel: HfBrowseViewModel) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Search Hugging Face TTS models") },
            )
            Button(onClick = viewModel::search, enabled = !state.loading) { Text("Search") }
        }

        state.error?.let { Text("Error: $it") }
        if (state.loading) CircularProgressIndicator()

        if (viewModel.recommended.isNotEmpty()) {
            Text("Recommended (one-tap)", fontWeight = FontWeight.Bold)
            viewModel.recommended.forEach { model ->
                RecommendedRow(
                    model = model,
                    isDownloading = state.downloadingId == model.id,
                    progress = state.progress.takeIf { state.downloadingId == model.id },
                    isImported = state.importedModelId == model.id,
                    onDownload = { viewModel.downloadBuiltIn(model) },
                )
            }
            Text("Or search Hugging Face:")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.results, key = { it.id }) { model ->
                ModelRow(
                    model = model,
                    isDownloading = state.downloadingId == model.id,
                    progress = state.progress.takeIf { state.downloadingId == model.id },
                    isImported = state.importedModelId == model.id,
                    onDownload = { viewModel.download(model) },
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
    isImported: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.displayName, fontWeight = FontWeight.Bold)
            Text("~${model.approxSizeMb} MB${model.note?.let { " · $it" } ?: ""}")
        }
        DownloadControl(isDownloading, progress, isImported, onDownload)
    }
}

@Composable
private fun ModelRow(
    model: HfModelSummary,
    isDownloading: Boolean,
    progress: Pair<Int, Int>?,
    isImported: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.id, fontWeight = FontWeight.Bold)
            Text("▼ ${model.downloads}   ♥ ${model.likes}")
        }
        DownloadControl(isDownloading, progress, isImported, onDownload)
    }
}

@Composable
private fun DownloadControl(
    isDownloading: Boolean,
    progress: Pair<Int, Int>?,
    isImported: Boolean,
    onDownload: () -> Unit,
) {
    if (isImported) {
        Text("Installed")
        return
    }
    if (isDownloading) {
        Text(progress?.let { (done, total) -> "$done/$total" } ?: "…")
        return
    }
    Button(onClick = onDownload) { Text("Download") }
}
