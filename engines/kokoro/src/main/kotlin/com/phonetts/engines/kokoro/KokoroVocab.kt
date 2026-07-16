package com.phonetts.engines.kokoro

import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull

/**
 * Parses the phoneme vocabulary out of Kokoro's `tokenizer.json` companion file — the REAL table
 * shipped by `onnx-community/Kokoro-82M-v1.0-ONNX`, PROVEN as the correct recipe by
 * `scripts/model-verify/run_kokoro.py` (`json.load(...)["model"]["vocab"]`). The file's shape is:
 * ```
 * { "model": { "vocab": { "$": 0, ";": 1, ":": 2, ... } } }
 * ```
 * i.e. ~115 entries mapping a single phoneme CHARACTER to its integer id.
 *
 * Read through the shared, dependency-free [MiniJson] reader every engine module already links
 * against for its small companion files, so this engine takes no JSON-library dependency and never
 * hardcodes the vocabulary (SSOT, spec rule 1): the table that turns IPA into token ids comes
 * straight from the model bundle, so a future export with a different vocabulary can never desync
 * from this engine. Malformed input, a missing `model`/`vocab` object, or a non-integer value all
 * fall out as an empty map rather than throwing (fail-closed, matching [MiniJson.parse] itself).
 */
object KokoroVocab {
    /** Phoneme character (one-character key) -> token id. Empty if the file is missing/foreign. */
    fun parse(text: String): Map<String, Long> {
        val root = MiniJson.parse(text)?.asObjectOrNull() ?: return emptyMap()
        val vocab = root[KEY_MODEL]?.asObjectOrNull()?.get(KEY_VOCAB)?.asObjectOrNull() ?: return emptyMap()

        val result = LinkedHashMap<String, Long>(vocab.size)
        for ((symbol, value) in vocab) {
            val id = value.asIntOrNull() ?: continue
            result[symbol] = id.toLong()
        }
        return result
    }

    private const val KEY_MODEL = "model"
    private const val KEY_VOCAB = "vocab"
}
