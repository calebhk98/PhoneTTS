package com.phonetts.core.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The runtime-agnostic tensor seam. The rank-0 case is the one worth guarding: a graph that
 * declares a scalar input as `shape=[]` (Inflect v2's `length_scale`/`noise_scale` do) is rejected
 * by ONNX Runtime when handed a `shape=[1]` tensor, so [Tensor.scalarFloat] and [Tensor.rank0Float]
 * are NOT interchangeable and must not quietly collapse into each other.
 */
class TensorTest {
    @Test
    fun `scalarFloat is rank 1 - a one-element vector`() {
        val tensor = Tensor.scalarFloat(1.5f)

        assertContentEquals(intArrayOf(1), tensor.shape)
        assertEquals(1.5f, tensor.asFloats().single())
    }

    @Test
    fun `rank0Float has no dimensions at all`() {
        val tensor = Tensor.rank0Float(0.8f)

        assertEquals(0, tensor.shape.size, "a rank-0 tensor's shape is empty, not [1]")
        assertEquals(0.8f, tensor.asFloats().single())
    }

    @Test
    fun `floats and longs keep the shape they were given`() {
        assertContentEquals(intArrayOf(1, 2, 3), Tensor.floats(FloatArray(6), intArrayOf(1, 2, 3)).shape)
        assertContentEquals(intArrayOf(1, 4), Tensor.longs(LongArray(4), intArrayOf(1, 4)).shape)
    }

    @Test
    fun `reading the wrong primitive type fails loudly rather than returning empty data`() {
        assertFailsWith<IllegalStateException> { Tensor.longs(longArrayOf(1L)).asFloats() }
        assertFailsWith<IllegalStateException> { Tensor.rank0Float(1f).asLongs() }
    }
}
