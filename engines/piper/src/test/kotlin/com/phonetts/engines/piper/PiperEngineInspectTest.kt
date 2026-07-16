package com.phonetts.engines.piper

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * §9.1 fail-closed coverage for [PiperEngine.inspect]: a Piper bundle is only ever a `*.onnx`
 * WITH its `*.onnx.json` sidecar carrying Piper's own fields (phoneme_id_map, audio.sample_rate).
 * A bare `.onnx`, a foreign bundle, or an onnx+json pair whose json is not a Piper sidecar must
 * all come back null so the resolver falls through to the user-pick fallback — never a guess.
 */
class PiperEngineInspectTest {
    private val engine = PiperEngine(engineContext())

    private val validSidecar =
        """
        {
          "audio": {"sample_rate": 22050},
          "espeak": {"voice": "en-us"},
          "phoneme_id_map": {"^": [1], "$": [2], "_": [0], "h": [10], "i": [11]},
          "inference": {"noise_scale": 0.667, "length_scale": 1.0, "noise_w": 0.8}
        }
        """.trimIndent()

    @Test
    fun `claims an onnx file that has a valid piper sidecar and populates the descriptor`() {
        val bundle =
            ModelBundle(
                id = "en_US-amy-medium",
                fileNames = setOf("voice.onnx", "voice.onnx.json"),
                sideFiles = mapOf("voice.onnx.json" to validSidecar),
                rootPath = "/models/en_US-amy-medium",
            )

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals("piper", match.engineId)
        val descriptor = match.descriptor
        assertEquals("piper", descriptor.engineId)
        assertEquals(Origin.BUILT_IN, descriptor.origin)
        assertEquals(22_050, descriptor.sampleRate)
        assertEquals(listOf("voice"), descriptor.voices.map { it.id })
        assertEquals("en-us", descriptor.voices.single().language)
        assertEquals("/models/en_US-amy-medium/voice.onnx", descriptor.assetPaths["voice.onnx"])
        assertEquals("/models/en_US-amy-medium/voice.onnx.json", descriptor.assetPaths["voice.onnx.json"])
    }

    @Test
    fun `returns null for a bare onnx with no sidecar at all`() {
        val bundle = ModelBundle(id = "bare", fileNames = setOf("voice.onnx"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `returns null when the onnx has a sidecar but it lacks piper fields`() {
        val bundle =
            ModelBundle(
                id = "foreign-pair",
                fileNames = setOf("voice.onnx", "voice.onnx.json"),
                sideFiles = mapOf("voice.onnx.json" to """{"totally_unrelated": true}"""),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `returns null for a foreign bundle with no onnx file at all`() {
        val bundle =
            ModelBundle(
                id = "some-other-model",
                fileNames = setOf("model.bin", "config.json"),
                sideFiles = mapOf("config.json" to """{"family": "not-piper"}"""),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `returns null when the sidecar json is malformed`() {
        val bundle =
            ModelBundle(
                id = "broken-json",
                fileNames = setOf("voice.onnx", "voice.onnx.json"),
                sideFiles = mapOf("voice.onnx.json" to "{not valid json"),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `forcedMatch never returns null and fills in family defaults for a bare onnx`() {
        val bundle = ModelBundle(id = "sideloaded", fileNames = setOf("mystery.onnx"), rootPath = "/models/sideloaded")

        val match = engine.forcedMatch(bundle)

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals(22_050, match.descriptor.sampleRate)
        assertEquals(listOf("mystery"), match.descriptor.voices.map { it.id })
    }

    @Test
    fun `forcedMatch throws when the bundle has no onnx weights at all`() {
        val bundle = ModelBundle(id = "no-weights", fileNames = setOf("readme.txt"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }
}
