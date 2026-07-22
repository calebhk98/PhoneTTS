package com.phonetts.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one float-to-int16 conversion shared by every encoder/sink ([Pcm16] doc comment). These
 * cover the edge cases the round-trip tests in [WavWriterTest] don't reach: non-finite input and
 * out-of-[-1,1] samples that must clamp rather than wrap or crash.
 */
class Pcm16Test {
    @Test
    fun nanSampleQuantizesToSilenceInsteadOfThrowing() {
        // Float.roundToInt() throws IllegalArgumentException on NaN (kotlin.math.MathKt), so a
        // stray NaN sample must be caught before scaling/rounding, not left to coerceIn.
        assertEquals(0.toShort(), Pcm16.toShort(Float.NaN))
    }

    @Test
    fun nanChunkEncodesToZeroBytesInsteadOfThrowing() {
        val bytes = Pcm16.encode(floatArrayOf(0.5f, Float.NaN, -0.5f))

        assertEquals(0.toShort(), Pcm16.toShort(Float.NaN))
        assertEquals(6, bytes.size)
        // Middle sample (bytes 2-3, little-endian) is the NaN one: silence, not garbage/a crash.
        assertEquals(0, bytes[2].toInt())
        assertEquals(0, bytes[3].toInt())
    }

    @Test
    fun samplesAboveOneClampToSixteenBitMax() {
        assertEquals(Short.MAX_VALUE, Pcm16.toShort(1.5f))
        assertEquals(Short.MAX_VALUE, Pcm16.toShort(2f))
        assertEquals(Short.MAX_VALUE, Pcm16.toShort(Float.POSITIVE_INFINITY))
    }

    @Test
    fun samplesBelowNegativeOneClampToSixteenBitMin() {
        assertEquals(Short.MIN_VALUE, Pcm16.toShort(-1.5f))
        assertEquals(Short.MIN_VALUE, Pcm16.toShort(-2f))
        assertEquals(Short.MIN_VALUE, Pcm16.toShort(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun boundaryValuesQuantizeWithoutClampingArtifacts() {
        assertEquals(0.toShort(), Pcm16.toShort(0f))
        assertEquals(Short.MAX_VALUE, Pcm16.toShort(1f))
        // Symmetric scaling by Short.MAX_VALUE (32767) means exactly -1f lands one short of
        // Short.MIN_VALUE (-32768) — that's -32767, not a clamp, since -1f is in-range input.
        assertEquals((-Short.MAX_VALUE).toShort(), Pcm16.toShort(-1f))
    }
}
