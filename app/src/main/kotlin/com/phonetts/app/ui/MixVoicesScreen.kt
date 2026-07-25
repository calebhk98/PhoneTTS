package com.phonetts.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonetts.app.ui.pickers.ModelPicker
import com.phonetts.app.ui.pickers.VoicePicker

/**
 * The "Mix voices" screen (issue #42; model picker added for issue #11): pick which downloaded
 * model to mix, two of its voices, and how far to blend between them, then preview and save the
 * in-between voice. The model choices themselves are DERIVED from
 * [MixVoicesViewModel.UiState.availableModels] (every registered model whose descriptor sets
 * `supportsVoiceBlend`) - no model name appears here as a literal.
 */
@Composable
fun MixVoicesScreen(viewModel: MixVoicesViewModel) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.availableModels.isEmpty()) {
            Text(
                "No downloaded model can blend voices yet. Models whose voices are continuous " +
                    "embeddings (e.g. Kokoro or KittenTTS) can be mixed here once downloaded.",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        ModelPicker(
            models = state.availableModels,
            selected = state.selectedModel,
            onSelect = { viewModel.selectModel(it.modelId) },
            outlined = false,
            label = "Model",
            placeholder = "",
        )

        VoicePicker(
            voices = state.voices,
            selectedVoiceId = state.voiceAId,
            onSelect = viewModel::setVoiceA,
            outlined = false,
            label = "Voice A",
            placeholder = "",
        )
        VoicePicker(
            voices = state.voices,
            selectedVoiceId = state.voiceBId,
            onSelect = viewModel::setVoiceB,
            outlined = false,
            label = "Voice B",
            placeholder = "",
        )

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
