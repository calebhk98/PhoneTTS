package com.phonetts.engines.executorch

import com.phonetts.core.runtime.Tensor

/**
 * Pure tensor-shape math bridging the duration predictor's output to the synthesizer's input —
 * VALIDATED shape/order, ASSUMED constant (`kokoro-export`'s `demo/inference_example.py`,
 * https://github.com/NorbertKlockiewicz/kokoro-export — the [MAX_DURATION] bound in particular is
 * copied from that script and not independently reverified against the real `.pte`'s export
 * metadata). Free functions with no engine/session dependency, so they are unit-testable without
 * any fake runtime.
 */
object DurationExpansion {
    /**
     * `torch.repeat_interleave(torch.arange(tokenCount), predDur)[:maxDuration]`: token index i
     * repeated `predDur[i]` times, concatenated in token order, truncated to at most [maxDuration]
     * entries. A non-positive duration contributes no entries; never throws.
     */
    fun indices(
        predDur: IntArray,
        maxDuration: Int,
    ): LongArray {
        val out = ArrayList<Long>(minOf(predDur.sumOfNonNegative(), maxDuration.coerceAtLeast(0)))
        for (token in predDur.indices) {
            repeat(predDur[token].coerceAtLeast(0)) {
                if (out.size >= maxDuration) return out.toLongArray()
                out.add(token.toLong())
            }
        }
        return out.toLongArray()
    }

    /**
     * `tensor[:, :length, :]` on a row-major `[1, dim1, dim2]` tensor flattened to a [FloatArray]:
     * keeps only the first [length] slices along the middle dimension. [length] is clamped to
     * [dim1] so a caller can pass the real (unpadded) token count even when the graph ran at a
     * larger bound.
     */
    fun truncateMiddleDim(
        flat: FloatArray,
        dim1: Int,
        dim2: Int,
        length: Int,
    ): FloatArray {
        val keep = length.coerceIn(0, dim1)
        return flat.copyOfRange(0, keep * dim2)
    }

    private fun IntArray.sumOfNonNegative(): Int = sumOf { it.coerceAtLeast(0) }
}

/**
 * Reads an integer-valued session output as an [IntArray] regardless of whether the runtime
 * decoded it as INT64 or FLOAT — ExecuTorch duration/count outputs commonly come back as either,
 * depending on the export, and :core's [Tensor] seam only ever carries the two. Mirrors
 * ExecuTorchRuntime's own try-one-then-the-other bridging, just for reading a single output
 * instead of writing an input tensor.
 */
fun Tensor.toIntCounts(): IntArray {
    val longs = runCatching { asLongs() }.getOrNull()
    if (longs != null) return IntArray(longs.size) { longs[it].toInt() }
    val floats = asFloats()
    return IntArray(floats.size) { floats[it].toInt() }
}
