package com.phonetts.engines.supertonic

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [SupertonicFrontend.toModelInput]'s pipeline: NFKD normalize, add a trailing period if
 * missing, wrap in a `<lang>...</lang>` tag, map each code point through the model's own indexer,
 * and drop anything the indexer has no entry for.
 */
class SupertonicFrontendTest {
    private fun frontendWithTagSupport(mapped: Map<Char, Int>): SupertonicFrontend {
        val indexer = IntArray(SupertonicUnicodeIndexer.EXPECTED_SIZE) { -1 }
        for ((ch, id) in mapped) indexer[ch.code] = id
        return SupertonicFrontend(indexer)
    }

    @Test
    fun `maps supported characters through the indexer in order`() {
        val frontend = frontendWithTagSupport(mapOf('h' to 10, 'i' to 11, '.' to 12))

        val input = frontend.toModelInput("hi.", "na")

        // "<na>hi.</na>" -- only h, i, . are in the indexer; every tag/bracket char is dropped.
        assertEquals(listOf(10L, 11L, 12L), input.tokenIds.toList())
    }

    @Test
    fun `unsupported characters are dropped rather than crashing`() {
        val frontend = frontendWithTagSupport(mapOf('z' to 1))

        val input = frontend.toModelInput("z5", "na")

        // '5' has no indexer entry (left as -1) -- dropped; only 'z' survives. 'z' is chosen because
        // it does NOT appear in the "<na>" language tag (unlike e.g. 'a'), so exactly one id results.
        assertEquals(listOf(1L), input.tokenIds.toList())
    }

    @Test
    fun `a trailing period is added when the text has no sentence-final punctuation`() {
        val periodId = 99
        val frontend = frontendWithTagSupport(mapOf('.' to periodId))

        val input = frontend.toModelInput("hello", "na")

        assertEquals(periodId.toLong(), input.tokenIds.last())
    }

    @Test
    fun `no extra period is added when the text already ends in sentence-final punctuation`() {
        val periodId = 99
        val frontend = frontendWithTagSupport(mapOf('.' to periodId))

        val input = frontend.toModelInput("hello.", "na")

        assertEquals(1, input.tokenIds.count { it == periodId.toLong() })
    }

    @Test
    fun `an unsupported language falls back to the na tag`() {
        // Build an indexer that maps every character of "<na>" and "</na>" but NOT "<xx>"/"</xx>",
        // so the produced ids differ depending on which tag was actually used.
        val naIndexer = IntArray(SupertonicUnicodeIndexer.EXPECTED_SIZE) { -1 }
        for ((i, ch) in "<na>hi.</na>".withIndex()) naIndexer[ch.code] = i
        val frontend = SupertonicFrontend(naIndexer)

        val input = frontend.toModelInput("hi.", "not-a-real-language-code")

        // Every character of the literal string "<na>hi.</na>" is present in naIndexer, so if the
        // frontend used <na> (not the invalid code) every character maps successfully.
        assertEquals("<na>hi.</na>".length, input.tokenIds.size)
    }
}
