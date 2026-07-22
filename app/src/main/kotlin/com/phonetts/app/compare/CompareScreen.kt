package com.phonetts.app.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor

/** Which comparison flow the screen is showing. Purely a UI switch — both drive the same [CompareViewModel]. */
private enum class CompareMode { AB, TOURNAMENT }

/**
 * Opt-in compare screen (issue #19-6, extended by issue #11): pick a model+voice for A and B,
 * synthesize the SAME text on each through [CompareViewModel]'s one generation path, and play them
 * back-to-back with clear labels — OR switch to tournament mode, where several model+voice picks
 * are judged two at a time, blind, in a single-elimination bracket, until a ranking is revealed.
 * NOT part of the normal reading flow — reached only from the drawer (the owner's call: "an option,
 * a new screen or toggle, not default").
 */
@Composable
fun CompareScreen(viewModel: CompareViewModel) {
    val state by viewModel.state.collectAsState()
    var mode by remember { mutableStateOf(CompareMode.AB) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Synthesizes the same text on two or more model/voice picks so you can compare them " +
                "directly — either a straight A/B, or a blind tournament bracket that ranks several at once.",
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
            CompareMode.AB -> AbCompareSection(viewModel, state)
            CompareMode.TOURNAMENT -> TournamentSection(viewModel, state)
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
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (playing) "Voice $label — playing…" else "Voice $label",
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
            OutlinedButton(onClick = onReplay, enabled = hasResult && !playing) { Text("Replay $label") }
        }
    }
}

// ---- Tournament / bracket mode (issue #11) -----------------------------------------------------

@Composable
private fun TournamentSection(
    viewModel: CompareViewModel,
    state: CompareViewModel.UiState,
) {
    val t = state.tournament
    val controller = viewModel.tournamentController

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Add several model+voice picks below, then judge them two at a time. You'll only see " +
                "\"Voice 1\" / \"Voice 2\" while judging — identities are revealed once the bracket finishes.",
            style = MaterialTheme.typography.bodySmall,
        )

        when {
            t.complete -> {
                RankingTable(t.revealedRanking)
                Button(onClick = controller::stop) { Text("New tournament") }
            }
            t.running -> {
                ActiveMatchCard(t, onPick = controller::pickWinner)
                OutlinedButton(onClick = controller::stop) { Text("Cancel tournament") }
            }
            else -> {
                RosterBuilder(
                    models = state.models,
                    roster = t.roster,
                    onAdd = controller::addEntry,
                    onRemove = controller::removeEntry,
                )
                Button(
                    onClick = controller::start,
                    enabled = t.roster.size >= 2 && state.text.isNotBlank(),
                ) { Text("Start blind tournament (${t.roster.size} entries)") }
            }
        }

        if (t.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        t.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun RosterBuilder(
    models: List<ModelDescriptor>,
    roster: List<CompareViewModel.TournamentEntry>,
    onAdd: (ModelDescriptor, String) -> Unit,
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
            Button(onClick = { onAdd(pendingModel, pendingVoice) }) { Text("Add to bracket") }
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
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Round ${t.roundNumber ?: 1} — blind pick", style = MaterialTheme.typography.titleMedium)
            BlindSlotsRow(t, onPick)
        }
    }
}

@Composable
private fun BlindSlotsRow(
    t: CompareViewModel.TournamentUiState,
    onPick: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BlindSlot(slot = 1, ready = t.slot1Ready, playing = t.playingSlot == 1, canPick = t.canPick, onPick = onPick)
        BlindSlot(slot = 2, ready = t.slot2Ready, playing = t.playingSlot == 2, canPick = t.canPick, onPick = onPick)
    }
}

@Composable
private fun BlindSlot(
    slot: Int,
    ready: Boolean,
    playing: Boolean,
    canPick: Boolean,
    onPick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val label =
            when {
                playing -> "Voice $slot — playing…"
                ready -> "Voice $slot — ready"
                else -> "Voice $slot — generating…"
            }
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { onPick(slot) }, enabled = canPick) { Text("Choose $slot") }
    }
}

@Composable
private fun RankingTable(rows: List<CompareViewModel.RevealedRankRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Final ranking — identities revealed", style = MaterialTheme.typography.titleMedium)
        rows.sortedBy { it.place }.forEach { row ->
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("#${row.place} — ${row.label}", modifier = Modifier.weight(1f))
                    Text(row.realTimeFactor?.let { "RTF %.2f×".format(it) } ?: "RTF —")
                }
                Text("Judged wins: ${row.winsRecorded}", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
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
            modifier = Modifier.menuAnchor().fillMaxWidth(),
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
            modifier = Modifier.menuAnchor().fillMaxWidth(),
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
