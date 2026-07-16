package com.phonetts.engines.melotts

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 9.1 — inspect() must fail closed: null unless MeloTTS's companion files (acoustic model +
 * BERT model + tokenizer + a config with a speaker table) are ALL present with confidence, and
 * a confident, fully-populated match when they are.
 */
class MeloEngineInspectTest {
    private fun engine() = MeloEngine(EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer()))

    private val validConfig =
        """{"data": {"spk2id": {"EN-US": 0, "EN-BR": 1}, "language": "EN"}}"""

    private fun validBundle(id: String = "melo-en") =
        ModelBundle(
            id = id,
            fileNames = setOf("model.onnx", "bert_model.onnx", "tokenizer.json", "config.json"),
            sideFiles = mapOf("config.json" to validConfig),
            rootPath = "/models/$id",
        )

    @Test
    fun `inspect claims a bundle with acoustic, BERT, tokenizer and a valid speaker config`() {
        val match = engine().inspect(validBundle())

        assertEquals(MeloEngine.ENGINE_ID, match?.engineId)
        val descriptor = requireNotNull(match).descriptor
        assertEquals("melo-en", descriptor.modelId)
        assertEquals(Origin.BUILT_IN, descriptor.origin)
        assertEquals(44_100, descriptor.sampleRate)
        assertEquals(setOf("EN-US", "EN-BR"), descriptor.voices.map { it.id }.toSet())
        assertEquals("/models/melo-en/model.onnx", descriptor.assetPaths[MeloEngine.ACOUSTIC_ASSET])
        assertEquals("/models/melo-en/bert_model.onnx", descriptor.assetPaths[MeloEngine.BERT_ASSET])
        assertEquals("/models/melo-en/tokenizer.json", descriptor.assetPaths[MeloEngine.TOKENIZER_ASSET])
        assertTrue(descriptor.voices.all { it.language == "EN" })
    }

    @Test
    fun `inspect returns null when the BERT model file is missing`() {
        val bundle = validBundle().copy(fileNames = setOf("model.onnx", "tokenizer.json", "config.json"))

        assertNull(engine().inspect(bundle))
    }

    @Test
    fun `inspect returns null when the tokenizer file is missing`() {
        val bundle = validBundle().copy(fileNames = setOf("model.onnx", "bert_model.onnx", "config.json"))

        assertNull(engine().inspect(bundle))
    }

    @Test
    fun `inspect returns null when the acoustic model file is missing`() {
        val bundle = validBundle().copy(fileNames = setOf("bert_model.onnx", "tokenizer.json", "config.json"))

        assertNull(engine().inspect(bundle))
    }

    @Test
    fun `inspect returns null when the config side file is absent`() {
        val bundle = validBundle().copy(sideFiles = emptyMap())

        assertNull(engine().inspect(bundle))
    }

    @Test
    fun `inspect returns null when the config has no spk2id speaker table`() {
        val bundle = validBundle().copy(sideFiles = mapOf("config.json" to """{"data": {"language": "EN"}}"""))

        assertNull(engine().inspect(bundle))
    }

    @Test
    fun `inspect returns null when the bundle has no root path to build asset paths from`() {
        val bundle = validBundle().copy(rootPath = null)

        assertNull(engine().inspect(bundle))
    }

    @Test
    fun `inspect returns null for a completely unrelated bundle`() {
        val bundle = ModelBundle(id = "mystery", fileNames = setOf("mystery.bin"))

        assertNull(engine().inspect(bundle))
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
    fun `forcedMatch throws when the bundle has no acoustic weights at all`() {
        val bundle = ModelBundle(id = "no-weights", fileNames = setOf("readme.txt"), rootPath = "/models/no-weights")

        assertTrue(runCatching { engine().forcedMatch(bundle) }.isFailure)
    }
}
