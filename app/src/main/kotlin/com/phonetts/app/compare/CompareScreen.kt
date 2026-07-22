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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor

/**
 * Opt-in A/B compare (issue #19-6): pick a model+voice for A and B, synthesize the SAME text on
 * each through [CompareViewModel]'s one generation path, and play them back-to-back with clear
 * labels. NOT part of the normal reading flow — reached only from the drawer (the owner's call:
 * "an option, a new screen or toggle, not default").
 */
@Composable
fun CompareScreen(viewModel: CompareViewModel) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Synthesizes the same text with two different model/voice picks and plays A then B, so " +
                "you can compare them directly.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::setText,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Text to compare") },
            minLines = 3,
        )

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
