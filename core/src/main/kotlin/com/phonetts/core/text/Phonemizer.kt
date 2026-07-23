package com.phonetts.core.text

/**
 * Text → phoneme string. The shared, espeak-ng-backed frontend used by several engines
 * satisfies this (its native implementation lives in :app); an engine with its own g2p can
 * ignore it. Kept generic and injected via [com.phonetts.core.engine.EngineContext] so that
 * no engine hardcodes a phonemizer and the seam stays fakeable in tests.
 *
 * How a phoneme string maps to the model's own phoneme→id vector is the ENGINE's business,
 * not this interface's - that mapping is exactly the kind of per-model knowledge that must
 * not leak into shared code.
 */
interface Phonemizer {
    fun phonemize(
        text: String,
        language: String,
    ): String
}
