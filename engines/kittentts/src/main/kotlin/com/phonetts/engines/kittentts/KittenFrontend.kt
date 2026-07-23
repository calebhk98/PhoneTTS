package com.phonetts.engines.kittentts

import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend
import com.phonetts.core.text.Phonemizer

/**
 * KittenTTS's text frontend. KittenTTS is StyleTTS2, whose phoneme->id table is the fixed
 * StyleTTS2 symbol set (pad + punctuation + ASCII letters + IPA letters) - NOT shipped as a
 * file, so it is the engine's own internal data here (spec §5.2: the phoneme->id mapping is the
 * engine's business and must not leak into shared code). Text is phonemized to an IPA string via
 * the shared [phonemizer] (espeak-ng in :app), each IPA character is mapped to its symbol id, and
 * the sequence is wrapped with the pad id (0) at both ends - matching the verified reference
 * recipe in `scripts/model-verify/run_kitten.py` (`input_ids = [0, *ids, 0]`).
 */
class KittenFrontend(private val phonemizer: Phonemizer) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val phonemes = phonemizer.phonemize(text, language)
        val ids = ArrayList<Long>(phonemes.length + 2)
        ids.add(PAD_ID)
        for (ch in phonemes) {
            SYMBOL_TO_ID[ch]?.let(ids::add) // drop symbols outside the table rather than crash
        }
        ids.add(PAD_ID)
        return ModelInput(tokenIds = ids.toLongArray())
    }

    private companion object {
        const val PAD_ID = 0L

        // The StyleTTS2 / KittenTTS symbol table, in order: pad, punctuation, ASCII letters, IPA
        // letters. Index == token id. Copied verbatim from the StyleTTS2 text cleaner (the same
        // table run_kitten.py validated against the real model).
        private const val PAD = "$"
        private const val PUNCTUATION = ";:,.!?¡¿-…\"«»“” "
        private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private const val IPA_LETTERS =
            "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊ" +
                "ʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ"

        val SYMBOL_TO_ID: Map<Char, Long> =
            (PAD + PUNCTUATION + LETTERS + IPA_LETTERS)
                .withIndex()
                .associate { (index, ch) -> ch to index.toLong() }
    }
}
