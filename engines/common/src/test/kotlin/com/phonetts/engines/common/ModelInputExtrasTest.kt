package com.phonetts.engines.common

import com.phonetts.core.engine.ExtraKey
import com.phonetts.core.engine.ModelInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 9.2-style seam test (issue #18 item 2): a single generic accessor over [ModelInput.extras] must
 * type-safely retrieve THREE genuinely different shapes of extra data — a [LongArray] of tone ids
 * (MeloTTS), a scalar [Int] (e.g. a speaker/style index a future engine adds), and a [FloatArray]
 * embedding/style vector (Kokoro/KittenTTS-shaped) — with no per-type helper code: every case below
 * goes through the SAME [ModelInput.extra]/[ModelInput.requireExtra] functions, parameterized only
 * by an [ExtraKey]. That is the proof that adding a fourth extra type needs zero changes here, only
 * a new key.
 */
class ModelInputExtrasTest {
    private val toneIds = ExtraKey.of<LongArray>("tones")
    private val speakerIndex = ExtraKey.of<Int>("speakerIndex")
    private val styleVector = ExtraKey.of<FloatArray>("style")

    @Test
    fun `reads a LongArray extra (tone ids) through the generic accessor`() {
        val input = ModelInput(tokenIds = longArrayOf(1, 2), extras = mapOf("tones" to longArrayOf(0L, 7L, 0L)))

        assertEquals(listOf(0L, 7L, 0L), input.extra(toneIds)?.toList())
    }

    @Test
    fun `reads a scalar Int extra through the SAME generic accessor`() {
        val input = ModelInput(tokenIds = longArrayOf(1), extras = mapOf("speakerIndex" to 3))

        assertEquals(3, input.extra(speakerIndex))
    }

    @Test
    fun `reads a FloatArray embedding extra through the SAME generic accessor`() {
        val embedding = floatArrayOf(0.1f, 0.2f, 0.3f)
        val input = ModelInput(tokenIds = longArrayOf(1), extras = mapOf("style" to embedding))

        assertEquals(embedding.toList(), input.extra(styleVector)?.toList())
    }

    @Test
    fun `a missing key returns null rather than throwing`() {
        val input = ModelInput(tokenIds = longArrayOf(1), extras = emptyMap())

        assertNull(input.extra(toneIds))
        assertNull(input.extra(speakerIndex))
        assertNull(input.extra(styleVector))
    }

    @Test
    fun `a value stored under the right name but the wrong type fails closed as null, not a ClassCastException`() {
        // "tones" holds an Int here, not the LongArray toneIds expects -- a foreign/mismatched
        // frontend's mistake this accessor must never crash on.
        val input = ModelInput(tokenIds = longArrayOf(1), extras = mapOf("tones" to 5))

        assertNull(input.extra(toneIds))
    }

    @Test
    fun `requireExtra fails loudly, naming the engine and the missing key, when the extra is absent`() {
        val input = ModelInput(tokenIds = longArrayOf(1), extras = emptyMap())

        val error = assertFailsWith<IllegalStateException> { input.requireExtra(toneIds, "TestEngine") }
        assertTrue(error.message!!.contains("TestEngine"), "error should name the engine")
        assertTrue(error.message!!.contains("tones"), "error should name the missing extra")
    }

    @Test
    fun `requireExtra returns the value directly when the extra is present and correctly typed`() {
        val input = ModelInput(tokenIds = longArrayOf(1), extras = mapOf("speakerIndex" to 9))

        assertEquals(9, input.requireExtra(speakerIndex, "TestEngine"))
    }
}
