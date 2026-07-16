package com.phonetts.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
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
 * Playback and export are consumers of the one `synthesize()` flow (spec §6.1). Playback runs the
 * flow into a [GeneratedAudio] buffer as fast as the model can generate (ahead of playback), while
 * a [BufferedPlayback] consumes it — so playback can pause while generation keeps running, and
 * resume already-generated audio without re-synthesizing. Live [GenerationStats] come from
 * [trackGeneration]; the voice-sample button measures real speed via [RtfEstimator].
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
        // Chosen export encoder (WAV/AAC/Opus); the list is derived from AppGraph.exportFormats.
        val exportFormat: AudioEncoder,
        // Favorited voice ids (spec §5.7). Sourced from graph.favoriteVoices, never invented here;
        // the voice picker reads this to star/sort — the voices themselves still come from
        // descriptor.voices (SSOT).
        val favoriteVoiceIds: Set<String> = emptySet(),
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
    private val playback = BufferedPlayback()
    private var genJob: Job? = null
    private var playJob: Job? = null

    init {
        refreshModels()
    }

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

    fun play() {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        stop()
        mutableState.update { it.copy(playing = true, paused = false, status = null, stats = null) }

        val audio = GeneratedAudio()
        genJob = viewModelScope.launch { generate(descriptor, voiceId, s.params, s.text, audio) }
        playJob =
            viewModelScope.launch {
                runCatching { playback.play(audio, descriptor.sampleRate, sink) }
                    .onFailure { e -> mutableState.update { it.copy(status = "Playback failed: ${e.message}") } }
                mutableState.update { it.copy(playing = false, paused = false) }
            }
    }

    // Run the ONE generation flow into [audio] as fast as the model produces, updating live stats.
    private suspend fun generate(
        descriptor: ModelDescriptor,
        voiceId: String,
        params: SynthesisParams,
        text: String,
        audio: GeneratedAudio,
    ) {
        runCatching {
            graph.engineManager.switchTo(descriptor.engineId, descriptor)
            val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
            val totalWords = WordCounter.count(text)
            engine.synthesize(text, voiceId, params)
                .trackGeneration(descriptor.sampleRate, totalWords = totalWords)
                .collect { (chunk, stats) ->
                    audio.append(chunk)
                    mutableState.update { it.copy(stats = stats) }
                }
        }.onFailure { e -> mutableState.update { it.copy(status = "Generation failed: ${e.message}") } }
        audio.markComplete()
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
