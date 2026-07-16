package com.phonetts.engines.kittentts

import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend
import com.phonetts.core.text.Phonemizer

/**
 * KittenTTS's text frontend.
 *
 * Real KittenTTS phonemizes with the bundled "misaki" G2P pipeline, then maps phonemes to ids
 * via a vocabulary table shipped inside the released weights (see docs/research/model-facts.md).
 * This module has no dependency on that vocab table, so [phonemizer] (the shared, injected
 * seam — `context.phonemizer` in [com.phonetts.core.engine.EngineContext], backed by espeak-ng
 * in `:app` and by `FakePhonemizer` in tests, per spec §5.2) is used to turn text into a phoneme
 * string, and each phoneme character's Unicode code point becomes its token id.
 *
 * ASSUMPTION (flagged per the issue's "comment where you assume it"): a one-id-per-code-point
 * mapping is only a scaffold that proves ids flow through to the inference session correctly.
 * It is almost certainly NOT the real KittenTTS phoneme->id table, which would need to be
 * extracted from the model's own vocab/tokens file once bundled. Swap this out once that file
 * is available; nothing outside this class needs to change to do so.
 */
class KittenFrontend(private val phonemizer: Phonemizer) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val phonemes = phonemizer.phonemize(text, language)
        val ids = phonemes.map { it.code.toLong() }.toLongArray()
        return ModelInput(tokenIds = ids)
    }
}
