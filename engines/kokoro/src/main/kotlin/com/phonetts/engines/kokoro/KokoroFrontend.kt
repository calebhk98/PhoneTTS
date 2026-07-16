package com.phonetts.engines.kokoro

import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend
import com.phonetts.core.text.Phonemizer

/**
 * Kokoro's own text frontend (spec §5.2). The reference implementation phonemizes with
 * **misaki** — a rule-based, lexicon-backed G2P — and falls back to **espeak-ng** for text/
 * languages misaki can't confidently handle (docs/research/model-facts.md: "Primary: misaki ...
 * Fallback: espeak-ng").
 *
 * This module carries no misaki lexicon data (that ships with the model bundle, not this jar),
 * so [misakiPhonemize] models only the deterministic, lexicon-free slice of misaki — plain ASCII
 * letters/whitespace — and returns null (== "not confident") for anything else, which sends the
 * text to [phonemizer] (real espeak-ng in `:app`; `FakePhonemizer` in tests) exactly as the real
 * pipeline falls back for text misaki can't resolve from its lexicon.
 */
class KokoroFrontend(
    private val phonemizer: Phonemizer,
) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val phonemes = misakiPhonemize(text) ?: phonemizer.phonemize(text, language)
        val tokenIds = phonemes.map { charToTokenId(it) }.toLongArray()
        return ModelInput(tokenIds)
    }

    /** Null == "misaki isn't confident here" -> caller falls back to [phonemizer]. */
    private fun misakiPhonemize(text: String): String? {
        if (text.isEmpty()) return null
        val supported = text.all { it.isWhitespace() || it in 'a'..'z' || it in 'A'..'Z' }
        if (!supported) return null
        return text.lowercase()
    }

    private fun charToTokenId(ch: Char): Long {
        val index = VOCAB.indexOf(ch)
        return if (index >= 0) index.toLong() else UNKNOWN_TOKEN_ID
    }

    private companion object {
        // Placeholder phoneme/character vocabulary for the seam. Kokoro's real vocabulary is the
        // ~178-symbol IPA set misaki emits, shipped in the model's tokenizer config — not
        // embedded in this module. Swap this table for the real one alongside real weights;
        // nothing outside this file depends on its contents.
        const val VOCAB = " abcdefghijklmnopqrstuvwxyz"
        const val UNKNOWN_TOKEN_ID = 0L
    }
}
