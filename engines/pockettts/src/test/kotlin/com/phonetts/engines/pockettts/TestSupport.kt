package com.phonetts.engines.pockettts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.testing.FakePhonemizer

// Shared scaffolding for this module's tests only - mirrors engines/ggmltts's TestSupport.kt.

/** An [EngineContext] with no runtime registered - this engine never asks for one (see class KDoc). */
internal fun emptyContext(): EngineContext = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

private const val SAMPLE_STATE_ENTRY =
    """{"index":0,"input_name":"state_0","output_name":"out_state_0","dtype":"float32","shape":[1]}"""

/**
 * A single-language Pocket TTS ONNX bundle exactly like `KevinAHM/pocket-tts-onnx/onnx/<language>/`
 * (docs/research/pockettts-facts.md): a `bundle.json` naming [bundleName]/[sampleRate]/the
 * [voiceIds] built-in voices plus the manifest arrays that fingerprint the schema, a
 * `tokenizer.model`, and all 5 real ONNX graphs - [quantizedInt8Only] picks which of the
 * quantizable graphs (flow_lm_main/flow_lm_flow/mimi_decoder) ship ONLY as `_int8.onnx`.
 * [omitManifests] drops fingerprint fields from `bundle.json` for fail-closed tests: `"flow"`
 * omits `flow_lm_state_manifest`, `"mimi"` omits `mimi_state_manifest`.
 */
internal fun pocketBundle(
    id: String = "pocket-tts-english",
    bundleName: String = "english_2026-04",
    sampleRate: Int = 24_000,
    voiceIds: List<String> = listOf("alba", "azelma", "cosette"),
    quantizedInt8Only: Set<String> = emptySet(),
    omitManifests: Set<String> = emptySet(),
): ModelBundle {
    val fileNames = mutableSetOf(PocketTtsEngine.BUNDLE_METADATA_FILE, PocketTtsEngine.TOKENIZER_FILE)
    PocketTtsEngine.ALL_GRAPH_STEMS.forEach { stem ->
        val suffix = if (stem in quantizedInt8Only) "_int8.onnx" else ".onnx"
        fileNames += "$stem$suffix"
    }

    val flowManifest = if ("flow" !in omitManifests) "\"flow_lm_state_manifest\":[$SAMPLE_STATE_ENTRY]," else ""
    val mimiManifest = if ("mimi" !in omitManifests) "\"mimi_state_manifest\":[$SAMPLE_STATE_ENTRY]," else ""
    val voicesJson = voiceIds.joinToString(",") { "\"$it\"" }
    val bundleJson =
        """
        {
          "bundle_name": "$bundleName",
          "sample_rate": $sampleRate,
          "tokenizer_file": "${PocketTtsEngine.TOKENIZER_FILE}",
          $flowManifest
          $mimiManifest
          "predefined_voices": [$voicesJson]
        }
        """.trimIndent()

    return ModelBundle(
        id = id,
        fileNames = fileNames,
        sideFiles = mapOf(PocketTtsEngine.BUNDLE_METADATA_FILE to bundleJson),
        rootPath = "/models/$id",
    )
}

/** A bundle missing [without] from an otherwise-valid [pocketBundle]'s file set. */
internal fun pocketBundleMissingFile(without: String): ModelBundle {
    val bundle = pocketBundle()
    return bundle.copy(fileNames = bundle.fileNames - without)
}
