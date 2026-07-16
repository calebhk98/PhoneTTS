package com.phonetts.engines.cosyvoice2

import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend
import com.phonetts.core.text.Phonemizer

/**
 * CosyVoice2's real frontend is token-based, not phoneme-based: production Qwen2LM consumes
 * BPE token ids produced by WeTextProcessing/ttsfrd text normalization (see
 * docs/research/model-facts.md — CosyVoice2 has no phoneme inventory at all). Shipping the
 * real Qwen2 BPE tokenizer is out of scope for this seam-proving ticket ("the real runtime
 * lands later"), so this frontend runs text through the shared [Phonemizer] seam purely as a
 * normalization pass — not for phoneme lookup — and then maps each resulting character onto a
 * placeholder id inside Qwen2's real vocabulary range, bracketed by BOS/EOS ids that match its
 * real special-token ids. This keeps [ModelInput.tokenIds] exercised end-to-end today; swapping
 * in the real BPE tokenizer later only touches this file — nothing in [CosyVoice2Engine] or the
 * inference seam changes.
 */
class CosyVoice2Frontend(
    private val phonemizer: Phonemizer,
) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val normalized = phonemizer.phonemize(text, language)
        val tokenIds = LongArray(normalized.length + 2)
        tokenIds[0] = BOS_TOKEN
        normalized.forEachIndexed { index, char -> tokenIds[index + 1] = charToTokenId(char) }
        tokenIds[tokenIds.lastIndex] = EOS_TOKEN
        return ModelInput(tokenIds = tokenIds, extras = mapOf(LANGUAGE_EXTRA to language))
    }

    private fun charToTokenId(char: Char): Long = (char.code % VOCAB_SIZE).toLong()

    companion object {
        // Qwen2's real vocabulary size / special-token ids (used only to keep placeholder ids
        // plausible in shape — this is NOT the real BPE mapping, see class doc above).
        private const val VOCAB_SIZE = 151_936
        const val BOS_TOKEN = 151_643L
        const val EOS_TOKEN = 151_645L
        const val LANGUAGE_EXTRA = "language"
    }
}
