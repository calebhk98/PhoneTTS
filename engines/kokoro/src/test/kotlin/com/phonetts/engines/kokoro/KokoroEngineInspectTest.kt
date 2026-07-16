package com.phonetts.engines.kokoro

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.testing.FakePhonemizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VALID_CONFIG =
    """{"family": "kokoro", "sample_rate": 24000, "speed_min": 0.5, "speed_max": 2.0, """ +
        """"default_voice": "af_heart", "default_speed": 1.0}"""

// Two voices only - a small fake table for the seam, NOT a claim that these are two of the
// real 54 Kokoro voices. Names/languages/embeddings here are test fixtures.
private const val VALID_VOICES =
    """[
        {"id": "af_heart", "name": "Heart", "language": "en-us", "embedding": [0.1, 0.2, 0.3]},
        {"id": "bf_emma", "name": "Emma", "language": "en-gb", "embedding": [0.4, -0.1, 0.2]}
    ]"""

private const val FOREIGN_CONFIG = """{"family": "piper", "sample_rate": 22050}"""

/**
 * inspect() must fail closed (spec §9.1): a confident claim only when BOTH companion files
 * (config + the voices/embeddings table) are present and identify Kokoro specifically; null for
 * a bare .onnx and for a foreign bundle that merely happens to share a file name.
 */
class KokoroEngineInspectTest {
    private fun engine() = KokoroEngine(EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer()))

    @Test
    fun claimsAFullKokoroBundle() {
        val bundle =
            ModelBundle(
                id = "kokoro-test",
                fileNames = setOf("model.onnx", "config.json", "voices.json"),
                sideFiles = mapOf("config.json" to VALID_CONFIG, "voices.json" to VALID_VOICES),
                rootPath = "/models/kokoro-test",
            )

        val match = engine().inspect(bundle)

        assertNotNull(match)
        assertEquals("kokoro", match.engineId)
        val descriptor = match.descriptor
        assertEquals(24_000, descriptor.sampleRate)
        assertEquals(Origin.BUILT_IN, descriptor.origin)
        assertEquals(setOf("af_heart", "bf_emma"), descriptor.voices.map { it.id }.toSet())
        assertEquals("af_heart", descriptor.defaultVoiceId)
        assertEquals(0.5f, descriptor.speedRange.start)
        assertEquals(2.0f, descriptor.speedRange.endInclusive)
        assertEquals("/models/kokoro-test/model.onnx", descriptor.assetPaths["weights"])
        assertEquals("/models/kokoro-test/voices.json", descriptor.assetPaths["voicesTable"])
    }

    @Test
    fun refusesABareOnnxFile() {
        val bundle = ModelBundle(id = "bare", fileNames = setOf("model.onnx"))

        assertNull(engine().inspect(bundle))
    }

    @Test
    fun refusesABundleMissingTheVoicesTableEvenWithAValidConfig() {
        val bundle =
            ModelBundle(
                id = "no-voices-table",
                fileNames = setOf("model.onnx", "config.json"),
                sideFiles = mapOf("config.json" to VALID_CONFIG),
            )

        assertNull(engine().inspect(bundle))
    }

    @Test
    fun refusesAForeignBundleWithADifferentFamilyMarker() {
        val bundle =
            ModelBundle(
                id = "foreign",
                fileNames = setOf("model.onnx", "config.json", "voices.json"),
                sideFiles = mapOf("config.json" to FOREIGN_CONFIG, "voices.json" to VALID_VOICES),
            )

        assertNull(engine().inspect(bundle))
    }

    @Test
    fun refusesABundleWithAnEmptyVoicesTable() {
        val bundle =
            ModelBundle(
                id = "empty-voices",
                fileNames = setOf("model.onnx", "config.json", "voices.json"),
                sideFiles = mapOf("config.json" to VALID_CONFIG, "voices.json" to "[]"),
            )

        assertNull(engine().inspect(bundle))
    }

    @Test
    fun forcedMatchNeverReturnsNullAndFillsFamilyDefaultsWhenNoManifestIsPresent() {
        val bundle = ModelBundle(id = "sideloaded", fileNames = setOf("weights.onnx"))

        val match = engine().forcedMatch(bundle)

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertTrue(match.descriptor.voices.isNotEmpty())
    }

    @Test
    fun forcedMatchUsesTheRealManifestWhenOneIsPresent() {
        val bundle =
            ModelBundle(
                id = "sideloaded-with-manifest",
                fileNames = setOf("model.onnx", "config.json", "voices.json"),
                sideFiles = mapOf("config.json" to VALID_CONFIG, "voices.json" to VALID_VOICES),
            )

        val match = engine().forcedMatch(bundle)

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals(setOf("af_heart", "bf_emma"), match.descriptor.voices.map { it.id }.toSet())
    }

    @Test
    fun forcedMatchThrowsOnlyWhenThereIsNoWeightsFileAtAll() {
        val bundle = ModelBundle(id = "no-weights", fileNames = setOf("config.json"))

        assertFailsWith<IllegalArgumentException> { engine().forcedMatch(bundle) }
    }
}
