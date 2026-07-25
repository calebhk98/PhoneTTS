package com.phonetts.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.phonetts.app.Screen
import com.phonetts.app.ui.pickers.UnifiedVoicePicker
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.ModelParameter
import com.phonetts.core.text.TextChunker
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The main screen. Model list, voice list and speed bounds are read entirely from the
 * [com.phonetts.core.registry.ModelCatalog] + the selected [ModelDescriptor] - register a model and
 * it appears here with no UI change (spec §7). Play and Export are the two consumers of the one
 * generation path.
 *
 * Layout: a hamburger [ModalNavigationDrawer] holds the navigation destinations (browse / manage /
 * sideload / help) so the body stays a clean stack of titled cards - voice, text, playback, output.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TtsScreen(
    viewModel: TtsViewModel,
    // One navigation callback, not one per destination. The drawer renders NAV_ITEMS and hands back
    // whichever Screen was tapped, so adding a destination needs no new parameter here.
    onNavigate: (Screen) -> Unit,
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
    // out from under the drawer, so the close is mostly cosmetic - but sideload just fires a launcher
    // and stays here, where the tidy close matters.
    val navigate: (() -> Unit) -> Unit = { action ->
        scope.launch { drawerState.close() }
        action()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // The SAME drawer every other screen renders (NAV_ITEMS) - Home no longer keeps its own
            // copy, so the menu is identical wherever you open it from.
            NavDrawerContent(
                current = Screen.MAIN,
                appVersion = appVersion,
                // MAIN is where we already are, so it just closes the drawer (matching how every
                // other screen's drawer marks its own entry).
                onNavigate = { destination ->
                    navigate { if (destination != Screen.MAIN) onNavigate(destination) }
                },
                onAction = { action ->
                    navigate {
                        when (action) {
                            NavAction.IMPORT_MODEL_FOLDER -> sideloadLauncher.launch(null)
                        }
                    }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("PhoneTTS") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open navigation menu")
                        }
                    },
                )
            },
            bottomBar = { BottomTransportBar(state, viewModel) },
        ) { innerPadding ->
            TtsBody(
                state = state,
                viewModel = viewModel,
                sleepTimer = sleepTimer,
                contentPadding = innerPadding,
                onNavigate = onNavigate,
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
    onNavigate: (Screen) -> Unit,
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
            UpdateBanner(
                update = update,
                download = state.updateDownload,
                onDownload = viewModel::downloadUpdate,
                onCancelDownload = viewModel::cancelUpdateDownload,
                onDismiss = viewModel::dismissUpdate,
            )
        }
        state.sideloadFailureExplanation?.let { explanation ->
            SideloadFailureBanner(
                explanation = explanation,
                onDismiss = viewModel::dismissSideloadFailureExplanation,
            )
        }

        VoiceCard(state, viewModel, onNavigate)
        TextCard(state, viewModel, onImport)
        PlaybackCard(state, viewModel, sleepTimer)
        OutputCard(state, viewModel, onExport, onSampleAll)
    }
}

/** A titled, elevated grouping - the unit that turns the old button wall into scannable sections. */
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
            // titleMedium in near-white (onSurface), not a small saturated label - a real section
            // header in the type hierarchy rather than an HTML fieldset legend (issue #123).
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

/**
 * Model + voice + tunable parameters - everything about *what* voice speaks. What used to be three
 * stacked widgets (a model dropdown, a voice dropdown, and a separate favorites chip row - issue: "the
 * favorites/voice selector seems weird") is now the single [UnifiedVoicePicker] summary row + sheet;
 * picking any voice in it switches model too when needed, so there is exactly one voice decision here,
 * not three.
 */
@Composable
private fun VoiceCard(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
    onNavigate: (Screen) -> Unit,
) {
    SectionCard("Voice") {
        if (state.models.isEmpty()) {
            EmptyModelsHint(onBrowse = { onNavigate(Screen.BROWSE) })
            return@SectionCard
        }
        UnifiedVoicePicker(
            models = state.models,
            selectedModelId = state.selected?.modelId,
            selectedVoiceId = state.voiceId,
            // Cross-model favorites (issue #119), reduced to the (modelId, voiceId) pairs the picker
            // needs - the single FavoritesStore source of truth still lives in the ViewModel.
            favoritePairs = state.favoriteVoiceOptions.map { it.modelId to it.voiceId }.toSet(),
            // A tap on any row - favorite or not - both picks the voice and switches the active model
            // when it differs; selectFavoriteVoice already does exactly that (issue #119 plumbing),
            // so this reuses it as the general "jump to this model+voice" action, not just favorites.
            onPick = viewModel::selectFavoriteVoice,
            // Starring is a bookmark, not a selection: it must never switch the active voice or
            // barge in on playback just because the sheet lists every model's voices at once.
            onToggleFavorite = { modelId, voice -> viewModel.toggleFavoriteVoice(modelId, voice.id) },
        )
        val descriptor = state.selected ?: return@SectionCard
        if (descriptor.parameters.isNotEmpty()) {
            HorizontalDivider()
            FieldLabel("Adjustments")
            ParameterControls(descriptor, state.paramValues) { id, value -> viewModel.setParam(id, value) }
        }
    }
}

