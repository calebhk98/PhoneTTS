package com.phonetts.engines.piper

import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage for [PiperFrontend] in isolation: phonemize via the injected [FakePhonemizer],
 * then map phonemes to ids using the voice's own `phoneme_id_map`, mirroring Piper's
 * BOS/PAD/EOS-wrapped `phonemes_to_ids`.
 */
class PiperFrontendTest {
    private val idMap =
        mapOf(
            "^" to listOf(1L),
            "$" to listOf(2L),
            "_" to listOf(0L),
            "h" to listOf(10L),
            "i" to listOf(11L),
        )

    @Test
    fun `phonemizes via the injected phonemizer then maps to ids with BOS-PAD-EOS framing`() {
        val phonemizer = FakePhonemizer { _ -> "hi" }
        val frontend = PiperFrontend(phonemizer, idMap)

        val input = frontend.toModelInput("hello", "en-us")

        assertEquals(listOf(1L, 10L, 0L, 11L, 0L, 2L), input.tokenIds.toList())
        assertEquals("hello" to "en-us", phonemizer.calls.single())
    }

    @Test
    fun `drops phonemes that are absent from the voice's phoneme_id_map`() {
        val phonemizer = FakePhonemizer { _ -> "hz" } // 'z' is not in idMap
        val frontend = PiperFrontend(phonemizer, idMap)

        val input = frontend.toModelInput("x", "en")

        // BOS, then 'h' -> 10 + pad, 'z' dropped entirely, then EOS.
        assertEquals(listOf(1L, 10L, 0L, 2L), input.tokenIds.toList())
    }

    @Test
    fun `an empty phoneme_id_map yields an empty id sequence without crashing`() {
        val phonemizer = FakePhonemizer { _ -> "hi" }
        val frontend = PiperFrontend(phonemizer, emptyMap())

        val input = frontend.toModelInput("hi", "en")

        assertEquals(emptyList(), input.tokenIds.toList())
    }
}
