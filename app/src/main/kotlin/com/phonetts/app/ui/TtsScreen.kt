package com.phonetts.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.phonetts.core.audio.transform.BassCut
import com.phonetts.core.audio.transform.DeEsser
import com.phonetts.core.audio.transform.PresenceBoost
import com.phonetts.core.audio.transform.TempoStretch
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.ModelParameter
import com.phonetts.core.prefs.ReadingTextPreferences
import com.phonetts.core.engine.Voice
import com.phonetts.core.text.TextChunker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * The main screen. Model list, voice list and speed bounds are read entirely from the
 * [com.phonetts.core.registry.ModelCatalog] + the selected [ModelDescriptor] — register a model and
 * it appears here with no UI change (spec §7). Play and Export are the two consumers of the one
 * generation path.
 *
 * Layout: a hamburger [ModalNavigationDrawer] holds the navigation destinations (browse / manage /
 * sideload / help) so the body stays a clean stack of titled cards — voice, text, playback, output.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TtsScreen(
    viewModel: TtsViewModel,
    onBrowseModels: () -> Unit,
    onManageModels: () -> Unit,
    onBenchmarks: () -> Unit,
    onHelp: () -> Unit,
    onMixVoices: () -> Unit = {},
    onLibrary: () -> Unit = {},
    onCompare: () -> Unit = {},
    appVersion: String? = null,
    sleepTimer: SleepTimerHandle = SleepTimerHandle.None,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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
    // Pick a destination folder, then write one sample clip per model into it (named by model).
    val sampleAllLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val tree = uri?.let { DocumentFile.fromTreeUri(context, it) } ?: return@rememberLauncherForActivityResult
            val mime = state.exportFormat.format.mimeType
            viewModel.sampleAllModels { name ->
                tree.createFile(mime, name)?.uri?.let { fileUri -> context.contentResolver.openOutputStream(fileUri) }
            }
        }

    // Close the drawer, then run the destination's action. Navigation actions swap the whole screen
    // out from under the drawer, so the close is mostly cosmetic — but sideload just fires a launcher
    // and stays here, where the tidy close matters.
    val navigate: (() -> Unit) -> Unit = { action ->
        scope.launch { drawerState.close() }
        action()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                appVersion = appVersion,
                onBrowseModels = { navigate(onBrowseModels) },
                onManageModels = { navigate(onManageModels) },
                onSideload = { navigate { sideloadLauncher.launch(null) } },
                onBenchmarks = { navigate(onBenchmarks) },
                onHelp = { navigate(onHelp) },
                onHome = { navigate {} },
                onMixVoices = { navigate(onMixVoices) },
                onLibrary = { navigate(onLibrary) },
                onCompare = { navigate(onCompare) },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("PhoneTTS") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            // The repo avoids the material-icons dependency (see back arrow / stars),
                            // so the menu affordance is the "trigram for heaven" hamburger glyph.
                            Text("☰", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                )
            },
        ) { innerPadding ->
            TtsBody(
                state = state,
                viewModel = viewModel,
                sleepTimer = sleepTimer,
                contentPadding = innerPadding,
                onImport = { importLauncher.launch(IMPORT_MIME_TYPES) },
                onExport = { exportLauncher.launch("speech.${state.exportFormat.format.fileExtension}") },
                onSampleAll = { sampleAllLauncher.launch(null) },
            )
        }
    }
}

/** The scrollable content stack: one titled [SectionCard] per concern. */
@Composable
private fun TtsBody(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
    sleepTimer: SleepTimerHandle,
    contentPadding: PaddingValues,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSampleAll: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.update?.let { update ->
            UpdateBanner(update, onDismiss = viewModel::dismissUpdate)
        }
        state.sideloadFailureExplanation?.let { explanation ->
            SideloadFailureBanner(
                explanation = explanation,
                onDismiss = viewModel::dismissSideloadFailureExplanation,
            )
        }

        VoiceCard(state, viewModel)
        TextCard(state, viewModel, onImport)
        PlaybackCard(state, viewModel)
        OutputCard(state, viewModel, sleepTimer, onExport, onSampleAll)
    }
}

