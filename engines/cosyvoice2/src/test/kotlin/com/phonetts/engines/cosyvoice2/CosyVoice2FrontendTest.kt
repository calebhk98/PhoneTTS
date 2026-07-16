package com.phonetts.engines.cosyvoice2

import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage for [CosyVoice2Frontend] in isolation, mirroring the dedicated frontend tests
 * the other engines have (e.g. Piper's `PiperFrontendTest`, MeloTTS's `MeloFrontendTest`): the
 * shared, injected [com.phonetts.core.text.Phonemizer] normalizes the text, and the result is
 * bracketed with Qwen2's BOS/EOS special-token ids and carried forward with its language extra
 * (see the ASSUMPTION documented on [CosyVoice2Frontend] itself — this is a placeholder mapping,
 * not the real BPE tokenizer).
 */
class CosyVoice2FrontendTest {
    @Test
    fun `toModelInput brackets the phonemized text with BOS and EOS token ids`() {
        val phonemizer = FakePhonemizer(mapping = { "hi" })
        val frontend = CosyVoice2Frontend(phonemizer)

        val input = frontend.toModelInput("hello", "en")

        assertEquals(CosyVoice2Frontend.BOS_TOKEN, input.tokenIds.first())
        assertEquals(CosyVoice2Frontend.EOS_TOKEN, input.tokenIds.last())
        // BOS + 'h' + 'i' + EOS.
        assertEquals(4, input.tokenIds.size)
    }

    @Test
    fun `toModelInput carries the language forward as an extra and calls the phonemizer once`() {
        val phonemizer = FakePhonemizer(mapping = { "hi" })
        val frontend = CosyVoice2Frontend(phonemizer)

        val input = frontend.toModelInput("hello", "en")

        assertEquals("en", input.extras[CosyVoice2Frontend.LANGUAGE_EXTRA])
        assertEquals(listOf("hello" to "en"), phonemizer.calls)
    }

    @Test
    fun `empty phonemized text still yields the BOS-EOS frame`() {
        val phonemizer = FakePhonemizer(mapping = { "" })
        val frontend = CosyVoice2Frontend(phonemizer)

        val input = frontend.toModelInput("", "en")

        assertEquals(2, input.tokenIds.size)
        assertEquals(CosyVoice2Frontend.BOS_TOKEN, input.tokenIds.first())
        assertEquals(CosyVoice2Frontend.EOS_TOKEN, input.tokenIds.last())
    }
}
