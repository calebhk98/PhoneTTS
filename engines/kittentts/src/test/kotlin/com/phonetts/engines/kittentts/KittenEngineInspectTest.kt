package com.phonetts.engines.kittentts

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers spec §9.1: `inspect()` must claim genuine KittenTTS bundles by their companion files
 * and fail closed -- return null, never guess -- for a bare `.onnx` and for a foreign/unrelated
 * bundle. The real voice companion is `voices.npz` (a binary ZIP archive), which
 * [com.phonetts.core.model.ModelBundle] cannot carry as a text side file, so `inspect()` can only
 * confirm the file is *present by name*, not read its embeddings (see [KittenEngine] KDoc).
 * Also covers `forcedMatch()`: the user's manual assignment is authoritative, so it never refuses
 * a bundle that has at least one `.onnx` weights file, filling in family defaults (a single
 * "Default" voice) when no `voices.npz` is present.
 */
class KittenEngineInspectTest {
    private val engine = KittenEngine(engineContext())

    private val validConfig = """{"model_type":"kitten_tts","sample_rate":24000}"""

    @Test
    fun `bare onnx with no companion files is refused`() {
        val bundle =
            ModelBundle(
                id = "mystery-model",
                fileNames = setOf("weights.onnx"),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `foreign bundle whose config lacks the KittenTTS marker is refused`() {
        val bundle =
            ModelBundle(
                id = "some-other-family",
                fileNames = setOf("weights.onnx", KittenEngine.CONFIG_FILE, KittenEngine.VOICES_FILE),
                sideFiles = mapOf(KittenEngine.CONFIG_FILE to """{"model_type":"totally_different_family"}"""),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `bundle missing the voices npz is refused even with a matching config`() {
        val bundle =
            ModelBundle(
                id = "half-a-bundle",
                fileNames = setOf("weights.onnx", KittenEngine.CONFIG_FILE),
                sideFiles = mapOf(KittenEngine.CONFIG_FILE to validConfig),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `genuine KittenTTS bundle is claimed and produces a complete descriptor`() {
        val bundle =
            ModelBundle(
                id = "kitten-nano",
                fileNames = setOf("kitten_tts_nano.onnx", KittenEngine.CONFIG_FILE, KittenEngine.VOICES_FILE),
                sideFiles = mapOf(KittenEngine.CONFIG_FILE to validConfig),
                rootPath = "/models/kitten-nano",
            )

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(KittenEngine.ENGINE_ID, match.engineId)

        val descriptor = match.descriptor
        assertEquals(24_000, descriptor.sampleRate)
        assertEquals(8, descriptor.voices.size)
        assertEquals(KittenEngine.VOICE_NAMES, descriptor.voices.map { it.name })
        assertEquals(KittenEngine.VOICE_NAMES, descriptor.voices.map { it.id })
        assertTrue(descriptor.voices.all { it.language == KittenEngine.LANGUAGE })
        assertEquals(KittenEngine.VOICE_NAMES.first(), descriptor.defaultVoiceId)
        assertEquals(
            "/models/kitten-nano/kitten_tts_nano.onnx",
            descriptor.assetPaths[KittenEngine.MODEL_ASSET_KEY],
        )
        assertEquals(
            "/models/kitten-nano/${KittenEngine.CONFIG_FILE}",
            descriptor.assetPaths[KittenEngine.CONFIG_ASSET_KEY],
        )
        assertEquals(
            "/models/kitten-nano/${KittenEngine.VOICES_FILE}",
            descriptor.assetPaths[KittenEngine.VOICES_ASSET_KEY],
        )
    }

    @Test
    fun `forcedMatch never returns null and fills in a default voice when no voices npz is present`() {
        val bundle =
            ModelBundle(
                id = "sideloaded-kitten",
                fileNames = setOf("weights.onnx"),
                rootPath = "/models/sideloaded-kitten",
            )

        val match = engine.forcedMatch(bundle)

        assertEquals(KittenEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals(listOf("Default"), match.descriptor.voices.map { it.name })
        assertTrue(match.descriptor.defaultSpeed in match.descriptor.speedRange)
        assertEquals(
            "/models/sideloaded-kitten/weights.onnx",
            match.descriptor.assetPaths[KittenEngine.MODEL_ASSET_KEY],
        )
    }

    @Test
    fun `forcedMatch uses the real 8-voice table when voices npz is present`() {
        val bundle =
            ModelBundle(
                id = "sideloaded-with-voices",
                fileNames = setOf("weights.onnx", KittenEngine.VOICES_FILE),
                rootPath = "/models/sideloaded-with-voices",
            )

        val match = engine.forcedMatch(bundle)

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals(8, match.descriptor.voices.size)
        assertEquals(KittenEngine.VOICE_NAMES, match.descriptor.voices.map { it.name })
    }

    @Test
    fun `forcedMatch throws when the bundle has no onnx weights file at all`() {
        val bundle = ModelBundle(id = "no-weights", fileNames = setOf("readme.txt"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }
}
