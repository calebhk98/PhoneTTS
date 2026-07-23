package com.phonetts.engines.kokoro

import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * [KokoroFrontend] against the PROVEN recipe (`scripts/model-verify/run_kokoro.py`): all text is
 * phonemized through the injected [com.phonetts.core.text.Phonemizer] (espeak-ng in `:app`), each
 * IPA character is mapped through the model's own `tokenizer.json` vocab (dropping characters the
 * vocab doesn't contain), and the result is wrapped with the pad id `0` at both ends.
 */
class KokoroFrontendTest {
    // A tiny stand-in for the model's tokenizer.json vocab - real ids for a handful of IPA chars.
    private val vocab = mapOf("h" to 50L, "ə" to 60L, "l" to 55L, "o" to 45L)

    @Test
    fun mapsPhonemeCharactersThroughTheVocabAndWrapsWithPadIds() {
        val phonemizer = FakePhonemizer(mapping = { "hələ" })
        val frontend = KokoroFrontend(vocab, phonemizer)

        val input = frontend.toModelInput("hello", "en-us")

        // "hələ" -> [50, 60, 55, 60], then wrapped: [0, ..., 0].
        assertContentEquals(longArrayOf(0L, 50L, 60L, 55L, 60L, 0L), input.tokenIds)
        assertEquals(listOf("hello" to "en-us"), phonemizer.calls)
    }

    @Test
    fun dropsCharactersThatAreNotInTheVocab() {
        // 'x' and the space are absent from the vocab, so they are dropped, not mapped to an id.
        val phonemizer = FakePhonemizer(mapping = { "h x o" })
        val frontend = KokoroFrontend(vocab, phonemizer)

        val input = frontend.toModelInput("ho", "en-us")

        assertContentEquals(longArrayOf(0L, 50L, 45L, 0L), input.tokenIds)
    }

    @Test
    fun anEmptyPhonemizationStillEmitsTheTwoPadIds() {
        val phonemizer = FakePhonemizer(mapping = { "" })
        val frontend = KokoroFrontend(vocab, phonemizer)

        val input = frontend.toModelInput("", "en-us")

        assertContentEquals(longArrayOf(0L, 0L), input.tokenIds)
    }

    @Test
    fun allTextRoutesThroughTheInjectedPhonemizer() {
        val phonemizer = FakePhonemizer(mapping = { "o" })
        val frontend = KokoroFrontend(vocab, phonemizer)

        frontend.toModelInput("plain ascii", "en-us")

        assertEquals(listOf("plain ascii" to "en-us"), phonemizer.calls)
    }
}
