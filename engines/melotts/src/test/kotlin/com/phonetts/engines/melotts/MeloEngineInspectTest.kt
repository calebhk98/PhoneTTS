package com.phonetts.engines.melotts

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 9.1 - inspect() must fail closed: null unless the MiaoMint/MeloTTS-ONNX companion files
 * (`model.onnx` + `tokens.txt` + `lexicon.txt` + a `metadata.json` identifying itself as
 * `melo-vits`) are ALL present with confidence, and a confident, fully-populated match - sample
 * rate and voice table read from `metadata.json`, never a hardcoded literal - when they are.
 */
class MeloEngineInspectTest {
    private fun engine() = MeloEngine(engineContext())

    private val validMetadata =
        """
        {"model_type":"melo-vits","language_code":"en","add_blank":1,"n_speakers":2,
         "sample_rate":44100,"speaker_id":1,"lang_id":2,"tone_start":7}
        """.trimIndent()

    private fun validBundle(id: String = "melo-en") =
        ModelBundle(
            id = id,
            fileNames = setOf("model.onnx", "tokens.txt", "lexicon.txt", "metadata.json"),
            sideFiles = mapOf("metadata.json" to validMetadata),
            rootPath = "/models/$id",
        )

    @Test
    fun `inspect claims a bundle with model, tokens, lexicon and a valid melo-vits metadata file`() {
        val match = engine().inspect(validBundle())

        assertEquals(MeloEngine.ENGINE_ID, match?.engineId)
        val descriptor = requireNotNull(match).descriptor
        assertEquals("melo-en", descriptor.modelId)
        assertEquals(Origin.BUILT_IN, descriptor.origin)
        assertEquals(44_100, descriptor.sampleRate)
        assertEquals(setOf("0", "1"), descriptor.voices.map { it.id }.toSet())
        assertEquals("1", descriptor.defaultVoiceId)
        assertEquals("/models/melo-en/model.onnx", descriptor.assetPaths[MeloEngine.ACOUSTIC_ASSET])
        assertEquals("/models/melo-en/tokens.txt", descriptor.assetPaths[MeloEngine.TOKENS_ASSET])
        assertEquals("/models/melo-en/lexicon.txt", descriptor.assetPaths[MeloEngine.LEXICON_ASSET])
        assertEquals("/models/melo-en/metadata.json", descriptor.assetPaths[MeloEngine.METADATA_ASSET])
        assertTrue(descriptor.voices.all { it.language == "en" })
    }

    @Test
    fun `inspect claims a bundle that identifies itself via the metadata comment field instead`() {
        val metadata = """{"comment":"melo english export","n_speakers":1,"sample_rate":44100}"""
        val bundle = validBundle().copy(sideFiles = mapOf("metadata.json" to metadata))

        val match = engine().inspect(bundle)

        assertEquals(MeloEngine.ENGINE_ID, match?.engineId)
    }

    @Test
    fun `inspect returns null when the tokens file is missing`() {
        val bundle = validBundle().copy(fileNames = setOf("model.onnx", "lexicon.txt", "metadata.json"))

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun `inspect returns null when the lexicon file is missing`() {
        val bundle = validBundle().copy(fileNames = setOf("model.onnx", "tokens.txt", "metadata.json"))

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun `inspect returns null when the onnx model file is missing`() {
        val bundle = validBundle().copy(fileNames = setOf("tokens.txt", "lexicon.txt", "metadata.json"))

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun `inspect returns null when the metadata side file is absent`() {
        val bundle = validBundle().copy(sideFiles = emptyMap())

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun `inspect returns null when metadata is valid JSON but names neither model_type nor comment as melo`() {
        val bundle = validBundle().copy(sideFiles = mapOf("metadata.json" to """{"n_speakers":2}"""))

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun `inspect returns null when metadata is malformed JSON`() {
        val bundle = validBundle().copy(sideFiles = mapOf("metadata.json" to "{not json"))

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun `inspect returns null when the bundle has no root path to build asset paths from`() {
        val bundle = validBundle().copy(rootPath = null)

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun `inspect returns null for a completely unrelated bundle`() {
        val bundle = ModelBundle(id = "mystery", fileNames = setOf("mystery.bin"))

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun `forcedMatch never returns null and fills in family defaults for a bare acoustic model`() {
        val bundle =
            ModelBundle(id = "sideloaded-melo", fileNames = setOf("model.onnx"), rootPath = "/models/sideloaded-melo")

        val match = engine().forcedMatch(bundle)

        assertEquals(MeloEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertTrue(match.descriptor.voices.isNotEmpty())
        assertTrue(match.descriptor.defaultSpeed in match.descriptor.speedRange)
    }

    @Test
    fun `forcedMatch honors a present metadata file's speaker count even without tokens or lexicon`() {
        val bundle =
            ModelBundle(
                id = "sideloaded-melo",
                fileNames = setOf("model.onnx", "metadata.json"),
                sideFiles = mapOf("metadata.json" to validMetadata),
                rootPath = "/models/sideloaded-melo",
            )

        val match = engine().forcedMatch(bundle)

        assertEquals(setOf("0", "1"), match.descriptor.voices.map { it.id }.toSet())
    }

    @Test
    fun `forcedMatch throws when the bundle has no onnx weights at all`() {
        val bundle = ModelBundle(id = "no-weights", fileNames = setOf("readme.txt"), rootPath = "/models/no-weights")

        assertTrue(runCatching { engine().forcedMatch(bundle) }.isFailure)
    }
}
