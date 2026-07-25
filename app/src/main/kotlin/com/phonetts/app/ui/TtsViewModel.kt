package com.phonetts.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.app.audio.AudioTrackSink
import com.phonetts.app.playback.GenerationNotifier
import com.phonetts.app.speech.AppUpdateController
import com.phonetts.app.speech.ExportController
import com.phonetts.app.speech.FavoritesController
import com.phonetts.app.speech.ReaderPlaybackController
import com.phonetts.app.speech.ReaderPreferencesController
import com.phonetts.app.speech.ReportingSink
import com.phonetts.app.speech.SideloadController
import com.phonetts.app.speech.SpeechPlayer
import com.phonetts.core.audio.AudioSink
import com.phonetts.core.audio.TransformingSink
import com.phonetts.core.audio.export.AudioEncoder
import com.phonetts.core.audio.transform.BassCut
import com.phonetts.core.audio.transform.Crossfade
import com.phonetts.core.audio.transform.DeEsser
import com.phonetts.core.audio.transform.LoudnessNormalize
import com.phonetts.core.audio.transform.PresenceBoost
import com.phonetts.core.audio.transform.SilenceTrim
import com.phonetts.core.audio.transform.TempoStretch
import com.phonetts.core.audio.transform.TransformChain
import com.phonetts.core.diagnostics.FailureContext
import com.phonetts.core.diagnostics.FailureExplainer
import com.phonetts.core.engine.BlendedVoiceCatalog
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.metrics.GenerationStats
import com.phonetts.core.metrics.ListeningTimeEstimator
import com.phonetts.core.metrics.WordCounter
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.ModelParameter
import com.phonetts.core.prefs.LastUsedSelection
import com.phonetts.core.prefs.ReadingTextPreferences
import com.phonetts.core.store.FavoriteVoiceRef
import com.phonetts.core.text.DocumentId
import com.phonetts.core.text.TextChunker
import com.phonetts.core.update.ApkDownloadState
import com.phonetts.core.update.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Drives the main TTS screen. Everything the UI shows is derived from the catalog and the
 * selected [ModelDescriptor] (models, voices, speed range) - no model fact is hardcoded here.
 *
 * This is the UI-facing FACADE the mission asked for: transport/generation now lives in
 * [ReaderPlaybackController] (built on [com.phonetts.core.speech.SpeechSession]), export/sampling in
 * [ExportController], sideload/import in [SideloadController], voice favorites in
 * [FavoritesController], and the update checker in [AppUpdateController]. [UiState]'s
 * `playing`/`paused`/`currentSentenceIndex` (and the elapsed/total/stats/status/resume fields) are
 * DERIVED from [ReaderPlaybackController.state] via the single collector in [init] - never written
 * by hand from an action function - so a superseded run cannot leave this screen showing stale
 * transport state (see [ReaderPlaybackController]'s own kdoc for the bug class this eliminates).
 *
 * [player] is the ONE physical [AudioSink] this screen shares with Compare/Mix Voices
 * ([SpeechPlayer]) - starting playback here barges in on whatever was audible on another screen,
 * and vice versa, instead of two independent `AudioTrack`s fighting each other.
 */
class TtsViewModel(
    private val graph: AppGraph,
    private val player: SpeechPlayer = SpeechPlayer.shared,
) : ViewModel() {
    data class UiState(
        val models: List<ModelDescriptor> = emptyList(),
        val selected: ModelDescriptor? = null,
        val voiceId: String? = null,
        // Saved voice mixes (issue #42) re-applied to the loaded engine for the selected model, so a
        // mix is selectable in the main voice dropdown like any built-in voice. Populated only for a
        // blendable model once its engine is loaded; empty otherwise. The picker reads [voices].
        val blendedVoices: List<Voice> = emptyList(),
        // The user's chosen value for each parameter the selected model declares (keyed by
        // ModelParameter.id). Dynamic: whatever parameters a model advertises get an entry here, so a
        // model that adds e.g. an emotion knob needs no new field. The generation path reads [params].
        val paramValues: Map<String, Float> = emptyMap(),
        val text: String = "",
        val busy: Boolean = false,
        // Derived from ReaderPlaybackController.state - see this class's kdoc. Never written by hand.
        val playing: Boolean = false,
        val paused: Boolean = false,
        val status: String? = null,
        // Lock-screen/notification progress (issue #26): elapsed reflects audio actually delivered to
        // the sink; total is the synthesis-free listening estimate for the text being played. Both in
        // millis, both 0 outside a play session. Surfaced to the media session via playbackController.
        val playbackElapsedMillis: Long = 0,
        val playbackTotalMillis: Long = 0,
        // Non-destructive post-processing toggles (applied to export; raw audio is never altered).
        val trimSilence: Boolean = false,
        val normalizeVolume: Boolean = false,
        val crossfadeJoins: Boolean = false,
        // EQ-flavored clarity presets (issue #40) - biquad transforms on the same non-destructive
        // export chain as the toggles above; timbre only, native Speed untouched (rule 2).
        val bassCut: Boolean = false,
        val presenceBoost: Boolean = false,
        val deEss: Boolean = false,
        // Opt-in, PLAYBACK-ONLY beyond-native tempo (issue #43). A separate WSOLA time-stretch that
        // is NOT the native "Speed" ModelParameter and never resamples for it (rule 2); off by
        // default, applied only on the playback sink, never to generation or export.
        val tempoBoost: Boolean = false,
        val tempoFactor: Float = DEFAULT_TEMPO_FACTOR,
        // Long-document mode (issue #34): when on, a generation's GeneratedAudio spills older chunks
        // to a disk scratch file past a live window, bounding RAM for book-length synthesis. Off by
        // default; when off, generation is byte-for-byte the in-RAM behaviour it always was.
        val longDocumentMode: Boolean = false,
        val stats: GenerationStats? = null,
        // The modelId currently loaded into EngineManager (or null if none). Compared against
        // `selected?.modelId` by the UI to show "loaded" vs "load model" - set by loadModel() and
        // also picked up as a side effect of generate()/sampleVoice()/export() loading it anyway.
        val loadedModelId: String? = null,
        // Chosen export encoder (WAV/AAC/Opus); the list is derived from AppGraph.exportFormats.
        val exportFormat: AudioEncoder,
        // Cross-model favorite voices (issue #119): (modelId, voiceId) pairs from the shared
        // FavoritesStore - the SINGLE source of truth for voice favorites. The Home quick-pick
        // resolves these against [models] into [favoriteVoiceOptions], skipping any whose model/voice
        // is no longer in the catalog (fail closed); the voice picker's per-voice stars read the
        // derived [favoriteVoiceIds] below (same store, no second favorites list).
        val favoriteVoiceRefs: List<FavoriteVoiceRef> = emptyList(),
        // Set when a newer APK is available on GitHub Releases; drives the dismissible update banner.
        val update: UpdateStatus? = null,
        // Transient result line for a manual "Check for updates" tap ("Checking…", "Up to date (v…)",
        // or a failure note). Null except right after a manual check; the launch check stays silent.
        val updateCheckStatus: String? = null,
        // Where the in-app APK download stands. The app fetches the update itself now (progress here)
        // instead of handing the URL to the browser, whose download of GitHub's short-lived signed
        // asset link would stall and never finish.
        val updateDownload: ApkDownloadState = ApkDownloadState.Idle,
        // Sentence index to resume from after a generation/export failure (issue #28), or null when
        // there's nothing to resume. Non-null drives the "Resume from where it stopped" action.
        val resumeSentenceIndex: Int? = null,
        // Font scale for the reading/editing text field (issue #29, the A− / A+ control). A display
        // preference only - persisted via ReadingTextPreferences; scales no model fact.
        val readingScale: Float = ReadingTextPreferences.DEFAULT_SCALE,
        // The absolute sentence index (TextChunker.intoSentences' indexing) currently being heard,
        // for karaoke-style highlighting and per-sentence skip (issue #19-3). Non-null only while a
        // play session is active - derived from ReaderPlaybackController.state.
        val currentSentenceIndex: Int? = null,
        // The DetectionFailureExplainer narration of why a sideloaded folder's model couldn't be
        // auto-detected (issue #19-2), or null when there's nothing to show. Cleared on the next
        // sideload attempt (success or failure) or explicit dismissal.
        val sideloadFailureExplanation: String? = null,
    ) {
        /** The chosen parameter values, as the [SynthesisParams] bag the one generation path consumes. */
        val params: SynthesisParams get() = SynthesisParams(paramValues)

        /**
         * The voice list the dropdown shows for the selected model: its own voices (SSOT) plus any
         * saved mixes re-applied to the loaded engine (issue #42), merged so a mix never duplicates a
         * built-in. Empty until a model is selected.
         */
        val voices: List<Voice>
            get() = selected?.let { BlendedVoiceCatalog.merge(it.voices, blendedVoices) } ?: emptyList()

        /**
         * The favorited voice ids for the currently selected model, derived from the single
         * [favoriteVoiceRefs] source of truth (FavoritesStore). The voice picker reads this to
         * star/sort; the voices themselves still come from descriptor.voices (SSOT). Empty when no
         * model is selected. Deriving it here (rather than storing a second set) is what keeps the
         * picker stars, the "Favorite this voice" toggle, and the Home quick-pick all in agreement.
         */
        val favoriteVoiceIds: Set<String>
            get() {
                val modelId = selected?.modelId ?: return emptySet()
                return favoriteVoiceRefs.filter { it.modelId == modelId }.map { it.voiceId }.toSet()
            }

        /**
         * Whether the currently selected model+voice is starred as a cross-model favorite (issue
         * #119) - drives the "Favorite this voice" toggle. False when no model/voice is selected.
         */
        val currentVoiceIsFavorite: Boolean
            get() {
                val modelId = selected?.modelId ?: return false
                val voice = voiceId ?: return false
                return FavoriteVoiceRef(modelId, voice) in favoriteVoiceRefs
            }

        /**
         * The cross-model favorites (issue #119) resolved to display labels against the catalog
         * [models] (SSOT for names). A ref whose model or voice no longer exists is dropped, so the
         * Home quick-pick never offers a favorite that can't be selected (fail closed).
         */
        val favoriteVoiceOptions: List<FavoriteVoiceOption>
            get() =
                favoriteVoiceRefs.mapNotNull { ref ->
                    val model = models.firstOrNull { it.modelId == ref.modelId } ?: return@mapNotNull null
                    val voice = model.voices.firstOrNull { it.id == ref.voiceId } ?: return@mapNotNull null
                    FavoriteVoiceOption(ref.modelId, ref.voiceId, "${voice.name} (${model.displayName})")
                }

        /**
         * Estimated time to *listen* to the current text at the current speed (issue #23), from a real
         * [WordCounter] count - not a measurement of the engine (that's [GenerationStats]). Recomputes
         * for free whenever the text or speed changes, so the UI updates without any synthesis. Zero
         * for empty text; the UI shows nothing in that case.
         */
        val estimatedListeningSeconds: Double
            get() = ListeningTimeEstimator.estimateSeconds(WordCounter.count(text), params.speed)
    }

    /** One cross-model favorite voice (issue #119) resolved to a display [label] for the Home quick-pick. */
    data class FavoriteVoiceOption(
        val modelId: String,
        val voiceId: String,
        val label: String,
    )

    /** The export encoders available on this device (WAV always; AAC always; Opus on API 29+). */
    val exportFormats: List<AudioEncoder> = graph.exportFormats

    /**
     * The control surface the background [com.phonetts.app.playback.PlaybackService] drives from
     * its notification / lock-screen controls - the SAME pause/resume/stop as the in-app buttons,
     * so there is no second control path.
     */
    val playbackController =
        object : com.phonetts.app.playback.PlaybackController {
            override val isPlaying: Boolean get() = mutableState.value.playing
            override val isPaused: Boolean get() = mutableState.value.paused

            // Elapsed/total for the lock-screen scrubber (issue #26); null when nothing is playing so
            // the session omits the scrubber rather than showing a stale one.
            override val progress: com.phonetts.app.playback.PlaybackProgress?
                get() {
                    val s = mutableState.value
                    if (!s.playing) return null
                    return com.phonetts.app.playback.PlaybackProgress(s.playbackElapsedMillis, s.playbackTotalMillis)
                }

            override fun pause() = pausePlayback()

            override fun resume() = resumePlayback()

            override fun stop() = this@TtsViewModel.stop()

            override fun skipForwardParagraph() = this@TtsViewModel.skipForwardParagraph()

            override fun skipBackParagraph() = this@TtsViewModel.skipBackParagraph()
        }

    private val mutableState = MutableStateFlow(UiState(exportFormat = exportFormats.first()))
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    // Set by the Activity while it is bound to the playback service (null when unbound). Every in-app
    // barge-in ([stop]) invokes it so the service knows the resulting stop was user-initiated and does
    // NOT fire the end-of-document cue (issue #32). Natural completion never calls [stop] - the play
    // coroutine just finishes - so the cue still fires only on a genuine end of document.
    var onUserStopRequested: (() -> Unit)? = null

    // Live "Generating sentence X of Y" system notification - a generation over more than a couple
    // sentences easily outlasts the user staying on this screen. NOTE: reads `graph.appContext`,
    // which AppGraph currently declares `private` - see the mission report for the one-field change
    // AppGraph needs (not made here - AppGraph.kt is outside this session's owned files).
    private val generationNotifier = GenerationNotifier(graph.appContext)

    private val readerController =
        ReaderPlaybackController(graph = graph, player = player, scope = viewModelScope, notifier = generationNotifier)
    private val exportController = ExportController(graph, player)
    private val sideloadController = SideloadController(graph)
    private val favoritesController = FavoritesController(graph)
    private val updateController = AppUpdateController(graph, viewModelScope)
    private val preferencesController = ReaderPreferencesController(graph)

    init {
        migrateLegacyFavorites()
        refreshModels()
        // Model hydration now runs off the main thread (so a large library doesn't stall launch), so
        // the catalog fills in AFTER this init. Re-read the model list - and retry the one-time
        // favorites migration - whenever the catalog changes, so the home screen populates as models
        // land. StateFlow is conflated, so a burst of hydration adds coalesces into a few refreshes.
        viewModelScope.launch {
            graph.catalog.revision.collect {
                migrateLegacyFavorites()
                refreshModels()
            }
        }
        // The one collector for every transport-derived field (mission: never write playing/paused/
        // currentSentenceIndex by hand - see ReaderPlaybackController's kdoc).
        viewModelScope.launch {
            readerController.state.collect { r ->
                mutableState.update {
                    it.copy(
                        playing = r.playing,
                        paused = r.paused,
                        currentSentenceIndex = r.currentSentenceIndex,
                        playbackElapsedMillis = r.playbackElapsedMillis,
                        playbackTotalMillis = r.playbackTotalMillis,
                        stats = r.stats,
                        status = r.status,
                        resumeSentenceIndex = r.resumeSentenceIndex,
                    )
                }
            }
        }
        viewModelScope.launch {
            updateController.state.collect { u ->
                mutableState.update {
                    it.copy(update = u.update, updateCheckStatus = u.updateCheckStatus, updateDownload = u.updateDownload)
                }
            }
        }
        mutableState.update {
            it.copy(
                readingScale = preferencesController.readingScale(),
                longDocumentMode = preferencesController.longDocumentModeEnabled(),
            )
        }
        restoreSession()
        updateController.checkForUpdate(announce = false)
    }

    private fun migrateLegacyFavorites() = favoritesController.migrateLegacyFavorites(graph.catalog.list())

    /** Manually re-run the update check (the Help screen's "Check for updates" button). */
    fun checkForUpdatesNow() = updateController.checkForUpdate(announce = true)

    fun downloadUpdate() = updateController.downloadUpdate()

    fun cancelUpdateDownload() = updateController.cancelUpdateDownload()

    fun dismissUpdate() = updateController.dismissUpdate()

    fun refreshModels() {
        val models = graph.catalog.list()
        // Only relevant the FIRST time a selection is made (app start): prefer the globally saved
        // last-used model/voice/speed (issue #19-1) over "just pick the first model in the list".
        // Once something is already selected, later refreshes (e.g. returning from Browse/Manage)
        // must never clobber the user's current pick.
        val restored = if (mutableState.value.selected == null) restoredSelection(models) else null
        mutableState.update { current ->
            val wasAlreadySelected = current.selected != null
            val selected = current.selected ?: restored?.descriptor ?: models.firstOrNull()
            val restoringThisModel =
                !wasAlreadySelected && restored != null && restored.descriptor.modelId == selected?.modelId
            current.copy(
                models = models,
                selected = selected,
                voiceId =
                    current.voiceId
                        ?: restored?.voiceId?.takeIf { restoringThisModel }
                        ?: selected?.let(::defaultVoiceIdFor),
                paramValues =
                    when {
                        wasAlreadySelected -> current.paramValues
                        selected == null -> current.paramValues
                        restoringThisModel -> defaultParamValues(selected, preferredSpeed = restored?.speed)
                        else -> defaultParamValues(selected)
                    },
                favoriteVoiceRefs = favoritesController.refs(),
            )
        }
        // If the selected model is the loaded one, re-surface its saved mixes - this is what makes a
        // mix saved on the Mix page appear in the dropdown on return (that page applied it to the
        // loaded engine already). A no-op before any engine is loaded (issue #42).
        mutableState.value.selected
            ?.takeIf { graph.engineManager.currentDescriptor?.modelId == it.modelId }
            ?.let(::refreshBlendedVoices)
    }

    private data class RestoredSelection(val descriptor: ModelDescriptor, val voiceId: String, val speed: Float)

    // Look up the globally saved last-used selection (issue #19-1) against the CURRENT catalog,
    // failing closed at every step: a saved modelId no longer in [models], or a saved voiceId no
    // longer on that model, or a saved speed outside the model's current range, is silently ignored
    // rather than crashing or selecting something the model doesn't actually offer.
    private fun restoredSelection(models: List<ModelDescriptor>): RestoredSelection? {
        val saved = graph.lastUsedSelection.last() ?: return null
        val descriptor = models.firstOrNull { it.modelId == saved.modelId } ?: return null
        val voiceId = descriptor.voices.firstOrNull { it.id == saved.voiceId }?.id ?: descriptor.defaultVoiceId
        val speed = saved.speed.takeIf { it in descriptor.speedRange } ?: descriptor.defaultSpeed
        return RestoredSelection(descriptor, voiceId, speed)
    }

    // Re-apply any saved voice mixes for [descriptor] to the just-loaded engine and expose the
    // resulting blended voices, so the main voice dropdown lists them alongside the built-ins
    // (issue #42). Gated on the descriptor's own supportsVoiceBlend fact (SSOT) - a non-blendable
    // model attempts nothing; specs whose source voices are missing are skipped by the applier
    // (fail-soft). Reads currentEngine, so call it after a successful switchTo/load.
    private fun refreshBlendedVoices(descriptor: ModelDescriptor) {
        if (!descriptor.supportsVoiceBlend) {
            mutableState.update { it.copy(blendedVoices = emptyList()) }
            return
        }
        val specs = graph.blendedVoices.forModel(descriptor.modelId)
        val voices = BlendedVoiceCatalog.apply(graph.engineManager.currentEngine, specs)
        mutableState.update { it.copy(blendedVoices = voices) }
    }

    fun selectModel(descriptor: ModelDescriptor) {
        // Switching model mid-utterance is a barge-in: abandon synthesis, drop buffered chunks, and
        // stop the AudioTrack (issue #45) before re-selecting. No-op cost when nothing is playing.
        abandonPlayback()
        mutableState.update {
            it.copy(
                selected = descriptor,
                voiceId = defaultVoiceIdFor(descriptor),
                paramValues = defaultParamValues(descriptor),
                // A different model's mixes never apply here; the new engine repopulates them on load.
                blendedVoices = emptyList(),
            )
        }
        saveLastUsedSelection()
    }

    // Each declared parameter's default value, keyed by id - the starting point for the controls.
    // [preferredSpeed] optionally overrides just the speed knob (issue #19-1's restored selection);
    // it only takes effect if the model actually declares a speed parameter (SSOT: never invents a
    // paramValues entry for a knob the descriptor didn't declare).
    private fun defaultParamValues(
        descriptor: ModelDescriptor,
        preferredSpeed: Float? = null,
    ): Map<String, Float> =
        descriptor.parameters.associate { param ->
            val value = if (param.id == ModelParameter.SPEED_ID) preferredSpeed ?: param.default else param.default
            param.id to value
        }

    // Remembering the user's manual pick as the per-language default
    // (defaultVoicePreference.setDefaultVoice) is what makes defaultVoiceIdFor's prefill useful next
    // time this language comes up.
    fun setVoice(voiceId: String) {
        // Switching voice mid-utterance is the same barge-in as stop/skip (issue #45).
        abandonPlayback()
        mutableState.update { current ->
            current.selected?.voices?.firstOrNull { it.id == voiceId }?.let(graph.defaultVoicePreference::setDefaultVoice)
            current.copy(voiceId = voiceId)
        }
        saveLastUsedSelection()
    }

    /**
     * Flips [voice]'s favorite state for the currently selected model, via the single FavoritesStore
     * source of truth (issue: favorites were split across two stores). The voice picker's stars and
     * the "Favorite this voice" toggle both read the derived [UiState.favoriteVoiceIds] / the same
     * refs, so they always agree. A no-op until a model is selected.
     */
    fun toggleFavoriteVoice(voice: Voice) {
        val modelId = mutableState.value.selected?.modelId ?: return
        toggleFavoriteVoice(modelId, voice.id)
    }

    /**
     * Star/unstar a voice belonging to ANY model, without selecting it.
     *
     * The single-argument overload above can only act on the currently selected model, which was
     * fine while the only way to reach a star was to open that model's own voice dropdown. The
     * unified voice sheet lists every model's voices at once, so starring one used to require
     * selecting its model first - meaning a tap on a star silently switched the voice and barged in
     * on playback. Favoriting is a bookmark, not a selection; it should never move playback.
     */
    fun toggleFavoriteVoice(
        modelId: String,
        voiceId: String,
    ) {
        val refs = favoritesController.toggle(modelId, voiceId)
        mutableState.update { it.copy(favoriteVoiceRefs = refs) }
    }

    /**
     * Star/unstar the currently selected model+voice as a favorite (issue #119) via the shared
     * FavoritesStore. Identical in effect to [toggleFavoriteVoice] for the selected voice - both
     * write the one favorites store - it just reads the model+voice from current state. A no-op
     * until a model+voice is actually selected.
     */
    fun toggleFavoriteCurrentVoice() {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        val voiceId = s.voiceId ?: return
        toggleFavoriteVoice(descriptor.modelId, voiceId)
    }

    /**
     * Jump to a favorite voice (issue #119) that may live in ANY model: switch the active model (only
     * when it differs) then the voice, in one tap, without hunting through each model's list. Fails
     * closed - a ref whose model/voice is no longer in the catalog is ignored (the quick-pick resolves
     * against the same catalog, so it never offers one anyway). Reuses the existing barge-in/save
     * discipline of [selectModel]/[setVoice].
     */
    fun selectFavoriteVoice(
        modelId: String,
        voiceId: String,
    ) {
        val descriptor = mutableState.value.models.firstOrNull { it.modelId == modelId } ?: return
        if (descriptor.voices.none { it.id == voiceId }) return
        if (mutableState.value.selected?.modelId != descriptor.modelId) selectModel(descriptor)
        setVoice(voiceId)
    }

    // Prefer the saved per-language default among THIS descriptor's own voices (SSOT - never a
    // voice the descriptor didn't offer), falling back to the descriptor's own default voice id.
    private fun defaultVoiceIdFor(descriptor: ModelDescriptor): String {
        val fallbackId = descriptor.defaultVoiceId
        val language = descriptor.voices.firstOrNull { it.id == fallbackId }?.language
        val saved = language?.let { graph.defaultVoicePreference.defaultVoice(it, descriptor.voices) }
        return saved?.id ?: fallbackId
    }

    /** Set the value of one declared parameter (e.g. "speed") by id - the UI's generic control hook. */
    fun setParam(
        id: String,
        value: Float,
    ) {
        mutableState.update { it.copy(paramValues = it.paramValues + (id to value)) }
        // Only the speed knob is part of the globally-remembered selection (issue #19-1); other
        // parameters (e.g. a future emotion selector) aren't in LastUsedSelection's scope.
        if (id == ModelParameter.SPEED_ID) saveLastUsedSelection()
    }

    // Persist the user's current model+voice+speed globally (issue #19-1) - restored as the initial
    // selection on next launch by refreshModels()/restoredSelection() above. Deliberately NOT keyed
    // to a document: LastUsedSelection is one shared "where I left off", separate from DocumentMemory's
    // per-document resume position. A no-op before a model/voice is actually selected.
    private fun saveLastUsedSelection() {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        val voiceId = s.voiceId ?: return
        graph.lastUsedSelection.record(LastUsedSelection(descriptor.modelId, voiceId, s.params.speed))
    }

    // Guards Play/Generate against empty text (issue #15): rather than a silent no-op that still
    // pays the switchTo()/engine.load() cost for nothing, surface clear feedback and return before
    // touching the engine at all - no model load for text that can't produce any sentences anyway.
    private fun requireNonBlankText(text: String): Boolean {
        if (text.isNotBlank()) return true
        mutableState.update { it.copy(status = "Enter some text to speak") }
        return false
    }

    fun setText(text: String) {
        mutableState.update { it.copy(text = text, resumeSentenceIndex = resumeIndexFor(text)) }
        scheduleTextPersist(text)
    }

    // Persist the reader's text so a relaunch restores it. Debounced rather than written on every
    // keystroke - the document can be book-length, and rewriting it per character would hammer the
    // disk on a budget phone. The pending write is also flushed on stop()/onCleared() so a kill
    // between keystrokes cannot lose more than the debounce window.
    //
    // This also un-strands DocumentMemory: its resume position is keyed by a hash of the text, so
    // once the text itself stopped surviving a restart, every saved resume point became unreachable.
    private var textPersistJob: Job? = null

    private fun scheduleTextPersist(text: String) {
        textPersistJob?.cancel()
        textPersistJob =
            viewModelScope.launch {
                delay(TEXT_PERSIST_DEBOUNCE_MS)
                withContext(Dispatchers.IO) { graph.sessionState.updateReader { it.copy(text = text) } }
            }
    }

    // Write any debounced text immediately (teardown paths).
    private fun flushTextPersist() {
        textPersistJob?.cancel()
        textPersistJob = null
        runCatching { graph.sessionState.updateReader { it.copy(text = mutableState.value.text) } }
    }

    // The saved resume point for this text, if any (issue #28) - surfaced only when it's past the
    // start (index 0 would just be "press Play"). Reads the per-document memory keyed by text content.
    private fun resumeIndexFor(text: String): Int? {
        if (text.isBlank()) return null
        return graph.documentMemory.resume(documentIdFor(text))?.sentenceIndex?.takeIf { it > 0 }
    }

    // A stable id for the current text so DocumentMemory can key its resume position to this document
    // (issues #24/#28). Content-derived: editing the text starts a fresh document, as expected. The
    // SAME id (com.phonetts.core.text.DocumentId) is what ReadingLibraryViewModel saves a document
    // under (issue #19-5), so opening a saved document here lines up with any resume point for free.
    private fun documentIdFor(text: String): String = DocumentId.of(text)

    /** A+ : one step larger reading font (issue #29), persisted immediately. */
    fun increaseTextScale() = mutableState.update { it.copy(readingScale = preferencesController.increaseTextScale()) }

    /** A− : one step smaller reading font (issue #29), persisted immediately. */
    fun decreaseTextScale() = mutableState.update { it.copy(readingScale = preferencesController.decreaseTextScale()) }

    // Put the user back where they were. Called once at startup, before the update check, so a
    // relaunch shows the text they were reading and the toggles they had set instead of a blank
    // screen with every setting reverted.
    private fun restoreSession() {
        val saved = graph.sessionState.reader()
        applyToggles(preferencesController.currentToggles())
        if (saved.text.isBlank()) return
        mutableState.update { it.copy(text = saved.text, resumeSentenceIndex = resumeIndexFor(saved.text)) }
    }

    // Every toggle setter below routes through ReaderPreferencesController (which persists it) and
    // copies the refreshed values back into UiState, so the two can never disagree about what a
    // toggle is currently set to.
    private fun applyToggles(t: ReaderPreferencesController.Toggles) =
        mutableState.update {
            it.copy(
                trimSilence = t.trimSilence,
                normalizeVolume = t.normalizeVolume,
                crossfadeJoins = t.crossfadeJoins,
                bassCut = t.bassCut,
                presenceBoost = t.presenceBoost,
                deEss = t.deEss,
                tempoBoost = t.tempoBoost,
                tempoFactor = t.tempoFactor,
            )
        }

    fun setTrimSilence(on: Boolean) = applyToggles(preferencesController.setTrimSilence(on))

    fun setNormalizeVolume(on: Boolean) = applyToggles(preferencesController.setNormalizeVolume(on))

    fun setCrossfadeJoins(on: Boolean) = applyToggles(preferencesController.setCrossfadeJoins(on))

    fun setBassCut(on: Boolean) = applyToggles(preferencesController.setBassCut(on))

    fun setPresenceBoost(on: Boolean) = applyToggles(preferencesController.setPresenceBoost(on))

    fun setDeEss(on: Boolean) = applyToggles(preferencesController.setDeEss(on))

    /** Toggle the opt-in, playback-only beyond-native tempo boost (issue #43). Off by default. */
    fun setTempoBoost(on: Boolean) = applyToggles(preferencesController.setTempoBoost(on))

    /** Set the beyond-native tempo factor (clamped to [ReaderPreferencesController]'s advertised range). */
    fun setTempoFactor(factor: Float) = applyToggles(preferencesController.setTempoFactor(factor))

    /** Toggle long-document (spill-to-disk) mode (issue #34), persisted immediately. Off by default. */
    fun setLongDocumentMode(on: Boolean) =
        mutableState.update {
            preferencesController.setLongDocumentMode(on)
            it.copy(longDocumentMode = on)
        }

    fun setExportFormat(encoder: AudioEncoder) = mutableState.update { it.copy(exportFormat = encoder) }

    /**
     * Load the selected model's engine ahead of time, so Generate/Play's first tap isn't also
     * paying the weight-load cost (EngineManager.switchTo is a no-op if this exact model is
     * already loaded - see core EngineManagerTest - so this genuinely saves the next call
     * something, it doesn't just move the cost around).
     */
    fun loadModel() {
        val descriptor = mutableState.value.selected ?: return
        mutableState.update { it.copy(busy = true, status = "Loading model…") }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { graph.engineManager.switchTo(descriptor.engineId, descriptor) }
            }
                .onSuccess {
                    refreshBlendedVoices(descriptor)
                    mutableState.update {
                        it.copy(busy = false, status = "Model loaded", loadedModelId = descriptor.modelId)
                    }
                }
                .onFailure { e ->
                    val status = FailureExplainer.explain(e, FailureContext.LOADING_MODEL)
                    mutableState.update { it.copy(busy = false, status = status) }
                }
        }
    }

    /**
     * Generate audio for the current text/voice/params WITHOUT playing it - lets you see the
     * stats (real-time factor, timing) before committing to playback. Same one generation path as
     * Play/Export (spec §6.1); this just stops short of consuming the flow into playback.
     */
    fun generateAudio() {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        if (!requireNonBlankText(s.text)) return
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        val documentId = documentIdFor(s.text)
        readerController.stop(descriptor, voiceId, s.params.speed, documentId)
        mutableState.update { it.copy(busy = true) }
        val selection = ReaderPlaybackController.Selection(descriptor, voiceId, s.text, s.paramValues, s.longDocumentMode)
        readerController.generateOnly(
            selection = selection,
            documentId = documentId,
            onEngineLoaded = ::refreshBlendedVoices,
            onModelLoaded = { loaded -> mutableState.update { it.copy(busy = false, loadedModelId = loaded ?: it.loadedModelId) } },
        )
    }

    /**
     * The primary Play button. Resumes by default.
     *
     * It used to be `startPlaybackFrom(0)` - hardcoded to the top, never consulting the resume point
     * that [com.phonetts.core.prefs.DocumentMemory] was already recording. Now it continues from
     * where playback actually stopped, and only falls back to the top when there is no resume point
     * or the saved one is no longer valid for the current voice/engine/speed.
     */
    fun play() = startPlaybackFrom(validatedResumeIndex() ?: 0)

    private fun validatedResumeIndex(): Int? {
        val s = mutableState.value
        val descriptor = s.selected ?: return null
        if (s.text.isBlank()) return null
        return readerController.validatedResumeIndex(
            descriptor,
            s.voiceId ?: descriptor.defaultVoiceId,
            s.params.speed,
            documentIdFor(s.text),
        )
    }

    /**
     * "Read from here" (issue #24): start playback from the sentence containing character [charOffset]
     * of the current text.
     */
    fun playFromCursor(charOffset: Int) =
        startPlaybackFrom(TextChunker.sentenceIndexAt(mutableState.value.text, charOffset))

    /** "Resume from where it stopped" (issue #28): replay from the last saved resume point. */
    fun resumeFromSaved() {
        val index = mutableState.value.resumeSentenceIndex ?: return
        startPlaybackFrom(index)
    }

    private fun startPlaybackFrom(startSentenceIndex: Int) {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        if (!requireNonBlankText(s.text)) return
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        val documentId = documentIdFor(s.text)
        val selection = ReaderPlaybackController.Selection(descriptor, voiceId, s.text, s.paramValues, s.longDocumentMode)
        readerController.playFrom(
            selection = selection,
            startSentenceIndex = startSentenceIndex,
            documentId = documentId,
            outputSink = playbackSink(s),
            onEngineLoaded = ::refreshBlendedVoices,
            onModelLoaded = { loaded -> loaded?.let { id -> mutableState.update { it.copy(loadedModelId = id) } } },
        )
    }

    /** Lock-screen "next paragraph" (issue #26): restart the one generation flow one paragraph on. */
    fun skipForwardParagraph() =
        startPlaybackFrom(TextChunker.nextParagraphStart(mutableState.value.text, currentPlaybackSentenceIndex()))

    /** Lock-screen "previous paragraph" (issue #26): restart the one generation flow one paragraph back. */
    fun skipBackParagraph() =
        startPlaybackFrom(TextChunker.previousParagraphStart(mutableState.value.text, currentPlaybackSentenceIndex()))

    /**
     * Per-sentence "next" (issue #19-3): the same restart-the-one-generation-flow-from-an-index
     * mechanism [skipForwardParagraph] uses, just one sentence forward instead of a whole paragraph.
     * Clamped to the last sentence. A no-op on blank text.
     */
    fun skipForwardSentence() {
        val text = mutableState.value.text
        if (text.isBlank()) return
        val lastIndex = (TextChunker.intoSentences(text).size - 1).coerceAtLeast(0)
        startPlaybackFrom((currentPlaybackSentenceIndex() + 1).coerceAtMost(lastIndex))
    }

    /** Per-sentence "previous" (issue #19-3): mirrors [skipForwardSentence], one sentence back. */
    fun skipBackSentence() {
        val text = mutableState.value.text
        if (text.isBlank()) return
        startPlaybackFrom((currentPlaybackSentenceIndex() - 1).coerceAtLeast(0))
    }

    /**
     * Seek to an absolute sentence [index] (the playback seek slider, issue #123). Clamped into the
     * document's range; a no-op on blank text.
     */
    fun seekToSentence(index: Int) {
        val text = mutableState.value.text
        if (text.isBlank()) return
        val lastIndex = (TextChunker.intoSentences(text).size - 1).coerceAtLeast(0)
        startPlaybackFrom(index.coerceIn(0, lastIndex))
    }

    /** Total sentences in the current text - the seek slider's upper bound. Zero for blank text. */
    fun sentenceCount(): Int = TextChunker.intoSentences(mutableState.value.text).size

    // The absolute sentence playback is at now - DERIVED state, so this simply reads it back.
    private fun currentPlaybackSentenceIndex(): Int = mutableState.value.currentSentenceIndex ?: 0

    // The sink playback drains into: the shared player's raw sink, wrapped with the opt-in
    // beyond-native tempo transform when enabled. PLAYBACK ONLY - export/generation never call this
    // (issue #43).
    private fun playbackSink(s: UiState): AudioSink {
        val raw = if (s.tempoBoost) TransformingSink(player.sink, TempoStretch(s.tempoFactor)) else player.sink
        // See ReportingSink's kdoc: SpeechSession's consumer coroutine has no catch of its own
        // around a sink failure, so this is what turns a hardware AudioTrack error back into the
        // friendly "Playback failed: …" status every screen used to show, instead of an uncaught
        // exception in viewModelScope.
        return ReportingSink(raw) { e -> mutableState.update { it.copy(status = FailureExplainer.explain(e, FailureContext.PLAYING)) } }
    }

    /** Pause audio playback; generation keeps running and filling the buffer. */
    fun pausePlayback() = readerController.pause()

    /** Resume playback of already-generated audio (no re-synthesis). */
    fun resumePlayback() = readerController.resume()

    /**
     * The transport Stop / skip / switch-voice barge-in (issue #45): halts audible playback
     * immediately. Generation, if still running, is deliberately left alone by
     * [ReaderPlaybackController.stop] so a subsequent Play resumes with MORE audio already
     * generated rather than starting over (see that class's kdoc for why this is a deliberate,
     * disclosed change from the pre-SpeechSession behaviour, which cancelled generation outright).
     */
    fun stop() {
        // Tell the service (if bound) this stop is user-initiated, so it doesn't misread the
        // resulting playing→stopped edge as a natural end-of-document and chime (issue #32).
        onUserStopRequested?.invoke()
        val s = mutableState.value
        readerController.stop(s.selected, s.voiceId, s.params.speed, s.text.takeIf { it.isNotBlank() }?.let(::documentIdFor))
    }

    // The stronger teardown for a model/voice switch (issue #45) - fully cancels generation too, so
    // the about-to-be-unloaded engine's synthesis coroutine can't race EngineManager.switchTo().
    private fun abandonPlayback() {
        val s = mutableState.value
        readerController.abandon(s.selected, s.voiceId, s.params.speed, s.text.takeIf { it.isNotBlank() }?.let(::documentIdFor))
    }

    /**
     * Measure this engine+voice's real render speed on a short phrase (never a guessed RTF) and
     * report it. Uses the same `synthesize()` path - no second generation path (spec §6.1).
     */
    fun sampleVoice() {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        mutableState.update { it.copy(busy = true, status = "Measuring voice…") }
        viewModelScope.launch {
            exportController.sampleVoice(descriptor, voiceId, s.params)
                .onSuccess { r ->
                    val rtf = "%.2f".format(r.realTimeFactor)
                    mutableState.update { it.copy(busy = false, status = "Voice sample: ${rtf}x real-time") }
                }
                .onFailure { e ->
                    val status = FailureExplainer.explain(e, FailureContext.GENERATING)
                    mutableState.update { it.copy(busy = false, status = status) }
                }
        }
    }

    /**
     * Generate a short spoken sample of EVERY downloaded model and save each to its own file, so
     * you can audition them back-to-back.
     */
    fun sampleAllModels(sink: AudioFileSink) {
        val models = mutableState.value.models
        if (models.isEmpty()) {
            mutableState.update { it.copy(status = "No models to sample yet") }
            return
        }
        val encoder = mutableState.value.exportFormat
        mutableState.update { it.copy(busy = true, status = "Sampling models…", stats = null) }
        viewModelScope.launch {
            val result =
                exportController.sampleAllModels(
                    models = models,
                    format = encoder,
                    sink = ExportController.FileSink { name -> sink.create(name) },
                    onProgress = { index, total, name -> mutableState.update { it.copy(status = "Sampling $index/$total: $name…") } },
                )
            mutableState.update { it.copy(busy = false, status = result.summary, loadedModelId = models.last().modelId) }
        }
    }

    /** Export the current text to an audio file, applying the enabled non-destructive transforms. */
    fun export(output: OutputStream) {
        val s = mutableState.value
        val descriptor = s.selected
        if (!requireNonBlankText(s.text) || descriptor == null) {
            runCatching { output.close() }
            return
        }
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        val documentId = documentIdFor(s.text)
        mutableState.update { it.copy(busy = true, status = "Exporting…") }
        viewModelScope.launch {
            exportController.export(
                descriptor = descriptor,
                voiceId = voiceId,
                params = s.params,
                text = s.text,
                format = s.exportFormat,
                transforms = buildTransforms(s),
                documentId = documentId,
                output = output,
            ) { result ->
                when (result) {
                    is ExportController.ExportResult.Success -> mutableState.update { it.copy(busy = false, status = "Exported") }
                    is ExportController.ExportResult.Skipped -> mutableState.update { it.copy(busy = false) }
                    is ExportController.ExportResult.Failure ->
                        mutableState.update {
                            it.copy(
                                busy = false,
                                status = result.status,
                                resumeSentenceIndex = result.resumeSentenceIndex.takeIf { idx -> idx > 0 },
                            )
                        }
                }
            }
        }
    }

    // Build the transform chain from the current toggles. Off by default and applied to a copy of
    // the audio, so the export is post-processed while the model's raw output is never altered.
    private fun buildTransforms(s: UiState): TransformChain =
        TransformChain
            .of(listOf(SilenceTrim(), LoudnessNormalize(), Crossfade(), BassCut(), PresenceBoost(), DeEsser()))
            .withEnabled(SilenceTrim.ID, s.trimSilence)
            .withEnabled(LoudnessNormalize.ID, s.normalizeVolume)
            .withEnabled(Crossfade.ID, s.crossfadeJoins)
            .withEnabled(BassCut.ID, s.bassCut)
            .withEnabled(PresenceBoost.ID, s.presenceBoost)
            .withEnabled(DeEsser.ID, s.deEss)

    fun sideloadFolder(uri: Uri) {
        mutableState.update { it.copy(busy = true, status = null, sideloadFailureExplanation = null) }
        viewModelScope.launch {
            when (val result = sideloadController.importFolder(uri)) {
                is SideloadController.ImportResult.Success -> {
                    mutableState.update {
                        it.copy(busy = false, status = "Added model", sideloadFailureExplanation = null)
                    }
                    refreshModels()
                }
                is SideloadController.ImportResult.Failure ->
                    mutableState.update {
                        it.copy(busy = false, status = result.status, sideloadFailureExplanation = result.explanation)
                    }
            }
        }
    }

    /** Dismiss the sideload-failure explanation panel (issue #19-2). */
    fun dismissSideloadFailureExplanation() = mutableState.update { it.copy(sideloadFailureExplanation = null) }

    fun importTextFrom(uri: Uri) {
        mutableState.update { it.copy(busy = true, status = null) }
        viewModelScope.launch {
            sideloadController.importText(uri)
                .onSuccess { text -> mutableState.update { it.copy(busy = false, text = text, status = "Imported text") } }
                .onFailure { e ->
                    val status = FailureExplainer.explain(e, FailureContext.IMPORTING)
                    mutableState.update { it.copy(busy = false, status = status) }
                }
        }
    }

    /**
     * Save the current text into the reading library (issue #19-5), reachable right from the main
     * screen. A no-op on blank text.
     */
    fun saveToLibrary() {
        val text = mutableState.value.text
        if (text.isBlank()) return
        sideloadController.saveToLibrary(text, documentIdFor(text))
        mutableState.update { it.copy(status = "Saved to library") }
    }

    override fun onCleared() {
        abandonPlayback() // also clears the generation notification and fully cancels generation
        flushTextPersist() // never lose the last edits to an unwritten debounce window
    }

    private companion object {
        // Long enough that typing doesn't cause a write per character, short enough that a process
        // death loses at most this much of what was typed.
        const val TEXT_PERSIST_DEBOUNCE_MS = 800L

        // Default beyond-native tempo factor when the (off-by-default) boost is first enabled - a
        // mild speed-up the user then adjusts. Not a native model parameter; see UiState.tempoFactor.
        const val DEFAULT_TEMPO_FACTOR = 1.5f
    }
}

/**
 * Creates one output file per model for [TtsViewModel.sampleAllModels], named [fileName] (no
 * extension - the sink adds the export format's). Returning null skips that model without aborting
 * the batch. Kept as a tiny functional interface so the ViewModel never touches SAF/DocumentFile
 * directly, exactly as [TtsViewModel.export] takes a plain OutputStream.
 */
fun interface AudioFileSink {
    fun create(fileName: String): OutputStream?
}
