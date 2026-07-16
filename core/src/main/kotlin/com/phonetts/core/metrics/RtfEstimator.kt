package com.phonetts.core.metrics

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
) {
    init {
        require(calibrationWordCount >= 0) { "calibrationWordCount must not be negative" }
        require(audioSecondsProduced >= 0.0) { "audioSecondsProduced must not be negative" }
        require(wallClockElapsedSeconds >= 0.0) { "wallClockElapsedSeconds must not be negative" }
        require(chunksProduced >= 0) { "chunksProduced must not be negative" }
    }

    /** wall / audio: measured seconds of wall-clock per second of audio, this run. */
    val realTimeFactor: Double
        get() = if (audioSecondsProduced <= 0.0) 0.0 else wallClockElapsedSeconds / audioSecondsProduced

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
 * Measures an engine's real generation speed by actually running it — never a hardcoded or
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
        speed: Float,
        calibrationText: String,
        sampleRate: Int,
        now: () -> Long = { System.nanoTime() },
    ): RtfResult {
        require(sampleRate > 0) { "sampleRate must be positive" }

        val wordCount = WordCounter.count(calibrationText)
        val startNanos = now()
        var samplesProduced = 0L
        var chunksProduced = 0

        engine.synthesize(calibrationText, voiceId, speed).collect { chunk ->
            samplesProduced += chunk.size
            chunksProduced++
        }

        val elapsedSeconds = ((now() - startNanos) / NANOS_PER_SECOND).coerceAtLeast(0.0)
        return RtfResult(
            calibrationWordCount = wordCount,
            audioSecondsProduced = samplesProduced / sampleRate.toDouble(),
            wallClockElapsedSeconds = elapsedSeconds,
            chunksProduced = chunksProduced,
        )
    }
}
