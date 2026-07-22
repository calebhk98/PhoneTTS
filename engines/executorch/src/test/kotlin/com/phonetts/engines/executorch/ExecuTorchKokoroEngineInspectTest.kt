package com.phonetts.engines.executorch

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val VALID_CONFIG =
    """{"modelName": "kokoro", "sample_rate": 24000, "speed_min": 0.5, "speed_max": 2.0, """ +
        """"default_voice": "af_heart", "default_speed": 1.0}"""

private const val FOREIGN_CONFIG = """{"modelName": "piper"}"""
private const val VOCAB = """{"h": 1, "e": 2, "l": 3, "o": 4}"""

// Two voices only -- a small fake fingerprint, NOT a claim these are two of the real ~20 voices.
// Each voice is its own file (VERIFIED, HF react-native-executorch-kokoro `voices/` listing), so
// inspect() sees these as bare file NAMES without touching any bytes.
private val VOICE_FILE_NAMES = setOf("voices/af_heart.bin", "voices/bf_emma.bin")

// VERIFIED (HF react-native-executorch-kokoro): the real repo's two-graph pipeline file names.
private const val DURATION_FILE = "xnnpack/standard/duration_predictor_std.pte"
private const val SYNTH_FILE = "xnnpack/standard/synthesizer_std.pte"
private const val VOCAB_FILE = ExecuTorchKokoroEngine.VOCAB_FILE

/**
 * inspect() must fail closed (spec §9.1): a confident claim only when ALL companion signals are
 * present -- a config.json naming the "kokoro" family, a vocab.json, AND at least one
 * voices/<name>.bin file present by name -- null for anything less, or a foreign bundle that
 * merely happens to share a file name.
 */
class ExecuTorchKokoroEngineInspectTest {
    private fun engine() = ExecuTorchKokoroEngine(engineContext())

    private fun fullBundle(id: String = "et-kokoro-test") =
        ModelBundle(
            id = id,
            fileNames = setOf(DURATION_FILE, SYNTH_FILE, "config.json", VOCAB_FILE) + VOICE_FILE_NAMES,
            sideFiles = mapOf("config.json" to VALID_CONFIG, VOCAB_FILE to VOCAB),
            rootPath = "/models/$id",
        )

    @Test
    fun claimsAFullExecuTorchKokoroBundle() {
        val match = engine().inspect(fullBundle())

        assertNotNull(match)
        assertEquals(ExecuTorchKokoroEngine.ENGINE_ID, match.engineId)
        val descriptor = match.descriptor
        assertEquals(24_000, descriptor.sampleRate)
        assertEquals(Origin.BUILT_IN, descriptor.origin)
        assertEquals(setOf("af_heart", "bf_emma"), descriptor.voices.map { it.id }.toSet())
        assertEquals("af_heart", descriptor.defaultVoiceId)
        assertEquals(0.5f, descriptor.speedRange.start)
        assertEquals(2.0f, descriptor.speedRange.endInclusive)
        assertTrue(descriptor.supportsVoiceBlend)
        assertEquals("/models/et-kokoro-test/$DURATION_FILE", descriptor.assetPaths["durationPredictor"])
        assertEquals("/models/et-kokoro-test/$SYNTH_FILE", descriptor.assetPaths["synthesizer"])
        assertEquals("/models/et-kokoro-test/voices", descriptor.assetPaths[ExecuTorchKokoroEngine.VOICES_DIR_ASSET])
        assertEquals(
            "/models/et-kokoro-test/$VOCAB_FILE",
            descriptor.assetPaths[ExecuTorchKokoroEngine.VOCAB_ASSET],
        )
    }

    @Test
    fun refusesABundleMissingVocabJsonEvenWithConfigAndVoices() {
        val bundle =
            ModelBundle(
                id = "no-vocab",
                fileNames = setOf(DURATION_FILE, SYNTH_FILE, "config.json") + VOICE_FILE_NAMES,
                sideFiles = mapOf("config.json" to VALID_CONFIG),
            )

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun refusesABundleMissingTheDurationPredictorPte() {
        val bundle =
            ModelBundle(
                id = "no-duration",
                fileNames = setOf(SYNTH_FILE, "config.json", VOCAB_FILE) + VOICE_FILE_NAMES,
                sideFiles = mapOf("config.json" to VALID_CONFIG, VOCAB_FILE to VOCAB),
            )

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun refusesABundleMissingTheSynthesizerPte() {
        val bundle =
            ModelBundle(
                id = "no-synth",
                fileNames = setOf(DURATION_FILE, "config.json", VOCAB_FILE) + VOICE_FILE_NAMES,
                sideFiles = mapOf("config.json" to VALID_CONFIG, VOCAB_FILE to VOCAB),
            )

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun refusesABundleMissingAnyVoiceBinFilesEvenWithAValidConfig() {
        val bundle =
            ModelBundle(
                id = "no-voices",
                fileNames = setOf(DURATION_FILE, SYNTH_FILE, "config.json", VOCAB_FILE),
                sideFiles = mapOf("config.json" to VALID_CONFIG, VOCAB_FILE to VOCAB),
            )

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun refusesAForeignBundleWithADifferentFamilyMarker() {
        val bundle =
            ModelBundle(
                id = "foreign",
                fileNames = setOf(DURATION_FILE, SYNTH_FILE, "config.json", VOCAB_FILE) + VOICE_FILE_NAMES,
                sideFiles = mapOf("config.json" to FOREIGN_CONFIG, VOCAB_FILE to VOCAB),
            )

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun refusesABundleWithNoConfigEvenWithVoiceAndVocabFilesPresent() {
        val bundle =
            ModelBundle(
                id = "no-config",
                fileNames = setOf(DURATION_FILE, SYNTH_FILE, VOCAB_FILE) + VOICE_FILE_NAMES,
                sideFiles = mapOf(VOCAB_FILE to VOCAB),
            )

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun forcedMatchNeverReturnsNullAndFillsFamilyDefaultsWhenNoManifestIsPresent() {
        val bundle = ModelBundle(id = "sideloaded", fileNames = setOf(DURATION_FILE, SYNTH_FILE))

        val match = engine().forcedMatch(bundle)

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertTrue(match.descriptor.voices.isNotEmpty())
    }

    @Test
    fun forcedMatchUsesTheRealManifestWhenOneIsPresent() {
        val match = engine().forcedMatch(fullBundle("sideloaded-with-manifest"))

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals(setOf("af_heart", "bf_emma"), match.descriptor.voices.map { it.id }.toSet())
    }

    @Test
    fun forcedMatchThrowsWhenTheDurationPredictorPteIsMissing() {
        val bundle = ModelBundle(id = "no-weights", fileNames = setOf(SYNTH_FILE, "config.json"))

        assertFailsWith<IllegalArgumentException> { engine().forcedMatch(bundle) }
    }

    @Test
    fun forcedMatchThrowsWhenTheSynthesizerPteIsMissing() {
        val bundle = ModelBundle(id = "no-weights", fileNames = setOf(DURATION_FILE, "config.json"))

        assertFailsWith<IllegalArgumentException> { engine().forcedMatch(bundle) }
    }
}
