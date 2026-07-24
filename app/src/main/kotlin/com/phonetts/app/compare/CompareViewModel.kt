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
import com.phonetts.core.compare.bracketRoundSizes
import com.phonetts.core.compare.mergeUnique
import com.phonetts.core.compare.prefetchAhead
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.store.FavoritesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.random.Random

private const val NANOS_PER_SECOND = 1_000_000_000.0

/** How many recent tournament generation failures are kept for the copyable error log (issue:
 * "auto-fail a failing voice"), mirroring [com.phonetts.app.hf.MAX_HF_ERROR_LOG]'s bound. */
private const val MAX_TOURNAMENT_ERROR_LOG = 25

/** Look-ahead prefetch bounds (issue #112). Always keep at least the immediate next entry generating
 * ([MIN_PREFETCH_AHEAD]); never run more than [MAX_PREFETCH_AHEAD] ahead of the current pairing, so
 * memory and the single serialized engine (spec rule 6) stay bounded even for a large fast field. */
private const val MIN_PREFETCH_AHEAD = 1
private const val MAX_PREFETCH_AHEAD = 6

/** Fallback per-entry generation estimate (seconds) used only until the first real measurement, so
 * the look-ahead has a cost to reason about on the very first prefetch decision (issue #112). */
private const val DEFAULT_GEN_ESTIMATE_SECONDS = 4.0

/** Entries' worth of generation time to seed the bank with when a tournament starts, so the first
 * pairing (both slots) and a little beyond are generated AHEAD instead of on-demand. Without this the
 * bank starts empty and only [MIN_PREFETCH_AHEAD] (1) entry prefetches, so the user waits on the very
 * first pairing's second slot - exactly the "should generate ahead of time" complaint (issue #112).
 * The seed is spent down as entries generate, so a slow field still converges to the banked-time model. */
private const val INITIAL_PREFETCH_CREDIT_ENTRIES = 3

/** How many recent generation times feed the rolling "typical generation seconds" estimate. */
private const val GEN_LOG_WINDOW = 8

