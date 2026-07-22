package com.phonetts.engines.executorch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecuTorchKokoroVocabTest {
    @Test
    fun parsesAFlatSymbolToIdObject() {
        val vocab = ExecuTorchKokoroVocab.parse("""{"h": 1, "e": 2, "l": 3, "o": 4}""")

        assertEquals(mapOf("h" to 1L, "e" to 2L, "l" to 3L, "o" to 4L), vocab)
    }

    @Test
    fun skipsAnyNonIntegerEntryRatherThanThrowing() {
        val vocab = ExecuTorchKokoroVocab.parse("""{"h": 1, "bad": "not-a-number"}""")

        assertEquals(mapOf("h" to 1L), vocab)
    }

    @Test
    fun malformedTextYieldsAnEmptyMap() {
        assertTrue(ExecuTorchKokoroVocab.parse("not json").isEmpty())
    }
}
