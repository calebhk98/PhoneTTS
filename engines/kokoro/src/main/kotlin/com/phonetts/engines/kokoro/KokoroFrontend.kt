package com.phonetts.engines.kokoro

import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend
import com.phonetts.core.text.Phonemizer

/**
 * Kokoro's own text frontend (spec §5.2), implementing the PROVEN recipe from
 * `scripts/model-verify/run_kokoro.py`:
 *
 *  1. Phonemize the text to a single IPA string via the injected [phonemizer] (real espeak-ng in
 *     `:app`, `FakePhonemizer` in tests) — exactly the `espeak-ng --ipa -v en-us` step the
 *     reference used.
 *  2. Map each IPA character through the model's own [vocab] (from its `tokenizer.json`,
 *     [KokoroVocab]), DROPPING characters the vocab doesn't contain
 *     (`[vocab[c] for c in ipa if c in vocab]`).
 *  3. Wrap the result with the pad id ([PAD_ID], `0`) at both ends (`[0, *tokens, 0]`).
 *
 * That wrapped sequence is exactly what [KokoroEngine] feeds the model's `input_ids` tensor. The
 * vocabulary is injected, never hardcoded (SSOT, spec rule 1): it is read from the bundle's
 * `tokenizer.json` at [KokoroEngine.load] time, so this frontend carries no phoneme table of its
 * own to desync from the weights.
 */
class KokoroFrontend(
    private val vocab: Map<String, Long>,
    private val phonemizer: Phonemizer,
) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val ipa = phonemizer.phonemize(text, language)
        val tokens = ipa.mapNotNull { char -> vocab[char.toString()] }
        return ModelInput(wrapWithPad(tokens))
    }

    /** `[0, *tokens, 0]`: the LongArray starts pad-filled, so only the middle needs writing. */
    private fun wrapWithPad(tokens: List<Long>): LongArray {
        val wrapped = LongArray(tokens.size + PAD_COUNT) { PAD_ID }
        for (index in tokens.indices) {
            wrapped[index + 1] = tokens[index]
        }
        return wrapped
    }

    private companion object {
        // Kokoro's pad id is 0 (tokenizer.json `vocab["$"] == 0`); the wrapped sequence carries one
        // pad at each end.
        const val PAD_ID = 0L
        const val PAD_COUNT = 2
    }
}
