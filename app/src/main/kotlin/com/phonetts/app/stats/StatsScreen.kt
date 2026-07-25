package com.phonetts.app.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonetts.core.download.hf.DiagnosticsEntry
import com.phonetts.core.download.hf.DiagnosticsKind
import com.phonetts.core.format.ByteFormat
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * The read-only Stats page (issue #130): four sections - Listening, Models & storage, Benchmarks,
 * Downloads - each a [Card] of [ListItem]s rather than a long column of raw [Text] (the app-wide
 * standing complaint this screen was explicitly asked not to repeat). Every figure is read straight
 * from [StatsViewModel.state]; this file does no measuring or estimating of its own. A section with
 * nothing recorded yet says so honestly instead of showing a wall of zeros.
 */
@Composable
fun StatsScreen(viewModel: StatsViewModel) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { ListeningSection(state) }
        item { ModelsAndStorageSection(state) }
        item { BenchmarksSection(state) }
        item { DownloadsSection(state) }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

// Every [ListItem] on this screen is read-only (none are clickable) - without this, its
// headline/supporting/trailing Text each land as their own separate TalkBack stop, so a single fact
// like "Total listening time: 5h 3m" reads as two disconnected swipes instead of one (issue #141 rule
// 3, "merge composite rows"). Shared as one val since the modifier itself carries no per-row state.
private val MergedListItem: Modifier = Modifier.semantics(mergeDescendants = true) {}

// --- Listening ------------------------------------------------------------------------------------

@Composable
private fun ListeningSection(state: StatsViewModel.UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Listening")
        if (!state.hasAnyListeningData) {
            EmptyStateCard(
                "No listening data recorded yet. Generate or play back some text to start tracking " +
                    "total listening time, words spoken, and generation count.",
            )
            return@Column
        }
        Card {
            Column {
                ListItem(
                    headlineContent = { Text("Total listening time") },
                    supportingContent = { Text(formatDuration(state.usage.totalListeningMillis)) },
                    modifier = MergedListItem,
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Words spoken") },
                    supportingContent = { Text(formatCount(state.usage.totalWordsSpoken)) },
                    modifier = MergedListItem,
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Generations run") },
                    supportingContent = { Text(formatCount(state.usage.generationCount)) },
                    modifier = MergedListItem,
                )
                if (state.topPlayedModels.isNotEmpty()) {
                    HorizontalDivider()
                    ListItem(headlineContent = { Text("Most played", fontWeight = FontWeight.Bold) })
                    state.topPlayedModels.forEach { row ->
                        ListItem(
                            headlineContent = { Text(row.displayName) },
                            trailingContent = { Text("${row.playCount}×") },
                            modifier = MergedListItem,
                        )
                    }
                }
            }
        }
    }
}

// --- Models & storage -----------------------------------------------------------------------------

@Composable
private fun ModelsAndStorageSection(state: StatsViewModel.UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Models & storage")
        if (state.modelCount == 0) {
            EmptyStateCard("No models downloaded yet. Add one from the model picker to see storage stats here.")
            return@Column
        }
        Card {
            Column {
                ListItem(
                    headlineContent = { Text("Downloaded models") },
                    supportingContent = { Text(formatCount(state.modelCount.toLong())) },
                    modifier = MergedListItem,
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Storage used") },
                    supportingContent = { Text(ByteFormat.format(state.totalStorageBytes)) },
                    modifier = MergedListItem,
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Favorites & flags") },
                    supportingContent = {
                        Text(
                            "${state.favoriteVoiceCount} favorite voices · " +
                                "${state.favoriteModelCount} favorite models · " +
                                "${state.flaggedModelCount} flagged",
                        )
                    },
                    modifier = MergedListItem,
                )
                state.modelStorageRows.forEach { row ->
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(row.displayName) },
                        supportingContent = { Text(peakRamLabel(row.observedPeakRamBytes)) },
                        trailingContent = { Text(ByteFormat.format(row.sizeBytes)) },
                        modifier = MergedListItem,
                    )
                }
            }
        }
    }
}

