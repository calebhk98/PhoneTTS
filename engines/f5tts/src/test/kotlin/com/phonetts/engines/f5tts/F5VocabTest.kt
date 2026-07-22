package com.phonetts.engines.f5tts

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `F5Vocab` mirrors `SWivid/F5-TTS`'s `list_str_to_idx` (README-io.md, quoted): one entry per
 * Unicode character, id = 0-based line number in `vocab.txt`, OOV falls back to id 0.
 */
class F5VocabTest {
    @Test
    fun `parse assigns 0-based line index as id, first line is space at id 0`() {
        val map = F5Vocab.parse(SAMPLE_VOCAB_TEXT)

        assertEquals(0, map[" "])
        assertEquals(1, map["!"])
        assertEquals(6, map["i"])
        assertEquals(7, map.size)
    }

    @Test
    fun `parse tolerates a trailing newline without adding a spurious empty entry`() {
        val map = F5Vocab.parse("$SAMPLE_VOCAB_TEXT\n")

        assertEquals(7, map.size)
    }

    @Test
    fun `parse tolerates CRLF line endings, matching the real vocab txt's format`() {
        val crlf = SAMPLE_VOCAB_TEXT.replace("\n", "\r\n")

        val map = F5Vocab.parse(crlf)

        assertEquals(0, map[" "])
        assertEquals(6, map["i"])
        assertEquals(7, map.size)
    }

    @Test
    fun `tokenIds maps each character through the vocab`() {
        val map = F5Vocab.parse(SAMPLE_VOCAB_TEXT)

        val ids = F5Vocab.tokenIds("hi", map)

        assertEquals(listOf(5L, 6L), ids.toList())
    }

    @Test
    fun `tokenIds falls back to id 0 for an out-of-vocabulary character, matching upstream's default`() {
        val map = F5Vocab.parse(SAMPLE_VOCAB_TEXT)

        val ids = F5Vocab.tokenIds("z", map)

        assertEquals(listOf(0L), ids.toList())
    }

    @Test
    fun `tokenIds on empty text yields an empty array`() {
        val map = F5Vocab.parse(SAMPLE_VOCAB_TEXT)

        val ids = F5Vocab.tokenIds("", map)

        assertEquals(0, ids.size)
    }
}
