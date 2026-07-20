package com.phonetts.app.benchmark

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Compare every downloaded model's real generation speed on the same phrase, and copy the numbers
 * out (Markdown) to paste into notes or an issue. Everything is measured by [BenchmarkViewModel] via
 * the metrics seam — no guessed figures.
 */
@Composable
fun BenchmarkScreen(viewModel: BenchmarkViewModel) {
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Runs each downloaded model on the same phrase and measures its speed. RTF is wall-clock " +
                "per second of audio — below 1.0× is faster than real-time.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::run, enabled = !state.running) {
                Text(if (state.running) "Running…" else "Run benchmarks")
            }
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(viewModel.resultsAsMarkdown())) },
                enabled = state.rows.any { it.ok } && !state.running,
            ) { Text("Copy results") }
        }

        if (state.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        if (state.rows.isNotEmpty()) ResultsTable(state.rows)
    }
}

@Composable
private fun ResultsTable(rows: List<BenchmarkViewModel.Row>) {
    // The table can be wider than the screen (long model names); let it scroll sideways on its own
    // rather than forcing the page to.
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        TableRow("Model", "RTF", "Audio s", "Wall s", header = true)
        HorizontalDivider()
        rows.forEach { row ->
            if (row.ok) {
                TableRow(row.displayName, fmt(row.realTimeFactor), fmt(row.audioSeconds), fmt(row.wallSeconds))
            } else {
                TableRow(row.displayName, "failed", "—", "—")
            }
        }
    }
}

@Composable
private fun TableRow(
    model: String,
    rtf: String,
    audio: String,
    wall: String,
    header: Boolean = false,
) {
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cell(model, MODEL_COL_WIDTH, weight)
        Cell(rtf, NUM_COL_WIDTH, weight)
        Cell(audio, NUM_COL_WIDTH, weight)
        Cell(wall, NUM_COL_WIDTH, weight)
    }
}

@Composable
private fun Cell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    weight: FontWeight,
) {
    Text(text, modifier = Modifier.width(width), fontWeight = weight, style = MaterialTheme.typography.bodyMedium)
}

private fun fmt(value: Double): String = "%.2f".format(value)

private val MODEL_COL_WIDTH = 200.dp
private val NUM_COL_WIDTH = 72.dp
