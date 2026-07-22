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
import androidx.compose.material3.TextButton
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

        // Power-user, OFF by default (issue #39): a hidden toggle reveals the persisted speed-trend /
        // thermal-throttling note so casual users aren't confused by normal run-to-run variance.
        TextButton(onClick = viewModel::toggleHistory) {
            Text(if (state.showHistory) "Hide speed-trend (advanced)" else "Show speed-trend (advanced)")
        }
        if (state.showHistory) SpeedTrendSection(state.rows)
    }
}

@Composable
private fun SpeedTrendSection(rows: List<BenchmarkViewModel.Row>) {
    val notes = rows.mapNotNull { row -> row.regressionNote?.let { row.displayName to it } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Speed trend vs your last benchmark on this device. A big slowdown usually means thermal " +
                "throttling on a phone with no dedicated AI chip — let it cool and try again.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (notes.isEmpty()) {
            Text("No regressions — speeds are in line with last time (or this is the first run).")
            return@Column
        }
        notes.forEach { (model, note) -> Text("$model: $note", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ResultsTable(rows: List<BenchmarkViewModel.Row>) {
    // The table can be wider than the screen (long model names); let it scroll sideways on its own
    // rather than forcing the page to.
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        TableRow(
            "Model",
            "RTF",
            "Audio s",
            "Gen s",
            "TTFA s",
            "Load s",
            "RAM used",
            "Est. RAM",
            header = true,
        )
        HorizontalDivider()
        rows.forEach { row -> TableRow(row) }
    }
}

@Composable
private fun TableRow(row: BenchmarkViewModel.Row) {
    if (!row.ok) {
        TableRow(row.displayName, "failed", "—", "—", "—", "—", ram(row.metrics?.processMemoryBytes), ram(row.peakRamBytes))
        return
    }
    TableRow(
        row.displayName,
        fmt(row.realTimeFactor),
        fmt(row.audioSeconds),
        fmt(row.wallSeconds),
        optionalFmt(row.metrics?.timeToFirstAudioSeconds),
        optionalFmt(row.metrics?.modelLoadSeconds),
        ram(row.metrics?.processMemoryBytes),
        ram(row.peakRamBytes),
    )
}

@Composable
private fun TableRow(
    model: String,
    rtf: String,
    audio: String,
    gen: String,
    ttfa: String,
    load: String,
    ramUsed: String,
    estRam: String,
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
        Cell(gen, NUM_COL_WIDTH, weight)
        Cell(ttfa, NUM_COL_WIDTH, weight)
        Cell(load, NUM_COL_WIDTH, weight)
        Cell(ramUsed, NUM_COL_WIDTH, weight)
        Cell(estRam, NUM_COL_WIDTH, weight)
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

// Table columns are narrow, so an unmeasured figure (issue #14: TTFA/load time not available on
// this run) reads as "—" here rather than the fuller "unknown" the Markdown export uses — same
// honesty (never a guessed number), just terser for the fixed-width cell.
private fun optionalFmt(value: Double?): String = value?.let { fmt(it) } ?: "—"

private fun ram(bytes: Long?): String = bytes?.let { "~${it / BYTES_PER_MEBIBYTE} MB" } ?: "?"

private const val BYTES_PER_MEBIBYTE = 1024L * 1024L
private val MODEL_COL_WIDTH = 200.dp
private val NUM_COL_WIDTH = 72.dp