/**
 * First-run hint when nothing is downloaded yet, phrased as a next step rather than a dead end -
 * an icon, a message, and one primary action (open the Browse screen) instead of two bare lines of
 * text with no way forward from this screen itself.
 */
@Composable
private fun EmptyModelsHint(onBrowse: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Filled.Download,
            contentDescription = null,
            modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "No voices installed yet",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Browse Hugging Face models or sideload a folder, then pick one here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Button(onClick = onBrowse) { Text("Browse voices") }
    }
}

private val EMPTY_STATE_ICON_SIZE = 48.dp

/** A small caps-style sub-label that breaks a dense card into scannable groups. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
        // the user is typing - an edit sets state.text before this recomposes, so the guard is false then.
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
            // Save into the reading library (issue #19-5) - reachable from here or the library
            // screen itself, which lists everything saved from either place.
            OutlinedButton(
                onClick = viewModel::saveToLibrary,
                enabled = state.text.isNotBlank(),
            ) { Text("Save to library") }
        }
    }
}

/**
 * Karaoke-style "now playing" view (issue #19-3): the document's sentences ([TextChunker.intoSentences]
 * - the SAME split the one generation path chunks by, so this always agrees with what's actually
 * playing), one per line, with the current sentence highlighted and auto-scrolled toward. Renders
 * nothing when there is nothing applicable to highlight: no active sentence, or no sentences at all
 * (blank text) - the guard clauses below cover both, so an empty document is simply absent, not broken.
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
    // than an exact pixel position (sentences wrap to different heights) - good enough to keep the
    // highlighted line on screen during a long document without measuring every line's layout.
    LaunchedEffect(index) {
        val target = scrollState.maxValue.toFloat() * index / sentences.size
        scrollState.animateScrollTo(target.toInt().coerceIn(0, scrollState.maxValue))
    }
    // Label it so the highlighted echo of the text below the field reads as "the sentence being
    // spoken now", not an unexplained duplicate of what was typed (issue #123).
    FieldLabel("Now reading")
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
// off-screen - the field scrolls internally past this height instead of growing unbounded inside the
// page's own scrolling Column. min keeps short text from collapsing below the 3-line minLines floor.
private val TEXT_FIELD_MIN_HEIGHT = 96.dp
private val TEXT_FIELD_MAX_HEIGHT = 280.dp

/**
 * Status and secondary playback extras. The transport itself (play/pause, prev/next/stop, the
 * sentence seek bar, and the live status line) now lives in the persistent [BottomTransportBar] docked
 * to the Scaffold, like a real media app's mini-player - it no longer scrolls away with the rest of
 * the page. Rarely-used actions (Load model, Generate, Sample voice) are folded into that bar's
 * overflow menu ([PlaybackOverflowMenu]) instead of sitting here as a wall of equal-weight buttons.
 * What's left here is the stuff that's genuinely secondary: resuming a prior stop, the "model loaded"
 * indicator, generation progress/stats, and the sleep timer.
 */
