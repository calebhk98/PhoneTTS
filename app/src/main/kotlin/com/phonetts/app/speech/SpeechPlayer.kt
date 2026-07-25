package com.phonetts.app.speech

import com.phonetts.app.audio.AudioTrackSink
import com.phonetts.core.audio.AudioSink
import com.phonetts.core.speech.SpeechSession
import kotlinx.coroutines.flow.first

/**
 * The ONE physical audio output every screen that can play generated speech shares - the fix for
 * "start reading on Home, background it, open Compare and hit preview, and you get two audio
 * streams" (this session's mission). Before this, [com.phonetts.app.ui.TtsViewModel],
 * `CompareViewModel` and `MixVoicesViewModel` each built their OWN `AudioTrackSink`, so nothing
 * stopped two of them writing to two independent `AudioTrack`s at once - each screen's own
 * transport only ever barged in on ITSELF.
 *
 * [SpeechPlayer] is a thin barge-in gate in front of the shared [AudioTrackSink]: [play] remembers
 * which [SpeechSession] is currently attached and, whenever a DIFFERENT session asks to play,
 * stops the previous one first - so starting playback anywhere always wins over whatever was
 * audible before, exactly like a single-track media player, regardless of which screen it came
 * from. [SpeechSession] itself only ever reasons about its own consumer run (see its class doc);
 * this class is what makes "one at a time" true ACROSS sessions/screens, not just within one.
 *
 * A process-wide [shared] instance is the default every ViewModel constructor below falls back
 * to, so the fix applies with no `AppGraph`/`MainActivity` wiring required. For real dependency
 * injection (a fake in a test, or a future non-singleton lifecycle), pass an instance in instead -
 * see this class's own kdoc note in the mission report for the one-line `AppGraph` addition that
 * would replace the singleton default with a graph-provided instance.
 */
class SpeechPlayer(
    private val hardwareSink: AudioTrackSink = AudioTrackSink(),
) {
    // Whichever SpeechSession is currently attached to [hardwareSink] - the ONE thing that may be
    // audible right now. Null before anything has ever played, or once [stop] tears it down.
    private var current: SpeechSession? = null

    /** The shared raw output. Wrap it (e.g. a playback-only `TransformingSink`, issue #43) per call
     * for a transform that should apply to just one screen's playback - the wrapper still ends up
     * writing to this same physical track, so the barge-in guarantee still holds. */
    val sink: AudioSink get() = hardwareSink

    /**
     * Start (or seek) [session] playing through [outputSink] (defaults to the shared raw [sink]).
     * If a DIFFERENT session was the current owner, it is stopped first - this is the barge-in:
     * whichever screen calls [play] most recently wins the shared output. A repeat call with the
     * SAME [session] (a seek/skip on the screen that already owns playback) never stops itself.
     */
    fun play(
        session: SpeechSession,
        outputSink: AudioSink = sink,
        fromIndex: Int? = null,
    ) {
        val previous = current
        if (previous != null && previous !== session) previous.stop()
        current = session
        session.play(outputSink, fromIndex)
    }

    /** Pause whichever session currently owns playback. A no-op if nothing is attached. */
    fun pause(): Unit = run { current?.pause() }

    /** Resume whichever session currently owns playback. A no-op if nothing is attached. */
    fun resume(): Unit = run { current?.resume() }

    /**
     * Immediate hardware stop (issue #45: a barge-in must take effect at once, not at the next
     * chunk boundary) plus consumer teardown. If [session] is given and it is NOT the current
     * owner (this screen was already barged out by another one), this is a safe no-op - the
     * hardware belongs to whoever barged in, and this screen has nothing left to stop.
     */
    fun stop(session: SpeechSession? = null) {
        if (session != null && current !== session) return
        current?.stop()
        hardwareSink.stop()
        current = null
    }

    /** Forget [session] as the current owner without touching the hardware - for teardown
     * (`onCleared`) after the session itself has already been closed/stopped. */
    fun release(session: SpeechSession) {
        if (current === session) current = null
    }

    /**
     * Attach [session] (WITHOUT restarting an already-finished playback run - unlike [play], this
     * never calls `session.play()`), then suspend until every chunk [session]'s buffer holds has
     * been delivered, or the run is stopped/barged-in-on out from under it. For the sequential
     * "play A to completion, then generate/play B" screens (Compare's A/B and tournament slots)
     * that need to know a play-to-completion call is genuinely done before moving on, without
     * SpeechSession exposing its internal consumer job.
     *
     * Relies on the fact that these callers only ever play a buffer whose generation ALREADY
     * finished: [session.producer.audio.count] is therefore a fixed target, so waiting for
     * `currentSentence` to reach it is exact and race-free (unlike racing on the isPlaying/isPaused
     * flags themselves, which can already have flipped back to false by the time a caller starts
     * observing a very short clip).
     */
    suspend fun playToCompletion(
        session: SpeechSession,
        outputSink: AudioSink = sink,
    ) {
        val total = session.producer.audio.count.value
        play(session, outputSink)
        if (total <= 0) return
        session.state.first { it.currentSentence >= total }
    }

    companion object {
        /** The process-wide default every ViewModel falls back to - see this class's kdoc. */
        val shared: SpeechPlayer by lazy { SpeechPlayer() }
    }
}
