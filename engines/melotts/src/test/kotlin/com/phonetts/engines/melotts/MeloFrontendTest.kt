package com.phonetts.engines.melotts

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves MeloTTS's PROVEN frontend contract (`scripts/model-verify/run_melo2.py`): `x`/`tones`
 * built from a real lexicon lookup (not an IPA approximation) with VITS `add_blank`
 * interspersing, punctuation-as-symbol handling, and a fail-closed `UNK` fallback for OOV words.
 */
class MeloFrontendTest {
    private val tokens =
        mapOf(
            "_" to 0,
            "p" to 1,
            "iy" to 2,
            "SP" to 3,
            "UNK" to 4,
            "," to 5,
        )

    private val lexicon =
        mapOf(
            "hi" to MeloLexicon.Entry(listOf("p", "iy"), listOf(7, 8)),
            "the" to MeloLexicon.Entry(listOf("p"), listOf(7)),
        )

    private fun frontend() = MeloFrontend(tokens, lexicon)

    @Test
    fun `toModelInput maps a lexicon word to its real phoneme and tone ids, add_blank-interspersed`() {
        val input = frontend().toModelInput("hi", "en")

        // "hi" -> lexicon phones [p, iy] -> ids [1, 2] -> intersperse_blank -> [0, 1, 0, 2, 0].
        assertEquals(listOf(0L, 1L, 0L, 2L, 0L), input.tokenIds.toList())
        val tones = input.extras.getValue(MeloFrontend.EXTRA_TONES) as LongArray
        // tones [7, 8] -> intersperse_blank -> [0, 7, 0, 8, 0].
        assertEquals(listOf(0L, 7L, 0L, 8L, 0L), tones.toList())
    }

    @Test
    fun `toModelInput concatenates phonemes and tones across multiple lexicon words`() {
        val input = frontend().toModelInput("the hi", "en")

        // "the"->[p](7), "hi"->[p,iy](7,8) => phones=[p,p,iy] ids=[1,1,2] -> intersperse length 7.
        assertEquals(listOf(0L, 1L, 0L, 1L, 0L, 2L, 0L), input.tokenIds.toList())
    }

    @Test
    fun `a punctuation token that is itself a known symbol contributes tone 0`() {
        val input = frontend().toModelInput("hi,", "en")

        // tokens: "hi" (lexicon) + "," (symbol table entry, tone 0).
        val commaId = tokens.getValue(",").toLong()
        assertEquals(commaId, input.tokenIds[5])
        val tones = input.extras.getValue(MeloFrontend.EXTRA_TONES) as LongArray
        assertEquals(0L, tones[5])
    }

    @Test
    fun `an out-of-vocabulary word falls back to the UNK symbol instead of crashing`() {
        val input = frontend().toModelInput("zzzznotaword", "en")

        val unkId = tokens.getValue("UNK").toLong()
        // core = [UNK] -> intersperse_blank -> [0, UNK, 0].
        assertEquals(listOf(0L, unkId, 0L), input.tokenIds.toList())
        val tones = input.extras.getValue(MeloFrontend.EXTRA_TONES) as LongArray
        assertEquals(listOf(0L, 0L, 0L), tones.toList())
    }

    @Test
    fun `an out-of-vocabulary word is skipped rather than crashing when the table has no UNK entry`() {
        val noUnkTokens = tokens - "UNK"
        val frontend = MeloFrontend(noUnkTokens, lexicon)

        val input = frontend.toModelInput("zzzznotaword", "en")

        // No phones produced at all -> intersperse_blank of an empty sequence -> just the pad.
        assertEquals(listOf(0L), input.tokenIds.toList())
    }

    @Test
    fun `text is lowercased before lexicon lookup`() {
        val input = frontend().toModelInput("HI", "en")

        assertEquals(listOf(0L, 1L, 0L, 2L, 0L), input.tokenIds.toList())
    }

    @Test
    fun `toModelInput produces stable ids for the same text across calls`() {
        val first = frontend().toModelInput("the hi", "en")
        val second = frontend().toModelInput("the hi", "en")

        assertEquals(first.tokenIds.toList(), second.tokenIds.toList())
    }
}
