package com.phonetts.app.compare

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor
import java.text.DateFormat
import java.util.Date

/** Which comparison flow the screen is showing. Purely a UI switch - both drive the same [CompareViewModel]. */
private enum class CompareMode { AB, TOURNAMENT }

/**
 * Opt-in compare screen (issue #19-6, extended by issue #11): pick a model+voice for A and B,
 * synthesize the SAME text on each through [CompareViewModel]'s one generation path, and play them
 * back-to-back with clear labels - OR switch to tournament mode, where several model+voice picks
 * are judged two at a time, blind, in a single-elimination bracket, until a ranking is revealed.
 * NOT part of the normal reading flow - reached only from the drawer (the owner's call: "an option,
 * a new screen or toggle, not default").
 */
@Composable
fun CompareScreen(viewModel: CompareViewModel) {
    val state by viewModel.state.collectAsState()
    var mode by remember { mutableStateOf(CompareMode.AB) }
    val context = LocalContext.current

    // Re-read the catalog every time this screen is (re)entered - the ViewModel is Activity-scoped
    // and otherwise keeps the model list it snapshotted the FIRST time Compare was ever opened, so
    // anything downloaded/discovered since was missing from the pickers and the tournament roster.
    LaunchedEffect(Unit) { viewModel.refreshModels() }

    // Save-to-file launchers (issue: "replay + save + relisten") - a SAF "Save As" picker per side,
    // same pattern TtsScreen's export launcher uses; the ViewModel/controller do the actual encode.
    val saveALauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
            uri?.let { context.contentResolver.openOutputStream(it)?.let(viewModel::saveA) }
        }
    val saveBLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
            uri?.let { context.contentResolver.openOutputStream(it)?.let(viewModel::saveB) }
        }
    // One shared launcher for tournament entries - CreateDocument only returns a Uri, so the entry
    // id it's saving is stashed here between "Save" and the picker's callback.
    var pendingSaveEntryId by remember { mutableStateOf<String?>(null) }
    val saveEntryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
            val entryId = pendingSaveEntryId
            pendingSaveEntryId = null
            if (uri != null && entryId != null) {
                context.contentResolver.openOutputStream(uri)?.let { viewModel.tournamentController.saveEntry(entryId, it) }
            }
        }
    val onSaveEntry: (String) -> Unit = { entryId ->
        pendingSaveEntryId = entryId
        saveEntryLauncher.launch("compare-$entryId.wav")
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Synthesizes the same text on two or more model/voice picks so you can compare them " +
                "directly - either a straight A/B, or a blind tournament bracket that ranks several at once.",
            style = MaterialTheme.typography.bodySmall,
        )

        ModeSelector(mode) { mode = it }

        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::setText,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Text to compare") },
            minLines = 3,
        )

        when (mode) {
            CompareMode.AB ->
                AbCompareSection(
                    viewModel = viewModel,
                    state = state,
                    onSaveA = { saveALauncher.launch("compare-a.wav") },
                    onSaveB = { saveBLauncher.launch("compare-b.wav") },
                )
            CompareMode.TOURNAMENT -> TournamentSection(viewModel, state, onSaveEntry)
        }
    }
}

