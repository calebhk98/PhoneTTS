package com.phonetts.engines.inflect

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.ModelParameter
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import com.phonetts.engines.common.testing.onnxEngineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Spec §9.1 (inspect() fails closed) and §9.2/§5.7 (the descriptor is the SSOT for every
 * user-visible model fact). Every fact asserted here is one the engine READS out of the bundle,
 * so a regression that starts guessing instead shows up as a failure rather than as plausible
 * audio.
 */
class InflectEngineInspectTest {
    private fun engine() = InflectEngine(onnxEngineContext())

    @Test
    fun `claims a genuine Inflect bundle and reads its facts from the config`() {
        val match = assertNotNull(engine().inspect(InflectFixtures.flatBundle()))

        assertEquals(InflectEngine.ENGINE_ID, match.engineId)
        val descriptor = match.descriptor
        assertEquals(24_000, descriptor.sampleRate, "sample rate must come from data.sampling_rate")
        assertEquals(Origin.BUILT_IN, descriptor.origin)
        assertEquals(listOf(InflectEngine.VOICE_ID), descriptor.voices.map { it.id })
        assertEquals(InflectEngine.VOICE_ID, descriptor.defaultVoiceId)
        assertEquals("en", descriptor.voices.single().language)
    }

    @Test
    fun `the two graph paths are recorded, joined onto the bundle root`() {
        val descriptor = assertNotNull(engine().inspect(InflectFixtures.flatBundle())).descriptor

        assertEquals(
            "${InflectFixtures.ROOT}/${InflectEngine.DURATION_FILE}",
            descriptor.assetPaths[InflectEngine.DURATION_ASSET],
        )
        assertEquals(
            "${InflectFixtures.ROOT}/${InflectEngine.DECODE_FILE}",
            descriptor.assetPaths[InflectEngine.DECODE_ASSET],
        )
    }

    @Test
    fun `the published repo layout with graphs under onnx is claimed too`() {
        val descriptor = assertNotNull(engine().inspect(InflectFixtures.repoLayoutBundle())).descriptor

        assertEquals(
            "${InflectFixtures.ROOT}/onnx/${InflectEngine.DURATION_FILE}",
            descriptor.assetPaths[InflectEngine.DURATION_ASSET],
        )
        assertEquals(
            "${InflectFixtures.ROOT}/onnx/${InflectEngine.DECODE_FILE}",
            descriptor.assetPaths[InflectEngine.DECODE_ASSET],
        )
    }

    @Test
    fun `the size variant is labelled from the config's own widths, not the folder name`() {
        // Same bundle id both times: only the config differs, so nothing but the model's own
        // declared architecture can be driving the label.
        val micro = assertNotNull(engine().inspect(InflectFixtures.flatBundle(InflectFixtures.MICRO_CONFIG, "x")))
        val nano = assertNotNull(engine().inspect(InflectFixtures.flatBundle(InflectFixtures.NANO_CONFIG, "x")))

        assertEquals(InflectVariant.MICRO_V2.displayName, micro.descriptor.displayName)
        assertEquals(InflectVariant.NANO_V2.displayName, nano.descriptor.displayName)
    }

    @Test
    fun `an unrecognised size is labelled honestly rather than mislabelled as a known one`() {
        val match = assertNotNull(engine().inspect(InflectFixtures.flatBundle(InflectFixtures.FUTURE_SIZE_CONFIG)))

        assertEquals(InflectVariant.UNKNOWN.displayName, match.descriptor.displayName)
        // ...and its facts still come from its own config, not from a known size's.
        assertEquals(22_050, match.descriptor.sampleRate)
    }

    @Test
    fun `both native knobs are advertised, each with the model's own published range`() {
        val descriptor = assertNotNull(engine().inspect(InflectFixtures.flatBundle())).descriptor

        assertEquals(
            listOf(ModelParameter.SPEED_ID, InflectEngine.VARIATION_ID),
            descriptor.parameters.map { it.id },
        )
        assertEquals(InflectEngine.SPEED_RANGE, descriptor.speedRange)
        assertEquals(InflectEngine.DEFAULT_SPEED, descriptor.defaultSpeed)
        val variation = descriptor.parameters.single { it.id == InflectEngine.VARIATION_ID }
        assertEquals(InflectEngine.VARIATION_RANGE, variation.range)
        assertEquals(InflectEngine.DEFAULT_VARIATION, variation.default)
    }

    @Test
    fun `one fixed voice means no voice blending is advertised`() {
        val descriptor = assertNotNull(engine().inspect(InflectFixtures.flatBundle())).descriptor

        assertTrue(!descriptor.supportsVoiceBlend, "Inflect has no continuous speaker vector to interpolate")
    }

    @Test
    fun `fails closed when the decode graph is missing`() {
        assertInspectRejects(
            engine(),
            ModelBundle(
                id = "half-a-pipeline",
                fileNames = setOf(InflectEngine.DURATION_FILE, InflectEngine.CONFIG_FILE),
                sideFiles = mapOf(InflectEngine.CONFIG_FILE to InflectFixtures.MICRO_CONFIG),
            ),
        )
    }

    @Test
    fun `fails closed when the duration graph is missing`() {
        assertInspectRejects(
            engine(),
            ModelBundle(
                id = "half-a-pipeline",
                fileNames = setOf(InflectEngine.DECODE_FILE, InflectEngine.CONFIG_FILE),
                sideFiles = mapOf(InflectEngine.CONFIG_FILE to InflectFixtures.MICRO_CONFIG),
            ),
        )
    }

    @Test
    fun `fails closed when there is no config to identify the bundle by`() {
        assertInspectRejects(
            engine(),
            ModelBundle(
                id = "bare-graphs",
                fileNames = setOf(InflectEngine.DURATION_FILE, InflectEngine.DECODE_FILE),
            ),
        )
    }

    @Test
    fun `fails closed on another family's config, even with identically named graphs`() {
        assertInspectRejects(engine(), InflectFixtures.flatBundle(InflectFixtures.FOREIGN_CONFIG))
    }

    @Test
    fun `fails closed on a malformed config rather than falling back to family defaults`() {
        assertInspectRejects(engine(), InflectFixtures.flatBundle("{ not json"))
    }

    @Test
    fun `forcedMatch honours the user's assignment and still reads the config when there is one`() {
        val match = engine().forcedMatch(InflectFixtures.flatBundle(InflectFixtures.NANO_CONFIG))

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals(InflectVariant.NANO_V2.displayName, match.descriptor.displayName)
        assertEquals(24_000, match.descriptor.sampleRate)
    }

    @Test
    fun `forcedMatch falls back to the family sample rate when the bundle has no config`() {
        val bundle =
            ModelBundle(
                id = "bare-graphs",
                fileNames = setOf(InflectEngine.DURATION_FILE, InflectEngine.DECODE_FILE),
                rootPath = InflectFixtures.ROOT,
            )

        val descriptor = engine().forcedMatch(bundle).descriptor

        assertEquals(InflectEngine.FAMILY_SAMPLE_RATE, descriptor.sampleRate)
        assertEquals(InflectVariant.UNKNOWN.displayName, descriptor.displayName)
    }

    @Test
    fun `forcedMatch refuses a bundle missing half the pipeline - it is structurally unrunnable`() {
        val bundle = ModelBundle(id = "half", fileNames = setOf(InflectEngine.DURATION_FILE))

        assertFailsWith<IllegalArgumentException> { engine().forcedMatch(bundle) }
    }
}