private fun peakRamLabel(observedPeakRamBytes: Long?): String =
    observedPeakRamBytes?.let { "Observed peak RAM: ${ByteFormat.format(it)}" } ?: "Peak RAM not yet measured"

// --- Benchmarks ------------------------------------------------------------------------------------

@Composable
private fun BenchmarksSection(state: StatsViewModel.UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Benchmarks")
        if (state.benchmarkTrends.isEmpty()) {
            EmptyStateCard(
                "No benchmark history on this device yet. Run the Benchmarks screen (with history " +
                    "turned on) to start tracking speed trends per engine.",
            )
            return@Column
        }
        Card {
            Column {
                state.benchmarkTrends.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(row.displayName) },
                        supportingContent = { Text("${row.sampleCount} sample(s) · ${trendLabel(row)}") },
                        trailingContent = { Text("${fmt2(row.latestRealTimeFactor)}×") },
                        modifier = MergedListItem,
                    )
                }
            }
        }
    }
}

// A compact "first → latest" trend note. RTF is wall-clock per second of audio: lower is faster, so
// a decrease is an improvement (or normal warm-cache variance) and an increase reads as "slower".
// A single sample has nothing to compare against, so it just states the one measurement.
private fun trendLabel(row: BenchmarkTrendRow): String {
    if (row.sampleCount <= 1) return "first measured at ${fmt2(row.firstRealTimeFactor)}×"
    if (row.firstRealTimeFactor <= 0.0) return "latest ${fmt2(row.latestRealTimeFactor)}×"
    val percentChange = ((row.latestRealTimeFactor - row.firstRealTimeFactor) / row.firstRealTimeFactor) * 100.0
    val rounded = kotlin.math.abs(percentChange.roundToInt())
    return when {
        percentChange <= -1.0 -> "$rounded% faster than first recorded"
        percentChange >= 1.0 -> "$rounded% slower than first recorded"
        else -> "steady since first recorded"
    }
}

// --- Downloads -------------------------------------------------------------------------------------

@Composable
private fun DownloadsSection(state: StatsViewModel.UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Downloads")
        val health = state.downloadHealth
        if (health.failureCount == 0 && health.noEngineYetCount == 0) {
            EmptyStateCard("No download issues recorded - every download so far has completed cleanly.")
            return@Column
        }
        Card {
            Column {
                ListItem(
                    headlineContent = { Text("Failed downloads") },
                    supportingContent = { Text(formatCount(health.failureCount.toLong())) },
                    modifier = MergedListItem,
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Downloaded, no engine yet") },
                    supportingContent = { Text(formatCount(health.noEngineYetCount.toLong())) },
                    modifier = MergedListItem,
                )
                health.recent.forEach { entry ->
                    HorizontalDivider()
                    DiagnosticsRow(entry)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsRow(entry: DiagnosticsEntry) {
    ListItem(
        headlineContent = { Text(entry.modelId) },
        supportingContent = { Text("${entry.detail} · ${formatTimestamp(entry.atMs)}") },
        trailingContent = { Text(kindLabel(entry.kind)) },
        modifier = MergedListItem,
    )
}

private fun formatTimestamp(atMs: Long): String = DateFormat.getDateTimeInstance().format(Date(atMs))

private fun kindLabel(kind: DiagnosticsKind): String =
    when (kind) {
        DiagnosticsKind.FAILURE -> "Failed"
        DiagnosticsKind.NO_ENGINE_YET -> "No engine yet"
    }

// --- shared helpers --------------------------------------------------------------------------------

@Composable
private fun EmptyStateCard(message: String) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatCount(value: Long): String = value.toString()

private fun fmt2(value: Double): String = "%.2f".format(value)

private fun formatDuration(totalMillis: Long): String {
    val totalSeconds = totalMillis / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
