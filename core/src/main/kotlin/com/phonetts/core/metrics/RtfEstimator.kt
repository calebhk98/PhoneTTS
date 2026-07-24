package com.phonetts.core.metrics

import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.engine.VoiceEngine
import kotlinx.coroutines.flow.collect

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val WORDS_PER_ESTIMATE_BATCH = 1_000

/**
 * The result of one calibration run: figures MEASURED while draining a real `synthesize()`
 * call, plus values purely derived from those measurements.
 */
data class RtfResult(
    val calibrationWordCount: Int,
    val audioSecondsProduced: Double,
    val wallClockElapsedSeconds: Double,
    val chunksProduced: Int,
    /**
     * Wall-clock seconds from generation start to the first emitted [FloatArray] chunk (issue #14) -
     * a real measurement, taken at the moment the first chunk actually lands. `null` when no chunk
     * ever arrived (nothing to measure), never a guessed value.
     */
    val timeToFirstAudioSeconds: Double? = null,
) {
    init {
        require(calibrationWordCount >= 0) { "calibrationWordCount must not be negative" }
        require(audioSecondsProduced >= 0.0) { "audioSecondsProduced must not be negative" }
        require(wallClockElapsedSeconds >= 0.0) { "wallClockElapsedSeconds must not be negative" }
        require(chunksProduced >= 0) { "chunksProduced must not be negative" }
        timeToFirstAudioSeconds?.let { require(it >= 0.0) { "timeToFirstAudioSeconds must not be negative" } }
    }

    /** wall / audio: measured seconds of wall-clock per second of audio, this run. */
    val realTimeFactor: Double
        get() = if (audioSecondsProduced <= 0.0) 0.0 else wallClockElapsedSeconds / audioSecondsProduced

    /**
     * Whether the produced audio is a plausible amount of speech for the calibration text, rather
     * than a model that silently emitted almost nothing (a wrong-language tokenizer dropping the
     * input, an empty synthesis). Natural TTS speech runs on the order of 0.3-0.6s of audio per
     * word; anything under [MIN_SECONDS_OF_SPEECH_PER_WORD] per word is far below any real voice and
     * is treated as broken output, not a legitimate (and, at RTF near 0, deceptively "fastest")
     * result. A calibration text with no countable words can't be judged, so it passes (fail-open on
     * the measurement, so this never turns a genuine-but-unmeasurable run into a false failure).
     */
    val isPlausibleSpeech: Boolean
        get() =
            calibrationWordCount <= 0 ||
                audioSecondsProduced >= calibrationWordCount * MIN_SECONDS_OF_SPEECH_PER_WORD

    companion object {
        /**
         * Conservative floor of audio-seconds-per-word below which benchmark output is deemed broken.
         * Set well under the ~0.25-0.3s/word of even very fast real speech so only genuinely degenerate
         * output (near-zero audio, e.g. the 0.06s an Arabic model emitted for an English phrase) is
         * flagged, never a merely fast-but-real voice.
         */
        const val MIN_SECONDS_OF_SPEECH_PER_WORD = 0.08
    }

    /**
     * Measured wall-clock seconds this engine+voice+speed took per word of the calibration
     * text, projected to a batch of [WORDS_PER_ESTIMATE_BATCH] words. `null` when the
     * calibration text had no words to measure a rate from.
     */
    val secondsPer1000Words: Double?
        get() {
            if (calibrationWordCount <= 0) return null
            return (wallClockElapsedSeconds / calibrationWordCount) * WORDS_PER_ESTIMATE_BATCH
        }
}

/**
 * Measures an engine's real generation speed by actually running it - never a hardcoded or
 * guessed RTF (this is the metrics seam's whole point). Drains a real `synthesize()` call over
 * [calibrationText] and times it against [now], producing a genuine measured [RtfResult].
 *
 * A caller can use the result to seed a [GenerationStats]-based ETA for a longer piece of text
 * (this engine+voice+speed's measured throughput doesn't change between calibration and the
 * real run), or simply to power a "voice sample" button that reports how fast this device
 * actually renders speech before committing to a long export.
 */
object RtfEstimator {
    suspend fun estimate(
        engine: VoiceEngine,
        voiceId: String,
        params: SynthesisParams,
        calibrationText: String,
        sampleRate: Int,
        now: () -> Long = { System.nanoTime() },
    ): RtfResult {
        require(sampleRate > 0) { "sampleRate must be positive" }

        val wordCount = WordCounter.count(calibrationText)
        val startNanos = now()
        var samplesProduced = 0L
        var chunksProduced = 0
        var firstChunkNanos: Long? = null

        engine.synthesize(calibrationText, voiceId, params).collect { chunk ->
            if (firstChunkNanos == null) firstChunkNanos = now()
            samplesProduced += chunk.size
            chunksProduced++
        }

        val elapsedSeconds = ((now() - startNanos) / NANOS_PER_SECOND).coerceAtLeast(0.0)
        val timeToFirstAudioSeconds =
            firstChunkNanos?.let { ((it - startNanos) / NANOS_PER_SECOND).coerceAtLeast(0.0) }
        return RtfResult(
            calibrationWordCount = wordCount,
            audioSecondsProduced = samplesProduced / sampleRate.toDouble(),
            wallClockElapsedSeconds = elapsedSeconds,
            chunksProduced = chunksProduced,
            timeToFirstAudioSeconds = timeToFirstAudioSeconds,
        )
    }
}
