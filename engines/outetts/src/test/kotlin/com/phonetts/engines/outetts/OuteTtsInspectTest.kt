package com.phonetts.engines.outetts

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Proves inspect() fails closed (spec §9.1): a bundle is only claimed with exactly one
 * `<name>.gguf` + `<name>.gguf.json` manifest pair naming a non-blank decoder, a decoder file that
 * is actually present in the bundle, a positive sample rate, and a non-blank license - AND at
 * least one `<voiceId>.speaker.json` speaker profile. forcedMatch() never refuses a user's
 * explicit choice except when the bundle has no usable LLM+manifest pair, or no speaker profile,
 * at all.
 */
class OuteTtsInspectTest {
    private val engine = OuteTtsEngine(emptyContext())

    @Test
    fun `inspect claims an LLM plus manifest plus speaker profile and reads its discovered facts`() {
        val bundle =
            validBundle(
                decoder = "dac",
                decoderFile = "dac-speech-v1.0.gguf",
                license = "Apache-2.0",
            )

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(OuteTtsEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.BUILT_IN, match.descriptor.origin)
        assertEquals(24_000, match.descriptor.sampleRate)
        assertEquals("dac", match.descriptor.assetPaths[OuteTtsEngine.DECODER_TYPE_ASSET_KEY])
        assertEquals("Apache-2.0", match.descriptor.assetPaths[OuteTtsEngine.LICENSE_ASSET_KEY])
        assertEquals("OuteTTS (Apache-2.0)", match.descriptor.displayName)
        assertEquals(
            "/models/${bundle.id}/outetts-1.0-0.6b-q4_k_m.gguf",
            match.descriptor.assetPaths[OuteTtsEngine.LLM_ASSET_KEY],
        )
        assertEquals(
            "/models/${bundle.id}/dac-speech-v1.0.gguf",
            match.descriptor.assetPaths[OuteTtsEngine.DECODER_ASSET_KEY],
        )
        assertEquals(
            "/models/${bundle.id}/en-female-1-neutral.speaker.json",
            match.descriptor.assetPaths["en-female-1-neutral"],
        )
        assertEquals(listOf("en-female-1-neutral"), match.descriptor.voices.map { it.id })
    }

    @Test
    fun `inspect works identically for a WavTokenizer-decoder checkpoint (no per-decoder code)`() {
        val bundle =
            validBundle(
                decoder = "wavtokenizer",
                decoderFile = "wavtokenizer-large-75-f16.gguf",
                license = "CC-BY-SA-4.0",
            )

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals("wavtokenizer", match.descriptor.assetPaths[OuteTtsEngine.DECODER_TYPE_ASSET_KEY])
        assertEquals("CC-BY-SA-4.0", match.descriptor.assetPaths[OuteTtsEngine.LICENSE_ASSET_KEY])
    }

    @Test
    fun `inspect claims a multi-voice bundle sharing one LLM and decoder`() {
        val bundle = multiVoiceBundle(voiceIds = listOf("en-female-1-neutral", "en-male-1-neutral"))

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(setOf("en-female-1-neutral", "en-male-1-neutral"), match.descriptor.voices.map { it.id }.toSet())
    }

