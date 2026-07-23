package com.phonetts.core.download.hf

import kotlin.math.roundToLong

/**
 * Estimates a browsed model's parameter count and a rough "how fast will this run" hint, purely
 * from facts already on hand - a repo's total download size ([HfSizeEstimate], fetched from the
 * file tree with no extra network call) and any short strings that might hint at a weight
 * precision (file names, HF tags, ...). Neither the HF search endpoint
 * ([HfModelSummary]) nor the tree endpoint ([HfTreeEntry]) expose safetensors/onnx tensor-count
 * metadata, so a byte-accurate parameter count isn't obtainable without yet another per-repo
 * request; this trades that for an honestly-labeled estimate derived from data every browsed
 * model already has (spec rule 1 SSOT: a *formula*, not a per-model literal - the same code runs
 * for every repo, built-in or sideloaded, with no name/family check anywhere in it).
 */
object ParameterCountEstimator {
    // Bytes-per-parameter for the weight precisions TTS repos commonly ship. Picked from the
    // precision token most other files/paths mention; a repo with no recognizable token falls back
    // to fp16, the modal precision for on-device TTS weights on Hugging Face today.
    private const val BYTES_PER_PARAM_FP32 = 4.0
    private const val BYTES_PER_PARAM_FP16 = 2.0
    private const val BYTES_PER_PARAM_INT8 = 1.0
    private const val BYTES_PER_PARAM_INT4 = 0.5
    private const val DEFAULT_BYTES_PER_PARAM = BYTES_PER_PARAM_FP16

    private val INT4_TOKENS = listOf("int4", "q4", "4bit")
    private val INT8_TOKENS = listOf("int8", "q8", "8bit", "quant")
    private val FP32_TOKENS = listOf("fp32", "f32", "float32")
    private val FP16_TOKENS = listOf("fp16", "f16", "float16", "half")

    /** Bytes-per-parameter implied by [precisionHints] (case-insensitive substring match on any
     * known precision token), falling back to [DEFAULT_BYTES_PER_PARAM] when none is recognized.
     * [precisionHints] can be repo file names, HF tags, or any other short strings that might
     * mention a precision - the match is a generic substring scan, not filename-specific. */
    fun bytesPerParamHint(precisionHints: List<String>): Double {
        val joined = precisionHints.joinToString(" ") { it.lowercase() }
        return when {
            INT4_TOKENS.any { it in joined } -> BYTES_PER_PARAM_INT4
            INT8_TOKENS.any { it in joined } -> BYTES_PER_PARAM_INT8
            FP32_TOKENS.any { it in joined } -> BYTES_PER_PARAM_FP32
            FP16_TOKENS.any { it in joined } -> BYTES_PER_PARAM_FP16
            else -> DEFAULT_BYTES_PER_PARAM
        }
    }

    /** Approximate parameter count from [totalBytes] of weights, refined by any precision hint in
     * [precisionHints]. Always an estimate - callers must label it as such (spec: never present a
     * guess as a fact). Zero/negative input yields zero rather than a nonsensical negative count. */
    fun estimate(
        totalBytes: Long,
        precisionHints: List<String> = emptyList(),
    ): Long {
        if (totalBytes <= 0L) return 0L
        val bytesPerParam = bytesPerParamHint(precisionHints)
        return (totalBytes / bytesPerParam).roundToLong()
    }
}

/**
 * Predicts a coarse "how many times faster than real-time" speed hint from an estimated parameter
 * count. This is a single formula applied uniformly to every model - never a per-model lookup
 * table (spec rule 1) - anchored to one reference point: a ~30M-parameter on-device TTS model
 * (the size class of the smallest built-in models, e.g. KittenTTS) synthesizing at roughly 8x
 * real-time on the project's target budget hardware (Galaxy A16-class, no NPU; see CLAUDE.md).
 * Speed is assumed to scale roughly inversely with parameter count for this class of TTS decoder -
 * a coarse but directionally honest approximation, not a benchmark result.
 */
object SpeedPredictor {
    private const val REFERENCE_PARAMS = 30_000_000.0
    private const val REFERENCE_REALTIME_MULTIPLE = 8.0

    // Below this many parameters the inverse-scaling formula would predict an implausibly large
    // multiple (e.g. a near-empty repo "running" at 1000x); clamp the input so tiny/degenerate
    // sizes still produce a sane, bounded prediction rather than a misleading spike.
    private const val MIN_PARAMS_FOR_SCALING = 1_000_000.0

    /** Predicted synthesis speed as a multiple of real-time (e.g. 4.0 = "~4x faster than
     * real-time playback"). Always positive; callers must label it as an estimate. */
    fun predictRealtimeMultiple(paramCount: Long): Double {
        if (paramCount <= 0L) return REFERENCE_REALTIME_MULTIPLE
        val effectiveParams = paramCount.toDouble().coerceAtLeast(MIN_PARAMS_FOR_SCALING)
        return REFERENCE_PARAMS / effectiveParams * REFERENCE_REALTIME_MULTIPLE
    }
}

/** Bundles a repo's estimated parameter count with the speed hint derived from it - the one call
 * site the browse UI needs, so it never has to sequence [ParameterCountEstimator] and
 * [SpeedPredictor] itself. */
data class ModelSpeedEstimate(
    val paramCount: Long,
    val realtimeMultiple: Double,
) {
    companion object {
        fun from(
            totalBytes: Long,
            precisionHints: List<String> = emptyList(),
        ): ModelSpeedEstimate {
            val params = ParameterCountEstimator.estimate(totalBytes, precisionHints)
            val realtimeMultiple = SpeedPredictor.predictRealtimeMultiple(params)
            return ModelSpeedEstimate(paramCount = params, realtimeMultiple = realtimeMultiple)
        }
    }
}
