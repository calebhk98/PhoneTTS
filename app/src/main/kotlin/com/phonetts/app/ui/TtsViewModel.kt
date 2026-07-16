package com.phonetts.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.app.audio.AudioTrackSink
import com.phonetts.core.audio.StreamingConsumer
import com.phonetts.core.audio.WavWriter
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
 * Drives the main TTS screen. Everything the UI shows is derived from the [ModelCatalog] and the
 * selected [ModelDescriptor] (models, voices, speed range) — no model fact is hardcoded here.
 * Playback and export are the two consumers of the one `synthesize()` flow (spec §6.1).
 */
class TtsViewModel(private val graph: AppGraph) : ViewModel() {
    data class UiState(
        val models: List<ModelDescriptor> = emptyList(),
        val selected: ModelDescriptor? = null,
        val voiceId: String? = null,
        val speed: Float = 1f,
        val text: String = "",
        val busy: Boolean = false,
        val playing: Boolean = false,
        val status: String? = null,
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private val sink = AudioTrackSink()
    private val streaming = StreamingConsumer()
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
                voiceId = current.voiceId ?: selected?.defaultVoiceId,
                speed = selected?.defaultSpeed ?: current.speed,
            )
        }
    }

    fun selectModel(descriptor: ModelDescriptor) =
        mutableState.update {
            it.copy(selected = descriptor, voiceId = descriptor.defaultVoiceId, speed = descriptor.defaultSpeed)
        }

    fun setVoice(voiceId: String) = mutableState.update { it.copy(voiceId = voiceId) }

    fun setSpeed(speed: Float) = mutableState.update { it.copy(speed = speed) }

    fun setText(text: String) = mutableState.update { it.copy(text = text) }

    fun play() {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        stop()
        mutableState.update { it.copy(playing = true, status = null) }
        playJob =
            viewModelScope.launch {
                runCatching {
                    graph.engineManager.switchTo(descriptor.engineId, descriptor)
                    val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                    streaming.play(engine.synthesize(s.text, voiceId, s.speed), descriptor.sampleRate, sink)
                }.onFailure { e -> mutableState.update { it.copy(status = "Playback failed: ${e.message}") } }
                mutableState.update { it.copy(playing = false) }
            }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        sink.stop()
        mutableState.update { it.copy(playing = false) }
    }

    /** Export the current text to a WAV [output] (the file consumer of the same generation path). */
    fun export(output: OutputStream) {
        val s = mutableState.value
        val descriptor = s.selected ?: return
        val voiceId = s.voiceId ?: descriptor.defaultVoiceId
        mutableState.update { it.copy(busy = true, status = null) }
        viewModelScope.launch {
            runCatching {
                graph.engineManager.switchTo(descriptor.engineId, descriptor)
                val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                withContext(Dispatchers.IO) {
                    output.use { WavWriter().write(engine.synthesize(s.text, voiceId, s.speed), descriptor.sampleRate, it) }
                }
            }
                .onSuccess { mutableState.update { it.copy(busy = false, status = "Saved audio file") } }
                .onFailure { e -> mutableState.update { it.copy(busy = false, status = "Export failed: ${e.message}") } }
        }
    }

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
}