/** A titled, elevated grouping — the unit that turns the old button wall into scannable sections. */
@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** Navigation destinations, tucked behind the hamburger so the main screen isn't a button wall. */
@Composable
private fun AppDrawer(
    appVersion: String?,
    onBrowseModels: () -> Unit,
    onManageModels: () -> Unit,
    onSideload: () -> Unit,
    onBenchmarks: () -> Unit,
    onHelp: () -> Unit,
    onHome: () -> Unit = {},
    onMixVoices: () -> Unit = {},
    onLibrary: () -> Unit = {},
    onCompare: () -> Unit = {},
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PhoneTTS", style = MaterialTheme.typography.headlineSmall)
            Text(
                appVersion?.let { "Offline text-to-speech · v$it" } ?: "Offline text-to-speech",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // This IS the Home screen, so Home stays listed and highlighted as the current page,
            // matching how every other page's drawer marks its own entry (issue #118). Tapping it
            // just closes the drawer.
            DrawerLink("Home", onHome, selected = true)
            DrawerLink("Browse models", onBrowseModels)
            DrawerLink("Manage models", onManageModels)
            DrawerLink("Sideload folder", onSideload)
            DrawerLink("Mix voices", onMixVoices)
            DrawerLink("Reading library", onLibrary)
            DrawerLink("Compare voices (A/B)", onCompare)
            DrawerLink("Benchmarks", onBenchmarks)
            DrawerLink("Help", onHelp)
        }
    }
}

