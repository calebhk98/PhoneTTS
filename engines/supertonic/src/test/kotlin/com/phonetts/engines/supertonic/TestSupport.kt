package com.phonetts.engines.supertonic

import com.phonetts.core.model.ModelBundle

// Shared scaffolding for this module's tests only (mirrors f5tts/TestSupport.kt).

/** A minimal, valid `onnx/tts.json` carrying [SupertonicEngine]'s fingerprint marker. */
internal const val VALID_CONFIG_JSON =
    """{"tts_version":"v1.7.3","ttl":{"chunk_compress_factor":6,"latent_dim":24},""" +
        """"ae":{"sample_rate":44100,"base_chunk_size":512}}"""

/** A tiny-but-valid unicode_indexer.json: 65536 entries, only a handful mapped (rest -1). */
internal fun sampleIndexerJson(mapped: Map<Char, Int> = mapOf('h' to 0, 'i' to 1, '.' to 2)): String {
    val values = IntArray(SupertonicUnicodeIndexer.EXPECTED_SIZE) { -1 }
    for ((ch, id) in mapped) values[ch.code] = id
    return values.joinToString(prefix = "[", postfix = "]")
}

/**
 * A valid unicode_indexer.json mapping every printable ASCII code point to itself (id == code
 * point) and everything else to -1 - lets engine-level tests reason about token COUNT (nothing in
 * an ASCII sentence gets dropped) without caring about the model's real, arbitrary index values.
 */
internal fun asciiIndexerJson(): String {
    val values = IntArray(SupertonicUnicodeIndexer.EXPECTED_SIZE) { -1 }
    for (c in 0..127) values[c] = c
    return values.joinToString(prefix = "[", postfix = "]")
}

/** A voice style JSON with the real `{style_ttl:{dims,data}, style_dp:{dims,data}}` shape. */
internal fun styleJson(
    ttl: FloatArray = FloatArray(TTL_SIZE) { 0.01f },
    dp: FloatArray = FloatArray(DP_SIZE) { 0.02f },
): String {
    fun tensor(
        dims: IntArray,
        data: FloatArray,
    ): String = """{"dims":[${dims.joinToString(",")}],"data":${nest(data.toList(), dims)}}"""
    return """{"style_ttl":${tensor(TTL_DIMS, ttl)},"style_dp":${tensor(DP_DIMS, dp)}}"""
}

/** Recursively nests a flat list into JSON arrays matching [dims], innermost dimension last. */
private fun nest(
    flat: List<Float>,
    dims: IntArray,
): String {
    if (dims.size == 1) return "[${flat.joinToString(",")}]"
    val chunkSize = flat.size / dims[0]
    val chunks = flat.chunked(chunkSize)
    return "[${chunks.joinToString(",") { nest(it, dims.copyOfRange(1, dims.size)) }}]"
}

internal val TTL_DIMS = intArrayOf(1, 50, 256)
internal val DP_DIMS = intArrayOf(1, 8, 16)
internal val TTL_SIZE = TTL_DIMS.fold(1) { acc, d -> acc * d }
internal val DP_SIZE = DP_DIMS.fold(1) { acc, d -> acc * d }

/** A bundle [SupertonicEngine.inspect] confidently recognizes: all required files + two voices. */
internal fun validBundle(
    id: String = "supertonic-bundle",
    voiceIds: Set<String> = setOf("M1", "F1"),
    extraFiles: Set<String> = emptySet(),
): ModelBundle =
    ModelBundle(
        id = id,
        fileNames =
            setOf(
                SupertonicEngine.DP_FILE,
                SupertonicEngine.TEXT_ENCODER_FILE,
                SupertonicEngine.VECTOR_ESTIMATOR_FILE,
                SupertonicEngine.VOCODER_FILE,
                SupertonicEngine.CONFIG_FILE,
                SupertonicEngine.INDEXER_FILE,
            ) +
                voiceIds.map { "${SupertonicEngine.VOICE_STYLES_DIR}$it${SupertonicEngine.VOICE_STYLE_SUFFIX}" } +
                extraFiles,
        sideFiles = mapOf(SupertonicEngine.CONFIG_FILE to VALID_CONFIG_JSON),
        rootPath = "/models/$id",
    )
