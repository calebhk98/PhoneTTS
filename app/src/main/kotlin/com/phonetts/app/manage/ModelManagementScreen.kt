package com.phonetts.app.manage

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonetts.core.model.Origin
import com.phonetts.core.registry.ModelUsage
import kotlin.math.ln
import kotlin.math.pow

/**
 * Lists every model currently in the catalog with its on-disk footprint and a Delete button
 * (spec §1.1.6, "removable models" finished with a UI). Deletion is fully derived from the
 * catalog too: no model is named here, so a newly registered model just shows up on next
 * [ModelManagementViewModel.refresh] with no code change (same SSOT discipline as the model
 * dropdown).
 *
 * Not wired into app navigation by this change — callers add a nav entry that constructs
 * [ModelManagementViewModel] from a [com.phonetts.core.registry.ModelManager] and passes it here.
 */
@Composable
fun ModelManagementScreen(viewModel: ModelManagementViewModel) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Storage used: ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodyLarge)

        state.error?.let { Text("Error: $it") }

        if (state.usage.isEmpty()) {
            Text("No models downloaded yet.")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.usage, key = { it.descriptor.modelId }) { usage ->
                ModelUsageRow(
                    usage = usage,
                    isDeleting = state.deletingId == usage.descriptor.modelId,
                    onDelete = { viewModel.delete(usage.descriptor.modelId) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ModelUsageRow(
    usage: ModelUsage,
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
        }
        DeleteControl(isDeleting, onDelete)
    }
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
