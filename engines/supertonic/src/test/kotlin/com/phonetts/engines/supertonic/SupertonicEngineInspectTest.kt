package com.phonetts.engines.supertonic

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
 * Proves inspect() fails closed (spec §9.1): only a bundle carrying all four ONNX graphs, a
 * marker-carrying `onnx/tts.json`, `onnx/unicode_indexer.json`, and at least one
 * `voice_styles/<name>.json` is claimed. forcedMatch() is the opposite: it never refuses a user's
 * explicit choice as long as the four graphs are present, throwing only when they genuinely aren't.
 */
class SupertonicEngineInspectTest {
    private val engine = SupertonicEngine(engineContext())

    @Test
    fun `inspect claims a complete bundle and produces a full descriptor`() {
        val bundle = validBundle()

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(SupertonicEngine.ENGINE_ID, match.engineId)
        val descriptor = match.descriptor
        assertEquals(Origin.BUILT_IN, descriptor.origin)
        assertEquals(44_100, descriptor.sampleRate)
        assertEquals(listOf("F1", "M1"), descriptor.voices.map { it.id })
        assertEquals("F1", descriptor.defaultVoiceId)
        assertEquals(
            "/models/${bundle.id}/${SupertonicEngine.VECTOR_ESTIMATOR_FILE}",
            descriptor.assetPaths[SupertonicEngine.VECTOR_ESTIMATOR_ASSET],
        )
        assertEquals(
            "/models/${bundle.id}/${SupertonicEngine.VOICE_STYLES_DIR}M1${SupertonicEngine.VOICE_STYLE_SUFFIX}",
            descriptor.assetPaths["voiceStyle:M1"],
        )
    }

    @Test
    fun `inspect declares a speed parameter routed to the model's native duration knob`() {
        val match = assertNotNull(engine.inspect(validBundle()))

        val speedParam = assertNotNull(match.descriptor.speedParameter)
        assertEquals(0.7f, speedParam.range?.start)
        assertEquals(2.0f, speedParam.range?.endInclusive)
        assertEquals(1.05f, match.descriptor.defaultSpeed)
    }

    @Test
    fun `inspect declares a language choice parameter covering the 31 supported languages plus na`() {
        val match = assertNotNull(engine.inspect(validBundle()))

        val languageParam = match.descriptor.parameters.first { it.id == SupertonicEngine.LANGUAGE_PARAMETER_ID }
        assertEquals(32, languageParam.choices.size)
        assertTrue("en" in languageParam.choices)
        assertTrue("na" in languageParam.choices)
        assertEquals("en", languageParam.choices[languageParam.default.toInt()])
    }

    @Test
    fun `inspect discovers voices by file name, not a hardcoded list`() {
        val bundle = validBundle(voiceIds = setOf("Custom1", "Custom2", "Custom3"))

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(listOf("Custom1", "Custom2", "Custom3"), match.descriptor.voices.map { it.id })
    }

    @Test
    fun `inspect fails closed when a graph is missing`() {
        val bundle =
            ModelBundle(
                id = "incomplete",
                fileNames =
                    setOf(
                        SupertonicEngine.DP_FILE,
                        SupertonicEngine.TEXT_ENCODER_FILE,
                        SupertonicEngine.VOCODER_FILE,
                        SupertonicEngine.CONFIG_FILE,
                        SupertonicEngine.INDEXER_FILE,
                        "voice_styles/M1.json",
                    ),
                sideFiles = mapOf(SupertonicEngine.CONFIG_FILE to VALID_CONFIG_JSON),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the config marker is absent`() {
        val bundle =
            validBundle().copy(
                sideFiles = mapOf(SupertonicEngine.CONFIG_FILE to """{"totally":"unrelated"}"""),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when there are no voice styles at all`() {
        val bundle = validBundle(voiceIds = emptySet())

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect never claims an unrelated bundle`() {
        val bundle = ModelBundle(id = "piper-voice", fileNames = setOf("en_US-voice.onnx", "en_US-voice.onnx.json"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `forcedMatch never returns null when all four graphs are present`() {
        val bundle =
            ModelBundle(
                id = "manual",
                fileNames =
                    setOf(
                        SupertonicEngine.DP_FILE,
                        SupertonicEngine.TEXT_ENCODER_FILE,
                        SupertonicEngine.VECTOR_ESTIMATOR_FILE,
                        SupertonicEngine.VOCODER_FILE,
                    ),
                rootPath = "/models/manual",
            )

        val match = engine.forcedMatch(bundle)

        assertEquals(SupertonicEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals(listOf(SupertonicEngine.DEFAULT_VOICE.id), match.descriptor.voices.map { it.id })
        // No real voice_styles/<name>.json for the DEFAULT_VOICE fallback, so no asset path is
        // recorded for it (see SupertonicEngine.buildAssetPaths's KDoc) - load() then fails loudly,
        // not silently, only if/when that specific voice is actually synthesized.
        assertEquals(null, match.descriptor.assetPaths["voiceStyle:${SupertonicEngine.DEFAULT_VOICE.id}"])
    }

    @Test
    fun `forcedMatch uses the real discovered voices when voice styles are present`() {
        val bundle =
            ModelBundle(
                id = "manual-with-voices",
                fileNames =
                    setOf(
                        SupertonicEngine.DP_FILE,
                        SupertonicEngine.TEXT_ENCODER_FILE,
                        SupertonicEngine.VECTOR_ESTIMATOR_FILE,
                        SupertonicEngine.VOCODER_FILE,
                        "voice_styles/M1.json",
                    ),
                rootPath = "/models/manual-with-voices",
            )

        val match = engine.forcedMatch(bundle)

        assertEquals(listOf("M1"), match.descriptor.voices.map { it.id })
        assertEquals(
            "/models/manual-with-voices/voice_styles/M1.json",
            match.descriptor.assetPaths["voiceStyle:M1"],
        )
    }

    @Test
    fun `forcedMatch throws when even one of the four graphs is missing`() {
        val bundle =
            ModelBundle(
                id = "partial",
                fileNames = setOf(SupertonicEngine.DP_FILE, SupertonicEngine.TEXT_ENCODER_FILE),
            )

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }

    @Test
    fun `forcedMatch throws for a bundle with none of Supertonic's graphs`() {
        val bundle = ModelBundle(id = "unusable", fileNames = setOf("readme.txt"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }
}
