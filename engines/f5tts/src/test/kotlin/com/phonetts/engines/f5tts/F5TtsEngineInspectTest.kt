package com.phonetts.engines.f5tts

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
 * Proves inspect() fails closed (spec §9.1): only a bundle carrying all three F5-TTS ONNX graphs
 * AND `vocab.txt` is claimed (README-io.md's fingerprint). forcedMatch() is the opposite: it
 * never refuses a user's explicit choice as long as the pipeline is structurally usable (all
 * three weight graphs present), throwing only when it genuinely isn't.
 */
class F5TtsEngineInspectTest {
    private val engine = F5TtsEngine(engineContext())

    @Test
    fun `inspect claims a bundle with all three graphs and vocab txt`() {
        val bundle = validBundle()

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(F5TtsEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.BUILT_IN, match.descriptor.origin)
        assertEquals(24_000, match.descriptor.sampleRate)
        assertEquals(
            "/models/${bundle.id}/${F5TtsEngine.TRANSFORMER_FILE}",
            match.descriptor.assetPaths[F5TtsEngine.TRANSFORMER_ASSET],
        )
        assertEquals(
            "/models/${bundle.id}/${F5TtsEngine.VOCAB_FILE}",
            match.descriptor.assetPaths[F5TtsEngine.VOCAB_ASSET],
        )
        assertEquals(listOf(F5TtsEngine.DEFAULT_VOICE.id), match.descriptor.voices.map { it.id })
    }

    @Test
    fun `inspect declares a speed parameter routed to the model's native max_duration`() {
        val match = assertNotNull(engine.inspect(validBundle()))

        val speedParam = assertNotNull(match.descriptor.speedParameter)
        assertEquals(1.0f, speedParam.default)
        assertEquals(1.0f, match.descriptor.defaultSpeed)
    }

    @Test
    fun `inspect declares no NFE or CFG parameter -- both are baked into the graph, not discoverable`() {
        val match = assertNotNull(engine.inspect(validBundle()))

        assertEquals(listOf("speed"), match.descriptor.parameters.map { it.id })
    }

    @Test
    fun `inspect discovers a bundled reference clip pair as a voice`() {
        val bundle = validBundle(extraFiles = setOf("alice.reference.wav", "alice.reference.txt"))

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(listOf("alice"), match.descriptor.voices.map { it.id })
        assertEquals("alice", match.descriptor.defaultVoiceId)
        assertEquals(
            "/models/${bundle.id}/alice.reference.wav",
            match.descriptor.assetPaths["alice.referenceAudio"],
        )
        assertEquals(
            "/models/${bundle.id}/alice.reference.txt",
            match.descriptor.assetPaths["alice.referenceText"],
        )
    }

    @Test
    fun `inspect discovers multiple reference clip voices, sorted`() {
        val bundle =
            validBundle(
                extraFiles =
                    setOf(
                        "zoe.reference.wav",
                        "zoe.reference.txt",
                        "alice.reference.wav",
                        "alice.reference.txt",
                    ),
            )

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(listOf("alice", "zoe"), match.descriptor.voices.map { it.id })
    }

    @Test
    fun `inspect ignores a reference audio file with no matching transcript`() {
        val bundle = validBundle(extraFiles = setOf("alice.reference.wav"))

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(listOf(F5TtsEngine.DEFAULT_VOICE.id), match.descriptor.voices.map { it.id })
    }

    @Test
    fun `inspect fails closed when the transformer graph is missing`() {
        val bundle =
            ModelBundle(
                id = "incomplete",
                fileNames = setOf(F5TtsEngine.PREPROCESS_FILE, F5TtsEngine.DECODE_FILE, F5TtsEngine.VOCAB_FILE),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when vocab txt is missing`() {
        val bundle =
            ModelBundle(
                id = "no-vocab",
                fileNames = setOf(F5TtsEngine.PREPROCESS_FILE, F5TtsEngine.TRANSFORMER_FILE, F5TtsEngine.DECODE_FILE),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect never claims an unrelated bundle`() {
        val bundle = ModelBundle(id = "piper-voice", fileNames = setOf("en_US-voice.onnx", "en_US-voice.onnx.json"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect never claims a bare single onnx file`() {
        val bundle = ModelBundle(id = "some-model", fileNames = setOf(F5TtsEngine.TRANSFORMER_FILE))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `forcedMatch never returns null when all three graphs are present`() {
        val bundle =
            ModelBundle(
                id = "manual",
                fileNames = setOf(F5TtsEngine.PREPROCESS_FILE, F5TtsEngine.TRANSFORMER_FILE, F5TtsEngine.DECODE_FILE),
                rootPath = "/models/manual",
            )

        val match = engine.forcedMatch(bundle)

        assertEquals(F5TtsEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertTrue(match.descriptor.voices.isNotEmpty())
        assertEquals(
            "/models/manual/${F5TtsEngine.PREPROCESS_FILE}",
            match.descriptor.assetPaths[F5TtsEngine.PREPROCESS_ASSET],
        )
    }

    @Test
    fun `forcedMatch throws when even one of the three graphs is missing`() {
        val bundle =
            ModelBundle(id = "partial", fileNames = setOf(F5TtsEngine.PREPROCESS_FILE, F5TtsEngine.TRANSFORMER_FILE))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }

    @Test
    fun `forcedMatch throws for a bundle with none of F5-TTS's graphs`() {
        val bundle = ModelBundle(id = "unusable", fileNames = setOf("readme.txt"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }
}
