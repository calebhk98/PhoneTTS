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

    // issue #95: speaches-ai/*, ufozone/*, and Lucasllfs/Razo-piper-voice all ship a valid Piper
    // sidecar under the plain name "config.json" instead of "<voice>.onnx.json".
    @Test
    fun `claims a single onnx paired with a plain config json sidecar`() {
        val bundle =
            ModelBundle(
                id = "speaches-ai-voice",
                fileNames = setOf("voice.onnx", "config.json"),
                sideFiles = mapOf("config.json" to validSidecar),
                rootPath = "/models/speaches-ai-voice",
            )

        val match = assertNotNull(engine.inspect(bundle))

        val descriptor = match.descriptor
        assertEquals(Origin.BUILT_IN, descriptor.origin)
        assertEquals(22_050, descriptor.sampleRate)
        assertEquals(listOf("voice"), descriptor.voices.map { it.id })
        assertEquals("/models/speaches-ai-voice/voice.onnx", descriptor.assetPaths["voice.onnx"])
        assertEquals("/models/speaches-ai-voice/config.json", descriptor.assetPaths["voice.onnx.json"])
    }

    @Test
    fun `returns null when the single onnx's config json is not piper shaped`() {
        val bundle =
            ModelBundle(
                id = "foreign-config-json",
                fileNames = setOf("voice.onnx", "config.json"),
                sideFiles = mapOf("config.json" to """{"totally_unrelated": true}"""),
            )

        assertInspectRejects(engine, bundle)
    }

    // ayousanz/piper-plus-* also ships a Piper-shaped config.json but needs extra
    // language_id/prosody inputs this engine does not feed — out of scope for issue #95, but this
    // pins the SAME rejection a foreign config.json gets, since [PiperVoiceConfig.parse] can't tell
    // the two apart from the sidecar alone. Named separately so a future piper-plus fix updates the
    // right expectation, not this one.
    @Test
    fun `does not falsely pair a stray config json across a multi onnx bundle`() {
        val bundle =
            ModelBundle(
                id = "multi-onnx-with-stray-config",
                fileNames = setOf("voice-a.onnx", "voice-b.onnx", "config.json"),
                sideFiles = mapOf("config.json" to validSidecar),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `still resolves the stem sidecar path when both stem json and a plain config json exist`() {
        val bundle =
            ModelBundle(
                id = "has-both-sidecars",
                fileNames = setOf("voice.onnx", "voice.onnx.json", "config.json"),
                sideFiles =
                    mapOf(
                        "voice.onnx.json" to validSidecar,
                        "config.json" to """{"totally_unrelated": true}""",
                    ),
                rootPath = "/models/has-both-sidecars",
            )

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals("/models/has-both-sidecars/voice.onnx.json", match.descriptor.assetPaths["voice.onnx.json"])
    }

    @Test
    fun `forcedMatch also accepts a plain config json sidecar for a single onnx`() {
        val bundle =
            ModelBundle(
                id = "forced-config-json",
                fileNames = setOf("voice.onnx", "config.json"),
                sideFiles = mapOf("config.json" to validSidecar),
                rootPath = "/models/forced-config-json",
            )

        val match = engine.forcedMatch(bundle)

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals(22_050, match.descriptor.sampleRate)
        assertEquals("/models/forced-config-json/config.json", match.descriptor.assetPaths["voice.onnx.json"])
    }
}
