package com.phonetts.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.app.BuildConfig
import com.phonetts.app.UserPickRequiredException
import com.phonetts.app.audio.AudioTrackSink
import com.phonetts.core.audio.AudioSink
import com.phonetts.core.audio.TransformingSink
import com.phonetts.core.audio.buffer.BufferedPlayback
import com.phonetts.core.audio.buffer.ChunkSpill
import com.phonetts.core.audio.buffer.GeneratedAudio
import com.phonetts.core.audio.export.AudioEncoder
import com.phonetts.core.audio.transform.BassCut
import com.phonetts.core.audio.transform.Crossfade
import com.phonetts.core.audio.transform.DeEsser
import com.phonetts.core.audio.transform.LoudnessNormalize
import com.phonetts.core.audio.transform.PresenceBoost
import com.phonetts.core.audio.transform.SilenceTrim
import com.phonetts.core.audio.transform.TempoStretch
import com.phonetts.core.audio.transform.TransformChain
import com.phonetts.core.engine.BlendedVoiceCatalog
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.metrics.GenerationStats
import com.phonetts.core.metrics.ListeningTimeEstimator
import com.phonetts.core.metrics.RtfEstimator
import com.phonetts.core.metrics.WordCounter
import com.phonetts.core.metrics.trackGeneration
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.ModelParameter
import com.phonetts.core.prefs.DocumentPosition
import com.phonetts.core.prefs.LastUsedSelection
import com.phonetts.core.prefs.ReadingTextPreferences
import com.phonetts.core.text.TextChunker
import com.phonetts.core.update.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Drives the main TTS screen. Everything the UI shows is derived from the catalog and the
 * selected [ModelDescriptor] (models, voices, speed range) — no model fact is hardcoded here.
 *
 * Playback and export are consumers of the one `synthesize()` flow (spec §6.1). [loadModel],
 * [generateAudio] and [play] split that one flow into three optional steps — load the engine,
 * generate into a buffer, play the buffer — each usable on its own, so [play] alone (its default
 * behavior) still generates-and-streams in one tap. Playback runs the flow into a [GeneratedAudio]
 * buffer as fast as the model can generate (ahead of playback), while a [BufferedPlayback]
 * consumes it — so playback can pause while generation keeps running, and resume already-generated
 * audio without re-synthesizing. Live [GenerationStats] come from [trackGeneration] for every
 * generation path (Play, Generate, and the voice-sample button's [RtfEstimator] run).
 */
class TtsViewModel(private val graph: AppGraph) : ViewModel() {
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
        // EQ-flavored clarity presets (issue #40) — biquad transforms on the same non-destructive
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
        // `selected?.modelId` by the UI to show "loaded" vs "load model" — set by loadModel() and
        // also picked up as a side effect of generate()/sampleVoice()/export() loading it anyway.
        val loadedModelId: String? = null,
        // Chosen export encoder (WAV/AAC/Opus); the list is derived from AppGraph.exportFormats.
        val exportFormat: AudioEncoder,
        // Favorited voice ids (spec §5.7). Sourced from graph.favoriteVoices, never invented here;
        // the voice picker reads this to star/sort — the voices themselves still come from
        // descriptor.voices (SSOT).
        val favoriteVoiceIds: Set<String> = emptySet(),
        // Set when a newer APK is available on GitHub Releases; drives the dismissible update banner.
        val update: UpdateStatus? = null,
        // Transient result line for a manual "Check for updates" tap ("Checking…", "Up to date (v…)",
        // or a failure note). Null except right after a manual check; the launch check stays silent.
        val updateCheckStatus: String? = null,
        // Sentence index to resume from after a generation/export failure (issue #28), or null when
        // there's nothing to resume. Non-null drives the "Resume from where it stopped" action.
        val resumeSentenceIndex: Int? = null,
        // Font scale for the reading/editing text field (issue #29, the A− / A+ control). A display
        // preference only — persisted via ReadingTextPreferences; scales no model fact.
        val readingScale: Float = ReadingTextPreferences.DEFAULT_SCALE,
        // The absolute sentence index (TextChunker.intoSentences' indexing) currently being heard,
        // for karaoke-style highlighting and per-sentence skip (issue #19-3). Non-null only while a
        // play session is active; driven off BufferedPlayback.playedChunks (see beginProgressTracking).
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
         * Estimated time to *listen* to the current text at the current speed (issue #23), from a real
         * [WordCounter] count — not a measurement of the engine (that's [GenerationStats]). Recomputes
         * for free whenever the text or speed changes, so the UI updates without any synthesis. Zero
         * for empty text; the UI shows nothing in that case.
         */
        val estimatedListeningSeconds: Double
            get() = ListeningTimeEstimator.estimateSeconds(WordCounter.count(text), params.speed)
    }

    /** The export encoders available on this device (WAV always; AAC always; Opus on API 29+). */
    val exportFormats: List<AudioEncoder> = graph.exportFormats

    /**
     * The control surface the background [com.phonetts.app.playback.PlaybackService] drives from
     * its notification / lock-screen controls — the SAME pause/resume/stop as the in-app buttons,
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
    // NOT fire the end-of-document cue (issue #32). Natural completion never calls [stop] — the play
    // coroutine just finishes — so the cue still fires only on a genuine end of document.
    var onUserStopRequested: (() -> Unit)? = null

    private val sink = AudioTrackSink()
    // A fresh BufferedPlayback per play session — see play(): the class is documented/tested
    // (core BufferedAudioTest) as single-use, since stop() latches its internal "stopped" flag
    // with no reset. Reusing one instance across sessions would make every play() after the first
    // stop() silently do nothing (stop() is called at the top of every play()/generateAudio() to
    // interrupt whatever came before).
    private var playback = BufferedPlayback()
    private var genJob: Job? = null
    private var playJob: Job? = null

    // Progress tracking (issue #26): the sentence index the current flow started at (so an absolute
    // "current sentence" = this + playback.playedChunks, which paragraph skips reason about), and the
    // collector that maps played samples into UiState's elapsed position.
    private var currentStartSentenceIndex = 0
    private var progressJob: Job? = null

    // Mirrors playback.playedChunks into UiState.currentSentenceIndex (issue #19-3), separate from
    // progressJob (played samples → elapsed millis) so either can be cancelled/reasoned about on its
    // own; both are started together in beginProgressTracking and cancelled together in stop().
    private var sentenceProgressJob: Job? = null

    // The most recently generated buffer, kept so a Play tap that exactly matches what was last
    // generated (same model/voice/text/params) replays instantly instead of re-synthesizing —
    // still the ONE generation path (spec §6.1), just not re-run when nothing changed.
    private var cachedAudio: GeneratedAudio? = null
    private var cachedKey: GenKey? = null

    private data class GenKey(
        val modelId: String,
        val voiceId: String,
        val text: String,
        val paramValues: Map<String, Float>,
    )

    init {
        refreshModels()
        mutableState.update {
            it.copy(
                readingScale = graph.readingTextPreferences.scale(),
                longDocumentMode = graph.longDocumentPreferences.enabled(),
            )
        }
        checkForUpdate(announce = false)
    }

    // Ask GitHub Releases (off the main thread) whether a newer APK exists. Only ever surfaces a
    // dismissible banner — never downloads or installs on its own (offer, don't force). Fail-closed:
    // any error leaves [UiState.update] null, so a network hiccup is silent.
    //
    // announce=false (launch): stay silent unless an update exists. announce=true (the manual
    // "Check for updates" button): also report "up to date" / a failure, so a tap always gives feedback.
    private fun checkForUpdate(announce: Boolean) {
        if (announce) mutableState.update { it.copy(updateCheckStatus = "Checking for updates…") }
        viewModelScope.launch(Dispatchers.IO) {
            val status =
                runCatching {
                    graph.updateChecker.check(BuildConfig.VERSION_NAME, AppGraph.REPO_OWNER, AppGraph.REPO_NAME)
                }.getOrNull()
            mutableState.update {
                when {
                    status == null -> if (announce) it.copy(updateCheckStatus = "Couldn't check — no connection?") else it
                    status.updateAvailable -> it.copy(update = status, updateCheckStatus = null)
                    announce -> it.copy(updateCheckStatus = "Up to date (v${status.currentVersion})")
                    else -> it
                }
            }
        }
    }

    /** Manually re-run the update check (the Help screen's "Check for updates" button). */
    fun checkForUpdatesNow() = checkForUpdate(announce = true)

    /** Dismiss the update banner for this session (the check runs again next launch). */
    fun dismissUpdate() = mutableState.update { it.copy(update = null) }

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
                favoriteVoiceIds = graph.favoriteVoices.favoriteIds(),
            )
        }
        // If the selected model is the loaded one, re-surface its saved mixes — this is what makes a
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
    // (issue #42). Gated on the descriptor's own supportsVoiceBlend fact (SSOT) — a non-blendable
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
        // Switching model mid-utterance is a barge-in: cancel synthesis, drop buffered chunks, and
        // stop the AudioTrack (issue #45) before re-selecting. No-op cost when nothing is playing.
        stop()
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

    // Each declared parameter's default value, keyed by id — the starting point for the controls.
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

    // Remembering the user's manual pick as the per-language default (favoriteVoices.setDefaultVoice)
    // is what makes defaultVoiceIdFor's prefill useful next time this language comes up.
    fun setVoice(voiceId: String) {
        // Switching voice mid-utterance is the same barge-in as stop/skip (issue #45): cancel
        // synthesis, drop buffered chunks, stop the AudioTrack before the new voice takes effect.
        stop()
        mutableState.update { current ->
            current.selected?.voices?.firstOrNull { it.id == voiceId }?.let(graph.favoriteVoices::setDefaultVoice)
            current.copy(voiceId = voiceId)
        }
        saveLastUsedSelection()
    }

    /** Flips [voice]'s favorite state; the voice picker re-sorts/stars off [UiState.favoriteVoiceIds]. */
    fun toggleFavoriteVoice(voice: Voice) =
        mutableState.update {
            graph.favoriteVoices.toggleFavorite(voice)
            it.copy(favoriteVoiceIds = graph.favoriteVoices.favoriteIds())
        }

    // Prefer the saved per-language default among THIS descriptor's own voices (SSOT — never a
    // voice the descriptor didn't offer), falling back to the descriptor's own default voice id.
    private fun defaultVoiceIdFor(descriptor: ModelDescriptor): String {
        val fallbackId = descriptor.defaultVoiceId
        val language = descriptor.voices.firstOrNull { it.id == fallbackId }?.language
        val saved = language?.let { graph.favoriteVoices.defaultVoice(it, descriptor.voices) }
        return saved?.id ?: fallbackId
    }

    /** Set the value of one declared parameter (e.g. "speed") by id — the UI's generic control hook. */
    fun setParam(
        id: String,
        value: Float,
    ) {
        mutableState.update { it.copy(paramValues = it.paramValues + (id to value)) }
        // Only the speed knob is part of the globally-remembered selection (issue #19-1); other
        // parameters (e.g. a future emotion selector) aren't in LastUsedSelection's scope.
        if (id == ModelParameter.SPEED_ID) saveLastUsedSelection()
    }

    // Persist the user's current model+voice+speed globally (issue #19-1) — restored as the initial
    // selection on next launch by refreshModels()/restoredSelection() above. Deliberately NOT keyed
    // to a document: LastUsedSelection is one shared "where I left off", separate from DocumentMemory's
    // per-document resume position. A no-op before a model/voice is actually selected.
    private fun saveLastUsedSelection() {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        val voiceId = s.voiceId ?: return
        graph.lastUsedSelection.record(LastUsedSelection(descriptor.modelId, voiceId, s.params.speed))
    }

    fun setText(text: String) =
        mutableState.update { it.copy(text = text, resumeSentenceIndex = resumeIndexFor(text)) }

    // The saved resume point for this text, if any (issue #28) — surfaced only when it's past the
    // start (index 0 would just be "press Play"). Reads the per-document memory keyed by text content.
    private fun resumeIndexFor(text: String): Int? {
        if (text.isBlank()) return null
        return graph.documentMemory.resume(documentIdFor(text))?.sentenceIndex?.takeIf { it > 0 }
    }

    // A stable id for the current text so DocumentMemory can key its resume position to this document
    // (issues #24/#28). Content-derived: editing the text starts a fresh document, as expected.
    private fun documentIdFor(text: String): String = text.hashCode().toString()

    /** A+ : one step larger reading font (issue #29), persisted immediately. */
    fun increaseTextScale() = mutableState.update { it.copy(readingScale = graph.readingTextPreferences.increased()) }

    /** A− : one step smaller reading font (issue #29), persisted immediately. */
    fun decreaseTextScale() = mutableState.update { it.copy(readingScale = graph.readingTextPreferences.decreased()) }

    fun setTrimSilence(on: Boolean) = mutableState.update { it.copy(trimSilence = on) }

    fun setNormalizeVolume(on: Boolean) = mutableState.update { it.copy(normalizeVolume = on) }

    fun setCrossfadeJoins(on: Boolean) = mutableState.update { it.copy(crossfadeJoins = on) }

    fun setBassCut(on: Boolean) = mutableState.update { it.copy(bassCut = on) }

    fun setPresenceBoost(on: Boolean) = mutableState.update { it.copy(presenceBoost = on) }

    fun setDeEss(on: Boolean) = mutableState.update { it.copy(deEss = on) }

    /** Toggle the opt-in, playback-only beyond-native tempo boost (issue #43). Off by default. */
    fun setTempoBoost(on: Boolean) = mutableState.update { it.copy(tempoBoost = on) }

    /** Set the beyond-native tempo factor (clamped to [TempoStretch]'s advertised 0.1x–10x range). */
    fun setTempoFactor(factor: Float) =
        mutableState.update {
            it.copy(tempoFactor = factor.coerceIn(TempoStretch.MIN_FACTOR, TempoStretch.MAX_FACTOR))
        }

    /** Toggle long-document (spill-to-disk) mode (issue #34), persisted immediately. Off by default. */
    fun setLongDocumentMode(on: Boolean) =
        mutableState.update {
            graph.longDocumentPreferences.setEnabled(on)
            it.copy(longDocumentMode = on)
        }

    fun setExportFormat(encoder: AudioEncoder) = mutableState.update { it.copy(exportFormat = encoder) }

    /**
     * Load the selected model's engine ahead of time, so Generate/Play's first tap isn't also
     * paying the weight-load cost (EngineManager.switchTo is a no-op if this exact model is
     * already loaded — see core EngineManagerTest — so this genuinely saves the next call
     * something, it doesn't just move the cost around).
     */
    fun loadModel() {
        val descriptor = mutableState.value.selected ?: return
        mutableState.update { it.copy(busy = true, status = "Loading model…") }
        viewModelScope.launch {
            // switchTo()/engine.load() runs synchronous ONNX/native weight loading (issue #18-4b) —
            // off Main so the UI stays responsive while a model loads.
            runCatching {
                withContext(Dispatchers.IO) { graph.engineManager.switchTo(descriptor.engineId, descriptor) }
            }
                .onSuccess {
                    refreshBlendedVoices(descriptor)
                    mutableState.update {
                        it.copy(busy = false, status = "Model loaded", loadedModelId = descriptor.modelId)
                    }
                }
                .onFailure { e -> mutableState.update { it.copy(busy = false, status = "Load failed: ${e.message}") } }
        }
    }

    /**
     * Generate audio for the current text/voice/params WITHOUT playing it — lets you see the
     * stats (real-time factor, timing) before committing to playback. Same one generation path as
     * Play/Export (spec §6.1); this just stops short of consuming the flow into playback. A
     * matching Play tap afterward replays this buffer instantly instead of regenerating it.
     */
    fun generateAudio() {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        stop()
        mutableState.update { it.copy(busy = true, status = "Generating…", stats = null) }
        val audio = newGeneratedAudio(descriptor)
        val documentId = documentIdFor(s.text)
        val key = GenKey(descriptor.modelId, voiceId, s.text, s.paramValues)
        genJob =
            viewModelScope.launch {
                val ok = generate(descriptor, voiceId, s.params, s.text, audio, startSentenceIndex = 0, documentId = documentId)
                if (ok) {
                    cache(audio, key)
                }
                val loaded = if (ok) descriptor.modelId else null
                mutableState.update { it.copy(busy = false, loadedModelId = loaded ?: it.loadedModelId) }
            }
    }

    // A GeneratedAudio for this descriptor: plain (everything in RAM) unless long-document mode is on
    // (issue #34), in which case it is backed by a ChunkSpill so older chunks spill to a cache-dir
    // scratch file past a live window. The ceiling is the model's own sampleRate (SSOT) × a fixed
    // policy duration; when off, the buffer is byte-for-byte the in-RAM one it always was.
    private fun newGeneratedAudio(descriptor: ModelDescriptor): GeneratedAudio {
        if (!mutableState.value.longDocumentMode) return GeneratedAudio()
        return GeneratedAudio(ChunkSpill(graph.newSpillFile(), descriptor.sampleRate * SPILL_WINDOW_SECONDS))
    }

    // Cache a freshly-generated buffer for instant replay, releasing the previous cache's spill file
    // (a no-op for a non-spilled buffer) so long-document scratch files don't accumulate.
    private fun cache(
        audio: GeneratedAudio,
        key: GenKey,
    ) {
        cachedAudio?.close()
        cachedAudio = audio
        cachedKey = key
    }

    /** Play from the top — the primary Play button (a method reference, so kept parameterless). */
    fun play() = startPlaybackFrom(0)

    /**
     * "Read from here" (issue #24): start playback from the sentence containing character [charOffset]
     * of the current text. The char→sentence mapping is [TextChunker.sentenceIndexAt] (a :core seam),
     * so tapping anywhere in a sentence begins there instead of at the top.
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
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        // Slice the sentence list at [startSentenceIndex] and feed only that onward through the SAME
        // one generation path (spec §6.1) — no second synthesis path. Index 0 keeps the full text.
        val effectiveText = sentencesFrom(s.text, startSentenceIndex)
        val documentId = documentIdFor(s.text)
        val key = GenKey(descriptor.modelId, voiceId, effectiveText, s.paramValues)
        stop()
        // A fresh instance per session (see the field doc above) — stop() above just latched the
        // previous one's "stopped" flag for good.
        playback = BufferedPlayback()
        // Beyond-native tempo (#43) lives on the PLAYBACK sink only: if the user opted in, wrap the
        // real sink so each chunk is WSOLA-stretched on its way out. Generation and export never see
        // this — they use `sink`/the export chain directly, so the native Speed knob is untouched.
        val playSink = playbackSink(s)
        // Track position for the lock-screen scrubber and anchor paragraph skips to this start (#26).
        currentStartSentenceIndex = startSentenceIndex
        beginProgressTracking(descriptor, effectiveText, s.params.speed)

        val cached = cachedAudio?.takeIf { cachedKey == key }
        if (cached != null) {
            // Exact match for what's already generated (e.g. right after Generate, or replaying
            // the last Play) — play it straight from the buffer, no re-synthesis, no delay.
            mutableState.update { it.copy(playing = true, paused = false, status = null) }
            playJob =
                viewModelScope.launch {
                    // BufferedPlayback.play drives AudioTrack.write(..., WRITE_BLOCKING) via the sink
                    // (issue #18-4b) — offload it off Main so scrolling/navigating stays responsive
                    // during playback. Core's BufferedPlayback/AudioTrackSink threading is untouched;
                    // only this call site moves.
                    runCatching {
                        withContext(Dispatchers.IO) { playback.play(cached, descriptor.sampleRate, playSink) }
                    }
                        .onFailure { e -> mutableState.update { it.copy(status = "Playback failed: ${e.message}") } }
                    mutableState.update { it.copy(playing = false, paused = false) }
                }
            return
        }

        // Play IS generate-and-stream (spec §6.1: one generation path, no separate "Generate"
        // button) — this status is the only feedback during the gap between tapping Play and the
        // first audio chunk landing (switching/loading the model can take a few seconds on a
        // budget phone). Cleared the moment the first chunk arrives, in generate() below.
        mutableState.update { it.copy(playing = true, paused = false, status = "Loading voice…", stats = null) }

        val audio = newGeneratedAudio(descriptor)
        genJob =
            viewModelScope.launch {
                val ok = generate(descriptor, voiceId, s.params, effectiveText, audio, startSentenceIndex, documentId)
                if (ok) {
                    cache(audio, key)
                    mutableState.update { it.copy(loadedModelId = descriptor.modelId) }
                }
            }
        playJob =
            viewModelScope.launch {
                // Same offload as the cached-replay branch above (issue #18-4b) — the blocking
                // AudioTrack write must not run on Main.
                runCatching { withContext(Dispatchers.IO) { playback.play(audio, descriptor.sampleRate, playSink) } }
                    .onFailure { e -> mutableState.update { it.copy(status = "Playback failed: ${e.message}") } }
                mutableState.update { it.copy(playing = false, paused = false) }
            }
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
     * Clamped to the last sentence — a forward skip at the end is a harmless no-op, not a run past
     * the text. A no-op on blank text (nothing to skip).
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

    // The absolute sentence playback is at now: where this flow started + the chunks (== sentences)
    // already delivered to the sink. The paragraph/sentence-skip math reasons in these whole-document
    // indices.
    private fun currentPlaybackSentenceIndex(): Int = currentStartSentenceIndex + playback.playedChunks.value

    // Publish progress for THIS play session (issue #26): reset elapsed, compute the synthesis-free
    // listening total for the sliced text, and collect played samples into elapsed so the media
    // session's scrubber advances as audio is heard. The collector rides the fresh `playback` above.
    // Also seeds/tracks the karaoke sentence index (issue #19-3) off the SAME playback instance.
    private fun beginProgressTracking(
        descriptor: ModelDescriptor,
        effectiveText: String,
        speed: Float,
    ) {
        val seconds = ListeningTimeEstimator.estimateSeconds(WordCounter.count(effectiveText), speed)
        val totalMillis = (seconds * MILLIS_PER_SECOND).toLong()
        mutableState.update {
            it.copy(
                playbackElapsedMillis = 0,
                playbackTotalMillis = totalMillis,
                currentSentenceIndex = currentStartSentenceIndex,
            )
        }
        progressJob?.cancel()
        progressJob =
            viewModelScope.launch {
                playback.playedSamples.collect { samples ->
                    mutableState.update { it.copy(playbackElapsedMillis = samples * MILLIS_PER_SECOND / descriptor.sampleRate) }
                }
            }
        sentenceProgressJob?.cancel()
        sentenceProgressJob =
            viewModelScope.launch {
                playback.playedChunks.collect { chunks ->
                    mutableState.update { it.copy(currentSentenceIndex = currentStartSentenceIndex + chunks) }
                }
            }
    }

    // The text from sentence [startIndex] onward, re-joined for the one generation path. Index 0 (or
    // any out-of-range value) yields the whole text unchanged. Each sentence keeps its terminator, so
    // the engine re-chunks it into the same sentences it would have anyway (spec §8).
    private fun sentencesFrom(
        text: String,
        startIndex: Int,
    ): String {
        if (startIndex <= 0) return text
        val remaining = TextChunker.intoSentences(text).drop(startIndex)
        return remaining.joinToString(" ").ifBlank { text }
    }

    // The sink playback drains into: the raw AudioTrack sink, wrapped with the opt-in beyond-native
    // tempo transform when enabled. PLAYBACK ONLY — export/generation never call this (issue #43).
    private fun playbackSink(s: UiState): AudioSink {
        if (!s.tempoBoost) return sink
        return TransformingSink(sink, TempoStretch(s.tempoFactor))
    }

    // Run the ONE generation flow into [audio] as fast as the model produces, updating live stats.
    // Returns false (audio left as whatever was collected before the failure) if generation threw.
    // On failure it records the last successfully-collected sentence into DocumentMemory so the UI
    // can offer "Resume from where it stopped" (issue #28); on success it clears any saved resume.
    private suspend fun generate(
        descriptor: ModelDescriptor,
        voiceId: String,
        params: SynthesisParams,
        text: String,
        audio: GeneratedAudio,
        startSentenceIndex: Int,
        documentId: String,
    ): Boolean {
        var chunksDone = 0
        val result =
            runCatching {
                // switchTo()/engine.load() blocks on synchronous weight loading (issue #18-4b) — off
                // Main so generation (Play/Generate/export all route through here) never freezes the UI.
                withContext(Dispatchers.IO) { graph.engineManager.switchTo(descriptor.engineId, descriptor) }
                val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                // Register saved mixes on the loaded engine so a blended voiceId resolves here (#42).
                refreshBlendedVoices(descriptor)
                val totalWords = WordCounter.count(text)
                engine.synthesize(text, voiceId, params)
                    .trackGeneration(descriptor.sampleRate, totalWords = totalWords)
                    .collect { (chunk, stats) ->
                        audio.append(chunk)
                        chunksDone = stats.chunksDone
                        // "Loading voice…"/"Generating…" only cover the gap before audio exists;
                        // once the first chunk lands, the live GenerationStatsView (rendered off
                        // `stats`) takes over.
                        mutableState.update { it.copy(stats = stats, status = null) }
                    }
            }.onFailure { e ->
                // One chunk == one sentence (AbstractVoiceEngine emits per TextChunker sentence), so
                // the next sentence to (re)try is startSentenceIndex + the chunks that fully landed.
                recordStop(descriptor, voiceId, params, documentId, startSentenceIndex + chunksDone, e)
            }
        audio.markComplete()
        if (result.isSuccess) clearResume(documentId)
        return result.isSuccess
    }

    // Persist the resume point and surface a "Resume from where it stopped" offer instead of a
    // dead-end error (issue #28). Only advertises a resume past the start — index 0 is just "Play".
    private fun recordStop(
        descriptor: ModelDescriptor,
        voiceId: String,
        params: SynthesisParams,
        documentId: String,
        resumeIndex: Int,
        error: Throwable,
    ) {
        graph.documentMemory.record(
            DocumentPosition(documentId, descriptor.engineId, voiceId, params.speed, resumeIndex),
        )
        mutableState.update {
            it.copy(
                status = "Stopped: ${error.message}",
                resumeSentenceIndex = resumeIndex.takeIf { idx -> idx > 0 },
            )
        }
    }

    // A clean, fully-generated document has no resume point — forget any saved one and clear the offer.
    private fun clearResume(documentId: String) {
        graph.documentMemory.forget(documentId)
        mutableState.update { it.copy(resumeSentenceIndex = null) }
    }

    /** Pause audio playback; generation keeps running and filling the buffer. */
    fun pausePlayback() {
        playback.pause()
        mutableState.update { it.copy(paused = true) }
    }

    /** Resume playback of already-generated audio (no re-synthesis). */
    fun resumePlayback() {
        playback.resume()
        mutableState.update { it.copy(paused = false) }
    }

    /**
     * The single barge-in path for stop / skip / switch-voice (issue #45) — a proper three-step
     * cancel, not just a UI-state reset:
     *   1. cancel the synthesis coroutine ([genJob]) so no further audio is generated;
     *   2. drop any buffered-but-unplayed chunks — [BufferedPlayback.stop] stops draining the
     *      [GeneratedAudio] buffer, and [AudioTrackSink.stop]'s flush discards PCM already queued
     *      in the AudioTrack but not yet heard;
     *   3. stop the AudioTrack ([sink] pause/flush/stop/release).
     * Then it clears the UI flags. Every switch (below) routes through here so the discipline holds.
     */
    fun stop() {
        // Tell the service (if bound) this stop is user-initiated, so it doesn't misread the
        // resulting playing→stopped edge as a natural end-of-document and chime (issue #32).
        onUserStopRequested?.invoke()
        playback.stop() // step 2a: stop draining the generated-audio buffer
        playJob?.cancel()
        genJob?.cancel() // step 1: cancel synthesis
        progressJob?.cancel() // stop mapping played samples into progress (issue #26)
        sentenceProgressJob?.cancel() // stop mapping played chunks into the karaoke index (issue #19-3)
        playJob = null
        genJob = null
        progressJob = null
        sentenceProgressJob = null
        sink.stop() // steps 2b + 3: flush queued PCM and stop the AudioTrack
        mutableState.update {
            it.copy(
                playing = false,
                paused = false,
                playbackElapsedMillis = 0,
                playbackTotalMillis = 0,
                currentSentenceIndex = null,
            )
        }
    }

    /**
     * Measure this engine+voice's real render speed on a short phrase (never a guessed RTF) and
     * report it. Uses the same `synthesize()` path — no second generation path (spec §6.1).
     */
    fun sampleVoice() {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        mutableState.update { it.copy(busy = true, status = "Measuring voice…") }
        viewModelScope.launch {
            runCatching {
                // switchTo()/engine.load() AND RtfEstimator's synthesis drain both block (weight load
                // + inference), so keep the whole measurement off the main thread (issue #18-4b).
                withContext(Dispatchers.IO) {
                    graph.engineManager.switchTo(descriptor.engineId, descriptor)
                    val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                    RtfEstimator.estimate(engine, voiceId, s.params, VOICE_SAMPLE_PHRASE, descriptor.sampleRate)
                }
            }
                .onSuccess { r ->
                    val rtf = "%.2f".format(r.realTimeFactor)
                    mutableState.update { it.copy(busy = false, status = "Voice sample: ${rtf}x real-time") }
                }
                .onFailure { e -> mutableState.update { it.copy(busy = false, status = "Sample failed: ${e.message}") } }
        }
    }

    /**
     * Generate a short spoken sample of EVERY downloaded model and save each to its own file, so
     * you can audition them back-to-back. Loops the catalog loading one engine at a time
     * ([EngineManager.switchTo] unloads the previous — one engine in memory, spec §6.2), synthesizing
     * the shared [VOICE_SAMPLE_PHRASE] with each model's default voice/params through the SAME one
     * generation path (spec §6.1), and encoding it with the current export format. Best-effort per
     * model: one that fails to load or generate is skipped and named in the summary, never fatal to
     * the rest. Raw voice (no transforms) so the samples compare the models themselves.
     */
    fun sampleAllModels(sink: AudioFileSink) {
        val models = mutableState.value.models
        if (models.isEmpty()) {
            mutableState.update { it.copy(status = "No models to sample yet") }
            return
        }
        val encoder = mutableState.value.exportFormat
        stop()
        mutableState.update { it.copy(busy = true, status = "Sampling models…", stats = null) }
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            models.forEachIndexed { index, descriptor ->
                mutableState.update {
                    it.copy(status = "Sampling ${index + 1}/${models.size}: ${descriptor.displayName}…")
                }
                if (!sampleOneModel(descriptor, encoder, sink)) failures += descriptor.displayName
            }
            mutableState.update {
                it.copy(
                    busy = false,
                    status = sampleSummary(models.size - failures.size, models.size, failures),
                    loadedModelId = models.last().modelId,
                )
            }
        }
    }

    // Synthesize + encode one model's sample into a freshly-created file. Returns false (and leaves
    // the batch running) if the engine won't load, generation throws, or the file can't be created.
    private suspend fun sampleOneModel(
        descriptor: ModelDescriptor,
        encoder: AudioEncoder,
        sink: AudioFileSink,
    ): Boolean =
        runCatching {
            // switchTo()/engine.load() blocks on synchronous weight loading (issue #18-4b).
            withContext(Dispatchers.IO) { graph.engineManager.switchTo(descriptor.engineId, descriptor) }
            val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
            val voiceId = defaultVoiceIdFor(descriptor)
            val params = SynthesisParams(defaultParamValues(descriptor))
            val output = sink.create(sampleBaseName(descriptor)) ?: error("could not create file")
            val audio = engine.synthesize(VOICE_SAMPLE_PHRASE, voiceId, params)
            withContext(Dispatchers.IO) {
                output.use { encoder.encode(audio, descriptor.sampleRate, it) }
            }
        }.isSuccess

    // A file-safe base name (no extension — the file sink appends the format's) from the display name.
    private fun sampleBaseName(descriptor: ModelDescriptor): String =
        descriptor.displayName.replace(FILE_UNSAFE, "_").trim().ifEmpty { descriptor.modelId }

    private fun sampleSummary(
        saved: Int,
        total: Int,
        failures: List<String>,
    ): String {
        if (failures.isEmpty()) return "Saved $saved sample${if (saved == 1) "" else "s"}"
        return "Saved $saved of $total — skipped: ${failures.joinToString()}"
    }

    /** Export the current text to a WAV [output], applying the enabled non-destructive transforms. */
    fun export(output: OutputStream) {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        val transforms = buildTransforms(s)
        val documentId = documentIdFor(s.text)
        var chunksDone = 0
        mutableState.update { it.copy(busy = true, status = null) }
        viewModelScope.launch {
            runCatching {
                // switchTo()/engine.load() blocks on synchronous weight loading (issue #18-4b) — the
                // encode() call below was already offloaded; this closes the gap before it.
                withContext(Dispatchers.IO) { graph.engineManager.switchTo(descriptor.engineId, descriptor) }
                val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                // onEach counts fully-emitted sentences (one chunk == one sentence) without altering
                // the flow the encoder consumes — the same resume-point tracking generate() does.
                val tracked = engine.synthesize(s.text, voiceId, s.params).onEach { chunksDone++ }
                withContext(Dispatchers.IO) {
                    output.use { s.exportFormat.encode(tracked, descriptor.sampleRate, it, transforms) }
                }
            }
                .onSuccess {
                    clearResume(documentId)
                    mutableState.update { it.copy(busy = false, status = "Saved audio file") }
                }
                .onFailure { e ->
                    graph.documentMemory.record(
                        DocumentPosition(documentId, descriptor.engineId, voiceId, s.params.speed, chunksDone),
                    )
                    mutableState.update {
                        it.copy(
                            busy = false,
                            status = "Export stopped: ${e.message}",
                            resumeSentenceIndex = chunksDone.takeIf { idx -> idx > 0 },
                        )
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
            runCatching { withContext(Dispatchers.IO) { graph.sideloadCoordinator.importFromTree(uri) } }
                .onSuccess {
                    mutableState.update {
                        it.copy(busy = false, status = "Added model", sideloadFailureExplanation = null)
                    }
                    refreshModels()
                }
                .onFailure { e ->
                    // A failed auto-detection carries the DetectionFailureExplainer's narration
                    // (issue #19-2) — surface it (with a Copy action, TtsScreen) instead of just the
                    // bare "could not identify" message.
                    val explanation = (e as? UserPickRequiredException)?.explanation
                    mutableState.update {
                        it.copy(
                            busy = false,
                            status = "Sideload failed: ${e.message}",
                            sideloadFailureExplanation = explanation,
                        )
                    }
                }
        }
    }

    /** Dismiss the sideload-failure explanation panel (issue #19-2). */
    fun dismissSideloadFailureExplanation() = mutableState.update { it.copy(sideloadFailureExplanation = null) }

    fun importTextFrom(uri: Uri) {
        mutableState.update { it.copy(busy = true, status = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.fileTextImporter.importText(uri) } }
                .onSuccess { text -> mutableState.update { it.copy(busy = false, text = text, status = "Imported text") } }
                .onFailure { e -> mutableState.update { it.copy(busy = false, status = "Import failed: ${e.message}") } }
        }
    }

    override fun onCleared() {
        stop()
        cachedAudio?.close() // release the last buffer's spill scratch file, if any (issue #34)
    }

    private companion object {
        const val VOICE_SAMPLE_PHRASE = "The quick brown fox jumps over the lazy dog."

        // Default beyond-native tempo factor when the (off-by-default) boost is first enabled — a
        // mild speed-up the user then adjusts. Not a native model parameter; see UiState.tempoFactor.
        const val DEFAULT_TEMPO_FACTOR = 1.5f

        // Seconds of audio kept resident in RAM before long-document mode spills older chunks to disk
        // (issue #34). A memory-policy number (not a model fact): the per-buffer sample ceiling is
        // this × the model's own sampleRate. Generous enough that pause/resume near the live edge
        // never hits disk, small enough to bound a book-length synthesis.
        const val SPILL_WINDOW_SECONDS = 30

        // Millis per second, for turning the listening-time estimate and played-sample counts into
        // the millisecond positions the media session's scrubber wants (issue #26).
        const val MILLIS_PER_SECOND = 1000

        // Characters not safe in a saved file name; the "sample every model" names are derived from
        // model display names, which can contain slashes/colons.
        val FILE_UNSAFE = Regex("""[^A-Za-z0-9 _-]""")
    }
}

/**
 * Creates one output file per model for [TtsViewModel.sampleAllModels], named [fileName] (no
 * extension — the sink adds the export format's). Returning null skips that model without aborting
 * the batch. Kept as a tiny functional interface so the ViewModel never touches SAF/DocumentFile
 * directly, exactly as [TtsViewModel.export] takes a plain OutputStream.
 */
fun interface AudioFileSink {
    fun create(fileName: String): OutputStream?
}
