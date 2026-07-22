package com.phonetts.engines.melotts

import com.phonetts.core.engine.ExtraKey
import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend

/**
 * MeloTTS's [TextFrontend] against the PROVEN MiaoMint/MeloTTS-ONNX contract
 * (`scripts/model-verify/run_melo2.py`, verified to produce real non-silent speech). Replaces the
 * old espeak-IPA-guessing frontend entirely: this model ships its own G2P dictionary
 * ([MeloLexicon]) and symbol table ([MeloTokens]), so pronunciation comes straight from the model
 * bundle, not an approximation (SSOT, spec rule 1).
 *
 * G2P, mirroring the reference recipe exactly:
 *  1. Tokenize lowercased text with `[a-zA-Z']+|[.,!?;:]` (words and standalone punctuation).
 *  2. A word in [lexicon] contributes its phonemes + tones. A punctuation token that is itself a
 *     known symbol contributes itself with tone 0. Anything else falls back to the `UNK` symbol
 *     (tone 0) if the table has one — this is the fail-closed OOV path (spec rule 4): unknown
 *     input never crashes, it degrades to the model's own escape hatch.
 *  3. VITS `add_blank` interspersing: `[0, s0, 0, s1, ..., sN, 0]` for both the symbol and tone
 *     sequences (id 0 is `tokens.txt`'s own blank/pad row, always `_`).
 */
class MeloFrontend(
    private val symbolToId: Map<String, Int>,
    private val lexicon: Map<String, MeloLexicon.Entry>,
) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val (phones, tones) = g2p(text)
        val symbolIds = phones.map { phone -> (symbolToId[phone] ?: unkId()).toLong() }
        val toneValues = tones.map { it.toLong() }

        return ModelInput(
            tokenIds = intersperseBlank(symbolIds),
            extras = mapOf(TONES_KEY.name to intersperseBlank(toneValues)),
        )
    }

    /** Real recipe G2P: lexicon lookup per word/punctuation token, OOV falls back to `UNK`. */
    private fun g2p(text: String): Pair<List<String>, List<Int>> {
        val phones = mutableListOf<String>()
        val tones = mutableListOf<Int>()
        for (token in TOKEN_REGEX.findAll(text.lowercase()).map { it.value }) {
            appendToken(token, phones, tones)
        }
        return phones to tones
    }

    private fun appendToken(
        token: String,
        phones: MutableList<String>,
        tones: MutableList<Int>,
    ) {
        val entry = lexicon[token]
        when {
            entry != null -> {
                phones += entry.phonemes
                tones += entry.tones
            }
            token in symbolToId -> {
                phones += token
                tones += PUNCTUATION_TONE
            }
            UNK_SYMBOL in symbolToId -> {
                phones += UNK_SYMBOL
                tones += PUNCTUATION_TONE
            }
            // No lexicon entry, not a known symbol, and no UNK escape hatch in this table: skip
            // rather than emit an invalid id (fail closed, spec rule 4).
        }
    }

    private fun unkId(): Int = symbolToId[UNK_SYMBOL] ?: BLANK_ID

    /** `intersperse_blank` upstream: `[0, v0, 0, v1, ..., vN, 0]`, length `2N+1`. */
    private fun intersperseBlank(values: List<Long>): LongArray {
        val result = LongArray(values.size * 2 + 1)
        for (index in values.indices) {
            result[index * 2 + 1] = values[index]
        }
        return result
    }

    companion object {
        /**
         * Typed key under which the interspersed tone sequence rides in [ModelInput.extras]
         * (issue #18 item 2) -- read back via the generic `ModelInput.requireExtra` accessor in
         * `com.phonetts.engines.common`, never a raw `as?` cast.
         */
        val TONES_KEY = ExtraKey.of<LongArray>("tones")

        private const val UNK_SYMBOL = "UNK"
        private const val BLANK_ID = 0
        private const val PUNCTUATION_TONE = 0
        private val TOKEN_REGEX = Regex("[a-zA-Z']+|[.,!?;:]")
    }
}
