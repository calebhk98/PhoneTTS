package com.phonetts.core.speech

import com.phonetts.core.audio.AudioSink
import com.phonetts.core.audio.buffer.BufferedPlayback
import com.phonetts.core.audio.buffer.GeneratedAudio
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * The single authoritative snapshot for a [SpeechSession] - the SSOT the UI renders (mission:
 * "one observable snapshot ... never mirrored"). Every field is *derived* reactively from the
 * producer and whichever consumer run is current; nothing here is ever written to directly, so
 * it cannot end up internally inconsistent the way two independently-toggled flags can (see
 * [SpeechSession]'s class doc for the exact bug this replaces).
 *
 * @property isGenerating true from [SpeechSession.start] until the producer finishes (successfully
 *   or not) - see [isComplete]/[generationError] to tell which.
 * @property sentencesReady how many sentences the producer has appended so far. Monotonic.
 * @property totalSentences the caller-supplied hint from [SpeechSession.start], or null if unknown.
 * @property isComplete true once the producer has finished with no error.
 * @property generationError non-null once the producer has failed; sentences generated before the
 *   failure are still in the buffer and still playable.
 * @property isPlaying true while the current consumer run is actively delivering audio (attached,
 *   not paused, not stopped/finished).
 * @property isPaused true while the current consumer run is paused.
 * @property currentSentence the sentence index the current (or most recent) consumer run has
 *   reached - the buffer index, not a document-absolute index (see [SpeechSession] class doc).
 */
data class SpeechSessionState(
    val isGenerating: Boolean = false,
    val sentencesReady: Int = 0,
    val totalSentences: Int? = null,
    val isComplete: Boolean = false,
    val generationError: Throwable? = null,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val currentSentence: Int = 0,
)

// The producer-side half of the combine that builds [SpeechSessionState] - everything [state]
// needs from [SentenceProducer] plus whether [SpeechSession.start] has been called yet (before
// that, GeneratedAudio.complete is legitimately false, which alone would misreport isGenerating).
private data class ProducerSnapshot(
    val started: Boolean,
    val ready: Int,
    val complete: Boolean,
    val failure: Throwable?,
    val total: Int?,
) {
    fun toState(consumer: ConsumerSnapshot) =
        SpeechSessionState(
            isGenerating = started && !complete,
            sentencesReady = ready,
            totalSentences = total,
            isComplete = complete && failure == null,
            generationError = failure,
            isPlaying = consumer.isPlaying,
            isPaused = consumer.isPaused,
            currentSentence = consumer.currentSentence,
        )
}

// The consumer-side half: whatever the CURRENT run reports, or all-false/zero before the first
// play() call. Never a blend of an old and a new run - see [SpeechSession.consumerSnapshot].
private data class ConsumerSnapshot(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val currentSentence: Int = 0,
)

// One consumer attempt: a fresh BufferedPlayback plus the buffer index it started from, so its
// position can be reported/resumed as startIndex + playedChunks. [activeState] is THIS run's own
// flag - flipped false only in the coroutine launched for THIS run - never a field shared with
// whatever run supersedes it, which is what makes a stale run's finally-block write harmless (see
// [SpeechSession.state]: nothing reads a superseded run's flows anymore).
private class ConsumerRun(
    val playback: BufferedPlayback,
    val startIndex: Int,
) {
    private val activeState = MutableStateFlow(true)

    /** Buffer index this run has reached: where it started plus what it has delivered. */
    val currentPosition: Int get() = startIndex + playback.playedChunks.value

    fun markFinished() {
        activeState.value = false
    }

    val snapshot: Flow<ConsumerSnapshot> =
        combine(playback.playedChunks, playback.isPaused, activeState) { played, paused, active ->
            ConsumerSnapshot(
                isPlaying = active && !paused,
                isPaused = active && paused,
                currentSentence = startIndex + played,
            )
        }
}

/**
 * Owns ONE document's generation-plus-playback lifecycle as two collaborators that never
 * reference each other - the repo owner's directive, verbatim: "play should know is the next
 * sentence ready - generation shouldn't know if playback occurs at all."
 *
 *  - The **producer** ([producer], a [SentenceProducer]) drains the ONE `VoiceEngine.synthesize()`
 *    flow into a retained [GeneratedAudio]. [start] is its entire public surface: no pause, no
 *    listener awareness, no playback position.
 *  - The **consumer** is a fresh [BufferedPlayback] per [play] call - it already knows how to
 *    pause/resume without touching generation, and how to start from any buffer index with no
 *    re-synthesis (see that class's doc). [SpeechSession] only decides WHICH consumer run is
 *    current; it never wires the two sides together beyond that.
 *
 * [state] is the single authoritative snapshot. It is never written to directly - every field is
 * derived reactively from the producer's flows and whichever [ConsumerRun] is current. Replacing
 * the current run (a seek, a fresh [play] after [stop]) is expressed as swapping one
 * [MutableStateFlow] reference, and [state] follows it with `flatMapLatest`, so a superseded run's
 * late-arriving completion can never again feed into what callers observe.
 *
 * That is the fix for the exact bug a device report caught: after a skip, the UI showed "not
 * playing" while audio continued, because the OLD run's completion callback and the NEW run's
 * start each independently wrote `playing = ...` into one shared, imperatively-mutated flag - a
 * plain data race between two coroutines with no ordering guarantee between them. Here there is no
 * shared flag to disagree on: only the run [activeRun] currently points at is ever listened to, so
 * a stale run's writes land on a [ConsumerRun] nothing subscribes to anymore.
 *
 * Indices are always LOCAL buffer indices into the one retained [GeneratedAudio] (0 = the first
 * sentence this session's producer will generate) - not an absolute position in some larger
 * document. A caller that resumes a saved position from a previous session (e.g. re-opening a
 * book) is expected to translate that the same way [SpeechSession] itself is scoped: one session
 * per (text, voice, params) key, exactly as today's `TtsViewModel.GenSession`/`GenKey` already do -
 * that translation is an application policy, not something a generic session primitive should
 * hardcode.
 *
 * Pure Kotlin, no Android. [scope] and [dispatcher] are both injected - this class never hardcodes
 * `Dispatchers.Default`/`viewModelScope`, so tests can run it under `TestScope`/`UnconfinedTestDispatcher`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeechSession(
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val sampleRate: Int,
    audio: GeneratedAudio = GeneratedAudio(),
) : Closeable {
    // A session owns its collectors as well as generation/playback. Closing it must release all
    // of them without cancelling the caller's (usually ViewModel-wide) scope.
    private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)

    /** The producer half - see [SentenceProducer]. Exposed for direct inspection if a caller needs it. */
    val producer = SentenceProducer(audio)

    private val startedState = MutableStateFlow(false)
    private val totalHintState = MutableStateFlow<Int?>(null)

    // The current consumer run, or null before the first [play]. See the class doc for why [state]
    // following THIS reference (rather than any field on a specific run) is what makes stale writes
    // harmless.
    private val activeRun = MutableStateFlow<ConsumerRun?>(null)

    private var genJob: Job? = null
    private var playJob: Job? = null

    private val producerSnapshot: Flow<ProducerSnapshot> =
        combine(
            startedState,
            producer.ready,
            producer.complete,
            producer.failure,
            totalHintState,
        ) { started, ready, complete, failure, total ->
            ProducerSnapshot(started, ready, complete, failure, total)
        }

    // flatMapLatest re-subscribes whenever [activeRun] changes and, crucially, CANCELS the
    // collection of the previous run's [ConsumerRun.snapshot] - the previous run's flows simply
    // are not observed anymore, no matter how its coroutine's teardown is scheduled.
    private val consumerSnapshot: Flow<ConsumerSnapshot> =
        activeRun.flatMapLatest { run -> run?.snapshot ?: flowOf(ConsumerSnapshot()) }

    /** The one SSOT snapshot - see the class doc. Safe to collect from construction onward. */
    val state: StateFlow<SpeechSessionState> =
        combine(producerSnapshot, consumerSnapshot) { p, c -> p.toState(c) }
            .stateIn(sessionScope, SharingStarted.Eagerly, SpeechSessionState())

    init {
        // Wait for cancelled producers/consumers to finish before deleting their spill file.
        sessionJob.invokeOnCompletion { producer.audio.close() }
    }

    /**
     * Start the producer: drive [flow] - the caller's already-configured `synthesize()` call,
     * untouched (CLAUDE.md rule 3: one generation path) - into the retained buffer. Callable
     * exactly once per session: a new document, voice, or parameter set gets a NEW [SpeechSession],
     * never a restarted one - [check] enforces that so a caller-side bug (accidentally calling this
     * twice) fails loudly instead of silently starting a second producer.
     *
     * [totalSentencesHint] is an optional, caller-known sentence count purely for
     * [SpeechSessionState.totalSentences]; pass null (the default) if it isn't known.
     */
    fun start(
        flow: Flow<FloatArray>,
        totalSentencesHint: Int? = null,
    ) {
        check(sessionJob.isActive) { "SpeechSession is closed" }
        check(genJob == null) { "SpeechSession.start() called more than once - start a new SpeechSession instead" }
        totalHintState.value = totalSentencesHint
        startedState.value = true
        genJob = sessionScope.launch(dispatcher) { producer.run(flow) }
    }

    /**
     * Play (or seek to) buffer index [fromIndex], defaulting to wherever the previous run left off
     * (0 if playback never started). Always begins a FRESH consumer run: used for the initial
     * play, and for every seek/skip alike. If [fromIndex] is already generated, playback starts
     * immediately with no re-synthesis; if it isn't yet, the new run simply waits for the producer
     * to reach it (the producer is not restarted, redirected, or even aware this happened). The
     * previous run (if any) is torn down first - the producer is left completely alone either way.
     */
    fun play(
        sink: AudioSink,
        fromIndex: Int? = null,
    ) {
        check(sessionJob.isActive) { "SpeechSession is closed" }
        require(fromIndex == null || fromIndex >= 0) { "negative playback index" }
        val startIndex = fromIndex ?: (activeRun.value?.currentPosition ?: 0)
        teardownCurrentRun()
        val run = ConsumerRun(BufferedPlayback(), startIndex)
        activeRun.value = run
        playJob =
            sessionScope.launch(dispatcher) {
                try {
                    run.playback.play(producer.audio, sampleRate, sink, fromIndex = startIndex)
                } finally {
                    run.markFinished()
                }
            }
    }

    /** Reads identically to [play] at call sites that are conceptually seeking, not (re)starting. */
    fun seek(
        sink: AudioSink,
        index: Int,
    ) = play(sink, index)

    /** Pause the CURRENT consumer run. The producer (if still running) keeps filling the buffer. */
    fun pause() {
        activeRun.value?.playback?.pause()
    }

    /** Resume the current run from exactly where it paused. Never re-synthesizes. */
    fun resume() {
        activeRun.value?.playback?.resume()
    }

    /**
     * Stop the current consumer run for good. The producer is untouched - it keeps generating (or
     * stays finished) so the NEXT [play] can pick up from wherever this one stopped, losing nothing
     * already produced.
     */
    fun stop() {
        teardownCurrentRun()
    }

    /**
     * Abandon this session entirely: stop the consumer, cancel the producer, and release the
     * buffer's resources once the cancelled jobs finish. Use this when the document,
     * voice, or parameters change - never for pause/seek, which must leave generation running.
     */
    override fun close() {
        teardownCurrentRun()
        genJob?.cancel()
        genJob = null
        sessionJob.cancel()
    }

    private fun teardownCurrentRun() {
        activeRun.value?.playback?.stop()
        playJob?.cancel()
        playJob = null
    }
}
