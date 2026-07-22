package com.phonetts.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.phonetts.core.audio.AudioSink
import com.phonetts.core.audio.Pcm16
import kotlin.math.max

/**
 * The real-time [AudioSink]: plays the streamed `Flow<FloatArray>` chunks through an [AudioTrack]
 * at the descriptor's sample rate (received via [onFormat] — never a hardcoded constant).
 *
 * Prefers 32-bit float PCM (no quantization needed for the engines' `FloatArray` output), but some
 * budget devices don't expose a float-PCM output path — `AudioTrack.Builder.build()` can throw, or
 * the built track can come back in `STATE_UNINITIALIZED`, on such hardware. [onFormat] falls back to
 * 16-bit PCM (via the same [Pcm16] quantization every file encoder already uses) rather than leaving
 * playback silently dead — this is the one seam PhoneTTS re-quantizes at, and only for the OUTPUT
 * device path, not the generation/export flow (rule 2 is about the model's Speed parameter, not the
 * unrelated question of which PCM width the speaker driver accepts).
 */
class AudioTrackSink : AudioSink {
    private var track: AudioTrack? = null
    private var useFloatEncoding = true
    private var sampleRateHz = 0

    // Total frames (== samples, mono) actually handed to AudioTrack.write() this session — only
    // counts what a write call reported as consumed, never the requested chunk size, so a
    // short/partial write (see writeAllFloat/writeAllShort) is never over-counted. This is the
    // target [awaitPlaybackDrained] polls the hardware playback head against before onEnd() is
    // allowed to release() the track, so the tail of a clip can never be torn off mid-flight.
    private var framesWritten = 0L

    override fun onFormat(sampleRate: Int) {
        // Every real call site routes through stop() first, but release defensively so a missed
        // discipline never leaks a native track instead of silently doing nothing. This is the
        // "starting a new session" case, so an immediate (flush) release is correct here — any
        // leftover audio from a track a caller forgot to stop is not this session's to play.
        releaseImmediately()
        sampleRateHz = sampleRate
        framesWritten = 0L
        val floatTrack = buildTrack(sampleRate, AudioFormat.ENCODING_PCM_FLOAT)
        if (floatTrack != null) {
            track = floatTrack
            useFloatEncoding = true
            return
        }
        // Float PCM unsupported on this device (getMinBufferSize error, build()/play() threw, or the
        // track never reached STATE_INITIALIZED) — fall back to 16-bit rather than play nothing.
        useFloatEncoding = false
        track =
            buildTrack(sampleRate, AudioFormat.ENCODING_PCM_16BIT)
                ?: error("AudioTrack failed to initialize at ${sampleRate}Hz (both float and 16-bit PCM)")
    }

