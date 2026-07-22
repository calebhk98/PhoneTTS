package com.phonetts.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.phonetts.core.audio.AudioSink
import kotlin.math.max

/**
 * The real-time [AudioSink]: plays the streamed `Flow<FloatArray>` chunks through an [AudioTrack]
 * at the descriptor's sample rate (received via [onFormat] — never a hardcoded constant). Uses
 * 32-bit float PCM so the engines' `FloatArray` output needs no quantization for playback.
 */
class AudioTrackSink : AudioSink {
    private var track: AudioTrack? = null

    override fun onFormat(sampleRate: Int) {
        // Defensive: release any still-live track from a previous session before replacing it — every
        // real call site already routes through stop() first (TtsViewModel.stop()/startPlaybackFrom()),
        // but leaving a track dangling here would leak the native AudioTrack if that discipline were
        // ever missed, silently wasting the very memory this app is budget-hardware-conscious about.
        release()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, ENCODING)
        val built =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(ENCODING)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuffer, sampleRate * BYTES_PER_FLOAT / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        track = built
        built.play()
    }

    override fun onChunk(samples: FloatArray) {
        track?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
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
        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
        private const val BYTES_PER_FLOAT = 4
    }
}
