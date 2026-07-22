package com.phonetts.engines.executorch

import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asObjectOrNull

/**
 * Parses the phoneme-character -> token-id vocabulary out of a `vocab.json` companion file.
 *
 * KNOWN GAP, flagged rather than papered over: VERIFIED (Hugging Face
 * `software-mansion/react-native-executorch-kokoro`) ships NO such file — the real pipeline's
 * `MODEL_VOCAB` table lives hardcoded inside the `kokoro-export` demo script
 * (`NorbertKlockiewicz/kokoro-export/demo/inference_example.py`), not in the model repo itself.
 * SSOT (CLAUDE.md rule 1) forbids this engine hardcoding that table the way the script does — the
 * vocabulary is a model fact and must come from the bundle, exactly like `:engines:kokoro` reads
 * `tokenizer.json`. So this engine defines its OWN companion-file convention, `vocab.json` — a flat
 * `{ "phoneme-character": id, ... }` object (the same shape `MODEL_VOCAB` would serialize to) —
 * and FAILS CLOSED (`inspect()` returns null) when it is absent. Packaging a real
 * react-native-executorch-kokoro bundle for PhoneTTS therefore requires adding this file
 * alongside the downloaded `.pte`/`voices/` assets; see `engines/executorch/INTEGRATION.md`.
 */
object ExecuTorchKokoroVocab {
    /** Phoneme character (one-character key) -> token id. Empty if the file is missing/foreign. */
    fun parse(text: String): Map<String, Long> {
        val root = MiniJson.parse(text)?.asObjectOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, Long>(root.size)
        for ((symbol, value) in root) {
            val id = value.asIntOrNull() ?: continue
            result[symbol] = id.toLong()
        }
        return result
    }
}
