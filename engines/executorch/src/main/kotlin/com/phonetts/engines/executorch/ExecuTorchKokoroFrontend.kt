package com.phonetts.engines.executorch

import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend
import com.phonetts.core.text.Phonemizer

/**
 * Kokoro-on-ExecuTorch's text frontend. Reuses `:engines:kokoro`'s PROVEN recipe (phonemize to IPA
 * via the injected [phonemizer], map each character through the bundle's own [vocab] dropping
 * unknown ones, pad-wrap with id `0` at both ends) because the ExecuTorch export's own reference
 * pipeline does EXACTLY the same thing (VALIDATED, `kokoro-export/demo/inference_example.py`:
 * `input_ids = [0] + input_ids[:input_length-2] + [0]`) — only the token-count ceiling differs,
 * since this export's bounded-dynamic-shape method caps the PADDED sequence at [MAX_TOTAL_TOKENS]
 * (128), not 512.
 *
 * The vocabulary is injected, never hardcoded (SSOT, spec rule 1) — read from the bundle's
 * `vocab.json` at `ExecuTorchKokoroEngine.load()` time (see [ExecuTorchKokoroVocab]'s kdoc for why
 * that file, rather than a real upstream one, is what this engine requires).
 */
class ExecuTorchKokoroFrontend(
    private val vocab: Map<String, Long>,
    private val phonemizer: Phonemizer,
) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val ipa = phonemizer.phonemize(text, language)
        val tokens = ipa.mapNotNull { char -> vocab[char.toString()] }.take(MAX_INNER_TOKENS)
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

    companion object {
        private const val PAD_ID = 0L

        /** How many pad ids [wrapWithPad] adds (one at each end); public so the engine can undo it. */
        const val PAD_COUNT = 2

        // VALIDATED (kokoro-export inference_example.py): the bounded-dynamic-shape duration
        // predictor method is named "forward_128" and accepts up to 128 PADDED tokens.
        const val MAX_TOTAL_TOKENS = 128
        const val MAX_INNER_TOKENS = MAX_TOTAL_TOKENS - PAD_COUNT
    }
}
