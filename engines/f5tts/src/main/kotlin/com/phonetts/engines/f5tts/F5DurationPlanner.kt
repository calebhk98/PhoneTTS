package com.phonetts.engines.f5tts

/**
 * Computes F5-TTS's `max_duration` (target mel-frame count) - the model's NATIVE duration/speed
 * control (`README-io.md` "Speed: routes to max_duration, not resampling"). `max_duration` is a
 * declared input of `F5_Preprocess.onnx` (`Export_F5.py`: `input_names=[..., 'max_duration']`)
 * that fixes how many mel frames the whole pipeline generates, so routing speed through it is a
 * genuine native-parameter route, never a post-hoc resample of output audio (CLAUDE.md rule 2).
 *
 * Mirrors the reference driver's formula (`F5-TTS-ONNX-Inference.py`, quoted in `README-io.md`):
 * ```
 * max_duration = ref_audio_len + int(ref_audio_len / ref_text_len * gen_text_len / SPEED)
 * ```
 * where `ref_audio_len` is the reference clip's length in MEL FRAMES, not samples - `ref_samples
 * / HOP_LENGTH` (`HOP_LENGTH = 256`, also quoted from the reference driver).
 *
 * A pure, deterministic function on purpose: it needs no ONNX graph to be exercised and verified.
 */
object F5DurationPlanner {
    /** Vocos/F5's mel hop length in samples (`F5-TTS-ONNX-Inference.py`'s `HOP_LENGTH`). */
    const val HOP_LENGTH_SAMPLES = 256

    /** Reference-clip length in mel frames, matching the reference driver's `ref_audio_len`. */
    fun refAudioFrames(refSampleCount: Int): Int = refSampleCount / HOP_LENGTH_SAMPLES

    /**
     * `max_duration` in mel frames for a generation call. Faster speech (higher [speed]) asks for
     * FEWER frames at the same text length; slower speech asks for more. Truncates like Python's
     * `int()` (toward zero), matching the quoted formula exactly rather than rounding.
     */
    fun maxDurationFrames(
        refAudioFrames: Int,
        refTextLength: Int,
        genTextLength: Int,
        speed: Float,
    ): Long {
        require(speed > 0f) { "speed must be positive, was $speed" }
        if (refTextLength <= 0) return refAudioFrames.toLong().coerceAtLeast(MIN_FRAMES)
        val extraFrames = (refAudioFrames.toDouble() / refTextLength * genTextLength / speed).toInt()
        return (refAudioFrames + extraFrames).toLong().coerceAtLeast(MIN_FRAMES)
    }

    private const val MIN_FRAMES = 1L
}