@Composable
private fun PlaybackCard(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
    sleepTimer: SleepTimerHandle,
) {
    SectionCard("Playback") {
        val selected = state.selected
        val isModelLoaded = selected != null && state.loadedModelId == selected.modelId

        // "Resume from where it stopped" (issue #28): shown only when a prior run left a resume point,
        // replacing the old dead-end error with a one-tap continuation from that sentence.
        if (state.resumeSentenceIndex != null && !state.playing) {
            OutlinedButton(
                onClick = viewModel::resumeFromSaved,
                enabled = selected != null && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Resume from where it stopped") }
        }

        if (isModelLoaded) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "Model loaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Generation progress, labeled and SEPARATE from the playback position above (issue #123):
        // shown whenever a synthesis is running, including generating ahead during playback.
        if (state.busy) {
            Text(
                "Generating…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        // Measured generation stats behind a Details toggle - useful, but diagnostic, not for every glance.
        state.stats?.let { GenerationStatsView(it) }
        state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        // Sleep timer sits with the transport now (issue #123: someone using it in the dark shouldn't
        // have to scroll to the bottom of the app to find it).
        HorizontalDivider()
        SleepTimerControls(sleepTimer)
    }
}

/**
 * The persistent bottom transport - docked to the Scaffold instead of scrolling with the rest of the
 * page, like a real media app's mini-player (issue: "designed to look like a desktop website"). Holds
 * the sentence seek bar (while playing), the live status line, and the transport row itself (prev,
 * play/pause, next, stop) with the [PlaybackOverflowMenu] anchored at its end for the rarely-used
 * Load model / Generate / Sample voice actions - so Play stays the one obvious primary action.
 * Absent entirely until at least one model is installed (nothing to transport yet).
 */
@Composable
private fun BottomTransportBar(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
) {
    if (state.models.isEmpty()) return
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.playing) {
                SentenceSeekBar(state, viewModel, modifier = Modifier.padding(horizontal = 16.dp))
            }
            // Live playback state, and the synthesis-free listening estimate ("~6 min at 1.25x", issue
            // #23) when idle. The estimate is suppressed once real generation stats exist, so the rough
            // "~4 sec" estimate and the measured "Generated 2.6s" never sit on screen contradicting each
            // other (issue #123).
            val statusLine =
                when {
                    state.playing && state.paused -> "Paused"
                    state.playing -> "Playing…"
                    state.estimatedListeningSeconds > 0 && state.stats == null ->
                        formatListeningEstimate(state.estimatedListeningSeconds, state.params.speed)
                    else -> null
                }
            statusLine?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TransportBar(state, viewModel)
                PlaybackOverflowMenu(state, viewModel, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

/**
 * The "..." menu for Load model / Generate / Sample voice (issue: button-wall) - the actions almost
 * nobody needs to reach directly, since Play already loads-and-generates-and-plays in one tap. Each
 * item keeps the exact enabled/disabled rule the old inline buttons had, so nothing reachable before
 * became unreachable - it just isn't sitting on screen at the same visual weight as Play anymore.
 */
@Composable
private fun PlaybackOverflowMenu(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.selected
    val isModelLoaded = selected != null && state.loadedModelId == selected.modelId
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More playback actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!isModelLoaded) {
                DropdownMenuItem(
                    text = { Text("Load model") },
                    enabled = selected != null && !state.busy && !state.playing,
                    onClick = {
                        expanded = false
                        viewModel.loadModel()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Generate") },
                enabled = selected != null && !state.busy && !state.playing,
                onClick = {
                    expanded = false
                    viewModel.generateAudio()
                },
            )
            DropdownMenuItem(
                text = { Text("Sample voice") },
                enabled = selected != null && !state.busy,
                onClick = {
                    expanded = false
                    viewModel.sampleVoice()
                },
            )
        }
    }
}

/**
 * Playback position as a sentence-level seek bar. Time/duration isn't plumbed through, but this
 * reader navigates by sentence anyway (that's what skip does), so the slider seeks to a sentence and
 * reads "Sentence X of N". Absent when there's nothing to move between (one sentence or blank). Seeks
 * on release, not mid-drag, so dragging across a long document doesn't re-trigger synthesis at every
 * step (each seek restarts the one generation flow from that sentence).
 */
@Composable
private fun SentenceSeekBar(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
    modifier: Modifier = Modifier,
) {
    val sentenceCount = remember(state.text) { TextChunker.intoSentences(state.text).size }
    if (sentenceCount <= 1) return
    val lastIndex = sentenceCount - 1
    val current = (state.currentSentenceIndex ?: 0).coerceIn(0, lastIndex)
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val value = (dragValue ?: current.toFloat()).coerceIn(0f, lastIndex.toFloat())
    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { viewModel.seekToSentence(it.roundToInt()) }
                dragValue = null
            },
            valueRange = 0f..lastIndex.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Sentence ${value.roundToInt() + 1} of $sentenceCount",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// The central button's (icon, label, action) for the current state: Play when idle, Pause while
// playing, Resume while paused. One place so the button and its TalkBack label never disagree.
private fun primaryPlaybackAction(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
): Triple<androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit> =
    when {
        !state.playing -> Triple(Icons.Filled.PlayArrow, "Play", viewModel::play)
        state.paused -> Triple(Icons.Filled.PlayArrow, "Resume", viewModel::resumePlayback)
        else -> Triple(Icons.Filled.Pause, "Pause", viewModel::pausePlayback)
    }

/**
 * A standard media-player transport row - previous, a large central play/pause, next, stop - centered
 * like an MP3 player. The centre is a prominent filled circular button (the primary action, usable
 * with a model selected); prev/next/stop are plain icon buttons enabled only while something is
 * playing. Every icon carries a TalkBack description, and each button meets the 48dp touch target.
 */
@Composable
private fun TransportBar(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
) {
    val (playIcon, playLabel, playAction) = primaryPlaybackAction(state, viewModel)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = viewModel::skipBackSentence,
            enabled = state.playing,
            modifier = Modifier.size(TRANSPORT_BUTTON_SIZE),
        ) {
            Icon(Icons.Filled.SkipPrevious, "Previous sentence", modifier = Modifier.size(TRANSPORT_ICON_SIZE))
        }
        FilledIconButton(
            onClick = playAction,
            enabled = state.selected != null && (state.playing || !state.busy),
            modifier = Modifier.size(TRANSPORT_PRIMARY_SIZE),
        ) {
            Icon(playIcon, contentDescription = playLabel, modifier = Modifier.size(TRANSPORT_PRIMARY_ICON_SIZE))
        }
        IconButton(
            onClick = viewModel::skipForwardSentence,
            enabled = state.playing,
            modifier = Modifier.size(TRANSPORT_BUTTON_SIZE),
        ) {
            Icon(Icons.Filled.SkipNext, "Next sentence", modifier = Modifier.size(TRANSPORT_ICON_SIZE))
        }
        IconButton(
            onClick = viewModel::stop,
            enabled = state.playing,
            modifier = Modifier.size(TRANSPORT_BUTTON_SIZE),
        ) {
            Icon(Icons.Filled.Stop, "Stop", modifier = Modifier.size(TRANSPORT_ICON_SIZE))
        }
    }
}

private val TRANSPORT_BUTTON_SIZE = 48.dp
private val TRANSPORT_ICON_SIZE = 32.dp
private val TRANSPORT_PRIMARY_SIZE = 64.dp
private val TRANSPORT_PRIMARY_ICON_SIZE = 36.dp

/** Export + the non-destructive post-processing toggles + the sleep timer - the "on the way out" knobs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutputCard(
    state: TtsViewModel.UiState,
    viewModel: TtsViewModel,
    onExport: () -> Unit,
    onSampleAll: () -> Unit,
) {
    SectionCard("Output") {
        var confirmSampleAll by remember { mutableStateOf(false) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // "Export audio", not "Export WAV" - the format selector below can change the format, so
            // naming the button after one format contradicted it (issue #123). The chosen format still
            // shows in the picker underneath.
            OutlinedButton(
                onClick = onExport,
                enabled = state.selected != null && !state.busy,
            ) { Text("Export audio") }
            // Audition every downloaded model at once: one sample clip per model, saved to a folder.
            // Confirmed first (issue #123): with many models this loads and runs each one in turn - a
            // long, battery-heavy job - so it shouldn't fire from a single tap next to Export.
            OutlinedButton(
                onClick = { confirmSampleAll = true },
                enabled = state.models.isNotEmpty() && !state.busy && !state.playing,
            ) { Text("Sample every model") }
        }
        if (confirmSampleAll) {
            val count = state.models.size
            AlertDialog(
                onDismissRequest = { confirmSampleAll = false },
                title = { Text("Sample every model?") },
                text = {
                    Text(
                        "This loads and generates a clip for all $count installed models, one at a time. " +
                            "It can take a while and use noticeable battery.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmSampleAll = false
                        onSampleAll()
                    }) { Text("Sample all $count") }
                },
                dismissButton = { TextButton(onClick = { confirmSampleAll = false }) { Text("Cancel") } },
            )
        }
        if (viewModel.exportFormats.size > 1) {
            ExportFormatPicker(viewModel.exportFormats, state.exportFormat, viewModel::setExportFormat)
        }
        // Post-processing toggles + font size moved to Settings, and the sleep timer moved up next to
        // the transport (issue #123), so the Output card is now just export + format.
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
        OutlinedTextField(
            value = selected.format.displayName,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
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

/**
 * Live, measured generation stats (spec: RTF is calculated, never guessed). This is diagnostic
 * detail - RTF, words/s, ETA - so it hides behind a tap rather than sitting on screen for every
 * casual listen (issue #123: it read as developer telemetry). Collapsed by default.
 */
@Composable
private fun GenerationStatsView(stats: com.phonetts.core.metrics.GenerationStats) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        if (expanded) "Hide generation details" else "Generation details",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { expanded = !expanded },
    )
    if (!expanded) return
    val rtf = "%.2f".format(stats.realTimeFactor)
    val audio = "%.1f".format(stats.audioSecondsProduced)
    val eta = stats.estimatedRemainingSeconds?.let { " · ETA ${"%.0f".format(it)}s" } ?: ""
    val wps = if (stats.wordsPerSecond > 0) " · ${"%.1f".format(stats.wordsPerSecond)} words/s" else ""
    Text(
        "Generated ${audio}s of audio · ${rtf}x real-time$wps$eta",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// Offer (never force) an update: a dismissible banner that downloads the new APK IN THE APP - with
// progress and SHA-256 verification - and then offers to install it. It used to just fire the asset
// URL at the browser, whose download of GitHub's short-lived signed link would stall and never
// finish; "Open in browser" is still there as a fallback (see UpdateActions). Shown only when the
// update check found a strictly-newer release with an installable APK.
@Composable
private fun UpdateBanner(
    update: com.phonetts.core.update.UpdateStatus,
    download: com.phonetts.core.update.ApkDownloadState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Update available: ${update.latestVersion} (you have ${update.currentVersion})")
            UpdateActions(update, download, onDownload, onCancelDownload)
            OutlinedButton(onClick = onDismiss) { Text("Not now") }
        }
    }
}

