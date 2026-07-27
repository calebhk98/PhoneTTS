package com.phonetts.engines.inflect

import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend
import com.phonetts.core.text.Phonemizer

/**
 * Inflect v2's text frontend: espeak-ng IPA (the shared [phonemizer], spec §5.2) mapped through
 * Inflect's OWN symbol table, then blank-interleaved the way its VITS graph expects.
 *
 * VERIFIED against the published runtime (`runtime/text/symbols.py` and
 * `onnx/inference_onnx.py::phonemes_to_tokens` in `owensong/Inflect-Micro-v2-ONNX`, both read at
 * authoring time):
 *
 *  - The table is `pad + punctuation + ASCII letters + IPA letters`, index == token id. It is
 *    NEAR-identical to the StyleTTS2 table `KittenFrontend` uses but **not** the same: Inflect's
 *    pad is `_` (not `$`) and its punctuation run carries an em dash (not a hyphen). Every id
 *    after the pad would therefore be off by nothing but the two substitutions would mis-map, so
 *    this table is the engine's own data (spec §5.2 - a phoneme->id map is never shared code).
 *  - `add_blank` is true for both published exports, so the id sequence is the pad id interleaved
 *    around every symbol: `[0, s0, 0, s1, 0, ..., sN, 0]`, length `2n + 1`.
 *
 * TWO DELIBERATE DIFFERENCES from the upstream Python frontend, both flagged rather than hidden:
 *
 *  1. **Unknown symbols are dropped, not fatal.** Upstream's `phonemes_to_tokens` raises `KeyError`
 *     on any character outside the table. Dropping matches what `PiperFrontend`/`KittenFrontend`
 *     already do here and keeps one stray character from killing a whole document mid-read.
 *  2. **Punctuation usually does not survive phonemization.** Upstream calls the `phonemizer`
 *     package with `preserve_punctuation=True`, which re-inserts `,.;:!?` into the IPA string;
 *     the app's espeak-ng bridge calls `espeak_TextToPhonemes` directly, which does not. The
 *     table's punctuation ids are therefore reachable in principle but rarely produced in practice,
 *     so phrase-internal prosody is flatter than upstream's. This affects every espeak-based engine
 *     in the app equally (Piper, KittenTTS, Kokoro), is a frontend limitation rather than a model
 *     one, and is NOT worked around here by faking punctuation the phonemizer did not emit.
 */
internal class InflectFrontend(private val phonemizer: Phonemizer) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val phonemes = phonemizer.phonemize(text, language)
        val symbols = phonemes.mapNotNull { SYMBOL_TO_ID[it] }
        return ModelInput(tokenIds = withBlanks(symbols))
    }

    /**
     * `phonemes_to_tokens`' blank interleave: a `2n + 1` sequence whose odd indices are the symbol
     * ids and whose even indices are all the pad id. An empty symbol list yields an empty array
     * (not a lone pad) so the engine can recognize "nothing speakable here" and skip the graph
     * rather than feed it a degenerate one-token input.
     */
    private fun withBlanks(symbols: List<Long>): LongArray {
        if (symbols.isEmpty()) return LongArray(0)
        val ids = LongArray(symbols.size * 2 + 1) { PAD_ID }
        for ((index, symbol) in symbols.withIndex()) {
            ids[index * 2 + 1] = symbol
        }
        return ids
    }

    internal companion object {
        /** `_` is index 0 of the table, so the pad id is 0 - stated, not assumed. */
        const val PAD_ID = 0L

        // Inflect v2's symbol table, verbatim from runtime/text/symbols.py, in table order.
        // The em dash and the combining vertical line below are written as escapes: both are
        // easy to mistake for a hyphen / to lose entirely in an editor, and a silent substitution
        // here would shift every id after it.
        private const val PAD = "_"
        private const val PUNCTUATION = ";:,.!?¡¿\u2014…\"«»“” "
        private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private const val IPA_LETTERS =
            "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊ" +
                "ʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'\u0329'ᵻ"

        /** The whole table in order; a symbol's index IS its token id. */
        const val SYMBOLS = PAD + PUNCTUATION + LETTERS + IPA_LETTERS

        val SYMBOL_TO_ID: Map<Char, Long> =
            SYMBOLS.withIndex().associate { (index, ch) -> ch to index.toLong() }
    }
}
