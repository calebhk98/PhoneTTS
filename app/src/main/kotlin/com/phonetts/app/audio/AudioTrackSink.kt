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
        // discipline never leaks a native track instead of silently doing nothing.
        release()
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
        val written =
            if (useFloatEncoding) {
                active.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            } else {
                val shorts = ShortArray(samples.size) { i -> Pcm16.toShort(samples[i]) }
                active.write(shorts, 0, shorts.size, AudioTrack.WRITE_BLOCKING)
            }
        if (written >= 0) return
        // A negative return is one of AudioTrack's ERROR_* codes (bad state, dead object, invalid
        // operation, ...) — surface it instead of silently dropping the rest of the utterance; the
        // ViewModel's runCatching around playback.play() turns this into a "Playback failed" status.
        release()
        error("AudioTrack.write failed with error code $written")
    }

    override fun onEnd() = release()

    /** Stop playback immediately (e.g. the user switched models mid-utterance). */
    fun stop() = release()

    private fun release() {
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
