package com.phonetts.engines.mms

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * §9.1 fail-closed coverage for [MmsEngine.inspect]: a bundle is only ever claimed when it has an
 * `.onnx` weights file AND a `config.json` that is BOTH `model_type: vits` AND self-describes as
 * MMS (`_name_or_path` containing "mms-tts") AND a non-empty `vocab.json`. A bare `.onnx`, a
 * foreign bundle, a generic (non-MMS) VITS export, or a config/vocab that fails to parse must all
 * come back null so the resolver falls through to the user-pick fallback -- never a guess.
 */
class MmsEngineInspectTest {
    private val engine = MmsEngine(engineContext())

    private val engConfig =
        """
        {
          "_name_or_path": "facebook/mms-tts-eng",
          "model_type": "vits",
          "sampling_rate": 16000,
          "num_speakers": 1,
          "speaking_rate": 1.0
        }
        """.trimIndent()

    private val engVocab =
        """{"k": 0, "'": 1, " ": 19, "h": 10, "i": 11}"""

    private val engTokenizerConfig =
        """
        {
          "add_blank": true,
          "is_uroman": false,
          "language": "eng",
          "normalize": true,
          "pad_token": "k",
          "phonemize": false,
          "tokenizer_class": "VitsTokenizer"
        }
        """.trimIndent()

    private val defaultFileNames =
        setOf("onnx/model.onnx", "onnx/model_quantized.onnx", "config.json", "vocab.json", "tokenizer_config.json")

    private fun bundle(
        id: String = "mms-tts-eng",
        fileNames: Set<String> = defaultFileNames,
        config: String? = engConfig,
        vocab: String? = engVocab,
        tokenizerConfig: String? = engTokenizerConfig,
        rootPath: String? = "/models/mms-tts-eng",
    ): ModelBundle {
        val sideFiles = mutableMapOf<String, String>()
        config?.let { sideFiles["config.json"] = it }
        vocab?.let { sideFiles["vocab.json"] = it }
        tokenizerConfig?.let { sideFiles["tokenizer_config.json"] = it }
        return ModelBundle(id = id, fileNames = fileNames, sideFiles = sideFiles, rootPath = rootPath)
    }

    @Test
    fun `claims a valid mms-tts bundle and populates the descriptor`() {
        val match = assertNotNull(engine.inspect(bundle()))

        assertEquals("mms", match.engineId)
        val descriptor = match.descriptor
        assertEquals("mms", descriptor.engineId)
        assertEquals(Origin.BUILT_IN, descriptor.origin)
        assertEquals(16_000, descriptor.sampleRate)
        assertEquals(1, descriptor.voices.size)
        assertEquals("eng", descriptor.voices.single().language)
        assertEquals(descriptor.voices.single().id, descriptor.defaultVoiceId)
        // No native speed knob -> no declared parameter -> the descriptor's speedRange locks shut.
        assertEquals(emptyList(), descriptor.parameters)
        assertEquals(1.0f..1.0f, descriptor.speedRange)
    }

    @Test
    fun `prefers the quantized onnx weights file when several variants are present`() {
        val severalVariants =
            setOf("onnx/model.onnx", "onnx/model_fp16.onnx", "onnx/model_quantized.onnx", "config.json", "vocab.json")
        val match = assertNotNull(engine.inspect(bundle(fileNames = severalVariants)))

        assertEquals("/models/mms-tts-eng/onnx/model_quantized.onnx", match.descriptor.assetPaths["weights"])
    }

    @Test
    fun `falls back to whatever onnx file exists when no preferred basename matches`() {
        val match =
            assertNotNull(
                engine.inspect(bundle(fileNames = setOf("onnx/weights.onnx", "config.json", "vocab.json"))),
            )

        assertEquals("/models/mms-tts-eng/onnx/weights.onnx", match.descriptor.assetPaths["weights"])
    }

    @Test
    fun `returns null for a bare onnx with no config or vocab at all`() {
        val noSideFiles =
            bundle(fileNames = setOf("onnx/model.onnx"), config = null, vocab = null, tokenizerConfig = null)

        assertInspectRejects(engine, noSideFiles)
    }

    @Test
    fun `returns null for a foreign bundle with no onnx file at all`() {
        val foreign =
            ModelBundle(
                id = "some-other-model",
                fileNames = setOf("model.bin", "config.json"),
                sideFiles = mapOf("config.json" to """{"family": "not-mms"}"""),
            )

        assertInspectRejects(engine, foreign)
    }

    @Test
    fun `returns null for a generic non-MMS vits export (model_type vits but no mms-tts marker)`() {
        val genericVitsConfig =
            """{"model_type": "vits", "_name_or_path": "some-org/other-vits-model", "sampling_rate": 22050}"""

        assertInspectRejects(engine, bundle(config = genericVitsConfig))
    }

    @Test
    fun `returns null when model_type is present but is not vits`() {
        val notVitsConfig =
            """{"model_type": "wavenet", "_name_or_path": "facebook/mms-tts-eng", "sampling_rate": 16000}"""

        assertInspectRejects(engine, bundle(config = notVitsConfig))
    }

    @Test
    fun `returns null when vocab json is missing even if config looks like mms`() {
        assertInspectRejects(engine, bundle(vocab = null))
    }

    @Test
    fun `returns null when vocab json is an empty object`() {
        assertInspectRejects(engine, bundle(vocab = "{}"))
    }

    @Test
    fun `returns null when config json is malformed`() {
        assertInspectRejects(engine, bundle(config = "{not valid json"))
    }

    @Test
    fun `returns null when config json is missing sampling_rate`() {
        val noSampleRate =
            bundle(config = """{"model_type": "vits", "_name_or_path": "facebook/mms-tts-eng"}""")

        assertInspectRejects(engine, noSampleRate)
    }

    @Test
    fun `succeeds without a tokenizer_config json, falling back to defaults`() {
        val match = assertNotNull(engine.inspect(bundle(tokenizerConfig = null)))

        assertEquals("und", match.descriptor.voices.single().language)
    }

    @Test
    fun `forcedMatch never returns null and fills in family defaults for a bare onnx`() {
        val sideloaded =
            ModelBundle(id = "sideloaded", fileNames = setOf("mystery.onnx"), rootPath = "/models/sideloaded")

        val match = engine.forcedMatch(sideloaded)

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals(16_000, match.descriptor.sampleRate)
        assertEquals(1, match.descriptor.voices.size)
        assertEquals(1.0f..1.0f, match.descriptor.speedRange)
    }

    @Test
    fun `forcedMatch uses the real config and vocab when present even for a manually assigned bundle`() {
        val match = engine.forcedMatch(bundle())

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals(16_000, match.descriptor.sampleRate)
        assertEquals("eng", match.descriptor.voices.single().language)
    }

    @Test
    fun `forcedMatch throws when the bundle has no onnx weights at all`() {
        val noWeights = ModelBundle(id = "no-weights", fileNames = setOf("readme.txt"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(noWeights) }
    }
}
