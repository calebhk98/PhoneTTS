package com.phonetts.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.engine.Voice

/**
 * The main screen. Model list, voice list and speed bounds are read entirely from the
 * [com.phonetts.core.registry.ModelCatalog] + the selected [ModelDescriptor] — register a model and
 * it appears here with no UI change (spec §7). Play and Export are the two consumers of the one
 * generation path.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TtsScreen(
    viewModel: TtsViewModel,
    onBrowseModels: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importTextFrom)
        }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
            uri?.let { context.contentResolver.openOutputStream(it)?.let(viewModel::export) }
        }
    val sideloadLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(viewModel::sideloadFolder)
        }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("PhoneTTS")

        if (state.models.isEmpty()) {
            Text("No models yet. Browse Hugging Face or sideload a folder to add one.")
        } else {
            ModelPicker(state.models, state.selected) { viewModel.selectModel(it) }
            state.selected?.let { descriptor ->
                VoicePicker(descriptor.voices, state.voiceId) { viewModel.setVoice(it) }
                SpeedControl(descriptor, state.speed) { viewModel.setSpeed(it) }
            }
        }

        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::setText,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Text to speak") },
            minLines = 3,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::play, enabled = state.selected != null && !state.playing) { Text("Play") }
            OutlinedButton(onClick = viewModel::stop, enabled = state.playing) { Text("Stop") }
            OutlinedButton(
                onClick = { exportLauncher.launch("speech.wav") },
                enabled = state.selected != null && !state.busy,
            ) { Text("Export WAV") }
            OutlinedButton(onClick = { importLauncher.launch(IMPORT_MIME_TYPES) }) { Text("Import file") }
            OutlinedButton(onClick = onBrowseModels) { Text("Browse models") }
            OutlinedButton(onClick = { sideloadLauncher.launch(null) }) { Text("Sideload folder") }
        }

        if (state.busy || state.playing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.status?.let { Text(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
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
private fun VoicePicker(
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

@Composable
private fun SpeedControl(
    descriptor: ModelDescriptor,
    speed: Float,
    onSpeed: (Float) -> Unit,
) {
    Text("Speed: ${"%.2f".format(speed)}x")
    Slider(
        value = speed,
        onValueChange = onSpeed,
        valueRange = descriptor.speedRange, // bounds come straight from the descriptor (SSOT)
    )
}

private val IMPORT_MIME_TYPES =
    arrayOf(
        "text/*",
        "application/pdf",
        com.phonetts.core.text.extract.DocxTextExtractor.DOCX_MIME,
    )
