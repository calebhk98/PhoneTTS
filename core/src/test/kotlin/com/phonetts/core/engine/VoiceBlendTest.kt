package com.phonetts.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Seam tests for the pure voice-mixing math (issue #42) - weighted-average correctness + bounds. */
class VoiceBlendTest {
    @Test
    fun `weight 0 reproduces voice A exactly`() {
        val a = floatArrayOf(1f, -2f, 3f)
        val b = floatArrayOf(9f, 9f, 9f)
        assertTrue(a.contentEquals(VoiceBlend.blend(a, b, 0f)))
    }

    @Test
    fun `weight 1 reproduces voice B exactly`() {
        val a = floatArrayOf(1f, -2f, 3f)
        val b = floatArrayOf(9f, 9f, 9f)
        assertTrue(b.contentEquals(VoiceBlend.blend(a, b, 1f)))
    }

    @Test
    fun `midpoint is the elementwise average`() {
        val a = floatArrayOf(0f, 10f, -4f)
        val b = floatArrayOf(2f, 20f, 4f)
        val mid = VoiceBlend.blend(a, b, 0.5f)
        assertEquals(1f, mid[0])
        assertEquals(15f, mid[1])
        assertEquals(0f, mid[2])
    }

    @Test
    fun `arbitrary weight is the linear interpolation toward B`() {
        val a = floatArrayOf(0f, 100f)
        val b = floatArrayOf(10f, 0f)
        val blended = VoiceBlend.blend(a, b, 0.25f)
        // a*0.75 + b*0.25
        assertEquals(2.5f, blended[0])
        assertEquals(75f, blended[1])
    }

    @Test
    fun `weight is clamped below the minimum`() {
        val a = floatArrayOf(3f, 7f)
        val b = floatArrayOf(9f, 1f)
        assertTrue(a.contentEquals(VoiceBlend.blend(a, b, -5f)))
    }

    @Test
    fun `weight is clamped above the maximum`() {
        val a = floatArrayOf(3f, 7f)
        val b = floatArrayOf(9f, 1f)
        assertTrue(b.contentEquals(VoiceBlend.blend(a, b, 5f)))
    }

    @Test
    fun `mismatched lengths are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            VoiceBlend.blend(floatArrayOf(1f, 2f), floatArrayOf(1f), 0.5f)
        }
    }
}
