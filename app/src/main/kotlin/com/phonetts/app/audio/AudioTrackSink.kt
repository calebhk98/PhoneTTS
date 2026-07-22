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

    override fun onFormat(sampleRate: Int) {
        // Every real call site routes through stop() first, but release defensively so a missed
        // discipline never leaks a native track instead of silently doing nothing. This is the
        // "starting a new session" case, so an immediate (flush) release is correct here — any
        // leftover audio from a track a caller forgot to stop is not this session's to play.
        releaseImmediately()
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

    override fun onChunk(samples: FloatArray) {
        val active = track ?: return
        if (useFloatEncoding) {
            writeAllFloat(active, samples)
        } else {
            writeAllShort(active, ShortArray(samples.size) { i -> Pcm16.toShort(samples[i]) })
        }
    }

    // AudioTrack.write(..., WRITE_BLOCKING) is documented to block until every requested sample is
    // consumed OR the track is paused/stopped/released out from under it (which can yield a SHORT
    // positive count, not just a negative error code) — e.g. a concurrent stop() (barge-in) racing a
    // still-in-flight write. Treating any non-negative return as "fully written" (the previous
    // behaviour) silently dropped the unwritten tail of that chunk, one contributor to audio cutting
    // out before the end. Loop until the whole chunk is actually consumed, a real error occurs, or
    // the track stops accepting data entirely (repeated zero-progress writes) so a barge-in still
    // terminates instead of spinning.
    private fun writeAllFloat(
        active: AudioTrack,
        samples: FloatArray,
    ) {
        var offset = 0
        while (offset < samples.size) {
            val written = active.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) failWrite(written)
            if (written == 0) return // track stopped accepting data mid-write (e.g. a concurrent barge-in)
            offset += written
        }
    }

    private fun writeAllShort(
        active: AudioTrack,
        shorts: ShortArray,
    ) {
        var offset = 0
        while (offset < shorts.size) {
            val written = active.write(shorts, offset, shorts.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) failWrite(written)
            if (written == 0) return
            offset += written
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
    // call returns. The previous implementation funnelled onEnd() through the same release() used for
    // user-initiated stop, which calls flush() — and AudioTrack.flush() is documented to DISCARD any
    // audio that has been written but not yet presented. That silently truncated up to that whole
    // buffer's worth of audio off the end of EVERY playback, not just an occasional race: for a short
    // utterance (a single sentence, or a quick fresh Play before enough chunks have queued up) the
    // entire clip could still be sitting unpresented when onEnd() fired, so the trim was total —
    // "pressing Play produces no sound" — while a long cached replay only lost an inaudible sliver off
    // the tail after minutes of already-drained playback, which read as "replay works". stop()
    // (below) keeps flush() — that IS the intended immediate-cutoff behaviour for a deliberate
    // barge-in (issue #45) — only the natural-completion path drops it so queued audio finishes.
    override fun onEnd() {
        track?.let { active ->
            runCatching { active.stop() } // MODE_STREAM: already-queued audio keeps playing to completion.
            active.release()
        }
        track = null
    }

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
        private const val BYTES_PER_FLOAT = 4
        private const val BYTES_PER_SHORT = 2
    }
}
