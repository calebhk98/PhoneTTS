package com.phonetts.engines.kittentts

import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Unit coverage for [KittenFrontend] in isolation, mirroring the dedicated frontend tests the
 * other phoneme-based engines have (e.g. Piper's `PiperFrontendTest`): phonemize via the shared,
 * injected [com.phonetts.core.text.Phonemizer] seam, then map each resulting character's Unicode
 * code point to a token id (see the ASSUMPTION documented on [KittenFrontend] itself).
 */
class KittenFrontendTest {
    @Test
    fun `toModelInput maps each phonemized character's code point to a token id`() {
        val phonemizer = FakePhonemizer(mapping = { "hi" })
        val frontend = KittenFrontend(phonemizer)

        val input = frontend.toModelInput("hello", "en")

        assertContentEquals(longArrayOf('h'.code.toLong(), 'i'.code.toLong()), input.tokenIds)
        assertEquals(listOf("hello" to "en"), phonemizer.calls)
    }

    @Test
    fun `empty phonemized text yields an empty token id sequence without crashing`() {
        val phonemizer = FakePhonemizer(mapping = { "" })
        val frontend = KittenFrontend(phonemizer)

        val input = frontend.toModelInput("", "en")

        assertEquals(0, input.tokenIds.size)
    }
}
