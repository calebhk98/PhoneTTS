package com.phonetts.engines.kokoro

import com.phonetts.core.engine.Voice
import com.phonetts.engines.common.json.JsonValue
import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asArrayOrNull
import com.phonetts.engines.common.json.asFloatOrNull
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.json.asStringOrNull

/**
 * Parses Kokoro's voices/embeddings table — `voices.json` — the companion file
 * [KokoroEngine.inspect] requires alongside `config.json` to fail-closed-identify a bundle
 * (spec §9.1, docs/research/model-facts.md: "54 voices ... selected via voice embeddings, a
 * voices table, one embedding per voice — not per file").
 *
 * The real Kokoro ships this table as a binary `.npz` (a numpy archive keyed by voice name).
 * This module has no numpy/binary-tensor dependency, and [com.phonetts.core.model.ModelBundle]
 * only carries *text* side files for fingerprinting, so this engine's own bundle format instead
 * uses a small JSON text manifest carrying both the per-voice display metadata (id/name/language,
 * which feed [com.phonetts.core.model.ModelDescriptor.voices]) and the style embedding itself
 * (which [KokoroEngine.load] reads from disk to feed [com.phonetts.core.runtime.Tensor] inputs at
 * synthesis time). A real integration would swap this parser for an `.npz` reader without
 * touching the [KokoroEngine] contract around it. Read through the shared, dependency-free
 * `com.phonetts.engines.common.json.MiniJson` reader every engine module already links against,
 * rather than a second hand-rolled parser private to this engine.
 *
 * Expected shape — a JSON array of flat objects:
 * ```
 * [
 *   {"id": "af_heart", "name": "Heart", "language": "en-us", "embedding": [0.1, 0.2, 0.3]},
 *   {"id": "bf_emma", "name": "Emma", "language": "en-gb", "embedding": [0.4, -0.1, 0.2]}
 * ]
 * ```
 */
object KokoroVoiceTable {
    data class Entry(val voice: Voice, val embedding: FloatArray)

    /** Parse the manifest text into ordered voice entries. Empty/malformed input yields no entries. */
    fun parse(manifest: String): List<Entry> {
        val entries = MiniJson.parse(manifest)?.asArrayOrNull() ?: return emptyList()
        return entries.mapNotNull(::toEntryOrNull)
    }

    private fun toEntryOrNull(value: JsonValue): Entry? {
        val obj = value.asObjectOrNull() ?: return null
        val id = obj[KEY_ID]?.asStringOrNull() ?: return null
        val name = obj[KEY_NAME]?.asStringOrNull() ?: return null
        val language = obj[KEY_LANGUAGE]?.asStringOrNull() ?: return null
        val embedding = obj[KEY_EMBEDDING]?.asArrayOrNull()?.mapNotNull { it.asFloatOrNull() } ?: return null
        return Entry(Voice(id = id, name = name, language = language), embedding.toFloatArray())
    }

    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_EMBEDDING = "embedding"
}
