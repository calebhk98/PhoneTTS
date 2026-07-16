package com.phonetts.engines.kokoro

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Direct unit tests for the voices/embeddings table parser used by [KokoroEngine]. */
class KokoroVoiceTableTest {
    @Test
    fun parsesEachVoiceWithItsOwnEmbedding() {
        val manifest =
            """
            [
                {"id": "af_heart", "name": "Heart", "language": "en-us", "embedding": [0.1, 0.2, 0.3]},
                {"id": "bf_emma", "name": "Emma", "language": "en-gb", "embedding": [0.4, -0.1, 0.2]}
            ]
            """.trimIndent()

        val entries = KokoroVoiceTable.parse(manifest)

        assertEquals(2, entries.size)
        assertEquals("af_heart", entries[0].voice.id)
        assertEquals("Heart", entries[0].voice.name)
        assertEquals("en-us", entries[0].voice.language)
        assertContentEquals(floatArrayOf(0.1f, 0.2f, 0.3f), entries[0].embedding)
        assertContentEquals(floatArrayOf(0.4f, -0.1f, 0.2f), entries[1].embedding)
    }

    @Test
    fun emptyArrayYieldsNoEntries() {
        assertTrue(KokoroVoiceTable.parse("[]").isEmpty())
    }

    @Test
    fun malformedTextYieldsNoEntriesRatherThanThrowing() {
        assertTrue(KokoroVoiceTable.parse("not json at all").isEmpty())
    }
}
