package com.phonetts.engines.kokoro

import com.phonetts.core.engine.Voice

/**
 * Parses Kokoro's voices/embeddings table — `voices.json` — the companion file
 * [KokoroEngine.inspect] requires alongside `config.json` to fail-closed-identify a bundle
 * (spec §9.1, docs/research/model-facts.md: "54 voices ... selected via voice embeddings, a
 * voices table, one embedding per voice — not per file").
 *
 * The real Kokoro ships this table as a binary `.npz` (a numpy archive keyed by voice name).
 * This module has no numpy/binary-tensor dependency, and [com.phonetts.core.model.ModelBundle]
 * only carries *text* side files for fingerprinting, so this engine's own bundle format instead
 * uses a small JSON-like text manifest carrying both the per-voice display metadata (id/name/
 * language, which feed [com.phonetts.core.model.ModelDescriptor.voices]) and the style embedding
 * itself (which [KokoroEngine.load] reads from disk to feed [com.phonetts.core.runtime.Tensor]
 * inputs at synthesis time). A real integration would swap this parser for an `.npz` reader
 * without touching the [KokoroEngine] contract around it.
 *
 * Expected shape — a JSON array of flat objects:
 * ```
 * [
 *   {"id": "af_heart", "name": "Heart", "language": "en-us", "embedding": [0.1, 0.2, 0.3]},
 *   {"id": "bf_emma", "name": "Emma", "language": "en-gb", "embedding": [0.4, -0.1, 0.2]}
 * ]
 * ```
 *
 * Minimal hand-rolled parser for this exact fixed shape — NOT a general-purpose JSON parser.
 */
object KokoroVoiceTable {
    private const val ID_GROUP = 1
    private const val NAME_GROUP = 2
    private const val LANG_GROUP = 3
    private const val EMBEDDING_GROUP = 4

    data class Entry(val voice: Voice, val embedding: FloatArray)

    private val ENTRY_REGEX =
        Regex(
            "\\{\\s*\"id\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*" +
                "\"language\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"embedding\"\\s*:\\s*\\[([^\\]]*)\\]\\s*\\}",
        )

    /** Parse the manifest text into ordered voice entries. Empty/malformed input yields no entries. */
    fun parse(manifest: String): List<Entry> = ENTRY_REGEX.findAll(manifest).map(::toEntry).toList()

    private fun toEntry(match: MatchResult): Entry {
        val g = match.groupValues
        val voice = Voice(id = g[ID_GROUP], name = g[NAME_GROUP], language = g[LANG_GROUP])
        return Entry(voice, parseEmbedding(g[EMBEDDING_GROUP]))
    }

    private fun parseEmbedding(csv: String): FloatArray =
        csv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toFloat() }
            .toFloatArray()
}
