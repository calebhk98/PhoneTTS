package com.phonetts.app.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.app.audio.AudioTrackSink
import com.phonetts.core.audio.buffer.BufferedPlayback
import com.phonetts.core.audio.buffer.GeneratedAudio
import com.phonetts.core.compare.Contender
import com.phonetts.core.compare.Pairing
import com.phonetts.core.compare.Tournament
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NANOS_PER_SECOND = 1_000_000_000.0

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

    /**
     * One roster entry for tournament mode (issue #11): a model+voice pick the user added to the
     * bracket, plus a caller-unique [id] the [Tournament] engine tracks it by. [label] is the
     * REVEALED identity string — the tournament UI must only read it for the roster-building list
     * (before judging starts) and the final ranking (after judging ends), never while a match is
     * being judged, which is what keeps the comparison blind.
     */
    data class TournamentEntry(
        val id: String,
        val descriptor: ModelDescriptor,
        val voiceId: String,
    ) {
        val label: String
            get() {
                val voiceName = descriptor.voices.firstOrNull { it.id == voiceId }?.name ?: voiceId
                return "${descriptor.displayName} — $voiceName"
            }
    }

    /** One row of the final, identity-revealed standings once a tournament completes. */
    data class RevealedRankRow(
        val place: Int,
        val label: String,
        val winsRecorded: Int,
        /** Measured real-time factor for this entry (issue #11), or null if it never got to generate. */
        val realTimeFactor: Double?,
    )

    /**
     * Tournament-mode UI state. Deliberately carries only anonymized slot numbers (1/2) while a
     * match is in progress — no [TournamentEntry]/[ModelDescriptor] reference lives here during
     * judging, so the blind comparison is enforced by what data even reaches Compose, not just by
     * screen code discipline. Identities only appear again in [revealedRanking] once [complete].
     */
    data class TournamentUiState(
        val roster: List<TournamentEntry> = emptyList(),
        val running: Boolean = false,
        val busy: Boolean = false,
        val roundNumber: Int? = null,
        val slot1Ready: Boolean = false,
        val slot2Ready: Boolean = false,
        val playingSlot: Int? = null,
        val status: String? = null,
        val complete: Boolean = false,
        val revealedRanking: List<RevealedRankRow> = emptyList(),
    ) {
        val canPick: Boolean get() = running && !busy && slot1Ready && slot2Ready && !complete
    }

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
        val tournament: TournamentUiState = TournamentUiState(),
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

    // Tournament mode (issue #11) is split into its own [TournamentController] purely to stay under
    // detekt's TooManyFunctions ceiling on this class (CLAUDE.md rule 9 / never-nesting-driven size
    // rules) — it still drives the exact SAME [generateInto]/[playAudio] this class uses for A/B,
    // passed in by reference, so there is still only one generation path and one playback path.
    val tournamentController: TournamentController =
        TournamentController(
            scope = viewModelScope,
            generate = ::generateInto,
            play = ::playAudio,
            currentText = { mutableState.value.text },
            currentState = { mutableState.value.tournament },
            update = { transform -> mutableState.update { it.copy(tournament = transform(it.tournament)) } },
            errorMessage = { mutableState.value.status },
        )

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
        playAudio(audio, rate)
        mutableState.update { it.copy(playing = null) }
    }

    // Low-level playback shared by A/B replay AND tournament-slot playback (below): swaps in a
    // fresh BufferedPlayback and drives it off Main (issue #18-4b) through the same AudioTrackSink
    // either mode uses. Callers own updating their own "who's playing" state around this call.
    private suspend fun playAudio(
        audio: GeneratedAudio,
        rate: Int,
    ) {
        playback = BufferedPlayback()
        // BufferedPlayback.play drives a blocking AudioTrack.write via the sink (issue #18-4b) —
        // offload it off Main, same as TtsViewModel/MixVoicesViewModel's playback calls.
        runCatching { withContext(Dispatchers.IO) { playback.play(audio, rate, sink) } }
            .onFailure { e -> mutableState.update { it.copy(status = "Playback failed: ${e.message}") } }
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
        mutableState.update { it.copy(playing = null, tournament = it.tournament.copy(playingSlot = null)) }
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
        tournamentController.releaseAudio()
        super.onCleared()
    }
}