@Composable
private fun DrawerLink(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

/** Model + voice + tunable parameters — everything about *what* voice speaks. */
@Composable
private fun VoiceCard(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
) {
    SectionCard("Voice") {
        if (state.models.isEmpty()) {
            Text("No models yet. Open the menu to browse Hugging Face or sideload a folder.")
            return@SectionCard
        }
        ModelPicker(state.models, state.selected) { viewModel.selectModel(it) }
        val descriptor = state.selected ?: return@SectionCard
        VoicePicker(
            // Descriptor voices (SSOT) plus any saved mixes re-applied to the loaded engine (#42).
            voices = state.voices,
            selectedVoiceId = state.voiceId,
            favoriteVoiceIds = state.favoriteVoiceIds,
            onSelect = viewModel::setVoice,
            onToggleFavorite = viewModel::toggleFavoriteVoice,
        )
        // Star the currently selected model+voice as a cross-model favorite (issue #119) — a separate
        // concept from the picker's per-voice stars above (which only re-order the current model's list).
        FavoriteVoiceToggle(
            isFavorite = state.currentVoiceIsFavorite,
            enabled = state.voiceId != null,
            onToggle = viewModel::toggleFavoriteCurrentVoice,
        )
        // Quick-pick of every starred voice across ALL models (issue #119): tap one to switch straight
        // to it. Absent when there are no resolvable favorites.
        FavoriteVoicesQuickPick(
            options = state.favoriteVoiceOptions,
            selectedModelId = descriptor.modelId,
            selectedVoiceId = state.voiceId,
            onSelect = viewModel::selectFavoriteVoice,
        )
        ParameterControls(descriptor, state.paramValues) { id, value -> viewModel.setParam(id, value) }
    }
}

/**
 * The text to read, plus the import-from-file affordance right where the text lives. The field
 * tracks its caret ([TextFieldValue]) so "Read from cursor" (issue #24) can start playback at the
 * sentence under the cursor, and its font scales with the A− / A+ control (issue #29).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextCard(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
    onImport: () -> Unit,
) {
    SectionCard("Text") {
        var fieldValue by remember { mutableStateOf(TextFieldValue(state.text)) }
        // Reflect external text changes (e.g. Import) into the field without stomping the caret while
        // the user is typing — an edit sets state.text before this recomposes, so the guard is false then.
        if (fieldValue.text != state.text) {
            fieldValue = fieldValue.copy(text = state.text, selection = TextRange(state.text.length))
        }
        val baseStyle = MaterialTheme.typography.bodyLarge
        // Bounded height (issue #75): a large loaded/typed document used to grow this field to fit
        // all its text, pushing Play/Generate off-screen by minutes of scrolling. Capping the height
        // and letting the field scroll internally (OutlinedTextField already does this once its
        // content exceeds its box) keeps the controls below reachable regardless of document length,
        // while short text still renders naturally down to minLines.
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                if (it.text != state.text) viewModel.setText(it.text)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = TEXT_FIELD_MIN_HEIGHT, max = TEXT_FIELD_MAX_HEIGHT),
            label = { Text("Text to speak") },
            textStyle = baseStyle.copy(fontSize = baseStyle.fontSize * state.readingScale),
            minLines = 3,
        )
        // Karaoke-style "now playing" sentence highlight (issue #19-3), separate from the editable
        // field above so editing never fights with highlighting mid-sentence; simply absent when
        // nothing is playing or the text is empty (KaraokeText itself guards both).
        KaraokeText(state.text, state.currentSentenceIndex)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onImport) { Text("Import from file") }
            OutlinedButton(
                onClick = { viewModel.playFromCursor(fieldValue.selection.start) },
                enabled = state.selected != null && !state.playing && state.text.isNotBlank(),
            ) { Text("Read from cursor") }
            // Save into the reading library (issue #19-5) — reachable from here or the library
            // screen itself, which lists everything saved from either place.
            OutlinedButton(
                onClick = viewModel::saveToLibrary,
                enabled = state.text.isNotBlank(),
            ) { Text("Save to library") }
            FontSizeControls(state, viewModel)
        }
    }
}

/** A− / A+ reading font size (issue #29); bounds come from [ReadingTextPreferences] (SSOT for them). */
@Composable
private fun FontSizeControls(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
) {
    OutlinedButton(
        onClick = viewModel::decreaseTextScale,
        enabled = state.readingScale > ReadingTextPreferences.MIN_SCALE,
    ) { Text("A−") }
    OutlinedButton(
        onClick = viewModel::increaseTextScale,
        enabled = state.readingScale < ReadingTextPreferences.MAX_SCALE,
    ) { Text("A+") }
}

/**
 * Karaoke-style "now playing" view (issue #19-3): the document's sentences ([TextChunker.intoSentences]
 * — the SAME split the one generation path chunks by, so this always agrees with what's actually
 * playing), one per line, with the current sentence highlighted and auto-scrolled toward. Renders
 * nothing when there is nothing applicable to highlight: no active sentence, or no sentences at all
 * (blank text) — the guard clauses below cover both, so an empty document is simply absent, not broken.
 */
@Composable
private fun KaraokeText(
    text: String,
    currentSentenceIndex: Int?,
) {
    if (currentSentenceIndex == null) return
    val sentences = remember(text) { TextChunker.intoSentences(text) }
    if (sentences.isEmpty()) return
    val index = currentSentenceIndex.coerceIn(0, sentences.lastIndex)
    val scrollState = rememberScrollState()
    // Best-effort scroll-into-view: proportional to how far through the sentence list we are, rather
    // than an exact pixel position (sentences wrap to different heights) — good enough to keep the
    // highlighted line on screen during a long document without measuring every line's layout.
    LaunchedEffect(index) {
        val target = scrollState.maxValue.toFloat() * index / sentences.size
        scrollState.animateScrollTo(target.toInt().coerceIn(0, scrollState.maxValue))
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = KARAOKE_MAX_HEIGHT)
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sentences.forEachIndexed { i, sentence ->
            val isCurrent = i == index
            Text(
                sentence,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                modifier =
                    if (isCurrent) {
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        Modifier.fillMaxWidth()
                    },
            )
        }
    }
}

private val KARAOKE_MAX_HEIGHT = 160.dp

// Issue #75: caps the "Text to speak" field so a large loaded/typed document can't push Play/Generate
// off-screen — the field scrolls internally past this height instead of growing unbounded inside the
// page's own scrolling Column. min keeps short text from collapsing below the 3-line minLines floor.
private val TEXT_FIELD_MIN_HEIGHT = 96.dp
private val TEXT_FIELD_MAX_HEIGHT = 280.dp

