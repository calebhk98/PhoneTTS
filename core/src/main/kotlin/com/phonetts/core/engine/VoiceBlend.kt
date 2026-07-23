package com.phonetts.core.engine

/**
 * The pure, model-agnostic math behind voice mixing (issue #42): a weighted average of two
 * equal-length speaker/style embedding vectors. It knows nothing about any engine, ONNX graph, or
 * file format - it is just linear interpolation over two `FloatArray`s, which is exactly what
 * blending two continuous speaker vectors is. An engine whose graph accepts such a vector
 * (Kokoro/KittenTTS, [com.phonetts.core.model.ModelDescriptor.supportsVoiceBlend]) hands its two
 * source vectors here to produce the in-between voice; models that select a voice by a discrete id
 * never reach this code.
 */
object VoiceBlend {
    /** The lowest / highest blend weight - a fraction toward voice B, so 0 = pure A, 1 = pure B. */
    const val MIN_WEIGHT: Float = 0f
    const val MAX_WEIGHT: Float = 1f

    /**
     * Linearly interpolate [a] toward [b] by [weight]: `result[i] = a[i] * (1 - w) + b[i] * w`,
     * where `w` is [weight] clamped to `[MIN_WEIGHT, MAX_WEIGHT]`. [weight] 0 reproduces [a]
     * exactly, 1 reproduces [b] exactly, 0.5 is the midpoint. Requires the two vectors to be the
     * same length (they are rows/tables of the same graph); a mismatch is a programming error.
     */
    fun blend(
        a: FloatArray,
        b: FloatArray,
        weight: Float,
    ): FloatArray {
        require(a.size == b.size) { "cannot blend embeddings of different sizes: ${a.size} vs ${b.size}" }
        val w = weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        val inv = 1f - w
        return FloatArray(a.size) { i -> a[i] * inv + b[i] * w }
    }
}