/**
 * Tournament / bracket mode (issue #11): drives a [Tournament] over a roster of model+voice picks,
 * generating and playing each pairing's two contenders through the SAME [generate]/[play] functions
 * [CompareViewModel] uses for A/B (passed in by reference — one generation path, one playback path,
 * spec rule 3), and remembering each entry's generated audio + measured real-time factor so an entry
 * that wins several rounds is synthesized exactly once, never re-measured on a repeat run.
 *
 * Split out of [CompareViewModel] itself purely to stay under detekt's TooManyFunctions ceiling on
 * that class (CLAUDE.md rule 9) — the roster/bracket/audio-cache state below is a cohesive unit of
 * its own regardless. [CompareViewModel] exposes this as a public `val` the screen calls directly.
 *
 * BLINDING is structural, not a screen-code convention: [update]/[currentState] only ever carry
 * [CompareViewModel.TournamentUiState], which never contains a [CompareViewModel.TournamentEntry] or
 * [ModelDescriptor] while a match is in progress — only the anonymized slot numbers 1/2. Identities
 * resurface only in [CompareViewModel.RevealedRankRow], once the bracket completes.
 */
class TournamentController(
    private val scope: CoroutineScope,
    private val generate: suspend (ModelDescriptor, String, String, GeneratedAudio) -> Boolean,
    private val play: suspend (GeneratedAudio, Int) -> Unit,
    private val currentText: () -> String,
    private val currentState: () -> CompareViewModel.TournamentUiState,
    private val update: ((CompareViewModel.TournamentUiState) -> CompareViewModel.TournamentUiState) -> Unit,
    private val errorMessage: () -> String?,
) {
    private var entrySeq = 0
    private var job: Job? = null
    private var bracket: Tournament<CompareViewModel.TournamentEntry>? = null
    private var currentPairing: Pairing<CompareViewModel.TournamentEntry>? = null
    private val entryAudio = mutableMapOf<String, GeneratedAudio>()
    private val entrySampleRate = mutableMapOf<String, Int>()
    private val entryRtf = mutableMapOf<String, Double>()

    /** Add a model+voice pick to the bracket roster. No-op once a tournament is already running. */
    fun addEntry(
        descriptor: ModelDescriptor,
        voiceId: String,
    ) {
        if (currentState().running) return
        val entry =
            CompareViewModel.TournamentEntry(id = "entry-${entrySeq++}", descriptor = descriptor, voiceId = voiceId)
        update { it.copy(roster = it.roster + entry) }
    }

    /** Remove a roster entry (before the tournament has started). */
    fun removeEntry(id: String) {
        if (currentState().running) return
        update { it.copy(roster = it.roster.filterNot { entry -> entry.id == id }) }
    }

    /**
     * Start a single-elimination bracket over the current roster (needs at least 2 entries and
     * non-blank text). The roster is shuffled here — in `:app`, never in the deterministic `:core`
     * [Tournament] engine itself — purely so the pairing order isn't predictable from the order
     * entries were added; that shuffle is the ONLY source of unpredictability, the bracket
     * progression stays fully deterministic once seeded (see [Tournament]'s docs).
     */
    fun start() {
        val state = currentState()
        if (state.roster.size < 2 || currentText().isBlank() || state.running) return

        releaseAudio()
        bracket = Tournament(state.roster.shuffled().map { Contender(it.id, it) })
        currentPairing = null
        update {
            it.copy(running = true, complete = false, revealedRanking = emptyList(), status = "Starting tournament…")
        }
        job = scope.launch { advance() }
    }

    /** The user's pick for the pairing currently on screen: 1 (side A of the pairing) or 2 (side B). */
    fun pickWinner(slot: Int) {
        val bracket = this.bracket ?: return
        val pairing = currentPairing ?: return
        if (!currentState().canPick) return
        bracket.recordWinner(if (slot == 1) pairing.a.id else pairing.b.id)
        currentPairing = null
        job = scope.launch { advance() }
    }

    /** Abandon the current tournament run — releases generated audio and returns to roster editing. */
    fun stop() {
        job?.cancel()
        releaseAudio()
        bracket = null
        currentPairing = null
        update { it.copy(running = false, busy = false, status = null) }
    }

    /** Release every cached entry's generated audio. Safe to call repeatedly / when nothing is cached. */
    fun releaseAudio() {
        entryAudio.values.forEach { it.close() }
        entryAudio.clear()
        entrySampleRate.clear()
        entryRtf.clear()
    }

    // Fetch the bracket's next pairing (blind — only slot numbers 1/2 ever reach [update]), generate
    // + play each side through the shared [generate]/[play] functions, then wait for [pickWinner].
    // When the bracket has no more pairings, reveal the final ranking instead.
    private suspend fun advance() {
        val bracket = this.bracket ?: return
        val pairing = bracket.nextPairing()
        if (pairing == null) {
            finish(bracket)
            return
        }
        currentPairing = pairing
        val text = currentText()
        update {
            it.copy(
                busy = true,
                roundNumber = pairing.round,
                slot1Ready = false,
                slot2Ready = false,
                status = "Generating 1…",
            )
        }

        if (!ensureGenerated(pairing.a.payload, text)) return fail("Slot 1")
        update { it.copy(slot1Ready = true, status = "Playing 1…") }
        playSlot(1, pairing.a.id)

        update { it.copy(status = "Generating 2…") }
        if (!ensureGenerated(pairing.b.payload, text)) return fail("Slot 2")
        update { it.copy(slot2Ready = true, status = "Playing 2…") }
        playSlot(2, pairing.b.id)

        update { it.copy(busy = false, status = "Round ${pairing.round} — pick the better one") }
    }

    private suspend fun playSlot(
        slot: Int,
        entryId: String,
    ) {
        update { it.copy(playingSlot = slot) }
        play(entryAudio.getValue(entryId), entrySampleRate.getValue(entryId))
        update { it.copy(playingSlot = null) }
    }

    private fun fail(label: String) {
        update { it.copy(busy = false, status = "$label failed: ${errorMessage() ?: "unknown error"}") }
    }

    // Generate (once) + measure this entry's real-time factor, exactly the way RtfEstimator/
    // BenchmarkViewModel measure it elsewhere: wall-clock around a real synthesize() drain, over the
    // audio-seconds actually produced. Cached by entry id so an entry that wins several rounds is
    // synthesized once and its measured RTF never gets diluted/overwritten by a repeat run.
    private suspend fun ensureGenerated(
        entry: CompareViewModel.TournamentEntry,
        text: String,
    ): Boolean {
        if (entryAudio.containsKey(entry.id)) return true
        val audio = GeneratedAudio()
        val startNanos = System.nanoTime()
        if (!generate(entry.descriptor, entry.voiceId, text, audio)) {
            audio.close()
            return false
        }
        val elapsedSeconds = (System.nanoTime() - startNanos) / NANOS_PER_SECOND
        val audioSeconds = audio.snapshot().sumOf { it.size }.toDouble() / entry.descriptor.sampleRate
        entryAudio[entry.id] = audio
        entrySampleRate[entry.id] = entry.descriptor.sampleRate
        entryRtf[entry.id] = if (audioSeconds > 0.0) elapsedSeconds / audioSeconds else 0.0
        return true
    }

    // Reveal identities: the bracket's ranking() (place + judged win count, :core, unit-tested) is
    // mapped straight onto each entry's REVEALED label and its measured RTF from ensureGenerated.
    private fun finish(bracket: Tournament<CompareViewModel.TournamentEntry>) {
        val rows =
            bracket.ranking().map { ranked ->
                CompareViewModel.RevealedRankRow(
                    place = ranked.place,
                    label = ranked.contender.payload.label,
                    winsRecorded = ranked.winsRecorded,
                    realTimeFactor = entryRtf[ranked.contender.id],
                )
            }
        val championLabel = rows.firstOrNull { it.place == 1 }?.label ?: "unknown"
        update {
            it.copy(
                busy = false,
                complete = true,
                revealedRanking = rows,
                status = "Tournament complete — champion: $championLabel",
            )
        }
    }
}