/**
 * Playback controls. Play is the single primary action (it generates on first tap, then replays
 * instantly); everything else — load-ahead, preview-only generate, sample, transport — is a
 * secondary button so the common path reads at a glance. Live progress/stats/status sit right here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackCard(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
) {
    SectionCard("Playback") {
        val selected = state.selected
        val isModelLoaded = selected != null && state.loadedModelId == selected.modelId

        Button(
            onClick = viewModel::play,
            enabled = selected != null && !state.playing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.playing) "Playing…" else "Play") }

        // Estimated listening time (issue #23): a synthesis-free "~6 min at 1.25×" that updates live
        // as the text or speed changes. Hidden for empty text (estimate is zero then).
        if (state.estimatedListeningSeconds > 0) {
            Text(
                formatListeningEstimate(state.estimatedListeningSeconds, state.params.speed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // "Resume from where it stopped" (issue #28): shown only when a prior run left a resume point,
        // replacing the old dead-end error with a one-tap continuation from that sentence.
        if (state.resumeSentenceIndex != null && !state.playing) {
            OutlinedButton(
                onClick = viewModel::resumeFromSaved,
                enabled = selected != null && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Resume from where it stopped") }
        }

        // Standard media transport (prev · play/pause · next · stop), only mid-playback. Play/Pause
        // is the prominent central control and toggles in place — pause now halts immediately at the
        // hardware level (BufferedPlayback→AudioTrackSink), not only at the next sentence boundary.
        // Prev/next are the same per-sentence restart-from-index mechanism the lock-screen skip uses.
        if (state.playing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = viewModel::skipBackSentence, modifier = Modifier.weight(1f)) { Text("⏮") }
                Button(
                    onClick = if (state.paused) viewModel::resumePlayback else viewModel::pausePlayback,
                    modifier = Modifier.weight(1.6f),
                ) { Text(if (state.paused) "▶  Resume" else "⏸  Pause") }
                OutlinedButton(onClick = viewModel::skipForwardSentence, modifier = Modifier.weight(1f)) { Text("⏭") }
                OutlinedButton(onClick = viewModel::stop, modifier = Modifier.weight(1f)) { Text("⏹") }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = viewModel::loadModel,
                enabled = selected != null && !state.busy && !state.playing && !isModelLoaded,
            ) { Text(if (isModelLoaded) "Model loaded" else "Load model") }
            OutlinedButton(
                onClick = viewModel::generateAudio,
                enabled = selected != null && !state.busy && !state.playing,
            ) { Text("Generate") }
            OutlinedButton(
                onClick = viewModel::sampleVoice,
                enabled = selected != null && !state.busy,
            ) { Text("Sample voice") }
        }

        if (state.busy || state.playing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.stats?.let { GenerationStatsView(it) }
        state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Export + the non-destructive post-processing toggles + the sleep timer — the "on the way out" knobs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutputCard(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
    sleepTimer: SleepTimerHandle,
    onExport: () -> Unit,
    onSampleAll: () -> Unit,
) {
    SectionCard("Output") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onExport,
                enabled = state.selected != null && !state.busy,
            ) { Text("Export ${state.exportFormat.format.fileExtension.uppercase()}") }
            // Audition every downloaded model at once: one sample clip per model, saved to a folder.
            OutlinedButton(
                onClick = onSampleAll,
                enabled = state.models.isNotEmpty() && !state.busy && !state.playing,
            ) { Text("Sample every model") }
        }
        if (viewModel.exportFormats.size > 1) {
            ExportFormatPicker(viewModel.exportFormats, state.exportFormat, viewModel::setExportFormat)
        }
        HorizontalDivider()
        TransformToggles(state, viewModel)
        HorizontalDivider()
        SleepTimerControls(sleepTimer)
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
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
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
    // EQ clarity presets (issue #40): timbre-only biquads, same non-destructive export chain.
    ToggleRow(BassCut().displayName, state.bassCut, viewModel::setBassCut)
    ToggleRow(PresenceBoost().displayName, state.presenceBoost, viewModel::setPresenceBoost)
    ToggleRow(DeEsser().displayName, state.deEss, viewModel::setDeEss)
    TempoBoostControl(state, viewModel)
    // Long-document mode (issue #34): opt-in memory ceiling — older audio spills to disk during a
    // book-length synthesis. Off by default; when off, generation is unchanged.
    ToggleRow("Long-document mode (spill audio to disk)", state.longDocumentMode, viewModel::setLongDocumentMode)
}

/**
 * Opt-in, PLAYBACK-ONLY beyond-native tempo (issue #43). Deliberately separate from the native
 * "Speed" control: this is a post-processed, pitch-preserving WSOLA time-stretch that never touches
 * the model's own speed parameter and never resamples for it (rule 2). Off by default; the factor
 * slider only appears once enabled.
 */
