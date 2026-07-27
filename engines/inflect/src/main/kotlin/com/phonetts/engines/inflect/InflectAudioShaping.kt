package com.phonetts.engines.inflect

import kotlin.math.roundToInt

/**
 * The per-chunk audio shaping Inflect v2's own published runtime applies around the neural graph -
 * a 5 ms edge fade, a punctuation-aware trailing pause, and a clamp to [-1, 1].
 *
 * This is model *packaging* behaviour, not post-processing the app invented: it is a direct port of
 * `edge_fade`, `boundary_pause_seconds` and the final `np.clip` in
 * `onnx/inference_onnx.py` (`owensong/Inflect-Micro-v2-ONNX`, read at authoring time). Upstream
 * splits long text into punctuation-aware chunks and joins them with these pauses; this app's one
 * generation path already emits one chunk per sentence (spec §8), so the pause is appended to the
 * chunk that precedes it rather than inserted between two of them. Same audio, one fewer buffer.
 *
 * It does NOT touch speed: the fade and the pause are fixed-length in seconds and are applied after
 * the graph has already produced audio at the requested native speed (CLAUDE.md rule 2 - nothing
 * here resamples anything).
 *
 * Pure and sample-rate-parameterised, so it is unit-testable with no model and no runtime.
 */
internal object InflectAudioShaping {
    /** `edge_fade`'s default: a 5 ms linear ramp in and out, to kill chunk-boundary clicks. */
    const val EDGE_FADE_MILLIS = 5.0f

    /** `boundary_pause_seconds`' fallback for a chunk that ends in anything untabulated. */
    const val DEFAULT_PAUSE_SECONDS = 0.08f

    private const val MILLIS_PER_SECOND = 1000.0f
    private const val PEAK = 1.0f

    // `boundary_pause_seconds`' table, one named constant per entry: longer breaths after
    // stronger stops. These are upstream's exact values, not tuning choices made here.
    private const val QUESTION_PAUSE_SECONDS = 0.28f
    private const val EXCLAMATION_PAUSE_SECONDS = 0.24f
    private const val PERIOD_PAUSE_SECONDS = 0.22f
    private const val SEMICOLON_PAUSE_SECONDS = 0.16f
    private const val COLON_PAUSE_SECONDS = 0.13f
    private const val COMMA_PAUSE_SECONDS = 0.09f

    private val PAUSE_SECONDS =
        mapOf(
            '?' to QUESTION_PAUSE_SECONDS,
            '!' to EXCLAMATION_PAUSE_SECONDS,
            '.' to PERIOD_PAUSE_SECONDS,
            ';' to SEMICOLON_PAUSE_SECONDS,
            ':' to COLON_PAUSE_SECONDS,
            ',' to COMMA_PAUSE_SECONDS,
        )

    /** The pause that follows a chunk ending with [sentence]'s final punctuation mark. */
    fun boundaryPauseSeconds(sentence: String): Float {
        val ending = sentence.trimEnd().lastOrNull() ?: return DEFAULT_PAUSE_SECONDS
        return PAUSE_SECONDS[ending] ?: DEFAULT_PAUSE_SECONDS
    }

    /**
     * How many samples each edge ramp covers: 5 ms at [sampleRate], but never more than half the
     * chunk, so the fade-in and fade-out of a very short chunk cannot overlap (upstream's
     * `min(round(sr * ms / 1000), waveform.size // 2)`).
     */
    fun edgeFadeFrames(
        sampleCount: Int,
        sampleRate: Int,
    ): Int {
        val requested = (sampleRate * EDGE_FADE_MILLIS / MILLIS_PER_SECOND).roundToInt()
        return minOf(requested, sampleCount / 2).coerceAtLeast(0)
    }

    /**
     * Edge-fade [samples], clamp them to [-1, 1], and append [sentence]'s trailing pause - the
     * whole of upstream's per-chunk shaping in one pass, returning a new array (the caller's
     * waveform came straight out of the runtime and is not modified).
     */
    fun shape(
        samples: FloatArray,
        sampleRate: Int,
        sentence: String,
    ): FloatArray {
        val pauseSamples = (sampleRate * boundaryPauseSeconds(sentence)).roundToInt().coerceAtLeast(0)
        val frames = edgeFadeFrames(samples.size, sampleRate)
        // Trailing samples stay 0.0f - that IS the pause; no need to fill them.
        val shaped = FloatArray(samples.size + pauseSamples)
        for (index in samples.indices) {
            shaped[index] = (samples[index] * fadeGain(index, samples.size, frames)).coerceIn(-PEAK, PEAK)
        }
        return shaped
    }

    /**
     * The ramp value at [index], matching `np.linspace(0, 1, frames)` applied at the head and
     * reversed at the tail: 0 at the very first/last sample, 1 once the ramp is complete, and a
     * flat 1 everywhere in between.
     */
    private fun fadeGain(
        index: Int,
        sampleCount: Int,
        frames: Int,
    ): Float {
        if (frames <= 0) return PEAK
        if (index < frames) return ramp(index, frames)
        val fromEnd = sampleCount - 1 - index
        if (fromEnd < frames) return ramp(fromEnd, frames)
        return PEAK
    }

    // linspace(0, 1, frames) with endpoint=True; a single-frame ramp is [0.0], as in numpy.
    private fun ramp(
        step: Int,
        frames: Int,
    ): Float = if (frames <= 1) 0f else step.toFloat() / (frames - 1)
}
