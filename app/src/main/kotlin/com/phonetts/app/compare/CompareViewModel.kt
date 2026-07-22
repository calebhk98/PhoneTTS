package com.phonetts.app.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.app.audio.AudioTrackSink
import com.phonetts.core.audio.buffer.BufferedPlayback
import com.phonetts.core.audio.buffer.GeneratedAudio
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the opt-in A/B compare screen (issue #19-6) — a dedicated, off-the-main-flow screen the
 * owner asked for ("an option, a new screen or toggle, not default"). Lets the user pick a model +
 * voice for A and for B (from [com.phonetts.core.registry.ModelCatalog]/[ModelDescriptor.voices] —
 * SSOT, nothing hardcoded), synthesize the SAME text on each through the exact one generation path
 * ([com.phonetts.core.engine.VoiceEngine.synthesize], spec §6.1), and play them back-to-back.
 *
 * Because only one engine is ever loaded ([com.phonetts.core.registry.EngineManager], spec §5.5), A
 * is generated and played to completion BEFORE the engine is switched to B — there is no concurrent
 * generation, just the same sequential switchTo/synthesize/collect calls the main reader and
 * benchmark screens already use. Each side's [GeneratedAudio] buffer is closed and replaced on the
 * next comparison run (see [runComparison]) so a repeated A/B/A/B session doesn't accumulate memory.
 */
class CompareViewModel(
    private val graph: AppGraph,
    initialText: String,
) : ViewModel() {
    /** One side's (A or B) model+voice choice. Voices always come from [ModelDescriptor.voices]. */
    data class Selection(
        val descriptor: ModelDescriptor?,
        val voiceId: String?,
    ) {
        val voices: List<Voice> get() = descriptor?.voices ?: emptyList()
    }

    enum class Slot { A, B }

    data class UiState(
        val models: List<ModelDescriptor> = emptyList(),
        val text: String = "",
        val a: Selection = Selection(null, null),
        val b: Selection = Selection(null, null),
        val busy: Boolean = false,
        val status: String? = null,
        /** Which side is audibly playing right now, or null when nothing is. */
        val playing: Slot? = null,
        val hasResultA: Boolean = false,
        val hasResultB: Boolean = false,
    )

    private val mutableState = MutableStateFlow(initialState(initialText))
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private val sink = AudioTrackSink()
    private var playback = BufferedPlayback()
    private var runJob: Job? = null
    private var playJob: Job? = null

    private var bufferA: GeneratedAudio? = null
    private var bufferB: GeneratedAudio? = null
    private var sampleRateA = 0
    private var sampleRateB = 0

    private fun initialState(text: String): UiState {
        val models = graph.catalog.list()
        return UiState(
            models = models,
            text = text,
            a = defaultSelection(models.getOrNull(0)),
            // Default B to a second model when one is downloaded, so the two pickers don't start
            // identical; falls back to the same model (still comparable by voice) with only one.
            b = defaultSelection(models.getOrNull(1) ?: models.getOrNull(0)),
        )
    }

    private fun defaultSelection(descriptor: ModelDescriptor?): Selection =
        Selection(descriptor, descriptor?.defaultVoiceId)

    fun setText(text: String) = mutableState.update { it.copy(text = text) }

    fun selectModelA(descriptor: ModelDescriptor) =
        mutableState.update { it.copy(a = defaultSelection(descriptor), hasResultA = false) }

    fun selectVoiceA(voiceId: String) =
        mutableState.update { it.copy(a = it.a.copy(voiceId = voiceId), hasResultA = false) }

    fun selectModelB(descriptor: ModelDescriptor) =
        mutableState.update { it.copy(b = defaultSelection(descriptor), hasResultB = false) }

    fun selectVoiceB(voiceId: String) =
        mutableState.update { it.copy(b = it.b.copy(voiceId = voiceId), hasResultB = false) }

    /**
     * Generate A, play it, then generate B, then play it — sequentially, on the SAME one generation
     * path both sides share. Cancels/replaces any run already in progress.
     */
    fun runComparison() {
        val s = mutableState.value
        val descriptorA = s.a.descriptor ?: return
        val descriptorB = s.b.descriptor ?: return
        if (s.text.isBlank()) return
        val voiceA = s.a.voiceId ?: descriptorA.defaultVoiceId
        val voiceB = s.b.voiceId ?: descriptorB.defaultVoiceId

        stopPlayback()
        releaseBuffers()
        mutableState.update {
            it.copy(
                busy = true,
                hasResultA = false,
                hasResultB = false,
                status = "Generating A: ${descriptorA.displayName}…",
            )
        }
        runJob =
            viewModelScope.launch {
                val audioA = GeneratedAudio()
                if (!generateInto(descriptorA, voiceA, s.text, audioA)) {
                    audioA.close()
                    mutableState.update { it.copy(busy = false, status = "A failed: see status") }
                    return@launch
                }
                bufferA = audioA
                sampleRateA = descriptorA.sampleRate
                mutableState.update { it.copy(hasResultA = true, status = "Playing A: ${descriptorA.displayName}…") }
                playBuffer(Slot.A)

                mutableState.update { it.copy(status = "Generating B: ${descriptorB.displayName}…") }
                val audioB = GeneratedAudio()
                if (!generateInto(descriptorB, voiceB, s.text, audioB)) {
                    audioB.close()
                    mutableState.update { it.copy(busy = false, status = "B failed: see status") }
                    return@launch
                }
                bufferB = audioB
                sampleRateB = descriptorB.sampleRate
                mutableState.update { it.copy(hasResultB = true, status = "Playing B: ${descriptorB.displayName}…") }
                playBuffer(Slot.B)

                mutableState.update { it.copy(busy = false, status = "Done — replay either side below") }
            }
    }

    // Load [descriptor]'s engine (unloading whatever was loaded before — one engine at a time, spec
    // §5.5) and drain its synthesize() flow into [into]. Returns false (status already set) on failure.
    private suspend fun generateInto(
        descriptor: ModelDescriptor,
        voiceId: String,
        text: String,
        into: GeneratedAudio,
    ): Boolean {
        val result =
            runCatching {
                // switchTo()/engine.load() blocks on synchronous weight loading (issue #18-4b) — off
                // Main, same as every other generation call site (TtsViewModel/BenchmarkViewModel).
                withContext(Dispatchers.IO) { graph.engineManager.switchTo(descriptor.engineId, descriptor) }
                val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                val params = SynthesisParams(descriptor.parameters.associate { it.id to it.default })
                // The engine's synthesize() flow already runs inference off the collecting thread
                // (AbstractVoiceEngine.flowOn) — no extra withContext needed around collect itself.
                engine.synthesize(text, voiceId, params).collect(into::append)
            }.onFailure { e -> mutableState.update { it.copy(status = "Generation failed: ${e.message}") } }
        into.markComplete()
        return result.isSuccess
    }

    private suspend fun playBuffer(slot: Slot) {
        val audio = bufferFor(slot) ?: return
        val rate = if (slot == Slot.A) sampleRateA else sampleRateB
        mutableState.update { it.copy(playing = slot) }
        playback = BufferedPlayback()
        // BufferedPlayback.play drives a blocking AudioTrack.write via the sink (issue #18-4b) —
        // offload it off Main, same as TtsViewModel/MixVoicesViewModel's playback calls.
        runCatching { withContext(Dispatchers.IO) { playback.play(audio, rate, sink) } }
            .onFailure { e -> mutableState.update { it.copy(status = "Playback failed: ${e.message}") } }
        mutableState.update { it.copy(playing = null) }
    }

    /** Replay A's already-generated buffer, without regenerating. No-op before a comparison has run. */
    fun replayA() = replay(Slot.A)

    /** Replay B's already-generated buffer, without regenerating. No-op before a comparison has run. */
    fun replayB() = replay(Slot.B)

    private fun replay(slot: Slot) {
        if (bufferFor(slot) == null) return
        stopPlayback()
        playJob = viewModelScope.launch { playBuffer(slot) }
    }

    private fun bufferFor(slot: Slot): GeneratedAudio? = if (slot == Slot.A) bufferA else bufferB

    /** Stop whatever is generating/playing right now. */
    fun stop() {
        runJob?.cancel()
        stopPlayback()
        mutableState.update { it.copy(busy = false) }
    }

    private fun stopPlayback() {
        playback.stop()
        playJob?.cancel()
        playJob = null
        sink.stop()
        mutableState.update { it.copy(playing = null) }
    }

    // Release both sides' buffers before starting a fresh run (keeps memory bounded — a repeated A/B
    // session never accumulates more than the current pair, spec: bound memory for generated audio).
    private fun releaseBuffers() {
        bufferA?.close()
        bufferB?.close()
        bufferA = null
        bufferB = null
        mutableState.update { it.copy(hasResultA = false, hasResultB = false) }
    }

    override fun onCleared() {
        stopPlayback()
        releaseBuffers()
        super.onCleared()
    }
}