// Surfaces DetectionFailureExplainer's narration of why a sideloaded folder's model couldn't be
// auto-detected (issue #19-2), with a Copy action (the LocalClipboardManager idiom BenchmarkScreen
// uses) so the owner can paste it into a bug report - this is read-only narration, it changes
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

// DYNAMIC: one control per parameter the model DECLARES (descriptor.parameters - the SSOT the engine
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContinuousControl(
    param: ModelParameter,
    value: Float,
    onValue: (Float) -> Unit,
) {
    val range = param.range ?: return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(param.displayName, modifier = Modifier.weight(1f))
        Text("%.2f".format(value), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
    Slider(value = value, onValueChange = onValue, valueRange = range)
    // Speed keeps its familiar preset shortcuts; other continuous knobs just get the slider. A
    // SingleChoiceSegmentedButtonRow (M3 guidance: segmented buttons for 2-5 choices) COMPRESSES to
    // fit one line instead of wrapping to two like the FilterChip row it replaces (issue: "speed
    // adjustments... on 2 lines, looking unprofessional") - still generated from the model's own
    // declared range (SSOT), never a hardcoded control or bound.
    if (param.id != ModelParameter.SPEED_ID) return
    // Only presets the model's own range actually supports - clamping out-of-range presets to
    // the boundary instead would show the same value twice (e.g. two "1.50x" segments).
    val presets = SPEED_PRESETS.filter { it in range }
    if (presets.isEmpty()) return
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        presets.forEachIndexed { index, preset ->
            SegmentedButton(
                selected = kotlin.math.abs(value - preset) < PRESET_MATCH_EPSILON,
                onClick = { onValue(preset) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size),
                label = { Text(formatSpeedPreset(preset)) },
            )
        }
    }
}

// "0.75x", "1x", "1.25x", "1.5x", "2x" - trailing zeros/dot trimmed so a whole-number preset reads
// as "1x", not "1.00x", matching the short segmented-button labels this control asks for.
private fun formatSpeedPreset(preset: Float): String {
    val trimmed = "%.2f".format(preset).trimEnd('0').trimEnd('.')
    return "${trimmed}x"
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
            FilterChip(
                selected = index == selected,
                onClick = { onIndex(index.toFloat()) },
                label = { Text(choice) },
            )
        }
    }
}

// Speed is a Float, so a preset chip is "selected" when the current value is essentially equal to it.
private const val PRESET_MATCH_EPSILON = 0.001f

private val SPEED_PRESETS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

// "~6 min at 1.25×" (or "~4 sec at 1.0×" under a minute) - the estimate itself (seconds) comes from
// ListeningTimeEstimator (:core, SSOT for the reading rate); this only rounds for display and formats
// the speed. Below a minute, rounding UP to "~1 min" overstated a short phrase's listening time by up
// to ~20x (issue #17) - showing seconds there instead keeps the estimate honest at that scale.
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

/** "Stop after N minutes" - routes to the same `stop()` a Stop tap uses (see [PlaybackService]). */
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
