package com.phonetts.engines.executorch

import com.phonetts.core.runtime.Tensor
import com.phonetts.engines.common.tensorOrError

/**
 * Builds the two ExecuTorch graphs' input tensor maps and bridges the duration predictor's output
 * into the synthesizer's input (VALIDATED shapes/order, `kokoro-export`'s
 * `demo/inference_example.py`). Pulled out of [ExecuTorchKokoroEngine] itself (rather than being
 * private methods there) purely to keep that class's own method count under the never-nesting
 * style budget (CLAUDE.md rule 9) — these functions read no engine instance state, only their
 * arguments.
 */
internal object ExecuTorchKokoroTensors {
    // VALIDATED tensor names (kokoro-export inference_example.py) -- ExecuTorch binds by
    // position, not name (see com.phonetts.app.runtime.ExecuTorchRuntime's kdoc), but these
    // document what each positional slot IS.
    private const val TOKENS_INPUT = "input_tokens"
    private const val MASK_INPUT = "text_mask"
    private const val STYLE_INPUT = "v_style"
    private const val SPEED_INPUT = "speed"
    private const val INDICES_INPUT = "indices"
    private const val DURATION_STATE_INPUT = "d"
    private const val VOICE_VEC_INPUT = "voice_vec"

    // Positional output-index names ExecuTorchRuntime's session assigns (ExecuTorch's
    // Module.execute returns an unnamed EValue[], not named tensors like ONNX).
    private const val PRED_DUR_OUTPUT = "output0"
    private const val DURATION_STATE_OUTPUT = "output1"
    const val AUDIO_OUTPUT = "output0"

    data class ExpandedDuration(val indices: LongArray, val dFlat: FloatArray, val dShape: IntArray)

    /** Order matters -- VALIDATED: (input_tokens, text_mask, v_style, speed). */
    fun durationInputs(
        tokenIds: LongArray,
        textMask: LongArray,
        styleSlice: FloatArray,
        speed: Float,
    ): Map<String, Tensor> =
        linkedMapOf(
            TOKENS_INPUT to Tensor.longs(tokenIds, intArrayOf(1, tokenIds.size)),
            MASK_INPUT to Tensor.longs(textMask, intArrayOf(1, textMask.size)),
            STYLE_INPUT to Tensor.floats(styleSlice, intArrayOf(1, styleSlice.size)),
            SPEED_INPUT to Tensor.scalarFloat(speed),
        )

    /**
     * Bridges the duration predictor's two outputs (`pred_dur`, `d`) into the synthesizer's
     * `indices`/`d` inputs (VALIDATED recipe, [DurationExpansion]).
     */
    fun expandDuration(
        durationOutputs: Map<String, Tensor>,
        paddedLength: Int,
        maxDuration: Int,
        engineLabel: String,
    ): ExpandedDuration {
        val predDurTensor = durationOutputs.tensorOrError(PRED_DUR_OUTPUT, engineLabel)
        val dTensor = durationOutputs.tensorOrError(DURATION_STATE_OUTPUT, engineLabel)

        val predDurFull = predDurTensor.toIntCounts()
        val predDur = predDurFull.copyOfRange(0, minOf(paddedLength, predDurFull.size))
        val indices = DurationExpansion.indices(predDur, maxDuration)

        val dShape = dTensor.shape
        val dim1 = dShape.getOrElse(1) { paddedLength }
        val dim2 = dShape.getOrElse(2) { 0 }
        val dFlat = DurationExpansion.truncateMiddleDim(dTensor.asFloats(), dim1, dim2, paddedLength)
        val keptDim1 = paddedLength.coerceAtMost(dim1)
        return ExpandedDuration(indices, dFlat, intArrayOf(1, keptDim1, dim2))
    }

    /** Order matters -- VALIDATED: (input_tokens, text_mask, indices, d, voice_vec). */
    fun synthesizerInputs(
        tokenIds: LongArray,
        textMask: LongArray,
        expanded: ExpandedDuration,
        voiceRow: FloatArray,
    ): Map<String, Tensor> =
        linkedMapOf(
            TOKENS_INPUT to Tensor.longs(tokenIds, intArrayOf(1, tokenIds.size)),
            MASK_INPUT to Tensor.longs(textMask, intArrayOf(1, textMask.size)),
            INDICES_INPUT to Tensor.longs(expanded.indices),
            DURATION_STATE_INPUT to Tensor.floats(expanded.dFlat, expanded.dShape),
            VOICE_VEC_INPUT to Tensor.floats(voiceRow, intArrayOf(1, voiceRow.size)),
        )
}
