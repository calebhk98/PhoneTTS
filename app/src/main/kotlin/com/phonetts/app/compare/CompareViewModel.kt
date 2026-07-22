package com.phonetts.app.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonetts.app.AppGraph
import com.phonetts.app.audio.AudioTrackSink
import com.phonetts.app.benchmark.BenchmarkViewModel
import com.phonetts.core.audio.buffer.BufferedPlayback
import com.phonetts.core.audio.buffer.GeneratedAudio
import com.phonetts.core.audio.export.WavEncoder
import com.phonetts.core.compare.Contender
import com.phonetts.core.compare.Pairing
import com.phonetts.core.compare.Tournament
import com.phonetts.core.compare.mergeUnique
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

private const val NANOS_PER_SECOND = 1_000_000_000.0

/** How many recent tournament generation failures are kept for the copyable error log (issue:
 * "auto-fail a failing voice"), mirroring [com.phonetts.app.hf.MAX_HF_ERROR_LOG]'s bound. */
private const val MAX_TOURNAMENT_ERROR_LOG = 25

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
        /** The contender id, so the revealed row can still replay/save its cached audio (below). */
        val entryId: String,
    )

    /**
     * One retained, copyable tournament generation failure: a voice that couldn't be synthesized
     * mid-bracket, auto-failed (its opponent advances without a pick) rather than blocking the
     * whole tournament. Mirrors [com.phonetts.app.hf.HfBrowseError]/`ErrorLogDialog`'s shape and
     * UX so the "copy errors" affordance below is a familiar pattern, not a new one.
     */
    data class TournamentError(
        val atMs: Long,
        val label: String,
        val message: String,
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
        // Opaque contender ids for the pairing currently on screen — NOT labels, so exposing them
        // to Compose doesn't leak identity while judging; they only let the UI ask for a replay/save
        // of a slot that's already generated (below), the same way `entryId` does once revealed.
        val slot1Id: String? = null,
        val slot2Id: String? = null,
        val playingSlot: Int? = null,
        val status: String? = null,
        val complete: Boolean = false,
        val revealedRanking: List<RevealedRankRow> = emptyList(),
        /** Generation failures auto-failed during this tournament run, newest first, bounded to
         * [MAX_TOURNAMENT_ERROR_LOG] — copyable via the screen's "copy errors" affordance. */
        val errors: List<TournamentError> = emptyList(),
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
            save = ::saveAudio,
            currentText = { mutableState.value.text },
            currentState = { mutableState.value.tournament },
            update = { transform -> mutableState.update { it.copy(tournament = transform(it.tournament)) } },
            errorMessage = { mutableState.value.status },
        )

    // Seed the text field with a sensible non-blank default (issue: the tournament start button was
    // greyed out because the field started empty) — but only when the caller didn't already hand us
    // real text (MainActivity seeds this from the main reader's current text field, which wins when
    // non-blank). Reuses the benchmark's pangram rather than duplicating a phrase literal here (SSOT).
    private fun initialState(text: String): UiState {
        val models = graph.catalog.list()
        return UiState(
            models = models,
            text = text.ifBlank { BenchmarkViewModel.BENCH_PHRASE },
            a = defaultSelection(models.getOrNull(0)),
            // Default B to a second model when one is downloaded, so the two pickers don't start
            // identical; falls back to the same model (still comparable by voice) with only one.
            b = defaultSelection(models.getOrNull(1) ?: models.getOrNull(0)),
        )
    }

    private fun defaultSelection(descriptor: ModelDescriptor?): Selection =
        Selection(descriptor, descriptor?.defaultVoiceId)

    /**
     * Re-read the catalog so models (and their voices) downloaded or engine-discovered AFTER this
     * screen was first opened actually appear in the pickers and the tournament roster builder
     * (bug: Compare showed a one-time snapshot taken at construction and never refreshed, unlike the
     * main reader which re-reads on every entry). Called on screen (re)entry. Keeps the current A/B
     * picks when their models still exist; re-seeds a default only when a pick's model is gone or was
     * never made.
     */
    fun refreshModels() {
        val models = graph.catalog.list()
        mutableState.update { current ->
            current.copy(
                models = models,
                a = reconcile(current.a, models) ?: defaultSelection(models.getOrNull(0)),
                b = reconcile(current.b, models) ?: defaultSelection(models.getOrNull(1) ?: models.getOrNull(0)),
            )
        }
    }

    // Keeps [selection] if its model is still installed (matched by modelId, refreshed to the new
    // descriptor instance); returns null when the model is gone or was never picked, so the caller
    // seeds a fresh default instead of leaving a dangling reference.
    private fun reconcile(
        selection: Selection,
        models: List<ModelDescriptor>,
    ): Selection? {
        val modelId = selection.descriptor?.modelId ?: return null
        val current = models.firstOrNull { it.modelId == modelId } ?: return null
        return selection.copy(descriptor = current)
    }

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

    /** Save A's already-generated buffer to [output] as WAV. No-op (closes [output]) before A has
     * a result. Same one-generation-path buffer replayA() plays — never re-synthesized to save. */
    fun saveA(output: OutputStream) = save(Slot.A, output)

    /** Save B's already-generated buffer to [output] as WAV. No-op (closes [output]) before B has
     * a result. Same one-generation-path buffer replayB() plays — never re-synthesized to save. */
    fun saveB(output: OutputStream) = save(Slot.B, output)

    private fun save(
        slot: Slot,
        output: OutputStream,
    ) {
        val audio = bufferFor(slot)
        if (audio == null) {
            runCatching { output.close() }
            return
        }
        val rate = if (slot == Slot.A) sampleRateA else sampleRateB
        val label = if (slot == Slot.A) "A" else "B"
        viewModelScope.launch {
            val ok = saveAudio(audio, rate, output)
            if (ok) mutableState.update { it.copy(status = "Saved $label") }
        }
    }

    // Shared by A/B save AND tournament-entry save (below): encodes the already-generated buffer
    // with the app's reference WavEncoder — the SAME export path TtsViewModel.export/sampleAllModels
    // use (spec: reuse the one save-to-file path, never invent a second one) — off Main.
    private suspend fun saveAudio(
        audio: GeneratedAudio,
        rate: Int,
        output: OutputStream,
    ): Boolean =
        runCatching {
            withContext(Dispatchers.IO) {
                output.use { wavEncoder().encode(audio.snapshot().asFlow(), rate, it) }
            }
        }.onFailure { e -> mutableState.update { it.copy(status = "Save failed: ${e.message}") } }
            .isSuccess

    // The app's already-configured WAV encoder (app-writable scratch dir — see ExportFormats kdoc
    // for why constructing a bare WavEncoder() here would break on Android) rather than a second one.
    private fun wavEncoder() = graph.exportFormats.first { it.format.id == WavEncoder.FORMAT.id }

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
    private val save: suspend (GeneratedAudio, Int, OutputStream) -> Boolean,
    private val currentText: () -> String,
    private val currentState: () -> CompareViewModel.TournamentUiState,
    private val update: ((CompareViewModel.TournamentUiState) -> CompareViewModel.TournamentUiState) -> Unit,
    private val errorMessage: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var entrySeq = 0
    private var job: Job? = null
    private var bracket: Tournament<CompareViewModel.TournamentEntry>? = null
    private var currentPairing: Pairing<CompareViewModel.TournamentEntry>? = null
    private val entryAudio = mutableMapOf<String, GeneratedAudio>()
    private val entrySampleRate = mutableMapOf<String, Int>()
    private val entryRtf = mutableMapOf<String, Double>()

    // Entries known to fail generation this run (issue: "auto-fail a failing voice") — once an id
    // lands here, every later pairing it would appear in short-circuits straight to auto-advancing
    // its opponent instead of retrying (and re-logging) the same failure.
    private val failedEntries = mutableSetOf<String>()

    /** Roster de-dup key shared by [addEntry]/[addAllModels]: same model + same voice = same pick. */
    private val rosterKey: (CompareViewModel.TournamentEntry) -> Pair<String, String> =
        { it.descriptor.modelId to it.voiceId }

    /**
     * Add a model+voice pick to the bracket roster, skipping it if that exact model+voice is already
     * present (issue #92: duplicate roster entries) — de-duping the existing roster too, in case it
     * accumulated duplicates before this rule existed. No-op once a tournament is already running.
     */
    fun addEntry(
        descriptor: ModelDescriptor,
        voiceId: String,
    ) {
        if (currentState().running) return
        val entry =
            CompareViewModel.TournamentEntry(id = "entry-${entrySeq++}", descriptor = descriptor, voiceId = voiceId)
        update { it.copy(roster = mergeIntoRoster(it.roster, listOf(entry))) }
    }

    /** Remove a roster entry (before the tournament has started). */
    fun removeEntry(id: String) {
        if (currentState().running) return
        update { it.copy(roster = it.roster.filterNot { entry -> entry.id == id }) }
    }

    /**
     * Add every installed model (each with its default voice) to the roster in one tap, skipping any
     * model+voice pair already present — so building a full-field tournament doesn't mean adding each
     * model one at a time (issue: "the tournament requires me to select all of them"; you can still
     * curate a subset with Add/Remove). No-op while a tournament is running.
     */
    fun addAllModels(models: List<ModelDescriptor>) {
        if (currentState().running) return
        val additions =
            models.map {
                CompareViewModel.TournamentEntry(id = "entry-${entrySeq++}", descriptor = it, voiceId = it.defaultVoiceId)
            }
        update { it.copy(roster = mergeIntoRoster(it.roster, additions)) }
    }

    // Shared by addEntry/addAllModels (issue #92): re-dedupes the CURRENT roster against itself
    // first (collapses any duplicate that slipped in before this rule existed), then appends only
    // the genuinely-new [additions] — see core's mergeUnique kdoc for the two-pass rationale.
    private fun mergeIntoRoster(
        current: List<CompareViewModel.TournamentEntry>,
        additions: List<CompareViewModel.TournamentEntry>,
    ): List<CompareViewModel.TournamentEntry> {
        val deduped = mergeUnique(emptyList(), current, rosterKey)
        return mergeUnique(deduped, additions, rosterKey)
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
            it.copy(
                running = true,
                complete = false,
                revealedRanking = emptyList(),
                errors = emptyList(),
                status = "Starting tournament…",
            )
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

    /** Release every cached entry's generated audio and forget any recorded generation failures.
     * Safe to call repeatedly / when nothing is cached. */
    fun releaseAudio() {
        entryAudio.values.forEach { it.close() }
        entryAudio.clear()
        entrySampleRate.clear()
        entryRtf.clear()
        failedEntries.clear()
    }

    /**
     * Replay an already-generated entry's cached audio without re-synthesizing — usable for the
     * current match's ready slot(s) (via [CompareViewModel.TournamentUiState.slot1Id]/`slot2Id`) or,
     * once a tournament completes, any revealed [CompareViewModel.RevealedRankRow.entryId]. No-op if
     * nothing is cached yet for [entryId] (e.g. it's the failing side of an auto-advanced pairing).
     */
    fun replayEntry(entryId: String) {
        val audio = entryAudio[entryId] ?: return
        val rate = entrySampleRate.getValue(entryId)
        scope.launch { play(audio, rate) }
    }

    /**
     * Save an already-generated entry's cached audio to [output] as WAV, through the SAME encoder
     * [CompareViewModel.saveA]/`saveB` use — one save-to-file path, never a second one. No-op
     * (closes [output]) if nothing is cached yet for [entryId].
     */
    fun saveEntry(
        entryId: String,
        output: OutputStream,
    ) {
        val audio = entryAudio[entryId]
        if (audio == null) {
            runCatching { output.close() }
            return
        }
        val rate = entrySampleRate.getValue(entryId)
        scope.launch {
            val ok = save(audio, rate, output)
            if (ok) update { it.copy(status = "Saved") }
        }
    }

    // Fetch the bracket's next pairing (blind — only slot numbers/ids, never labels, ever reach
    // [update]), generate + play each side through the shared [generate]/[play] functions, then wait
    // for [pickWinner] — UNLESS one (or both) sides failed to generate, in which case the working
    // side is auto-advanced without a pick (issue: "auto-fail a failing voice") so one bad voice
    // never blocks the whole bracket. When the bracket has no more pairings, reveal the ranking.
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
                slot1Id = pairing.a.id,
                slot2Id = pairing.b.id,
                status = "Generating 1…",
            )
        }

        val aReady = ensureGenerated(pairing.a.payload, text)
        if (aReady) {
            update { it.copy(slot1Ready = true, status = "Playing 1…") }
            playSlot(1, pairing.a.id)
        }

        update { it.copy(status = "Generating 2…") }
        val bReady = ensureGenerated(pairing.b.payload, text)
        if (bReady) {
            update { it.copy(slot2Ready = true, status = "Playing 2…") }
            playSlot(2, pairing.b.id)
        }

        when {
            aReady && bReady -> update { it.copy(busy = false, status = "Round ${pairing.round} — pick the better one") }
            aReady -> autoAdvance(bracket, pairing.a.id, "Slot 2 failed to generate — auto-advancing Slot 1")
            bReady -> autoAdvance(bracket, pairing.b.id, "Slot 1 failed to generate — auto-advancing Slot 2")
            // Both sides failed: the bracket engine still needs a winner to keep draining (there is
            // no "double loss" concept), so the first side advances — but stays in [failedEntries],
            // so it auto-loses its NEXT pairing too rather than riding a fluke all the way to champion.
            else -> autoAdvance(bracket, pairing.a.id, "Both sides failed to generate — advancing Slot 1 by default")
        }
    }

    // Records [winnerId] the bracket chose automatically (its opponent failed to generate) and keeps
    // draining the bracket without waiting for a user pick.
    private suspend fun autoAdvance(
        bracket: Tournament<CompareViewModel.TournamentEntry>,
        winnerId: String,
        statusMsg: String,
    ) {
        bracket.recordWinner(winnerId)
        currentPairing = null
        update { it.copy(status = statusMsg) }
        advance()
    }

    private suspend fun playSlot(
        slot: Int,
        entryId: String,
    ) {
        update { it.copy(playingSlot = slot) }
        play(entryAudio.getValue(entryId), entrySampleRate.getValue(entryId))
        update { it.copy(playingSlot = null) }
    }

    // Generate (once) + measure this entry's real-time factor, exactly the way RtfEstimator/
    // BenchmarkViewModel measure it elsewhere: wall-clock around a real synthesize() drain, over the
    // audio-seconds actually produced. Cached by entry id so an entry that wins several rounds is
    // synthesized once and its measured RTF never gets diluted/overwritten by a repeat run. A prior
    // failure is remembered too (see [failedEntries]) so a repeat meeting never retries/re-logs it.
    private suspend fun ensureGenerated(
        entry: CompareViewModel.TournamentEntry,
        text: String,
    ): Boolean {
        if (entryAudio.containsKey(entry.id)) return true
        if (entry.id in failedEntries) return false
        val audio = GeneratedAudio()
        val startNanos = System.nanoTime()
        if (!generate(entry.descriptor, entry.voiceId, text, audio)) {
            audio.close()
            recordFailure(entry)
            return false
        }
        val elapsedSeconds = (System.nanoTime() - startNanos) / NANOS_PER_SECOND
        val audioSeconds = audio.snapshot().sumOf { it.size }.toDouble() / entry.descriptor.sampleRate
        entryAudio[entry.id] = audio
        entrySampleRate[entry.id] = entry.descriptor.sampleRate
        entryRtf[entry.id] = if (audioSeconds > 0.0) elapsedSeconds / audioSeconds else 0.0
        return true
    }

    // Marks [entry] as auto-failed and appends a copyable log row (issue: "auto-fail a failing voice
    // + copyable error log"), mirroring HfBrowseError/ErrorLogDialog's shape/UX, bounded the same way.
    private fun recordFailure(entry: CompareViewModel.TournamentEntry) {
        failedEntries += entry.id
        val logged =
            CompareViewModel.TournamentError(
                atMs = clock(),
                label = entry.label,
                message = errorMessage() ?: "generation failed",
            )
        update { it.copy(errors = (listOf(logged) + it.errors).take(MAX_TOURNAMENT_ERROR_LOG)) }
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
                    entryId = ranked.contender.id,
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
