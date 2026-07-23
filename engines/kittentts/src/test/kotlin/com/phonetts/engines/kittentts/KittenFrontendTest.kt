package com.phonetts.engines.kittentts

import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for [KittenFrontend]: phonemize via the shared, injected
 * [com.phonetts.core.text.Phonemizer] seam, then map each IPA character through the fixed
 * StyleTTS2 symbol table and wrap the sequence with the pad id (0) at both ends - the contract
 * `scripts/model-verify/run_kitten.py` validated against the real model.
 */
class KittenFrontendTest {
    @Test
    fun `toModelInput maps IPA chars to StyleTTS2 ids and pads both ends`() {
        val phonemizer = FakePhonemizer(mapping = { "hi" })
        val frontend = KittenFrontend(phonemizer)

        val input = frontend.toModelInput("hello", "en")

        // Wrapped with the pad id (0) at both ends...
        assertEquals(0L, input.tokenIds.first())
        assertEquals(0L, input.tokenIds.last())
        assertEquals(4, input.tokenIds.size)
        // ...and 'h','i' (adjacent ASCII letters) map to adjacent, positive symbol ids.
        val h = input.tokenIds[1]
        val i = input.tokenIds[2]
        assertTrue(h > 0 && i == h + 1, "expected consecutive positive ids for h,i; got $h,$i")
        assertEquals(listOf("hello" to "en"), phonemizer.calls)
    }

    @Test
    fun `unknown symbols are dropped rather than crashing`() {
        // A char not in the StyleTTS2 table (a digit) is skipped; only the two pads remain.
        val frontend = KittenFrontend(FakePhonemizer(mapping = { "5" }))

        val input = frontend.toModelInput("5", "en")

        assertEquals(listOf(0L, 0L), input.tokenIds.toList())
    }

    @Test
    fun `empty phonemized text yields just the two pad ids without crashing`() {
        val frontend = KittenFrontend(FakePhonemizer(mapping = { "" }))

        val input = frontend.toModelInput("", "en")

        assertEquals(listOf(0L, 0L), input.tokenIds.toList())
    }
}