    // Attempts to build, initialize, and start an AudioTrack at [encoding]. Returns null (never
    // throws) on any failure so the caller can fall back to a different encoding instead of leaving
    // playback silently dead.
    private fun buildTrack(
        sampleRate: Int,
        encoding: Int,
    ): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, encoding)
        if (minBuffer <= 0) return null // ERROR / ERROR_BAD_VALUE: this encoding isn't usable here
        val built = runCatching { newTrack(sampleRate, encoding, minBuffer) }.getOrNull() ?: return null
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            built.release()
            return null
        }
        if (runCatching { built.play() }.isFailure) {
            built.release()
            return null
        }
        return built
    }

    private fun newTrack(
        sampleRate: Int,
        encoding: Int,
        minBuffer: Int,
    ): AudioTrack {
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) BYTES_PER_FLOAT else BYTES_PER_SHORT
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuffer, sampleRate * bytesPerSample / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // Streaming during generation (issue: "doesn't play while generating") means real gaps of
    // several SECONDS can open up between onChunk() calls whenever the model is slower than
    // real-time — BufferedPlayback correctly waits at the live edge for the next sentence (see
    // core BufferedPlayback/GeneratedAudio, exercised concurrently on real dispatchers by
    // BufferedAudioTest), so the AudioTrack's ring buffer runs dry and sits idle between chunks.
    // That idle-then-resume pattern is exactly when a stale write() can return a transient SHORT
    // count that is NOT a real barge-in (see writeAllFloat/writeAllShort below) — re-arming the
    // track defensively here means a chunk that arrives after a long generation gap always finds
    // the track actually in PLAYSTATE_PLAYING before writing to it, instead of silently writing
    // into (or bailing out of) a track the system parked while nothing was flowing.
    override fun onChunk(samples: FloatArray) {
        val active = track ?: return
        ensurePlaying(active)
        if (useFloatEncoding) {
            writeAllFloat(active, samples)
        } else {
            writeAllShort(active, ShortArray(samples.size) { i -> Pcm16.toShort(samples[i]) })
        }
    }

    // A track that is still THIS sink's current one (i.e. no barge-in has superseded it — see
    // [writeAllFloat]/[writeAllShort] below) but isn't in PLAYSTATE_PLAYING re-arms itself before
    // the write: a long idle gap between chunks (a slow model, or the live-edge wait itself) can
    // leave AudioTrack parked by the system for reasons that never went through THIS sink's own
    // stop()/onFormat() (e.g. an OS-level audio interruption), and without this, every following
    // write() on that track would keep returning a short/zero count forever — audible as "played
    // the first sentence or two, then silence for the rest of generation" rather than a real stop.
    // A genuinely barge-in-released track is never reached here (onChunk's `track` lookup above
    // already returns early once stop() has nulled it), so this can't resurrect a deliberate stop.
    private fun ensurePlaying(active: AudioTrack) {
        if (active.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { active.play() }
    }

    // AudioTrack.write(..., WRITE_BLOCKING) is documented to block until every requested sample is
    // consumed OR the track is paused/stopped/released out from under it (which can yield a SHORT
    // positive count, not just a negative error code) — e.g. a concurrent stop() (barge-in) racing a
    // still-in-flight write. Treating any non-negative return as "fully written" (the previous
    // behaviour) silently dropped the unwritten tail of that chunk, one contributor to audio cutting
    // out before the end. Loop until the whole chunk is actually consumed, a real error occurs, or
    // the track stops accepting data entirely.
    //
    // A written==0 result used to be treated unconditionally as "this must be a barge-in, give up
    // silently" — but WRITE_BLOCKING can also return 0 transiently while the track is still ours
    // and still PLAYING (e.g. right after a long live-edge stall while streaming during generation,
    // per the class-level note above), and unlike a real barge-in nothing else ever retries that
    // write. BufferedPlayback has no idea the sink silently dropped a chunk — it advances its index
    // the moment sink.onChunk() RETURNS, whether or not any samples actually reached the speaker —
    // so one mistaken bail here reads as "stopped playing partway through, or never started" for
    // the rest of the session even though generation keeps going. Only treat it as a genuine
    // barge-in (and stop retrying) once `track` has actually moved on from [active] — i.e. some
    // other call already ran stop()/onFormat() out from under this write — bounded so a track stuck
    // returning 0 for a real reason still surfaces as a clear failure instead of spinning forever.
    private fun writeAllFloat(
        active: AudioTrack,
        samples: FloatArray,
    ) {
        var offset = 0
        var zeroStreak = 0
        while (offset < samples.size) {
            val written = active.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) failWrite(written)
            if (written == 0) {
                zeroStreak = handleZeroProgress(active, zeroStreak) ?: return
                continue
            }
            zeroStreak = 0
            offset += written
            framesWritten += written
        }
    }

    private fun writeAllShort(
        active: AudioTrack,
        shorts: ShortArray,
    ) {
        var offset = 0
        var zeroStreak = 0
        while (offset < shorts.size) {
            val written = active.write(shorts, offset, shorts.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) failWrite(written)
            if (written == 0) {
                zeroStreak = handleZeroProgress(active, zeroStreak) ?: return
                continue
            }
            zeroStreak = 0
            offset += written
            framesWritten += written
        }
    }

    // Shared zero-progress policy for both write loops above. Returns the new streak count to keep
    // retrying with, or null when the caller should give up (a real barge-in — `track` has moved on
    // from [active] — or the retry budget is exhausted, which surfaces as a hard failure via
    // failWrite() rather than silently returning as if the chunk had played).
    private fun handleZeroProgress(
        active: AudioTrack,
        zeroStreak: Int,
    ): Int? {
        if (track !== active) return null // a real barge-in: stop()/onFormat() already moved on
        val nextStreak = zeroStreak + 1
        if (nextStreak >= MAX_ZERO_WRITE_RETRIES) failWrite(0)
        return nextStreak
    }

    // Instant, hardware-level pause (issue: Pause didn't stop until the current sentence finished).
    // AudioTrack.pause() halts output immediately but KEEPS the buffered PCM, so a WRITE_BLOCKING
    // write in flight simply blocks (the ring buffer stops draining) until resume(), and no queued
    // audio is lost — the opposite of stop()'s flush. A no-op if there's no live track.
    override fun pause() {
        track?.let { active -> runCatching { active.pause() } }
    }

    // Resume after [pause]: re-arm the track so the parked write continues and playback picks up
    // exactly where it stopped. Guarded so resuming a track that's somehow already playing is a no-op.
    override fun resume() {
        track?.let { active ->
            if (active.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { active.play() }
        }
    }

    // A negative return is one of AudioTrack's ERROR_* codes (bad state, dead object, invalid
    // operation, ...) — surfaced instead of silently dropping the rest of the utterance; the
    // ViewModel's runCatching around playback.play() turns this into a "Playback failed" status.
    private fun failWrite(written: Int): Nothing {
        releaseImmediately()
        error("AudioTrack.write failed with error code $written")
    }

    // Natural end of a fully-delivered utterance (spec: no error, no barge-in). WRITE_BLOCKING only
    // guarantees the last chunk's samples were copied into the AudioTrack's internal ring buffer —
    // that buffer is at least half a second deep (see BYTES_PER_FLOAT/BYTES_PER_SHORT sizing below),
    // so audio can still be sitting there, not yet physically played, the instant the last onChunk()
    // call returns. An earlier implementation funnelled onEnd() through the same release() used for
    // user-initiated stop, which calls flush() — and AudioTrack.flush() is documented to DISCARD any
    // audio that has been written but not yet presented. That silently truncated up to that whole
    // buffer's worth of audio off the end of EVERY playback, not just an occasional race: for a short
    // utterance (a single sentence, or a quick fresh Play before enough chunks have queued up) the
    // entire clip could still be sitting unpresented when onEnd() fired, so the trim was total —
    // "pressing Play produces no sound" — while a long cached replay only lost an inaudible sliver off
    // the tail after minutes of already-drained playback, which read as "replay works". Switching to a
    // non-flushing stop() fixed that, but NOT the "last word missing" report that persisted: stop() is
    // only documented to let MODE_STREAM's queued data keep draining — that draining happens
    // asynchronously in the HAL, on its own timeline, and calling release() right after stop() (as
    // before) can tear the native track down before the OEM driver has actually finished presenting
    // that tail, cutting it off regardless of flush(). [awaitPlaybackDrained] makes the wait explicit
    // instead of trusting stop()'s timing, so release() never runs until the hardware has genuinely
    // caught up to everything this sink wrote. stop() (below, the barge-in path) keeps flush() — that
    // IS the intended immediate-cutoff behaviour (issue #45) — only the natural-completion path here
    // waits for the queued audio to finish first.
    override fun onEnd() {
        val active = track ?: return
        awaitPlaybackDrained(active)
        // A barge-in (stop()/onFormat()) may have superseded this track while draining — it already
        // tore the track down itself, so there is nothing left for this call to do.
        if (track !== active) return
        runCatching { active.stop() }
        active.release()
        track = null
    }

    // Polls [active]'s hardware playback head until it has caught up with [framesWritten] (everything
    // this sink has handed to AudioTrack this session), so [onEnd] never releases the track while
    // audio is still in flight. Bounded by the outstanding audio's own duration plus a fixed grace
    // margin, so a genuinely stuck head position (a hardware fault, not just OEM-driver slack) can
    // never hang playback forever — and it exits immediately once a barge-in supersedes this track,
    // since the tail no longer matters at all then.
    private fun awaitPlaybackDrained(active: AudioTrack) {
        val target = framesWritten
        val remainingFrames = (target - headPositionFrames(active)).coerceAtLeast(0L)
        val remainingMillis = if (sampleRateHz > 0) remainingFrames * MILLIS_PER_SECOND / sampleRateHz else 0L
        val deadlineNanos = System.nanoTime() + (remainingMillis + DRAIN_GRACE_MILLIS) * NANOS_PER_MILLI
        while (track === active && headPositionFrames(active) < target && System.nanoTime() < deadlineNanos) {
            runCatching { Thread.sleep(DRAIN_POLL_MILLIS) }
        }
    }

    // AudioTrack.getPlaybackHeadPosition() is documented to behave like an unsigned 32-bit frame
    // counter, so a long-running track's raw Int can go negative long before framesWritten (a Long)
    // does. Widen it the same way this codebase already widens the WAV header's byte counts, so a
    // wrapped value never reads as "already past the target" and ends the drain wait early.
    private fun headPositionFrames(active: AudioTrack): Long = active.playbackHeadPosition.toLong() and UINT_MASK

    /** Stop playback immediately (e.g. the user switched models mid-utterance). */
    fun stop() = releaseImmediately()

    // Defensive/barge-in teardown: discards anything queued but not yet heard (via flush()). Used by
    // [stop] (the deliberate immediate-cutoff path, issue #45) and defensively at the top of
    // [onFormat] in case a previous session's track was never cleaned up — deliberately NOT used by
    // [onEnd] (natural completion), see its kdoc above.
    private fun releaseImmediately() {
        track?.let { active ->
            runCatching {
                active.pause()
                active.flush()
                active.stop()
            }
            active.release()
        }
        track = null
    }

    companion object {
        // How many consecutive transient (not-a-barge-in) zero-progress writes to retry before
        // giving up and surfacing a real failure via failWrite() — bounds handleZeroProgress()'s
        // retry loop so a track stuck returning 0 for some other genuine reason doesn't spin the
        // playback coroutine forever instead of reporting "Playback failed".
        private const val MAX_ZERO_WRITE_RETRIES = 50

        private const val BYTES_PER_FLOAT = 4
        private const val BYTES_PER_SHORT = 2

        // [awaitPlaybackDrained] tuning: poll cheaply and often (audio, not UI, so 15ms is not
        // perceptible), and always allow at least this much extra grace beyond the computed remaining
        // duration for OEM-driver/scheduling slack before giving up and releasing anyway.
        private const val DRAIN_POLL_MILLIS = 15L
        private const val DRAIN_GRACE_MILLIS = 750L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val UINT_MASK = 0xffffffffL
    }
}
