package com.phonetts.engines.kokoro

import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [KokoroFrontend]: misaki g2p handles the text it's confident about (plain ASCII
 * letters/whitespace here, standing in for misaki's lexicon-backed coverage) without touching
 * the injected [com.phonetts.core.text.Phonemizer]; anything else falls back to it, exactly as
 * Kokoro's reference pipeline falls back to espeak-ng (docs/research/model-facts.md).
 */
class KokoroFrontendTest {
    @Test
    fun asciiTextIsHandledByMisakiWithoutFallingBackToEspeak() {
        val phonemizer = FakePhonemizer()
        val frontend = KokoroFrontend(phonemizer)

        // VOCAB = " abc...z" -> 'a' is index 1, 'b' is index 2.
        val input = frontend.toModelInput("ab", "en-us")

        assertContentEquals(longArrayOf(1L, 2L), input.tokenIds)
        assertTrue(phonemizer.calls.isEmpty())
    }

    @Test
    fun nonLatinTextFallsBackToTheInjectedPhonemizer() {
        val phonemizer = FakePhonemizer(mapping = { "ab" })
        val frontend = KokoroFrontend(phonemizer)

        val input = frontend.toModelInput("こんにちは", "ja")

        assertContentEquals(longArrayOf(1L, 2L), input.tokenIds)
        assertEquals(listOf("こんにちは" to "ja"), phonemizer.calls)
    }

    @Test
    fun digitsAlsoFallBackToTheInjectedPhonemizer() {
        val phonemizer = FakePhonemizer(mapping = { "a" })
        val frontend = KokoroFrontend(phonemizer)

        frontend.toModelInput("2026", "en-us")

        assertEquals(listOf("2026" to "en-us"), phonemizer.calls)
    }

    @Test
    fun charactersOutsideThePlaceholderVocabMapToTheUnknownToken() {
        val phonemizer = FakePhonemizer(mapping = { "əz" })
        val frontend = KokoroFrontend(phonemizer)

        val input = frontend.toModelInput("2026", "en-us")

        // 'z' is in VOCAB (index 26); 'ə' is not, so it maps to the unknown-token id (0).
        assertContentEquals(longArrayOf(0L, 26L), input.tokenIds)
    }
}
