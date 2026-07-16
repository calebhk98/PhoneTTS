package com.phonetts.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.engine.Voice
import kotlinx.coroutines.delay

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
    onManageModels: () -> Unit,
    onHelp: () -> Unit,
    sleepTimer: SleepTimerHandle = SleepTimerHandle.None,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importTextFrom)
        }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri ->
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
                VoicePicker(
                    voices = descriptor.voices,
                    selectedVoiceId = state.voiceId,
                    favoriteVoiceIds = state.favoriteVoiceIds,
                    onSelect = viewModel::setVoice,
                    onToggleFavorite = viewModel::toggleFavoriteVoice,
                )
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
            // Pause the audio while generation keeps running; resume replays already-generated audio.
            if (state.playing && !state.paused) {
                OutlinedButton(onClick = viewModel::pausePlayback) { Text("Pause") }
            }
            if (state.playing && state.paused) {
                OutlinedButton(onClick = viewModel::resumePlayback) { Text("Resume") }
            }
            OutlinedButton(onClick = viewModel::stop, enabled = state.playing) { Text("Stop") }
            OutlinedButton(
                onClick = viewModel::sampleVoice,
                enabled = state.selected != null && !state.busy,
            ) { Text("Sample voice") }
            OutlinedButton(
                onClick = { exportLauncher.launch("speech.${state.exportFormat.format.fileExtension}") },
                enabled = state.selected != null && !state.busy,
            ) { Text("Export ${state.exportFormat.format.fileExtension.uppercase()}") }
            OutlinedButton(onClick = { importLauncher.launch(IMPORT_MIME_TYPES) }) { Text("Import file") }
            OutlinedButton(onClick = onBrowseModels) { Text("Browse models") }
            OutlinedButton(onClick = onManageModels) { Text("Manage models") }
            OutlinedButton(onClick = onHelp) { Text("Help") }
            OutlinedButton(onClick = { sideloadLauncher.launch(null) }) { Text("Sideload folder") }
        }

        TransformToggles(state, viewModel)
        if (viewModel.exportFormats.size > 1) {
            ExportFormatPicker(viewModel.exportFormats, state.exportFormat, viewModel::setExportFormat)
        }
        SleepTimerControls(sleepTimer)

        if (state.busy || state.playing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.stats?.let { GenerationStatsView(it) }
        state.status?.let { Text(it) }
    }
}

/** Export-format picker; the list + display names come from the encoder registry (SSOT). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportFormatPicker(
    formats: List<com.phonetts.core.audio.export.AudioEncoder>,
    selected: com.phonetts.core.audio.export.AudioEncoder,
    onSelect: (com.phonetts.core.audio.export.AudioEncoder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        TextField(
            value = selected.format.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Export format") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            formats.forEach { encoder ->
                DropdownMenuItem(
                    text = { Text(encoder.format.displayName) },
                    onClick = {
                        onSelect(encoder)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Non-destructive post-processing toggles (spec: applied to export, raw audio never altered). */
@Composable
private fun TransformToggles(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
) {
    ToggleRow("Trim silence", state.trimSilence, viewModel::setTrimSilence)
    ToggleRow("Normalize volume", state.normalizeVolume, viewModel::setNormalizeVolume)
    ToggleRow("Crossfade joins", state.crossfadeJoins, viewModel::setCrossfadeJoins)
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = checked, onCheckedChange = onChecked)
        Text(label)
    }
}

/** Live, measured generation stats (spec: RTF is calculated, never guessed). */
@Composable
private fun GenerationStatsView(stats: com.phonetts.core.metrics.GenerationStats) {
    val rtf = "%.2f".format(stats.realTimeFactor)
    val audio = "%.1f".format(stats.audioSecondsProduced)
    val eta = stats.estimatedRemainingSeconds?.let { " · ETA ${"%.0f".format(it)}s" } ?: ""
    val wps = if (stats.wordsPerSecond > 0) " · ${"%.1f".format(stats.wordsPerSecond)} words/s" else ""
    Text("Generated ${audio}s audio · ${rtf}x real-time$wps$eta")
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

/**
 * Voice picker; voices always come from [voices] (a descriptor's own list — SSOT). Favorited
 * voices ([favoriteVoiceIds], sourced from [com.phonetts.core.prefs.FavoriteVoices]) sort first
 * and show a filled star; the star toggle never changes the voice list itself, only its order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePicker(
    voices: List<Voice>,
    selectedVoiceId: String?,
    favoriteVoiceIds: Set<String>,
    onSelect: (String) -> Unit,
    onToggleFavorite: (Voice) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = voices.firstOrNull { it.id == selectedVoiceId }?.name ?: "Select a voice"
    val ordered = voices.sortedByDescending { it.id in favoriteVoiceIds }
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
            ordered.forEach { voice ->
                DropdownMenuItem(
                    text = { Text("${voice.name} (${voice.language})") },
                    trailingIcon = { FavoriteStar(voice.id in favoriteVoiceIds) { onToggleFavorite(voice) } },
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
private fun FavoriteStar(
    isFavorite: Boolean,
    onToggle: () -> Unit,
) {
    Text(
        text = if (isFavorite) "★" else "☆", // filled / outline star
        modifier = Modifier.clickable(onClick = onToggle),
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
    // Preset shortcuts, clamped to the model's own range (still SSOT — no bound invented here).
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SPEED_PRESETS.forEach { preset ->
            val clamped = preset.coerceIn(descriptor.speedRange)
            OutlinedButton(onClick = { onSpeed(clamped) }) { Text("${"%.2fx".format(clamped)}") }
        }
    }
}

private val SPEED_PRESETS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private val IMPORT_MIME_TYPES =
    arrayOf(
        "text/*",
        "application/pdf",
        com.phonetts.core.text.extract.DocxTextExtractor.DOCX_MIME,
    )

/**
 * Facade [MainActivity] implements over `PlaybackService.LocalBinder`'s sleep-timer calls, so
 * this UI file needs no dependency on the playback service/binder types (spec: Compose UI stays
 * a consumer, never owns service-binding lifecycle). [None] is the safe default while unbound.
 */
interface SleepTimerHandle {
    fun start(durationMillis: Long)

    fun cancel()

    fun isRunning(): Boolean

    fun remainingMillis(): Long

    companion object {
        val None =
            object : SleepTimerHandle {
                override fun start(durationMillis: Long) = Unit

                override fun cancel() = Unit

                override fun isRunning() = false

                override fun remainingMillis() = 0L
            }
    }
}

/** "Stop after N minutes" — routes to the same `stop()` a Stop tap uses (see [PlaybackService]). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerControls(sleepTimer: SleepTimerHandle) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(sleepTimer) {
        while (true) {
            delay(SLEEP_TIMER_TICK_MILLIS)
            tick++
        }
    }
    // Re-executed each tick (key(tick) re-composes this block) so the remaining time stays live.
    key(tick) {
        val running = sleepTimer.isRunning()
        Text(if (running) "Sleep timer: ${formatRemaining(sleepTimer.remainingMillis())}" else "Sleep timer: off")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SLEEP_TIMER_PRESET_MINUTES.forEach { minutes ->
            OutlinedButton(onClick = { sleepTimer.start(minutes * MILLIS_PER_MINUTE) }) { Text("${minutes}m") }
        }
        OutlinedButton(onClick = sleepTimer::cancel) { Text("Off") }
    }
}

private fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis / MILLIS_PER_SECOND).coerceAtLeast(0)
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private val SLEEP_TIMER_PRESET_MINUTES = listOf(15L, 30L, 60L)
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SLEEP_TIMER_TICK_MILLIS = 1_000L
