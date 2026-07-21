package com.phonetts.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.app.audio.AudioTrackSink
import com.phonetts.core.audio.buffer.BufferedPlayback
import com.phonetts.core.audio.buffer.GeneratedAudio
import com.phonetts.core.engine.BlendableVoices
import com.phonetts.core.engine.BlendedVoiceSpec
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the "Mix voices" screen (issue #42). It is a consumer of the same abstractions as the main
 * reader — it never special-cases a model. Whether mixing is even offered is DERIVED from the
 * selected model's [ModelDescriptor.supportsVoiceBlend] fact (SSOT), and a saved mix is applied to
 * whatever engine the model loads only if that engine implements [BlendableVoices]. So this screen
 * automatically lights up for any future blendable model and stays dark for the rest, with no code
 * change here (rule 5 — no `when(modelType)`).
 *
 * A saved mix is just a [BlendedVoiceSpec] recipe persisted via [AppGraph.blendedVoices]; the
 * in-between embedding is recomputed by the engine from the loaded model each time, so nothing but
 * the two source ids + weight is stored.
 */
class MixVoicesViewModel(
    private val graph: AppGraph,
    private val descriptor: ModelDescriptor?,
) : ViewModel() {
    data class UiState(
        val supported: Boolean = false,
        val voices: List<Voice> = emptyList(),
        val voiceAId: String? = null,
        val voiceBId: String? = null,
        // Fraction toward voice B in 0..1 (the slider shows it as 0–100%).
        val weight: Float = DEFAULT_WEIGHT,
        val name: String = "",
        val saved: List<BlendedVoiceSpec> = emptyList(),
        val status: String? = null,
        val busy: Boolean = false,
    )

    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private val sink = AudioTrackSink()
    private var playback = BufferedPlayback()
    private var genJob: Job? = null
    private var playJob: Job? = null

    private fun initialState(): UiState {
        val model = descriptor
        if (model == null || !model.supportsVoiceBlend) return UiState(supported = false)
        val voices = model.voices
        return UiState(
            supported = true,
            voices = voices,
            voiceAId = voices.getOrNull(0)?.id,
            voiceBId = voices.getOrNull(1)?.id ?: voices.getOrNull(0)?.id,
            saved = graph.blendedVoices.forModel(model.modelId),
        )
    }

    fun setVoiceA(id: String) = mutableState.update { it.copy(voiceAId = id) }

    fun setVoiceB(id: String) = mutableState.update { it.copy(voiceBId = id) }

    fun setWeight(value: Float) =
        mutableState.update { it.copy(weight = value.coerceIn(0f, 1f)) }

    fun setName(value: String) = mutableState.update { it.copy(name = value) }

    /**
     * Persist the current mix as a new selectable voice and apply it to the loaded engine so it is
     * usable immediately. Fails closed (a status message, no save) on missing input.
     */
    fun save() {
        val model = descriptor ?: return
        val spec = buildSpec(model) ?: return
        graph.blendedVoices.save(spec)
        applyToEngine(model, spec)
        mutableState.update {
            it.copy(saved = graph.blendedVoices.forModel(model.modelId), status = "Saved \"${spec.name}\"", name = "")
        }
    }

    /** Remove a previously saved mix. */
    fun delete(spec: BlendedVoiceSpec) {
        val model = descriptor ?: return
        graph.blendedVoices.remove(model.modelId, spec.id)
        mutableState.update { it.copy(saved = graph.blendedVoices.forModel(model.modelId)) }
    }

    /**
     * Preview the current mix end-to-end through the REAL engine and one generation path: load the
     * model, register the blended voice, then synthesize a short phrase and play it. This proves the
     * blend actually renders audio, not just persists.
     */
    fun preview(text: String) {
        val model = descriptor ?: return
        val spec = buildSpec(model) ?: return
        stop()
        playback = BufferedPlayback()
        mutableState.update { it.copy(busy = true, status = "Rendering preview…") }
        val phrase = text.ifBlank { PREVIEW_PHRASE }
        val audio = GeneratedAudio()
        genJob =
            viewModelScope.launch {
                val ok = renderPreview(model, spec, phrase, audio)
                mutableState.update { it.copy(busy = false, status = if (ok) null else it.status) }
            }
        playJob =
            viewModelScope.launch {
                runCatching { playback.play(audio, model.sampleRate, sink) }
                    .onFailure { e -> mutableState.update { it.copy(status = "Playback failed: ${e.message}") } }
            }
    }

    fun stop() {
        playback.stop()
        genJob?.cancel()
        playJob?.cancel()
        genJob = null
        playJob = null
        sink.stop()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private suspend fun renderPreview(
        model: ModelDescriptor,
        spec: BlendedVoiceSpec,
        phrase: String,
        audio: GeneratedAudio,
    ): Boolean {
        val result =
            runCatching {
                graph.engineManager.switchTo(model.engineId, model)
                val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                val blend = engine as? BlendableVoices ?: error("this model can't blend voices")
                val voice = blend.addBlendedVoice(spec) ?: error("pick two of this model's voices")
                engine.synthesize(phrase, voice.id, SynthesisParams(model.parameters.associate { it.id to it.default }))
                    .collect(audio::append)
            }
                .onFailure { e -> mutableState.update { it.copy(status = "Preview failed: ${e.message}") } }
        audio.markComplete()
        return result.isSuccess
    }

    // Apply a saved mix to the engine ONLY if that model is the one currently loaded — otherwise it
    // is applied lazily the next time the model loads (the engine reads it from persistence). No-op
    // if the loaded engine can't blend.
    private fun applyToEngine(
        model: ModelDescriptor,
        spec: BlendedVoiceSpec,
    ) {
        if (graph.engineManager.currentDescriptor?.modelId != model.modelId) return
        (graph.engineManager.currentEngine as? BlendableVoices)?.addBlendedVoice(spec)
    }

    private fun buildSpec(model: ModelDescriptor): BlendedVoiceSpec? {
        val s = mutableState.value
        val aId = s.voiceAId
        val bId = s.voiceBId
        if (aId == null || bId == null) {
            mutableState.update { it.copy(status = "Pick two voices to mix") }
            return null
        }
        val label = s.name.ifBlank { defaultName(s) }
        return BlendedVoiceSpec(
            id = "$BLEND_ID_PREFIX${aId}_${bId}_${(s.weight * PERCENT).toInt()}",
            name = label,
            modelId = model.modelId,
            voiceAId = aId,
            voiceBId = bId,
            weight = s.weight,
        )
    }

    private fun defaultName(s: UiState): String {
        val a = s.voices.firstOrNull { it.id == s.voiceAId }?.name ?: s.voiceAId
        val b = s.voices.firstOrNull { it.id == s.voiceBId }?.name ?: s.voiceBId
        return "$a + $b (${(s.weight * PERCENT).toInt()}%)"
    }

    private companion object {
        const val DEFAULT_WEIGHT = 0.5f
        const val PERCENT = 100
        const val PREVIEW_PHRASE = "This is a preview of the blended voice."
        const val BLEND_ID_PREFIX = "blend_"
    }
}