@Composable
private fun ModeSelector(
    mode: CompareMode,
    onChange: (CompareMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeButton("A/B", selected = mode == CompareMode.AB) { onChange(CompareMode.AB) }
        ModeButton("Tournament", selected = mode == CompareMode.TOURNAMENT) { onChange(CompareMode.TOURNAMENT) }
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

// ---- A/B mode (issue #19-6) -------------------------------------------------------------------

@Composable
private fun AbCompareSection(
    viewModel: CompareViewModel,
    state: CompareViewModel.UiState,
    onSaveA: () -> Unit,
    onSaveB: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SlotCard(
            label = "A",
            models = state.models,
            selection = state.a,
            onSelectModel = viewModel::selectModelA,
            onSelectVoice = viewModel::selectVoiceA,
            playing = state.playing == CompareViewModel.Slot.A,
            hasResult = state.hasResultA,
            onReplay = viewModel::replayA,
            onSave = onSaveA,
        )
        SlotCard(
            label = "B",
            models = state.models,
            selection = state.b,
            onSelectModel = viewModel::selectModelB,
            onSelectVoice = viewModel::selectVoiceB,
            playing = state.playing == CompareViewModel.Slot.B,
            hasResult = state.hasResultB,
            onReplay = viewModel::replayB,
            onSave = onSaveB,
        )

        val canRun = !state.busy && state.text.isNotBlank() && state.a.descriptor != null && state.b.descriptor != null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::runComparison, enabled = canRun) {
                Text(if (state.busy) "Running…" else "Generate & play A then B")
            }
            if (state.busy) OutlinedButton(onClick = viewModel::stop) { Text("Stop") }
        }

        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SlotCard(
    label: String,
    models: List<ModelDescriptor>,
    selection: CompareViewModel.Selection,
    onSelectModel: (ModelDescriptor) -> Unit,
    onSelectVoice: (String) -> Unit,
    playing: Boolean,
    hasResult: Boolean,
    onReplay: () -> Unit,
    onSave: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (playing) "Voice $label - playing…" else "Voice $label",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (models.isEmpty()) {
                Text("No models downloaded yet.")
                return@Column
            }
            CompareModelPicker(models, selection.descriptor, onSelectModel)
            CompareVoicePicker(selection.voices, selection.voiceId, onSelectVoice)
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReplay, enabled = hasResult && !playing) { Text("Replay $label") }
                // Exports this side's clip to a .wav file (system file picker), not a "favorite".
                OutlinedButton(onClick = onSave, enabled = hasResult) { Text("Export $label") }
            }
        }
    }
}

// ---- Tournament / bracket mode (issue #11) -----------------------------------------------------

@Composable
private fun TournamentSection(
    viewModel: CompareViewModel,
    state: CompareViewModel.UiState,
    onSaveEntry: (String) -> Unit,
) {
    val t = state.tournament
    val controller = viewModel.tournamentController
    var showErrorLog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Add several model+voice picks below, then judge them two at a time. You'll only see " +
                "\"Voice 1\" / \"Voice 2\" while judging - identities are revealed once the bracket finishes.",
            style = MaterialTheme.typography.bodySmall,
        )

        when {
            t.complete -> {
                RankingTable(
                    rows = t.revealedRanking,
                    favoriteModelIds = t.favoriteModelIds,
                    onReplay = controller::replayEntry,
                    onSave = onSaveEntry,
                    onToggleFavorite = controller::toggleFavoriteResultModel,
                )
                if (t.flaggedReview.isNotEmpty()) {
                    FlaggedReviewSection(t.flaggedReview, onDelete = controller::deleteFlaggedModel)
                }
                Button(onClick = controller::stop) { Text("New tournament") }
            }
            t.running -> {
                ActiveMatchCard(
                    t = t,
                    onPick = controller::pickWinner,
                    onReplay = controller::replayEntry,
                    onFlag = controller::flagSlot,
                )
                OutlinedButton(onClick = controller::stop) { Text("Cancel tournament") }
            }
            else -> {
                RosterBuilder(
                    models = state.models,
                    roster = t.roster,
                    onAdd = controller::addEntry,
                    onAddAll = { controller.addAllModels(state.models) },
                    onRemove = controller::removeEntry,
                )
                Button(
                    onClick = controller::start,
                    enabled = t.roster.size >= 2 && state.text.isNotBlank(),
                ) { Text("Start blind tournament (${t.roster.size} entries)") }
            }
        }

        // A failing voice auto-fails (its opponent advances without a pick) rather than blocking the
        // whole tournament - every such failure lands here, copyable, so it can be reported/debugged.
        if (t.errors.isNotEmpty()) {
            TextButton(onClick = { showErrorLog = true }) { Text("Errors (${t.errors.size})") }
        }

        if (t.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        t.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    if (showErrorLog) {
        TournamentErrorLogDialog(errors = t.errors, onDismiss = { showErrorLog = false })
    }
}

/** Every auto-failed tournament voice this run (issue: "auto-fail a failing voice + copyable error
 * log"): selectable text plus a "Copy all" button, mirroring Browse's `ErrorLogDialog`. */
