package com.phonetts.core.convert

import com.phonetts.core.model.ModelBundle

/**
 * A fail-closed recognizer that rebuilds a downloaded [ModelBundle] whose weights are in a
 * non-runnable format (safetensors, `.nemo`, raw PyTorch) into a format an engine can run
 * (typically ONNX), for a KNOWN architecture, in pure Kotlin/native with no PyTorch/NeMo stack
 * on device (issue #120).
 *
 * This mirrors the engine `inspect()` contract exactly (spec rule #4, and how
 * [com.phonetts.core.resolver.Resolver] tries engines):
 *  - [canTranscode] returns true ONLY when this transcoder is confident it recognizes the
 *    bundle's architecture. An unrecognized bundle is never a guess.
 *  - [transcode] returns a sealed [TranscodeResult]. It returns [TranscodeResult.NotMine] rather
 *    than throwing when it turns out not to own the bundle, so the registry can move on.
 *
 * There is no `when(architecture)` switch anywhere: a new recipe is added by registering a new
 * transcoder, and removed by dropping its registration - nothing else changes.
 */
interface BundleTranscoder {
    /** A stable identifier for this recipe (e.g. "vits-onnx"), for logging and display only. */
    val id: String

    /** True only if this transcoder is confident it recognizes [bundle]'s architecture. */
    fun canTranscode(bundle: ModelBundle): Boolean

    /**
     * Transcodes [bundle] into runnable files written under [outputDir] (a path string, keeping
     * this seam Android-free). Returns [TranscodeResult.Converted] with the produced file names on
     * success, [TranscodeResult.NotMine] if on closer inspection the bundle is not this recipe's,
     * or [TranscodeResult.Failed] with a human-readable reason when a recognized bundle could not
     * be converted.
     */
    fun transcode(
        bundle: ModelBundle,
        outputDir: String,
    ): TranscodeResult
}

/** The outcome of a [BundleTranscoder.transcode] attempt. */
sealed interface TranscodeResult {
    /** Conversion succeeded, producing [files] (relative names) under the output directory. */
    data class Converted(val files: List<String>) : TranscodeResult

    /** The bundle turned out not to belong to this transcoder; try the next one. */
    data object NotMine : TranscodeResult

    /** The bundle was recognized but could not be converted; [reason] is safe to show a user. */
    data class Failed(val reason: String) : TranscodeResult
}
