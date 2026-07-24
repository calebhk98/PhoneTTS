package com.phonetts.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonetts.core.audio.transform.BassCut
import com.phonetts.core.audio.transform.DeEsser
import com.phonetts.core.audio.transform.PresenceBoost
import com.phonetts.core.audio.transform.TempoStretch
import com.phonetts.core.prefs.ReadingTextPreferences

/**
 * Settings: the "set once and forget" knobs, moved off the Home screen so Home stays focused on
 * text + playback (issue #123). Everything here reads and writes the same [TtsViewModel] state the
 * Home screen used, so nothing about the behavior changes - only where the controls live. The
 * post-processing toggles are still the non-destructive export chain (spec: raw audio never altered),
 * now grouped with a one-line description each instead of an undifferentiated flat list.
 */
@Composable
fun SettingsScreen(viewModel: TtsViewModel) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection("Reading") {
            FontSizeSetting(state, viewModel)
        }

        SettingsSection("Audio cleanup") {
            SettingToggle(
                "Trim silence",
                "Remove leading and trailing silence from each clip.",
                state.trimSilence,
                viewModel::setTrimSilence,
            )
            SettingToggle(
                "Normalize volume",
                "Even out loudness so clips play at a consistent level.",
                state.normalizeVolume,
                viewModel::setNormalizeVolume,
            )
            SettingToggle(
                "Crossfade joins",
                "Blend sentence joins so they don't click or jump.",
                state.crossfadeJoins,
                viewModel::setCrossfadeJoins,
            )
        }

        SettingsSection("Tone") {
            SettingToggle(BassCut().displayName, "Cut low rumble for a clearer voice.", state.bassCut, viewModel::setBassCut)
            SettingToggle(
                PresenceBoost().displayName,
                "Lift the presence range so speech sits forward.",
                state.presenceBoost,
                viewModel::setPresenceBoost,
            )
            SettingToggle(DeEsser().displayName, "Tame harsh \"s\" sounds.", state.deEss, viewModel::setDeEss)
        }

        SettingsSection("Playback tempo") {
            SettingToggle(
                "Playback speed (post-processed)",
                "A pitch-preserving speed-up applied on playback only - separate from the model's own Speed control.",
                state.tempoBoost,
                viewModel::setTempoBoost,
            )
            if (state.tempoBoost) {
                Text(
                    "Tempo ${"%.1f".format(state.tempoFactor)}x",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.tempoFactor,
                    onValueChange = viewModel::setTempoFactor,
                    valueRange = TempoStretch.MIN_FACTOR..TempoStretch.MAX_FACTOR,
                )
            }
        }

        SettingsSection("Long documents") {
            SettingToggle(
                "Spill audio to disk",
                "For book-length text: keep memory low by writing older audio to disk during a long synthesis.",
                state.longDocumentMode,
                viewModel::setLongDocumentMode,
            )
        }
    }
}

/** A titled settings group card - mirrors Home's SectionCard so the two screens read as one app. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

/** A labeled toggle with a one-line description underneath, so each knob explains itself (issue #123). */
@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // weight(1f) lets the title/subtitle wrap within their own share of the row rather than
        // pushing the fixed-size Switch off-screen (issues #20/#21).
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/**
 * Reading font size (issue #29): scales the Home reading/editing text on TOP of the system font
 * size. Bounds come from [ReadingTextPreferences] (SSOT for them). Lives in Settings now rather than
 * on the reading screen itself (issue #123).
 */
@Composable
private fun FontSizeSetting(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
) {
    Text(
        "Text size scales the reading text; your system font size still applies on top.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = viewModel::decreaseTextScale,
            enabled = state.readingScale > ReadingTextPreferences.MIN_SCALE,
        ) { Text("A-") }
        OutlinedButton(
            onClick = viewModel::increaseTextScale,
            enabled = state.readingScale < ReadingTextPreferences.MAX_SCALE,
        ) { Text("A+") }
        Text("${"%.0f".format(state.readingScale * PERCENT)}%", style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

private const val PERCENT = 100f