@Composable
private fun TournamentErrorLogDialog(
    errors: List<CompareViewModel.TournamentError>,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val formatter = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val fullText = remember(errors) { errors.joinToString("\n\n") { formatTournamentErrorLine(it, formatter) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(fullText)) }) { Text("Copy all") }
        },
        title = { Text("Tournament errors") },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    errors.forEach { error ->
                        Text(formatTournamentErrorLine(error, formatter), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

private fun formatTournamentErrorLine(
    error: CompareViewModel.TournamentError,
    formatter: DateFormat,
): String {
    val time = formatter.format(Date(error.atMs))
    return "[$time] ${error.label} - ${error.message}"
}

@Composable
private fun RosterBuilder(
    models: List<ModelDescriptor>,
    roster: List<CompareViewModel.TournamentEntry>,
    onAdd: (ModelDescriptor, String) -> Unit,
    onAddAll: () -> Unit,
    onRemove: (String) -> Unit,
) {
    if (models.isEmpty()) {
        Text("No models downloaded yet.")
        return
    }

    var pendingModel by remember(models) { mutableStateOf(models.first()) }
    var pendingVoice by remember(pendingModel) { mutableStateOf(pendingModel.defaultVoiceId) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add an entry to the bracket", style = MaterialTheme.typography.titleMedium)
            CompareModelPicker(models, pendingModel) {
                pendingModel = it
                pendingVoice = it.defaultVoiceId
            }
            CompareVoicePicker(pendingModel.voices, pendingVoice) { pendingVoice = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAdd(pendingModel, pendingVoice) }) { Text("Add to bracket") }
                // Convenience so you don't have to add every model by hand for a full-field bracket
                // (each is added with its default voice; already-added picks are skipped).
                OutlinedButton(onClick = onAddAll) { Text("Add all models") }
            }
        }
    }

    if (roster.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Bracket roster (${roster.size})", style = MaterialTheme.typography.titleMedium)
        roster.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.label, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onRemove(entry.id) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun ActiveMatchCard(
    t: CompareViewModel.TournamentUiState,
    onPick: (Int) -> Unit,
    onReplay: (String) -> Unit,
    onFlag: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(roundHeadline(t), style = MaterialTheme.typography.titleMedium)
            BlindSlotsRow(t, onPick, onReplay, onFlag)
        }
    }
}

// "Round R - comparison (X/N)" when the within-round counter is known (issue #113b), else a plain
// "Round R - blind pick". The counter is anonymous, so showing it never breaks the blind comparison.
private fun roundHeadline(t: CompareViewModel.TournamentUiState): String {
    val round = t.roundNumber ?: 1
    val current = t.comparisonInRound
    val total = t.comparisonsInRound
    if (current == null || total == null) return "Round $round - blind pick"
    return "Round $round - comparison ($current/$total)"
}

@Composable
private fun BlindSlotsRow(
    t: CompareViewModel.TournamentUiState,
    onPick: (Int) -> Unit,
    onReplay: (String) -> Unit,
    onFlag: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BlindSlot(
            slot = 1,
            entryId = t.slot1Id,
            ready = t.slot1Ready,
            playing = t.playingSlot == 1,
            flagged = t.slot1Flagged,
            canPick = t.canPick,
            onPick = onPick,
            onReplay = onReplay,
            onFlag = onFlag,
        )
        BlindSlot(
            slot = 2,
            entryId = t.slot2Id,
            ready = t.slot2Ready,
            playing = t.playingSlot == 2,
            flagged = t.slot2Flagged,
            canPick = t.canPick,
            onPick = onPick,
            onReplay = onReplay,
            onFlag = onFlag,
        )
    }
}

// One blind slot's card: while judging, [entryId] is an OPAQUE contender id (never a label) - just
// enough for "Replay"/"Flag" to act on the already-generated audio / underlying model without
// regenerating, without revealing which model/voice it is (issues: "replay + relisten", #113d
// downvote). The flag toggle acts on the hidden model behind the slot, staying blind. There is no
// export here on purpose: saving a still-anonymous clip to a compare-entry-N.wav file mid-judging
// has no clear use and only crowded the choose/replay/flag actions - exporting lives on the final
// ranking page, once identities are revealed and the file can be named for its model.
@Composable
private fun BlindSlot(
    slot: Int,
    entryId: String?,
    ready: Boolean,
    playing: Boolean,
    flagged: Boolean,
    canPick: Boolean,
    onPick: (Int) -> Unit,
    onReplay: (String) -> Unit,
    onFlag: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val label =
            when {
                playing -> "Voice $slot - playing…"
                ready -> "Voice $slot - ready"
                else -> "Voice $slot - generating…"
            }
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { onPick(slot) }, enabled = canPick) { Text("Choose $slot") }
        OutlinedButton(onClick = { entryId?.let(onReplay) }, enabled = ready && !playing) { Text("Replay $slot") }
        OutlinedButton(onClick = { onFlag(slot) }, enabled = ready) {
            Text(if (flagged) "Flagged - undo" else "Flag as bad")
        }
    }
}

