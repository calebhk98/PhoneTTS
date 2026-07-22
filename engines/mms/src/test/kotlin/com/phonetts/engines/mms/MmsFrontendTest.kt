package com.phonetts.engines.mms

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage for [MmsFrontend] in isolation: lowercase -> drop out-of-vocab characters ->
 * trim -> (optionally) intersperse the blank/pad id, mirroring the Rust-tokenizers `normalizer`
 * baked into every real `Xenova/mms-tts-*` `tokenizer.json` (see [MmsFrontend]'s KDoc).
 */
class MmsFrontendTest {
    // 'p' is deliberately the pad/blank character here (id 0) -- distinct from the letters used in
    // test text so interspersed pads are unambiguous in assertions.
    private val vocab =
        mapOf(
            "p" to 0L,
            " " to 1L,
            "h" to 2L,
            "i" to 3L,
        )

    @Test
    fun `intersperses the blank id before every kept character and once more at the end`() {
        val frontend = MmsFrontend(vocab, padId = 0L, addBlank = true)

        val input = frontend.toModelInput("hi", "eng")

        // pad, h, pad, i, pad
        assertEquals(listOf(0L, 2L, 0L, 3L, 0L), input.tokenIds.toList())
    }

    @Test
    fun `lowercases the input before mapping`() {
        val frontend = MmsFrontend(vocab, padId = 0L, addBlank = true)

        val input = frontend.toModelInput("HI", "eng")

        assertEquals(listOf(0L, 2L, 0L, 3L, 0L), input.tokenIds.toList())
    }

    @Test
    fun `drops characters absent from the vocab instead of crashing`() {
        val frontend = MmsFrontend(vocab, padId = 0L, addBlank = true)

        // 'z' is not in vocab and must be dropped entirely, not mapped to any id.
        val input = frontend.toModelInput("hzi", "eng")

        assertEquals(listOf(0L, 2L, 0L, 3L, 0L), input.tokenIds.toList())
    }

    @Test
    fun `trims leading and trailing whitespace after filtering, keeping interior whitespace`() {
        val frontend = MmsFrontend(vocab, padId = 0L, addBlank = true)

        val input = frontend.toModelInput("  h i  ", "eng")

        // trimmed to "h i": pad, h, pad, space, pad, i, pad
        assertEquals(listOf(0L, 2L, 0L, 1L, 0L, 3L, 0L), input.tokenIds.toList())
    }

    @Test
    fun `an empty string after filtering yields just the single blank id when addBlank is true`() {
        val frontend = MmsFrontend(vocab, padId = 0L, addBlank = true)

        val input = frontend.toModelInput("zzz", "eng")

        assertEquals(listOf(0L), input.tokenIds.toList())
    }

    @Test
    fun `addBlank false emits raw character ids with no interspersing`() {
        val frontend = MmsFrontend(vocab, padId = 0L, addBlank = false)

        val input = frontend.toModelInput("hi", "eng")

        assertEquals(listOf(2L, 3L), input.tokenIds.toList())
    }

    @Test
    fun `an empty vocab yields an empty id sequence without crashing when addBlank is false`() {
        val frontend = MmsFrontend(emptyMap(), padId = 0L, addBlank = false)

        val input = frontend.toModelInput("hi", "eng")

        assertEquals(emptyList(), input.tokenIds.toList())
    }
}
