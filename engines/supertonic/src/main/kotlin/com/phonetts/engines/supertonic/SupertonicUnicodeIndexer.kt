package com.phonetts.engines.supertonic

import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asArrayOrNull
import com.phonetts.engines.common.json.asIntOrNull

/**
 * Supertonic's `onnx/unicode_indexer.json`: a flat JSON array of 65536 entries (one per Unicode
 * code point in the Basic Multilingual Plane, `0x0000`-`0xFFFF`), mapping each code point to the
 * model's own character-embedding index, or `-1` if the model was not trained on that character.
 *
 * VALIDATED (docs/research/supertonic-facts.md): downloaded `onnx/unicode_indexer.json` directly
 * from `Supertone/supertonic-3` on Hugging Face (2026-07-24) - a JSON array of exactly 65536
 * entries, values ranging `-1..8320` (8321 supported entries; `ord('A') -> 33`, `ord('a') -> 60`).
 * Cross-checked against the parsing code in both `supertone-inc/supertonic-py`'s
 * `supertonic/core.py` (`UnicodeProcessor._load_indexer`/`_text_to_unicode_values`, which builds
 * `ord(char) -> self.indexer[val]`) and the official `supertone-inc/supertonic` Java example's
 * `Helper.loadJsonLongArray` (`UnicodeProcessor` reads the same file the same way) - both read this
 * exact file as a flat array indexed by raw Unicode code point.
 *
 * This is NOT a phoneme table: Supertonic tokenizes raw characters directly, no g2p/phonemizer step
 * (mirrors [com.phonetts.engines.mms.MmsEngine]'s character-level frontend, not the espeak-ng-backed
 * ones), so [com.phonetts.core.text.Phonemizer] is unused by this engine.
 */
internal object SupertonicUnicodeIndexer {
    /** Number of entries the real file always has - one per Basic-Multilingual-Plane code point. */
    const val EXPECTED_SIZE = 0x10000

    /**
     * Parses [json] into a code-point -> model-index array, or null if malformed / not the
     * expected flat 65536-entry shape (fail closed - never guess at partial/corrupt data).
     */
    fun parse(json: String): IntArray? {
        val entries = MiniJson.parse(json)?.asArrayOrNull() ?: return null
        if (entries.size != EXPECTED_SIZE) return null
        val indexer = IntArray(entries.size)
        for (i in entries.indices) {
            indexer[i] = entries[i].asIntOrNull() ?: return null
        }
        return indexer
    }
}
