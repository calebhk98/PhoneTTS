package com.phonetts.app.speech

import com.phonetts.core.audio.AudioSink
import kotlinx.coroutines.CancellationException

/**
 * Wraps [delegate] so a hardware/sink-level failure (e.g. `AudioTrack.write` erroring out) is
 * caught and reported via [onFailure] instead of propagating uncaught.
 *
 * [com.phonetts.core.speech.SpeechSession]'s consumer coroutine (`ConsumerRun` in that class) has
 * no catch around its call into [com.phonetts.core.audio.buffer.BufferedPlayback.play] - only the
 * PRODUCER side ([com.phonetts.core.speech.SentenceProducer]) is documented to catch `Throwable`
 * broadly. Before this session's migration, a playback-sink exception was caught by the
 * `runCatching { withContext(IO) { playback.play(...) } }` every call site wrapped around itself;
 * adopting [SpeechSession] moves that call inside the session, where nothing wraps it. Without this
 * wrapper, a genuine `AudioTrack` failure would surface as an uncaught exception in the ViewModel's
 * `viewModelScope` (backed by a bare `SupervisorJob`, no installed
 * [kotlinx.coroutines.CoroutineExceptionHandler]) instead of the friendly "Playback failed: …"
 * status message every screen used to show - a real behavior change this class exists to prevent
 * (see the mission report for why `core/speech` itself couldn't be the place to fix this instead).
 *
 * Once [onFailure] has fired once for a given instance, every further call is a no-op - the
 * underlying track is presumed gone, so there's nothing left to usefully retry.
 */
class ReportingSink(
    private val delegate: AudioSink,
    private val onFailure: (Throwable) -> Unit,
) : AudioSink {
    private var failed = false

    override fun onFormat(sampleRate: Int) = guard { delegate.onFormat(sampleRate) }

    override fun onChunk(samples: FloatArray) = guard { delegate.onChunk(samples) }

    override fun pause() = guard { delegate.pause() }

    override fun resume() = guard { delegate.resume() }

    override fun onEnd() = guard { delegate.onEnd() }

    private inline fun guard(block: () -> Unit) {
        if (failed) return
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Throwable) {
            failed = true
            onFailure(e)
        }
    }
}
