package com.phonetts.core.audio

import com.phonetts.core.audio.transform.AudioTransform

/**
 * A playback-path [AudioSink] decorator that runs each chunk through an [AudioTransform] on its way
 * to the real [downstream] sink. This is how the opt-in, off-by-default [TempoStretch] (issue #43)
 * reaches PLAYBACK ONLY: the ViewModel wraps the streaming sink with this when the user enables the
 * "Extra tempo boost" control, leaving generation and file export completely untouched (they never
 * see this sink). It is deliberately separate from the export [TransformChain] so the beyond-native
 * time-stretch can never leak onto the native "Speed" control or the export path.
 *
 * The transform is applied per chunk (each generated chunk is a sentence-sized segment), so audio
 * still streams — the first stretched chunk plays before the whole utterance is generated. Because
 * the transform may change a chunk's length, [onChunk] forwards every segment the transform returns.
 */
class TransformingSink(
    private val downstream: AudioSink,
    private val transform: AudioTransform,
) : AudioSink {
    private var sampleRate: Int = 0

    override fun onFormat(sampleRate: Int) {
        this.sampleRate = sampleRate
        downstream.onFormat(sampleRate)
    }

    override fun onChunk(samples: FloatArray) {
        val processed = transform.apply(listOf(samples), sampleRate)
        processed.forEach { downstream.onChunk(it) }
    }

    // Pause/resume are hardware-level and stateless here, so just forward them to the real sink
    // (without this, the default no-op would swallow them and the beyond-native-tempo playback path
    // would lose instant pause).
    override fun pause() = downstream.pause()

    override fun resume() = downstream.resume()

    override fun onEnd() {
        downstream.onEnd()
    }
}
