package com.phonetts.core.speech

import com.phonetts.core.audio.buffer.GeneratedAudio
import com.phonetts.core.audio.buffer.collectInto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The producer half of a [SpeechSession] (the repo owner's directive: generation shouldn't know
 * whether playback occurs at all). A [SentenceProducer] drives the ONE `VoiceEngine.synthesize()`
 * flow (spec §6.1, CLAUDE.md rule 3) into a retained [GeneratedAudio], in order, and nothing else.
 * It has:
 *
 *  - no pause - it always runs at full speed, because a paused *listener* is not this class's
 *    concern (that's [com.phonetts.core.audio.buffer.BufferedPlayback], the consumer side);
 *  - no notion of "is anyone listening" - [run] does not take a sink, an index, or a playback
 *    reference of any kind;
 *  - no playback position - its only outputs are how many sentences are ready ([ready]), whether
 *    it is done ([complete]), and whether it failed ([failure]).
 *
 * Fail-closed: a mid-document failure is captured in [failure] rather than thrown out of [run]
 * uncaught, and [GeneratedAudio.markComplete] still runs (via
 * [com.phonetts.core.audio.buffer.collectInto]) so every sentence generated before the failure
 * stays exactly as playable as if generation had finished normally - nothing already produced is
 * discarded, and a consumer parked at the live edge is released instead of hanging forever.
 * Cancelling the coroutine calling [run] (a deliberate stop of the whole session) is NOT swallowed
 * as a failure - it propagates as [CancellationException] like any other suspend function, per
 * structured-concurrency convention - but [GeneratedAudio.markComplete] still runs first via the
 * same `finally`, so a cancelled generation leaves its audio exactly as usable too.
 */
class SentenceProducer(
    val audio: GeneratedAudio = GeneratedAudio(),
) {
    private val failureState = MutableStateFlow<Throwable?>(null)

    /** Sentences generated so far. Grows monotonically as [run] proceeds; never shrinks. */
    val ready: StateFlow<Int> get() = audio.count

    /** True once generation has finished - successfully or not (see [failure] to tell which). */
    val complete: StateFlow<Boolean> get() = audio.complete

    /** Non-null once generation has failed. Sentences generated before the failure remain in [audio]. */
    val failure: StateFlow<Throwable?> = failureState.asStateFlow()

    /**
     * Drive [flow] - the caller's already-configured `synthesize(text, voiceId, params)` call,
     * passed through untouched so this can never become a second synthesis path - into [audio]
     * until it completes, fails, or the calling coroutine is cancelled. Suspends for as long as
     * generation takes; callers launch this as their own job so it runs concurrently with (and
     * fully independently of) any consumer.
     *
     * Deliberately catches `Throwable`: a producer must survive ANY engine failure and record it, so
     * whatever audio it already produced stays playable and the consumer can report why the rest
     * never arrived. Narrowing it would let an unanticipated engine error escape and take the
     * already-generated audio with it - the exact "audio is lost" failure this class exists to
     * prevent. Cancellation is rethrown first, since that is control flow, not a failure.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun run(flow: Flow<FloatArray>) {
        try {
            flow.collectInto(audio)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            failureState.value = failure
        }
    }
}
