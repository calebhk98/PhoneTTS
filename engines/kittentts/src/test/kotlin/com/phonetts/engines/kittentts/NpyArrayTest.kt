package com.phonetts.engines.kittentts

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** Direct unit tests for the `.npy` v1.0 float32 decoder [NpyArray] used by [KittenVoiceTable]. */
class NpyArrayTest {
    @Test
    fun decodesA256DimRowRoundTrip() {
        val floats = FloatArray(256) { index -> index * 0.5f - 10f }

        val decoded = NpyArray.parseFloats(NpzFixtures.npyBytes(floats))

        assertContentEquals(floats, decoded)
    }

    @Test
    fun decodesASmallHandBuiltArray() {
        val floats = floatArrayOf(0.1f, -0.2f, 3.5f)

        val decoded = NpyArray.parseFloats(NpzFixtures.npyBytes(floats))

        assertContentEquals(floats, decoded)
    }

    @Test
    fun rejectsBytesWithoutTheNumpyMagic() {
        val notNpy = "not an npy file at all, just plain text".toByteArray()

        assertFailsWith<IllegalArgumentException> { NpyArray.parseFloats(notNpy) }
    }

    @Test
    fun rejectsATruncatedHeader() {
        val truncated = byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte())

        assertFailsWith<IllegalArgumentException> { NpyArray.parseFloats(truncated) }
    }
}
