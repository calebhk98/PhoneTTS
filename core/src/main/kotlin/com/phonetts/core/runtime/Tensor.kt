package com.phonetts.core.runtime

/**
 * A minimal, runtime-agnostic tensor: a flat primitive buffer plus a shape. This is all the
 * seam needs - concrete runtimes (ONNX, an LLM backend) convert to and from their own native
 * tensor types at the edge. Deliberately not modelling dtypes beyond float/long: those are the
 * only two an engine feeds a TTS graph (phoneme/token ids as long, scalars/audio as float).
 */
class Tensor private constructor(
    val shape: IntArray,
    private val floats: FloatArray?,
    private val longs: LongArray?,
) {
    fun asFloats(): FloatArray = floats ?: error("tensor holds no float data")

    fun asLongs(): LongArray = longs ?: error("tensor holds no long data")

    companion object {
        fun floats(
            data: FloatArray,
            shape: IntArray = intArrayOf(data.size),
        ): Tensor = Tensor(shape, data, null)

        fun longs(
            data: LongArray,
            shape: IntArray = intArrayOf(data.size),
        ): Tensor = Tensor(shape, null, data)

        /** A rank-1, single-element float tensor - the common carrier for a scalar speed/param value. */
        fun scalarFloat(value: Float): Tensor = Tensor(intArrayOf(1), floatArrayOf(value), null)
    }
}
