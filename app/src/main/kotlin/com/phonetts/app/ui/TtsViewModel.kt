package com.phonetts.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.app.BuildConfig
import com.phonetts.app.audio.AudioTrackSink
import com.phonetts.core.audio.buffer.BufferedPlayback
import com.phonetts.core.audio.buffer.GeneratedAudio
import com.phonetts.core.audio.export.AudioEncoder
import com.phonetts.core.audio.transform.Crossfade
import com.phonetts.core.audio.transform.LoudnessNormalize
import com.phonetts.core.audio.transform.SilenceTrim
import com.phonetts.core.audio.transform.TransformChain
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.metrics.GenerationStats
import com.phonetts.core.metrics.RtfEstimator
import com.phonetts.core.metrics.WordCounter
import com.phonetts.core.metrics.trackGeneration
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.update.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        // The user's chosen value for each parameter the selected model declares (keyed by
        // ModelParameter.id). Dynamic: whatever parameters a model advertises get an entry here, so a
        // model that adds e.g. an emotion knob needs no new field. The generation path reads [params].
        val paramValues: Map<String, Float> = emptyMap(),
        val text: String = "",
        val busy: Boolean = false,
        val playing: Boolean = false,
        val paused: Boolean = false,
        val status: String? = null,
        // Non-destructive post-processing toggles (applied to export; raw audio is never altered).
        val trimSilence: Boolean = false,
        val normalizeVolume: Boolean = false,
        val crossfadeJoins: Boolean = false,
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
    ) {
        /** The chosen parameter values, as the [SynthesisParams] bag the one generation path consumes. */
        val params: SynthesisParams get() = SynthesisParams(paramValues)
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

            override fun pause() = pausePlayback()

            override fun resume() = resumePlayback()

            override fun stop() = this@TtsViewModel.stop()
        }

    private val mutableState = MutableStateFlow(UiState(exportFormat = exportFormats.first()))
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private val sink = AudioTrackSink()
    // A fresh BufferedPlayback per play session — see play(): the class is documented/tested
    // (core BufferedAudioTest) as single-use, since stop() latches its internal "stopped" flag
    // with no reset. Reusing one instance across sessions would make every play() after the first
    // stop() silently do nothing (stop() is called at the top of every play()/generateAudio() to
    // interrupt whatever came before).
    private var playback = BufferedPlayback()
    private var genJob: Job? = null
    private var playJob: Job? = null

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
        checkForUpdate()
    }

    // Ask GitHub Releases (off the main thread) whether a newer APK exists. Only ever surfaces a
    // dismissible banner — never downloads or installs on its own (offer, don't force). Fail-closed:
    // any error leaves [UiState.update] null, so a network hiccup is silent.
    private fun checkForUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            val status =
                runCatching {
                    graph.updateChecker.check(BuildConfig.VERSION_NAME, AppGraph.REPO_OWNER, AppGraph.REPO_NAME)
                }.getOrNull() ?: return@launch
            if (status.updateAvailable) mutableState.update { it.copy(update = status) }
        }
    }

    /** Dismiss the update banner for this session (the check runs again next launch). */
    fun dismissUpdate() = mutableState.update { it.copy(update = null) }

    fun refreshModels() {
        val models = graph.catalog.list()
        mutableState.update { current ->
            val selected = current.selected ?: models.firstOrNull()
            current.copy(
                models = models,
                selected = selected,
                voiceId = current.voiceId ?: selected?.let(::defaultVoiceIdFor),
                paramValues = selected?.let(::defaultParamValues) ?: current.paramValues,
                favoriteVoiceIds = graph.favoriteVoices.favoriteIds(),
            )
        }
    }

    fun selectModel(descriptor: ModelDescriptor) =
        mutableState.update {
            it.copy(
                selected = descriptor,
                voiceId = defaultVoiceIdFor(descriptor),
                paramValues = defaultParamValues(descriptor),
            )
        }

    // Each declared parameter's default value, keyed by id — the starting point for the controls.
    private fun defaultParamValues(descriptor: ModelDescriptor): Map<String, Float> =
        descriptor.parameters.associate { it.id to it.default }

    // Remembering the user's manual pick as the per-language default (favoriteVoices.setDefaultVoice)
    // is what makes defaultVoiceIdFor's prefill useful next time this language comes up.
    fun setVoice(voiceId: String) =
        mutableState.update { current ->
            current.selected?.voices?.firstOrNull { it.id == voiceId }?.let(graph.favoriteVoices::setDefaultVoice)
            current.copy(voiceId = voiceId)
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
    ) = mutableState.update { it.copy(paramValues = it.paramValues + (id to value)) }

    fun setText(text: String) = mutableState.update { it.copy(text = text) }

    fun setTrimSilence(on: Boolean) = mutableState.update { it.copy(trimSilence = on) }

    fun setNormalizeVolume(on: Boolean) = mutableState.update { it.copy(normalizeVolume = on) }

    fun setCrossfadeJoins(on: Boolean) = mutableState.update { it.copy(crossfadeJoins = on) }

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
            runCatching { graph.engineManager.switchTo(descriptor.engineId, descriptor) }
                .onSuccess {
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
        val audio = GeneratedAudio()
        val key = GenKey(descriptor.modelId, voiceId, s.text, s.paramValues)
        genJob =
            viewModelScope.launch {
                val ok = generate(descriptor, voiceId, s.params, s.text, audio)
                if (ok) {
                    cachedAudio = audio
                    cachedKey = key
                }
                val loaded = if (ok) descriptor.modelId else null
                mutableState.update { it.copy(busy = false, loadedModelId = loaded ?: it.loadedModelId) }
            }
    }

    fun play() {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        val key = GenKey(descriptor.modelId, voiceId, s.text, s.paramValues)
        stop()
        // A fresh instance per session (see the field doc above) — stop() above just latched the
        // previous one's "stopped" flag for good.
        playback = BufferedPlayback()

        val cached = cachedAudio?.takeIf { cachedKey == key }
        if (cached != null) {
            // Exact match for what's already generated (e.g. right after Generate, or replaying
            // the last Play) — play it straight from the buffer, no re-synthesis, no delay.
            mutableState.update { it.copy(playing = true, paused = false, status = null) }
            playJob =
                viewModelScope.launch {
                    runCatching { playback.play(cached, descriptor.sampleRate, sink) }
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

        val audio = GeneratedAudio()
        genJob =
            viewModelScope.launch {
                val ok = generate(descriptor, voiceId, s.params, s.text, audio)
                if (ok) {
                    cachedAudio = audio
                    cachedKey = key
                    mutableState.update { it.copy(loadedModelId = descriptor.modelId) }
                }
            }
        playJob =
            viewModelScope.launch {
                runCatching { playback.play(audio, descriptor.sampleRate, sink) }
                    .onFailure { e -> mutableState.update { it.copy(status = "Playback failed: ${e.message}") } }
                mutableState.update { it.copy(playing = false, paused = false) }
            }
    }

    // Run the ONE generation flow into [audio] as fast as the model produces, updating live stats.
    // Returns false (audio left as whatever was collected before the failure) if generation threw.
    private suspend fun generate(
        descriptor: ModelDescriptor,
        voiceId: String,
        params: SynthesisParams,
        text: String,
        audio: GeneratedAudio,
    ): Boolean {
        val result =
            runCatching {
                graph.engineManager.switchTo(descriptor.engineId, descriptor)
                val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                val totalWords = WordCounter.count(text)
                engine.synthesize(text, voiceId, params)
                    .trackGeneration(descriptor.sampleRate, totalWords = totalWords)
                    .collect { (chunk, stats) ->
                        audio.append(chunk)
                        // "Loading voice…"/"Generating…" only cover the gap before audio exists;
                        // once the first chunk lands, the live GenerationStatsView (rendered off
                        // `stats`) takes over.
                        mutableState.update { it.copy(stats = stats, status = null) }
                    }
            }.onFailure { e -> mutableState.update { it.copy(status = "Generation failed: ${e.message}") } }
        audio.markComplete()
        return result.isSuccess
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

    fun stop() {
        playback.stop()
        playJob?.cancel()
        genJob?.cancel()
        playJob = null
        genJob = null
        sink.stop()
        mutableState.update { it.copy(playing = false, paused = false) }
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
                graph.engineManager.switchTo(descriptor.engineId, descriptor)
                val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                RtfEstimator.estimate(engine, voiceId, s.params, VOICE_SAMPLE_PHRASE, descriptor.sampleRate)
            }
                .onSuccess { r ->
                    val rtf = "%.2f".format(r.realTimeFactor)
                    mutableState.update { it.copy(busy = false, status = "Voice sample: ${rtf}x real-time") }
                }
                .onFailure { e -> mutableState.update { it.copy(busy = false, status = "Sample failed: ${e.message}") } }
        }
    }

    /** Export the current text to a WAV [output], applying the enabled non-destructive transforms. */
    fun export(output: OutputStream) {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        val transforms = buildTransforms(s)
        mutableState.update { it.copy(busy = true, status = null) }
        viewModelScope.launch {
            runCatching {
                graph.engineManager.switchTo(descriptor.engineId, descriptor)
                val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                withContext(Dispatchers.IO) {
                    output.use {
                        s.exportFormat.encode(
                            engine.synthesize(s.text, voiceId, s.params),
                            descriptor.sampleRate,
                            it,
                            transforms,
                        )
                    }
                }
            }
                .onSuccess { mutableState.update { it.copy(busy = false, status = "Saved audio file") } }
                .onFailure { e -> mutableState.update { it.copy(busy = false, status = "Export failed: ${e.message}") } }
        }
    }

    // Build the transform chain from the current toggles. Off by default and applied to a copy of
    // the audio, so the export is post-processed while the model's raw output is never altered.
    private fun buildTransforms(s: UiState): TransformChain =
        TransformChain.of(listOf(SilenceTrim(), LoudnessNormalize(), Crossfade()))
            .withEnabled(SilenceTrim.ID, s.trimSilence)
            .withEnabled(LoudnessNormalize.ID, s.normalizeVolume)
            .withEnabled(Crossfade.ID, s.crossfadeJoins)

    fun sideloadFolder(uri: Uri) {
        mutableState.update { it.copy(busy = true, status = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.sideloadCoordinator.importFromTree(uri) } }
                .onSuccess {
                    mutableState.update { it.copy(busy = false, status = "Added model") }
                    refreshModels()
                }
                .onFailure { e -> mutableState.update { it.copy(busy = false, status = "Sideload failed: ${e.message}") } }
        }
    }

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
    }

    private companion object {
        const val VOICE_SAMPLE_PHRASE = "The quick brown fox jumps over the lazy dog."
    }
}
