package com.phonetts.engines.supertonic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SupertonicUnicodeIndexerTest {
    @Test
    fun `parses a flat 65536-entry array and preserves values by code point`() {
        val json = sampleIndexerJson(mapOf('h' to 5, 'i' to 6))

        val indexer = assertNotNull(SupertonicUnicodeIndexer.parse(json))

        assertEquals(SupertonicUnicodeIndexer.EXPECTED_SIZE, indexer.size)
        assertEquals(5, indexer['h'.code])
        assertEquals(6, indexer['i'.code])
        assertEquals(-1, indexer['z'.code])
    }

    @Test
    fun `an array of the wrong length is rejected rather than silently truncated`() {
        val json = (0 until 100).joinToString(prefix = "[", postfix = "]")

        assertNull(SupertonicUnicodeIndexer.parse(json))
    }

    @Test
    fun `malformed json yields null, never throws`() {
        assertNull(SupertonicUnicodeIndexer.parse("not json"))
    }

    @Test
    fun `a non-array document is rejected`() {
        assertNull(SupertonicUnicodeIndexer.parse("""{"not":"an array"}"""))
    }
}
