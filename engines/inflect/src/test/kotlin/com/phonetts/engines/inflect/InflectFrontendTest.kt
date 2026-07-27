package com.phonetts.engines.inflect

import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phoneme->id contract, anchored to values computed independently from the published
 * `runtime/text/symbols.py` (`symbols = [pad] + punctuation + letters + ipa_letters`) rather than
 * to whatever this implementation happens to produce - so a silently mutated table fails here.
 */
class InflectFrontendTest {
    private fun frontend(mapping: (String) -> String = { it }) = InflectFrontend(FakePhonemizer(mapping))

    @Test
    fun `the pad is index 0, so the blank id is 0`() {
        assertEquals(0L, InflectFrontend.SYMBOL_TO_ID.getValue('_'))
    }

    @Test
    fun `the table's section boundaries land where symbols py puts them`() {
        // pad(1) + punctuation(16) = 17 entries before the letters; the space is the last
        // punctuation entry, i.e. index 16 (symbols.py's own SPACE_ID).
        assertEquals(16L, InflectFrontend.SYMBOL_TO_ID.getValue(' '))
        assertEquals(17L, InflectFrontend.SYMBOL_TO_ID.getValue('A'))
        assertEquals(17L + 26L, InflectFrontend.SYMBOL_TO_ID.getValue('a'))
        // ...and the IPA block starts right after the 52 ASCII letters.
        assertEquals(17L + 52L, InflectFrontend.SYMBOL_TO_ID.getValue('ɑ'))
    }

    @Test
    fun `it is Inflect's table, not the StyleTTS2 one KittenTTS uses`() {
        // The two differences that would silently mis-map every id if the wrong table were copied.
        assertTrue('_' in InflectFrontend.SYMBOL_TO_ID, "Inflect's pad is '_', not '$'")
        assertTrue('—' in InflectFrontend.SYMBOL_TO_ID, "Inflect's punctuation carries an em dash")
        assertTrue('-' !in InflectFrontend.SYMBOL_TO_ID, "a hyphen is NOT in Inflect's table")
    }

    @Test
    fun `ids are blank-interleaved into a 2n+1 sequence`() {
        val ids = frontend().toModelInput("ab", "en").tokenIds

        assertEquals(5, ids.size)
        assertContentEquals(
            longArrayOf(
                InflectFrontend.PAD_ID,
                InflectFrontend.SYMBOL_TO_ID.getValue('a'),
                InflectFrontend.PAD_ID,
                InflectFrontend.SYMBOL_TO_ID.getValue('b'),
                InflectFrontend.PAD_ID,
            ),
            ids,
        )
    }

    @Test
    fun `symbols outside the table are dropped rather than crashing the read`() {
        // A tie bar the IPA normalizer would normally have removed, plus an emoji.
        val ids = frontend { "a͡b😀" }.toModelInput("anything", "en").tokenIds

        assertEquals(5, ids.size, "only 'a' and 'b' are speakable, so 2n+1 = 5")
    }

    @Test
    fun `nothing speakable yields no tokens, so the engine can skip the graph`() {
        val ids = frontend { "😀" }.toModelInput("anything", "en").tokenIds

        assertEquals(0, ids.size)
    }

    @Test
    fun `the phonemizer is asked for the engine's language`() {
        val phonemizer = FakePhonemizer()

        InflectFrontend(phonemizer).toModelInput("Hello", InflectEngine.LANGUAGE)

        assertEquals(listOf("Hello" to "en"), phonemizer.calls)
    }
}