/**
 * Drives the opt-in A/B compare screen (issue #19-6) - a dedicated, off-the-main-flow screen the
 * owner asked for ("an option, a new screen or toggle, not default"). Lets the user pick a model +
 * voice for A and for B (from [com.phonetts.core.registry.ModelCatalog]/[ModelDescriptor.voices] -
 * SSOT, nothing hardcoded), synthesize the SAME text on each through the exact one generation path
 * ([com.phonetts.core.engine.VoiceEngine.synthesize], spec §6.1), and play them back-to-back.
 *
 * Because only one engine is ever loaded ([com.phonetts.core.registry.EngineManager], spec §5.5), A
 * is generated and played to completion BEFORE the engine is switched to B - there is no concurrent
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
     * REVEALED identity string - the tournament UI must only read it for the roster-building list
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
                return "${descriptor.displayName} - $voiceName"
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
        /** The model id behind this row, so the results page can favorite it (issue #113c). */
        val modelId: String,
    )

    /** One flagged/downvoted model surfaced on the results page for deletion or export (issue #113d).
     * Only built once the tournament completes, so revealing the identifying [label] stays blind-safe. */
    data class FlaggedReviewRow(
        val modelId: String,
        val label: String,
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
     * match is in progress - no [TournamentEntry]/[ModelDescriptor] reference lives here during
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
        // Opaque contender ids for the pairing currently on screen - NOT labels, so exposing them
        // to Compose doesn't leak identity while judging; they only let the UI ask for a replay/save
        // of a slot that's already generated (below), the same way `entryId` does once revealed.
        val slot1Id: String? = null,
        val slot2Id: String? = null,
        val playingSlot: Int? = null,
        /** Whether the model behind each blind slot is currently flagged/downvoted (issue #113d).
         * A plain boolean, so a flag indicator never reveals WHICH model a slot is while judging. */
        val slot1Flagged: Boolean = false,
        val slot2Flagged: Boolean = false,
        /** Progress within the current round (issue #113b): X = this comparison, N = comparisons in
         * the round. Both null before a round is under way. Anonymous, so it is blind-safe. */
        val comparisonInRound: Int? = null,
        val comparisonsInRound: Int? = null,
        val status: String? = null,
        val complete: Boolean = false,
        val revealedRanking: List<RevealedRankRow> = emptyList(),
        /** Model ids the user has favorited, mirrored so the results page can render its stars
         * reactively (issue #113c). Refreshed from the shared FavoritesStore on every change. */
        val favoriteModelIds: Set<String> = emptySet(),
        /** Models flagged/downvoted during THIS run, revealed for delete/export on the results page
         * (issue #113d). Empty until the tournament completes. */
        val flaggedReview: List<FlaggedReviewRow> = emptyList(),
        /** Generation failures auto-failed during this tournament run, newest first, bounded to
         * [MAX_TOURNAMENT_ERROR_LOG] - copyable via the screen's "copy errors" affordance. */
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
    // rules) - it still drives the exact SAME [generateInto]/[playAudio] this class uses for A/B,
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
            favorites = graph.favoritesStore,
            modelLabel = ::modelDisplayName,
            deleteModel = ::deleteModelFromDisk,
        )

    // Look up a model's revealed display name for the flagged-review list (issue #113d). SSOT: the
    // catalog descriptor is the only source of a display name, never a literal here.
    private fun modelDisplayName(modelId: String): String =
        graph.catalog.list().firstOrNull { it.modelId == modelId }?.displayName ?: modelId

    // Delete a flagged model through the SAME path the Manage screen uses (issue #113d) -
    // [com.phonetts.core.registry.ModelManager.remove] unloads it if loaded, deletes its weights,
    // and clears its saved override - off the main thread, then re-reads the catalog so the pickers
    // and roster reflect the removal. Returns whether anything was actually removed.
    private suspend fun deleteModelFromDisk(modelId: String): Boolean {
        val removal =
            runCatching { withContext(Dispatchers.IO) { graph.modelManager.remove(modelId) } }
                .onFailure { e -> mutableState.update { it.copy(status = "Delete failed: ${e.message}") } }
                .getOrNull() ?: return false
        refreshModels()
        return removal.removedFromCatalog || removal.filesDeleted
    }

    // Seed the text field with a sensible non-blank default (issue: the tournament start button was
    // greyed out because the field started empty) - but only when the caller didn't already hand us
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
     * Generate A, play it, then generate B, then play it - sequentially, on the SAME one generation
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

                mutableState.update { it.copy(busy = false, status = "Done - replay either side below") }
            }
    }

    // Load [descriptor]'s engine (unloading whatever was loaded before - one engine at a time, spec
    // §5.5) and drain its synthesize() flow into [into]. Returns false (status already set) on failure.
    private suspend fun generateInto(
        descriptor: ModelDescriptor,
        voiceId: String,
        text: String,
        into: GeneratedAudio,
    ): Boolean {
        val result =
            runCatching {
                // switchTo()/engine.load() blocks on synchronous weight loading (issue #18-4b) - off
                // Main, same as every other generation call site (TtsViewModel/BenchmarkViewModel).
                withContext(Dispatchers.IO) { graph.engineManager.switchTo(descriptor.engineId, descriptor) }
                val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                val params = SynthesisParams(descriptor.parameters.associate { it.id to it.default })
                // The engine's synthesize() flow already runs inference off the collecting thread
                // (AbstractVoiceEngine.flowOn) - no extra withContext needed around collect itself.
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
        // BufferedPlayback.play drives a blocking AudioTrack.write via the sink (issue #18-4b) -
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
     * a result. Same one-generation-path buffer replayA() plays - never re-synthesized to save. */
    fun saveA(output: OutputStream) = save(Slot.A, output)

    /** Save B's already-generated buffer to [output] as WAV. No-op (closes [output]) before B has
     * a result. Same one-generation-path buffer replayB() plays - never re-synthesized to save. */
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
    // with the app's reference WavEncoder - the SAME export path TtsViewModel.export/sampleAllModels
    // use (spec: reuse the one save-to-file path, never invent a second one) - off Main.
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

    // The app's already-configured WAV encoder (app-writable scratch dir - see ExportFormats kdoc
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

    // Release both sides' buffers before starting a fresh run (keeps memory bounded - a repeated A/B
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
 * [CompareViewModel] uses for A/B (passed in by reference - one generation path, one playback path,
 * spec rule 3), and remembering each entry's generated audio + measured real-time factor so an entry
 * that wins several rounds is synthesized exactly once, never re-measured on a repeat run.
 *
 * Split out of [CompareViewModel] itself purely to stay under detekt's TooManyFunctions ceiling on
 * that class (CLAUDE.md rule 9) - the roster/bracket/audio-cache state below is a cohesive unit of
 * its own regardless. [CompareViewModel] exposes this as a public `val` the screen calls directly.
 *
 * BLINDING is structural, not a screen-code convention: [update]/[currentState] only ever carry
 * [CompareViewModel.TournamentUiState], which never contains a [CompareViewModel.TournamentEntry] or
 * [ModelDescriptor] while a match is in progress - only the anonymized slot numbers 1/2. Identities
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
    private val favorites: FavoritesStore,
    private val modelLabel: (String) -> String,
    private val deleteModel: suspend (String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private var entrySeq = 0
    private var job: Job? = null
    private var bracket: Tournament<CompareViewModel.TournamentEntry>? = null
    private var currentPairing: Pairing<CompareViewModel.TournamentEntry>? = null
    private val entryAudio = mutableMapOf<String, GeneratedAudio>()
    private val entrySampleRate = mutableMapOf<String, Int>()
    private val entryRtf = mutableMapOf<String, Double>()

    // Which contender is shown as Slot 1 vs Slot 2 for the pairing on screen. Re-decided EVERY
    // pairing by [random] (issue #113a) so a model can't be recognized by always sitting in the same
    // slot; also the mapping [pickWinner]/[flagSlot] resolve a tapped slot back to a contender by.
    private var slot1: Contender<CompareViewModel.TournamentEntry>? = null
    private var slot2: Contender<CompareViewModel.TournamentEntry>? = null

    // --- Look-ahead prefetch (issue #112) --------------------------------------------------------
    // ONE lock serializes every generate() call (foreground pairing generation AND the background
    // prefetch pump), so only one engine is ever loaded at a time (spec rule 6) even though audio is
    // produced ahead of playback. Playback itself needs no engine, so it overlaps prefetch freely.
    private val generationLock = Mutex()
    private var prefetchJob: Job? = null
    private var prefetchOrder: List<String> = emptyList()

    // Spare ("banked") seconds available for prefetching: credited as the user listens/replays,
    // spent as upcoming entries are generated ahead (see [prefetchAhead]).
    private var bankedSeconds = 0.0
    private var lastGenerationSeconds = 0.0
    private val generationSecondsLog = mutableListOf<Double>()
    private val entryAudioSeconds = mutableMapOf<String, Double>()

    // Entries already shown in a pairing - the look-ahead only counts entries generated but NOT yet
    // reached as "ahead" inventory, so it keeps generating past what the user has already seen.
    private val presentedEntries = mutableSetOf<String>()

    // Round progress (issue #113b): comparison sizes per round (from [bracketRoundSizes]) plus a
    // running position within the current round.
    private var roundSizes: List<Int> = emptyList()
    private var currentRoundNum = 0
    private var comparisonInRound = 0

    // Models the user flagged/downvoted during THIS run (issue #113d), for the results-page review.
    private val flaggedThisRun = mutableSetOf<String>()

    // Entries known to fail generation this run (issue: "auto-fail a failing voice") - once an id
    // lands here, every later pairing it would appear in short-circuits straight to auto-advancing
    // its opponent instead of retrying (and re-logging) the same failure.
    private val failedEntries = mutableSetOf<String>()

    /** Roster de-dup key shared by [addEntry]/[addAllModels]: same model + same voice = same pick. */
    private val rosterKey: (CompareViewModel.TournamentEntry) -> Pair<String, String> =
        { it.descriptor.modelId to it.voiceId }

    /**
     * Add a model+voice pick to the bracket roster, skipping it if that exact model+voice is already
     * present (issue #92: duplicate roster entries) - de-duping the existing roster too, in case it
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
     * model+voice pair already present - so building a full-field tournament doesn't mean adding each
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
    // the genuinely-new [additions] - see core's mergeUnique kdoc for the two-pass rationale.
    private fun mergeIntoRoster(
        current: List<CompareViewModel.TournamentEntry>,
        additions: List<CompareViewModel.TournamentEntry>,
    ): List<CompareViewModel.TournamentEntry> {
        val deduped = mergeUnique(emptyList(), current, rosterKey)
        return mergeUnique(deduped, additions, rosterKey)
    }

    /**
     * Start a single-elimination bracket over the current roster (needs at least 2 entries and
     * non-blank text). The roster is shuffled here - in `:app`, never in the deterministic `:core`
     * [Tournament] engine itself - purely so the pairing order isn't predictable from the order
     * entries were added; that shuffle is the ONLY source of unpredictability, the bracket
     * progression stays fully deterministic once seeded (see [Tournament]'s docs).
     */
    fun start() {
        val state = currentState()
        if (state.roster.size < 2 || currentText().isBlank() || state.running) return

        releaseAudio()
        // Seed the look-ahead bank (releaseAudio just zeroed it) so the FIRST pairing generates ahead
        // instead of on-demand: with an empty bank only one entry prefetches, leaving the user waiting
        // on slot 2. A few entries' worth of credit lets both first slots (and a little more) be ready
        // before judging starts; it is spent down as entries generate (issue #112).
        bankedSeconds = INITIAL_PREFETCH_CREDIT_ENTRIES * DEFAULT_GEN_ESTIMATE_SECONDS
        // Shuffle once for the bracket order, and reuse that same order as the prefetch/generation
        // order (issue #112: "randomize the model order, then generate ahead") - every entry is
        // needed for at least one pairing, so generating in bracket order never wastes work.
        val shuffled = state.roster.shuffled(random)
        bracket = Tournament(shuffled.map { Contender(it.id, it) })
        prefetchOrder = shuffled.map { it.id }
        roundSizes = bracketRoundSizes(state.roster.size)
        currentRoundNum = 0
        comparisonInRound = 0
        flaggedThisRun.clear()
        currentPairing = null
        update {
            it.copy(
                running = true,
                complete = false,
                revealedRanking = emptyList(),
                favoriteModelIds = favorites.favoriteModels().toSet(),
                flaggedReview = emptyList(),
                errors = emptyList(),
                status = "Starting tournament…",
            )
        }
        job = scope.launch { advance() }
        schedulePrefetch()
    }

    /** The user's pick for the pairing currently on screen: Slot 1 or Slot 2. Which contender each
     * slot maps to is the per-pairing randomized mapping ([slot1]/[slot2], issue #113a). */
    fun pickWinner(slot: Int) {
        val bracket = this.bracket ?: return
        val winner = (if (slot == 1) slot1 else slot2) ?: return
        if (!currentState().canPick) return
        bracket.recordWinner(winner.id)
        currentPairing = null
        job = scope.launch { advance() }
    }

    /** Abandon the current tournament run - releases generated audio and returns to roster editing. */
    fun stop() {
        job?.cancel()
        prefetchJob?.cancel()
        releaseAudio()
        bracket = null
        currentPairing = null
        slot1 = null
        slot2 = null
        update { it.copy(running = false, busy = false, status = null) }
    }

    /** Release every cached entry's generated audio, reset the look-ahead scheduler, and forget any
     * recorded generation failures. Safe to call repeatedly / when nothing is cached. */
    fun releaseAudio() {
        entryAudio.values.forEach { it.close() }
        entryAudio.clear()
        entrySampleRate.clear()
        entryRtf.clear()
        entryAudioSeconds.clear()
        generationSecondsLog.clear()
        presentedEntries.clear()
        bankedSeconds = 0.0
        lastGenerationSeconds = 0.0
        failedEntries.clear()
    }

    /**
     * Replay an already-generated entry's cached audio without re-synthesizing - usable for the
     * current match's ready slot(s) (via [CompareViewModel.TournamentUiState.slot1Id]/`slot2Id`) or,
     * once a tournament completes, any revealed [CompareViewModel.RevealedRankRow.entryId]. No-op if
     * nothing is cached yet for [entryId] (e.g. it's the failing side of an auto-advanced pairing).
     */
    fun replayEntry(entryId: String) {
        val audio = entryAudio[entryId] ?: return
        val rate = entrySampleRate.getValue(entryId)
        // Replaying an example is more time during which nothing needs the engine - bank it and push
        // the look-ahead further ahead (issue #112: "when the user replays, generate further ahead").
        bankedSeconds += entryAudioSeconds[entryId] ?: 0.0
        schedulePrefetch()
        scope.launch { play(audio, rate) }
    }

    /**
     * Save an already-generated entry's cached audio to [output] as WAV, through the SAME encoder
     * [CompareViewModel.saveA]/`saveB` use - one save-to-file path, never a second one. No-op
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

    // Fetch the bracket's next pairing (blind - only slot numbers/ids, never labels, ever reach
    // [update]), pick a fresh random Slot 1/Slot 2 side mapping (issue #113a) and advance the
    // within-round comparison counter (issue #113b), then hand off to [presentPairing]. When the
    // bracket has no more pairings, reveal the ranking.
    private suspend fun advance() {
        val bracket = this.bracket ?: return
        val pairing = bracket.nextPairing()
        if (pairing == null) {
            finish(bracket)
            return
        }
        currentPairing = pairing
        val swap = random.nextBoolean()
        val s1 = if (swap) pairing.b else pairing.a
        val s2 = if (swap) pairing.a else pairing.b
        slot1 = s1
        slot2 = s2
        if (pairing.round != currentRoundNum) {
            currentRoundNum = pairing.round
            comparisonInRound = 1
        } else {
            comparisonInRound += 1
        }
        presentPairing(bracket, s1, s2, pairing.round)
    }

    // Generate + play each side through the shared [generate]/[play] functions (each side may already
    // be cached by the prefetch pump, in which case generation returns instantly), then wait for
    // [pickWinner] - UNLESS one (or both) sides failed to generate, in which case the working side is
    // auto-advanced without a pick (issue: "auto-fail a failing voice") so one bad voice never blocks
    // the whole bracket. Only anonymized slot numbers/ids/flags ever reach [update], never a label.
    private suspend fun presentPairing(
        bracket: Tournament<CompareViewModel.TournamentEntry>,
        s1: Contender<CompareViewModel.TournamentEntry>,
        s2: Contender<CompareViewModel.TournamentEntry>,
        round: Int,
    ) {
        val text = currentText()
        update {
            it.copy(
                busy = true,
                roundNumber = round,
                comparisonInRound = comparisonInRound,
                comparisonsInRound = roundSizes.getOrNull(round - 1),
                slot1Ready = false,
                slot2Ready = false,
                slot1Id = s1.id,
                slot2Id = s2.id,
                slot1Flagged = favorites.isFlagged(s1.payload.descriptor.modelId),
                slot2Flagged = favorites.isFlagged(s2.payload.descriptor.modelId),
                status = "Generating 1…",
            )
        }

        val aReady = ensureGenerated(s1.payload, text)
        if (aReady) {
            update { it.copy(slot1Ready = true, status = "Playing 1…") }
            playSlot(1, s1.id)
        }

        update { it.copy(status = "Generating 2…") }
        val bReady = ensureGenerated(s2.payload, text)
        if (bReady) {
            update { it.copy(slot2Ready = true, status = "Playing 2…") }
            playSlot(2, s2.id)
        }

        when {
            aReady && bReady -> onBothReady(s1.id, s2.id, round)
            aReady -> autoAdvance(bracket, s1.id, "Slot 2 failed to generate - auto-advancing Slot 1")
            bReady -> autoAdvance(bracket, s2.id, "Slot 1 failed to generate - auto-advancing Slot 2")
            // Both sides failed: the bracket engine still needs a winner to keep draining (there is
            // no "double loss" concept), so the first side advances - but stays in [failedEntries],
            // so it auto-loses its NEXT pairing too rather than riding a fluke all the way to champion.
            else -> autoAdvance(bracket, s1.id, "Both sides failed to generate - advancing Slot 1 by default")
        }
    }

    // Both slots are ready and awaiting the user's blind pick: mark both as presented, bank the time
    // the user is about to spend listening/deciding (issue #112), and push the look-ahead further.
    private fun onBothReady(
        id1: String,
        id2: String,
        round: Int,
    ) {
        presentedEntries += id1
        presentedEntries += id2
        bankedSeconds += (entryAudioSeconds[id1] ?: 0.0) + (entryAudioSeconds[id2] ?: 0.0)
        update { it.copy(busy = false, status = "Round $round - pick the better one") }
        schedulePrefetch()
    }

    // Launch the background prefetch pump if it isn't already running (issue #112). Idempotent, so
    // any event that frees time (a pairing shown, a replay) can safely re-trigger it.
    private fun schedulePrefetch() {
        if (prefetchJob?.isActive == true) return
        prefetchJob = scope.launch { runPrefetch() }
    }

    // Generate upcoming entries ahead of the pairing the user is judging, one at a time, until the
    // banked time no longer affords running further ahead ([prefetchAhead]) or every entry is done.
    // Shares [generationLock] with foreground generation, so at most one engine is ever loaded (spec
    // rule 6) even though this runs concurrently with playback of the current slot.
    private suspend fun runPrefetch() {
        while (currentState().running && !currentState().complete) {
            val nextId = prefetchOrder.firstOrNull { it !in entryAudio && it !in failedEntries } ?: return
            val remaining = prefetchOrder.count { it !in entryAudio && it !in failedEntries }
            val estimate = if (generationSecondsLog.isEmpty()) DEFAULT_GEN_ESTIMATE_SECONDS else generationSecondsLog.average()
            val target = prefetchAhead(bankedSeconds, List(remaining) { estimate }, MIN_PREFETCH_AHEAD, MAX_PREFETCH_AHEAD)
            val ahead = entryAudio.keys.count { it !in presentedEntries }
            if (ahead >= target) return
            val entry = currentState().roster.firstOrNull { it.id == nextId } ?: return
            val ok = ensureGenerated(entry, currentText())
            if (ok) bankedSeconds -= lastGenerationSeconds
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
    // Guarded by [generationLock] so the foreground pairing and the background prefetch pump never
    // load two engines at once (spec rule 6); the cache is re-checked under the lock in case the
    // other path produced this same entry while this call was waiting.
    private suspend fun ensureGenerated(
        entry: CompareViewModel.TournamentEntry,
        text: String,
    ): Boolean =
        generationLock.withLock {
            if (entryAudio.containsKey(entry.id)) return@withLock true
            if (entry.id in failedEntries) return@withLock false
            val audio = GeneratedAudio()
            val startNanos = System.nanoTime()
            if (!generate(entry.descriptor, entry.voiceId, text, audio)) {
                audio.close()
                recordFailure(entry)
                lastGenerationSeconds = 0.0
                return@withLock false
            }
            val elapsedSeconds = (System.nanoTime() - startNanos) / NANOS_PER_SECOND
            val audioSeconds = audio.snapshot().sumOf { it.size }.toDouble() / entry.descriptor.sampleRate
            entryAudio[entry.id] = audio
            entrySampleRate[entry.id] = entry.descriptor.sampleRate
            entryAudioSeconds[entry.id] = audioSeconds
            entryRtf[entry.id] = if (audioSeconds > 0.0) elapsedSeconds / audioSeconds else 0.0
            lastGenerationSeconds = elapsedSeconds
            generationSecondsLog += elapsedSeconds
            if (generationSecondsLog.size > GEN_LOG_WINDOW) generationSecondsLog.removeAt(0)
            true
        }

    /** Flag/downvote (or un-flag) the model behind a blind slot mid-tournament (issue #113d). Keeps
     * the comparison blind: only the slot number is user-facing; the model id is resolved internally
     * from the [slot1]/[slot2] mapping and never shown until the results page reveals identities. */
    fun flagSlot(slot: Int) {
        val contender = (if (slot == 1) slot1 else slot2) ?: return
        val modelId = contender.payload.descriptor.modelId
        val nowFlagged = !favorites.isFlagged(modelId)
        if (nowFlagged) {
            favorites.flagModel(modelId, reason = contender.payload.label, atMs = clock())
            flaggedThisRun += modelId
        } else {
            favorites.unflagModel(modelId)
            flaggedThisRun -= modelId
        }
        update {
            it.copy(
                slot1Flagged = if (slot == 1) nowFlagged else it.slot1Flagged,
                slot2Flagged = if (slot == 2) nowFlagged else it.slot2Flagged,
            )
        }
    }

    /** Toggle a revealed model as a favorite from the results page (issue #113c), mirroring the new
     * state back so the star re-renders. Backed by the shared [FavoritesStore]. */
    fun toggleFavoriteResultModel(modelId: String) {
        favorites.toggleFavoriteModel(modelId)
        update { it.copy(favoriteModelIds = favorites.favoriteModels().toSet()) }
    }

    /** Delete a flagged model after the tournament (issue #113d), through the SAME delete path the
     * Manage screen uses (injected [deleteModel]). Un-flags it and drops it from the review list. */
    fun deleteFlaggedModel(modelId: String) {
        scope.launch {
            deleteModel(modelId)
            favorites.unflagModel(modelId)
            flaggedThisRun -= modelId
            update { it.copy(flaggedReview = it.flaggedReview.filterNot { row -> row.modelId == modelId }) }
        }
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
    // mapped straight onto each entry's REVEALED label and its measured RTF from ensureGenerated, and
    // the models flagged during this run are surfaced (with labels) for delete/export (issue #113d).
    private fun finish(bracket: Tournament<CompareViewModel.TournamentEntry>) {
        prefetchJob?.cancel()
        val rows =
            bracket.ranking().map { ranked ->
                CompareViewModel.RevealedRankRow(
                    place = ranked.place,
                    label = ranked.contender.payload.label,
                    winsRecorded = ranked.winsRecorded,
                    realTimeFactor = entryRtf[ranked.contender.id],
                    entryId = ranked.contender.id,
                    modelId = ranked.contender.payload.descriptor.modelId,
                )
            }
        val championLabel = rows.firstOrNull { it.place == 1 }?.label ?: "unknown"
        update {
            it.copy(
                busy = false,
                complete = true,
                revealedRanking = rows,
                favoriteModelIds = favorites.favoriteModels().toSet(),
                flaggedReview = flaggedThisRun.map { id -> CompareViewModel.FlaggedReviewRow(id, modelLabel(id)) },
                status = "Tournament complete - champion: $championLabel",
            )
        }
    }
}
