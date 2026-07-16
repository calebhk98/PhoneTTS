package com.phonetts.engines.kokoro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [KokoroVocab] reads the `model.vocab` object out of a Kokoro `tokenizer.json` into a char->id
 * map, and fails closed (empty map) on malformed or foreign input — the SSOT source the frontend
 * turns IPA into token ids with, never a hardcoded table.
 */
class KokoroVocabTest {
    // The `$` pad key is written via a char literal so it can't be read as a Kotlin string template.
    private val pad = "${'$'}"

    @Test
    fun extractsTheModelVocabObjectAsACharToIdMap() {
        val json = """{"model": {"vocab": {"$pad": 0, ";": 1, "h": 50}}}"""

        val vocab = KokoroVocab.parse(json)

        assertEquals(mapOf(pad to 0L, ";" to 1L, "h" to 50L), vocab)
    }

    @Test
    fun returnsAnEmptyMapForMalformedJson() {
        assertTrue(KokoroVocab.parse("{not json").isEmpty())
    }

    @Test
    fun returnsAnEmptyMapWhenTheModelOrVocabObjectIsAbsent() {
        assertTrue(KokoroVocab.parse("""{"model": {}}""").isEmpty())
        assertTrue(KokoroVocab.parse("""{}""").isEmpty())
    }

    @Test
    fun skipsEntriesWhoseValueIsNotAnInteger() {
        val vocab = KokoroVocab.parse("""{"model": {"vocab": {"a": 1, "b": "x"}}}""")

        assertEquals(mapOf("a" to 1L), vocab)
    }
}