@Composable
private fun RankingTable(
    rows: List<CompareViewModel.RevealedRankRow>,
    favoriteModelIds: Set<String>,
    onReplay: (String) -> Unit,
    onSave: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Final ranking - identities revealed", style = MaterialTheme.typography.titleMedium)
        rows.sortedBy { it.place }.forEach { row ->
            // A row with no RTF never got a real synthesis result (a same-pairing double-failure -
            // see TournamentController.advance()'s kdoc), so there is no cached audio to replay/save.
            val hasAudio = row.realTimeFactor != null
            val isFavorite = row.modelId in favoriteModelIds
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("#${row.place} - ${row.label}", modifier = Modifier.weight(1f))
                    Text(row.realTimeFactor?.let { "RTF %.2fx".format(it) } ?: "RTF -")
                }
                Text("Judged wins: ${row.winsRecorded}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onReplay(row.entryId) }, enabled = hasAudio) { Text("Replay") }
                    // "Export WAV", not "Save": this writes the clip to a .wav file via the system
                    // file picker - it is not "save as my favorite" (that's the Favorite button beside
                    // it). The old "Save" label read as favoriting and confused which button did what.
                    OutlinedButton(onClick = { onSave(row.entryId) }, enabled = hasAudio) { Text("Export WAV") }
                    OutlinedButton(onClick = { onToggleFavorite(row.modelId) }) {
                        Text(if (isFavorite) "Favorited" else "Favorite")
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

/** Models the user downvoted during the run, surfaced once identities are revealed (issue #113d):
 * each can be deleted (through the app's normal model-delete path) or the whole list copied to a
 * shareable log to investigate later (e.g. an "English" slot that was actually a Russian model). */
@Composable
private fun FlaggedReviewSection(
    rows: List<CompareViewModel.FlaggedReviewRow>,
    onDelete: (String) -> Unit,
) {
    var showLog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Flagged models (${rows.size})", style = MaterialTheme.typography.titleMedium)
        Text(
            "Downvoted during this tournament. Delete one to reclaim its space, or copy the list to " +
                "share/investigate.",
            style = MaterialTheme.typography.bodySmall,
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.label, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onDelete(row.modelId) }) { Text("Delete") }
            }
        }
        TextButton(onClick = { showLog = true }) { Text("Copy flagged log") }
    }

    if (showLog) {
        FlaggedLogDialog(rows = rows, onDismiss = { showLog = false })
    }
}

/** Selectable text of every flagged model plus a "Copy all" button, mirroring [TournamentErrorLogDialog]
 * so the export affordance is the same familiar pattern (issue #113d "copy/export them to a log"). */
@Composable
private fun FlaggedLogDialog(
    rows: List<CompareViewModel.FlaggedReviewRow>,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val fullText = remember(rows) { rows.joinToString("\n") { "${it.label} (${it.modelId})" } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(fullText)) }) { Text("Copy all") }
        },
        title = { Text("Flagged models") },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rows.forEach { row ->
                        Text("${row.label} (${row.modelId})", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareModelPicker(
    models: List<ModelDescriptor>,
    selected: ModelDescriptor?,
    onSelect: (ModelDescriptor) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        TextField(
            value = selected?.displayName ?: "Select a model",
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName) },
                    onClick = {
                        onSelect(model)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareVoicePicker(
    voices: List<Voice>,
    selectedVoiceId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = voices.firstOrNull { it.id == selectedVoiceId }?.name ?: "Select a voice"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        TextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Voice") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text("${voice.name} (${voice.language})") },
                    onClick = {
                        onSelect(voice.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
