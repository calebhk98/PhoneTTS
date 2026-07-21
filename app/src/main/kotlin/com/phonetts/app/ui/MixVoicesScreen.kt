package com.phonetts.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonetts.core.engine.Voice

/**
 * The "Mix voices" screen (issue #42): pick two of the selected model's voices, choose how far to
 * blend between them, then preview and save the in-between voice. Whether this screen has anything
 * to offer is DERIVED from [MixVoicesViewModel.UiState.supported] (the model descriptor's
 * `supportsVoiceBlend` fact) — no model name appears here.
 */
@Composable
fun MixVoicesScreen(viewModel: MixVoicesViewModel) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.supported) {
            Text(
                "The selected model can't blend voices. Pick a model whose voices are continuous " +
                    "embeddings (e.g. Kokoro or KittenTTS) to mix them here.",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        VoicePicker("Voice A", state.voices, state.voiceAId, viewModel::setVoiceA)
        VoicePicker("Voice B", state.voices, state.voiceBId, viewModel::setVoiceB)

        val percentB = (state.weight * 100).toInt()
        Text("Blend: ${100 - percentB}% A  /  $percentB% B")
        Slider(value = state.weight, onValueChange = viewModel::setWeight)

        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.preview("") }, enabled = !state.busy) { Text("Preview") }
            Button(onClick = viewModel::save, enabled = !state.busy) { Text("Save voice") }
        }

        state.status?.let { Text(it) }

        if (state.saved.isNotEmpty()) {
            Text("Saved mixes")
            state.saved.forEach { spec ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(spec.name, modifier = Modifier.padding(end = 8.dp))
                    OutlinedButton(onClick = { viewModel.delete(spec) }) { Text("Delete") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePicker(
    label: String,
    voices: List<Voice>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = voices.firstOrNull { it.id == selectedId }?.name ?: ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        TextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice.name) },
                    onClick = {
                        onSelect(voice.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