    @Test
    fun `inspect fails closed when the gguf has no manifest sidecar`() {
        val bundle = ModelBundle(id = "bare", fileNames = setOf("some-llm.gguf", "voice.speaker.json"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the manifest is malformed JSON`() {
        val bundle =
            ModelBundle(
                id = "broken-manifest",
                fileNames = setOf("llm.gguf", "llm.gguf.json", "voice.speaker.json"),
                sideFiles = mapOf("llm.gguf.json" to "{not valid json"),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the manifest is missing a decoder id`() {
        val bundle =
            ModelBundle(
                id = "no-decoder",
                fileNames = setOf("llm.gguf", "llm.gguf.json", "decoder.gguf", "voice.speaker.json"),
                sideFiles =
                    mapOf(
                        "llm.gguf.json" to """{"decoder_file":"decoder.gguf","sample_rate":24000,"license":"MIT"}""",
                    ),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the manifest names a decoder_file not present in the bundle`() {
        val bundle =
            ModelBundle(
                id = "missing-decoder-file",
                fileNames = setOf("llm.gguf", "llm.gguf.json", "voice.speaker.json"),
                sideFiles =
                    mapOf(
                        "llm.gguf.json" to
                            """{"decoder":"dac","decoder_file":"nowhere.gguf","sample_rate":24000,"license":"MIT"}""",
                    ),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the manifest sample rate is missing or non-positive`() {
        val bundle =
            ModelBundle(
                id = "bad-rate",
                fileNames = setOf("llm.gguf", "llm.gguf.json", "decoder.gguf", "voice.speaker.json"),
                sideFiles =
                    mapOf(
                        "llm.gguf.json" to
                            """{"decoder":"dac","decoder_file":"decoder.gguf","sample_rate":0,"license":"MIT"}""",
                    ),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the manifest is missing a license`() {
        val bundle =
            ModelBundle(
                id = "no-license",
                fileNames = setOf("llm.gguf", "llm.gguf.json", "decoder.gguf", "voice.speaker.json"),
                sideFiles =
                    mapOf(
                        "llm.gguf.json" to """{"decoder":"dac","decoder_file":"decoder.gguf","sample_rate":24000}""",
                    ),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when there is no speaker profile at all`() {
        val bundle =
            ModelBundle(
                id = "no-voices",
                fileNames = setOf("llm.gguf", "llm.gguf.json", "decoder.gguf"),
                sideFiles =
                    mapOf(
                        "llm.gguf.json" to
                            """{"decoder":"dac","decoder_file":"decoder.gguf","sample_rate":24000,"license":"MIT"}""",
                    ),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect refuses a bundle with two ambiguous LLM plus manifest candidates`() {
        val bundle =
            ModelBundle(
                id = "two-llms",
                fileNames =
                    setOf(
                        "a.gguf",
                        "a.gguf.json",
                        "b.gguf",
                        "b.gguf.json",
                        "decoder.gguf",
                        "voice.speaker.json",
                    ),
                sideFiles =
                    mapOf(
                        "a.gguf.json" to
                            """{"decoder":"dac","decoder_file":"decoder.gguf","sample_rate":24000,"license":"MIT"}""",
                        "b.gguf.json" to
                            """{"decoder":"dac","decoder_file":"decoder.gguf","sample_rate":24000,"license":"MIT"}""",
                    ),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect never claims an unrelated bundle`() {
        val bundle = ModelBundle(id = "piper-onnx", fileNames = setOf("en_US-voice.onnx", "en_US-voice.onnx.json"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `forcedMatch accepts a valid single-voice bundle`() {
        val bundle =
            validBundle(
                decoder = "wavtokenizer",
                decoderFile = "wavtokenizer-large-75-f16.gguf",
                license = "CC-BY-NC-4.0",
            )

        val match = engine.forcedMatch(bundle)

        assertEquals(OuteTtsEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals("wavtokenizer", match.descriptor.assetPaths[OuteTtsEngine.DECODER_TYPE_ASSET_KEY])
    }

    @Test
    fun `forcedMatch picks the first LLM candidate rather than refuse on ambiguity`() {
        val bundle =
            ModelBundle(
                id = "two-llms-forced",
                fileNames =
                    setOf(
                        "a.gguf",
                        "a.gguf.json",
                        "b.gguf",
                        "b.gguf.json",
                        "decoder.gguf",
                        "voice.speaker.json",
                    ),
                sideFiles =
                    mapOf(
                        "a.gguf.json" to
                            """{"decoder":"dac","decoder_file":"decoder.gguf","sample_rate":24000,"license":"MIT"}""",
                        "b.gguf.json" to
                            """{"decoder":"dac","decoder_file":"decoder.gguf","sample_rate":16000,"license":"MIT"}""",
                    ),
            )

        val match = engine.forcedMatch(bundle)

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
    }

    @Test
    fun `forcedMatch throws when the bundle has no usable gguf plus manifest pair`() {
        val bundle = ModelBundle(id = "unusable", fileNames = setOf("readme.txt"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }

    @Test
    fun `forcedMatch throws for a bare gguf with no manifest (cannot invent a sample rate)`() {
        val bundle = ModelBundle(id = "bare-gguf", fileNames = setOf("some-llm.gguf", "voice.speaker.json"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }

    @Test
    fun `forcedMatch throws when there is no speaker profile (cannot invent a voice)`() {
        val bundle =
            ModelBundle(
                id = "no-voices-forced",
                fileNames = setOf("llm.gguf", "llm.gguf.json", "decoder.gguf"),
                sideFiles =
                    mapOf(
                        "llm.gguf.json" to
                            """{"decoder":"dac","decoder_file":"decoder.gguf","sample_rate":24000,"license":"MIT"}""",
                    ),
            )

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }
}
