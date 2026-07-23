package com.phonetts.engines.kokoro

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
    """{"family": "kokoro", "sample_rate": 24000, "speed_min": 0.5, "speed_max": 2.0, """ +
        """"default_voice": "af_heart", "default_speed": 1.0}"""

private const val FOREIGN_CONFIG = """{"family": "piper", "sample_rate": 22050}"""

// Two voices only - a small fake fingerprint for the seam, NOT a claim that these are two of the
// real ~54 Kokoro voices. Unlike KittenTTS's single zipped voices.npz, each Kokoro voice is its
// own file, so inspect() sees these as bare file NAMES -- voices/*.bin is binary, never a text
// side file (DirectoryBundleReader excludes .bin from side files) -- which is enough to build the
// voice list without reading any bytes.
private val VOICE_FILE_NAMES = setOf("voices/af_heart.bin", "voices/bf_emma.bin")

// tokenizer.json (the phoneme vocabulary) is now a required fingerprint signal - every real Kokoro
// bundle ships it, and without it the frontend can't turn IPA into token ids.
private const val TOKENIZER_FILE = "tokenizer.json"

/**
 * inspect() must fail closed (spec §9.1): a confident claim only when ALL companion signals are
 * present -- a config.json naming the "kokoro" family, a tokenizer.json, AND at least one
 * voices/<name>.bin file present by name -- null for a bare .onnx and for a foreign bundle that
 * merely happens to share a file name.
 */
class KokoroEngineInspectTest {
    private fun engine() = KokoroEngine(engineContext())

    @Test
    fun claimsAFullKokoroBundle() {
        val bundle =
            ModelBundle(
                id = "kokoro-test",
                fileNames = setOf("model.onnx", "config.json", TOKENIZER_FILE) + VOICE_FILE_NAMES,
                sideFiles = mapOf("config.json" to VALID_CONFIG),
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
        assertEquals("/models/kokoro-test/voices", descriptor.assetPaths[KokoroEngine.VOICES_DIR_ASSET])
        assertEquals("/models/kokoro-test/tokenizer.json", descriptor.assetPaths[KokoroEngine.TOKENIZER_ASSET])
    }

    @Test
    fun refusesABundleMissingTokenizerJsonEvenWithConfigAndVoices() {
        val bundle =
            ModelBundle(
                id = "no-tokenizer",
                fileNames = setOf("model.onnx", "config.json") + VOICE_FILE_NAMES,
                sideFiles = mapOf("config.json" to VALID_CONFIG),
            )

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun refusesABareOnnxFile() {
        val bundle = ModelBundle(id = "bare", fileNames = setOf("model.onnx"))

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun refusesABundleMissingAnyVoiceBinFilesEvenWithAValidConfig() {
        val bundle =
            ModelBundle(
                id = "no-voices",
                fileNames = setOf("model.onnx", "config.json", TOKENIZER_FILE),
                sideFiles = mapOf("config.json" to VALID_CONFIG),
            )

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun refusesAKittenV01BundleWearingTheGenericStyleTts2Marker() {
        // onnx-community/kitten-tts-nano-0.1-ONNX ships the SAME layout Kokoro fingerprints (a
        // style_text_to_speech_2 config.json, tokenizer.json, voices/<name>.bin) but its voices are
        // KittenTTS's expr-voice-* set. Kokoro must fail closed so KittenTTS owns it (issue #111),
        // rather than claiming it and mislabeling it "Kokoro".
        val bundle =
            ModelBundle(
                id = "kitten-v0.1-onnx-community",
                fileNames =
                    setOf("onnx/model.onnx", "config.json", TOKENIZER_FILE) +
                        setOf("voices/expr-voice-2-m.bin", "voices/expr-voice-2-f.bin"),
                sideFiles = mapOf("config.json" to """{"model_type": "style_text_to_speech_2"}"""),
            )

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun stillClaimsAGenericStyleTts2BundleWhoseVoicesAreGenuinelyKokoro() {
        // Guards the regression: the REAL onnx-community/Kokoro-82M-v1.0-ONNX config is exactly
        // {"model_type":"style_text_to_speech_2"} with af_/bf_ voices. The generic marker must still
        // be honored when the voices are NOT the foreign kitten signature.
        val bundle =
            ModelBundle(
                id = "kokoro-styletts2",
                fileNames = setOf("model.onnx", "config.json", TOKENIZER_FILE) + VOICE_FILE_NAMES,
                sideFiles = mapOf("config.json" to """{"model_type": "style_text_to_speech_2"}"""),
                rootPath = "/models/kokoro-styletts2",
            )

        assertNotNull(engine().inspect(bundle))
    }

    @Test
    fun refusesAForeignBundleWithADifferentFamilyMarker() {
        val bundle =
            ModelBundle(
                id = "foreign",
                fileNames = setOf("model.onnx", "config.json") + VOICE_FILE_NAMES,
                sideFiles = mapOf("config.json" to FOREIGN_CONFIG),
            )

        assertInspectRejects(engine(), bundle)
    }

    @Test
    fun refusesABundleWithNoConfigEvenWithVoiceFilesPresent() {
        val bundle =
            ModelBundle(
                id = "no-config",
                fileNames = setOf("model.onnx") + VOICE_FILE_NAMES,
            )

        assertInspectRejects(engine(), bundle)
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
                fileNames = setOf("model.onnx", "config.json", TOKENIZER_FILE) + VOICE_FILE_NAMES,
                sideFiles = mapOf("config.json" to VALID_CONFIG),
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