@Composable
private fun TempoBoostControl(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
) {
    ToggleRow("Playback speed (post-processed)", state.tempoBoost, viewModel::setTempoBoost)
    if (!state.tempoBoost) return
    Text(
        "Playback tempo ${"%.1f".format(state.tempoFactor)}x (pitch-preserving, not the model's Speed)",
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(
        value = state.tempoFactor,
        onValueChange = viewModel::setTempoFactor,
        valueRange = TempoStretch.MIN_FACTOR..TempoStretch.MAX_FACTOR,
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // weight(1f) lets a long label (e.g. "Long-document mode (spill audio to disk)") wrap onto
        // a second line within its own share of the row instead of pushing the fixed-size Switch
        // past the row's bound — without it, a long label and the Switch fight over unbounded width
        // and the Switch ends up overlapped or shoved off-screen (issues #20/#21).
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** Live, measured generation stats (spec: RTF is calculated, never guessed). */
@Composable
private fun GenerationStatsView(stats: com.phonetts.core.metrics.GenerationStats) {
    val rtf = "%.2f".format(stats.realTimeFactor)
    val audio = "%.1f".format(stats.audioSecondsProduced)
    val eta = stats.estimatedRemainingSeconds?.let { " · ETA ${"%.0f".format(it)}s" } ?: ""
    val wps = if (stats.wordsPerSecond > 0) " · ${"%.1f".format(stats.wordsPerSecond)} words/s" else ""
    Text(
        "Generated ${audio}s audio · ${rtf}x real-time$wps$eta",
        style = MaterialTheme.typography.bodySmall,
    )
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
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
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

/**
 * Star/unstar the currently selected model+voice as a cross-model favorite (issue #119). Separate
 * from the voice picker's own per-voice stars (which only re-order the current model's list): this
 * feeds the [FavoriteVoicesQuickPick] below, which surfaces starred voices across ALL models.
 */
@Composable
private fun FavoriteVoiceToggle(
    isFavorite: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Favorite this voice", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onToggle, enabled = enabled) {
            Text(if (isFavorite) "★ Favorited" else "☆ Favorite")
        }
    }
}

/**
 * Cross-model favorite voices (issue #119): a quick-pick of every voice the user starred, in ANY
 * model, so switching to a favorite never means hunting through each model's list. The options are
 * resolved against the catalog (SSOT) in the ViewModel — a ref whose model/voice no longer exists is
 * already dropped there (fail closed), so this is simply absent when there is nothing to offer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavoriteVoicesQuickPick(
    options: List<TtsViewModel.FavoriteVoiceOption>,
    selectedModelId: String?,
    selectedVoiceId: String?,
    onSelect: (String, String) -> Unit,
) {
    if (options.isEmpty()) return
    Text("Favorites", style = MaterialTheme.typography.bodyMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isCurrent = option.modelId == selectedModelId && option.voiceId == selectedVoiceId
            if (isCurrent) {
                Button(onClick = { onSelect(option.modelId, option.voiceId) }) { Text(option.label) }
            } else {
                OutlinedButton(onClick = { onSelect(option.modelId, option.voiceId) }) { Text(option.label) }
            }
        }
    }
}

// Offer (never force) an update: a dismissible banner that opens the new APK's download URL in the
// browser, where the user chooses to download and install it. Shown only when the update check found
// a strictly-newer release with an installable APK.
@Composable
private fun UpdateBanner(
    update: com.phonetts.core.update.UpdateStatus,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val target = update.apkDownloadUrl ?: update.releasePageUrl
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Update available: ${update.latestVersion} (you have ${update.currentVersion})")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { target?.let(uriHandler::openUri) }, enabled = target != null) { Text("Download") }
                OutlinedButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}

// Surfaces DetectionFailureExplainer's narration of why a sideloaded folder's model couldn't be
// auto-detected (issue #19-2), with a Copy action (the LocalClipboardManager idiom BenchmarkScreen
// uses) so the owner can paste it into a bug report — this is read-only narration, it changes
// nothing and offers no retry (the user still picks a folder again via the drawer's Sideload link).
@Composable
private fun SideloadFailureBanner(
    explanation: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Couldn't identify that model", style = MaterialTheme.typography.titleSmall)
            Text(explanation, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(explanation)) }) { Text("Copy") }
                OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

// DYNAMIC: one control per parameter the model DECLARES (descriptor.parameters — the SSOT the engine
// filled by inspecting the model). A model with no tunable parameters (e.g. CosyVoice3) renders no
// controls at all; a model that later adds an emotion selector shows a chooser here with NO change to
// this code. Nothing about "speed" is special-cased except its familiar preset shortcuts.
@Composable
private fun ParameterControls(
    descriptor: ModelDescriptor,
    paramValues: Map<String, Float>,
    onSet: (String, Float) -> Unit,
) {
    descriptor.parameters.forEach { param ->
        val value = paramValues[param.id] ?: param.default
        when (param.kind) {
            ModelParameter.Kind.CONTINUOUS -> ContinuousControl(param, value) { onSet(param.id, it) }
            ModelParameter.Kind.CHOICE -> ChoiceControl(param, value) { onSet(param.id, it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContinuousControl(
    param: ModelParameter,
    value: Float,
    onValue: (Float) -> Unit,
) {
    val range = param.range ?: return
    Text("${param.displayName}: ${"%.2f".format(value)}")
    Slider(value = value, onValueChange = onValue, valueRange = range)
    // Speed keeps its familiar preset shortcuts; other continuous knobs just get the slider.
    if (param.id != ModelParameter.SPEED_ID) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Only presets the model's own range actually supports — clamping out-of-range presets to
        // the boundary instead would show the same value twice (e.g. two "1.50x" buttons).
        SPEED_PRESETS.filter { it in range }.forEach { preset ->
            OutlinedButton(onClick = { onValue(preset) }) { Text("${"%.2fx".format(preset)}") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceControl(
    param: ModelParameter,
    value: Float,
    onIndex: (Float) -> Unit,
) {
    val selected = value.toInt()
    Text(param.displayName)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        param.choices.forEachIndexed { index, choice ->
            if (index == selected) {
                Button(onClick = { onIndex(index.toFloat()) }) { Text(choice) }
            } else {
                OutlinedButton(onClick = { onIndex(index.toFloat()) }) { Text(choice) }
            }
        }
    }
}

private val SPEED_PRESETS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

// "~6 min at 1.25×" (or "~4 sec at 1.0×" under a minute) — the estimate itself (seconds) comes from
// ListeningTimeEstimator (:core, SSOT for the reading rate); this only rounds for display and formats
// the speed. Below a minute, rounding UP to "~1 min" overstated a short phrase's listening time by up
// to ~20x (issue #17) — showing seconds there instead keeps the estimate honest at that scale.
private fun formatListeningEstimate(
    seconds: Double,
    speed: Float,
): String {
    if (seconds < SECONDS_PER_MINUTE) {
        val wholeSeconds = ceil(seconds).toInt().coerceAtLeast(1)
        return "~$wholeSeconds sec at ${formatSpeed(speed)}"
    }
    val minutes = ceil(seconds / SECONDS_PER_MINUTE).toInt().coerceAtLeast(1)
    return "~$minutes min at ${formatSpeed(speed)}"
}

private fun formatSpeed(speed: Float): String {
    val trimmed = "%.2f".format(speed).trimEnd('0').trimEnd('.')
    return "${trimmed}×"
}

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
