package com.phonetts.engines.neutts

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Proves inspect() fails closed (spec §9.1): only a `<name>.gguf` + `<name>.gguf.json` manifest
 * naming a positive sample rate, a non-blank input format, a present codec-decoder file, and at
 * least one valid `(ref_audio, ref_text)` voice entry is claimed - and a bundle with more than one
 * such candidate (ambiguous which file is the real backbone) is refused rather than guessed at.
 * forcedMatch() never refuses a user's explicit choice except when the bundle has no usable
 * candidate at all - but per its own contract it is free to pick the first candidate instead of
 * refusing on ambiguity, unlike inspect()'s auto-detect.
 */
class NeuTtsInspectTest {
    private val engine = NeuTtsEngine(emptyContext())

    @Test
    fun `inspect claims a gguf plus manifest and reads its discovered facts`() {
        val bundle = validBundle(sampleRate = 24_000, voiceId = "dave", refAudioFile = "dave.wav")

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(NeuTtsEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.BUILT_IN, match.descriptor.origin)
        assertEquals(24_000, match.descriptor.sampleRate)
        assertEquals("phonemes", match.descriptor.assetPaths[NeuTtsEngine.INPUT_FORMAT_ASSET_KEY])
        assertEquals(
            "/models/${bundle.id}/neutts-nano-Q4_0.gguf",
            match.descriptor.assetPaths[NeuTtsEngine.GGUF_ASSET_KEY],
        )
        assertEquals(
            "/models/${bundle.id}/neucodec-decoder.onnx",
            match.descriptor.assetPaths[NeuTtsEngine.CODEC_DECODER_ASSET_KEY],
        )
        assertEquals(listOf("dave"), match.descriptor.voices.map { it.id })
    }

    @Test
    fun `inspect claims a multi-voice bundle and reads every clone voice`() {
        val bundle = multiVoiceBundle()

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(setOf("dave", "jo"), match.descriptor.voices.map { it.id }.toSet())
    }

    @Test
    fun `inspect drops a malformed individual voice entry but keeps the valid ones`() {
        val bundle =
            ModelBundle(
                id = "partial-voices",
                fileNames = setOf("nano.gguf", "nano.gguf.json", "neucodec-decoder.onnx", "dave.wav"),
                sideFiles =
                    mapOf(
                        "nano.gguf.json" to
                            """{"sample_rate":24000,"input_format":"phonemes","codec_decoder":"neucodec-decoder.onnx",
                            "voices":[{"id":"dave","ref_audio":"dave.wav","ref_text":"hi"},
                            {"id":"missing-audio","ref_audio":"nope.wav","ref_text":"hi"},
                            {"id":"no-text","ref_audio":"dave.wav"}]}""",
                    ),
            )

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(listOf("dave"), match.descriptor.voices.map { it.id })
    }

    @Test
    fun `inspect fails closed when the gguf has no manifest sidecar`() {
        val bundle = ModelBundle(id = "bare", fileNames = setOf("nano.gguf"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the manifest is malformed JSON`() {
        val bundle =
            ModelBundle(
                id = "broken-manifest",
                fileNames = setOf("nano.gguf", "nano.gguf.json"),
                sideFiles = mapOf("nano.gguf.json" to "{not valid json"),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the sample rate is missing or non-positive`() {
        val bundle =
            ModelBundle(
                id = "bad-rate",
                fileNames = setOf("nano.gguf", "nano.gguf.json", "neucodec-decoder.onnx", "dave.wav"),
                sideFiles =
                    mapOf(
                        "nano.gguf.json" to
                            """{"sample_rate":0,"input_format":"phonemes","codec_decoder":"neucodec-decoder.onnx",
                            "voices":[{"id":"dave","ref_audio":"dave.wav","ref_text":"hi"}]}""",
                    ),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when input_format is missing or blank`() {
        val bundle =
            ModelBundle(
                id = "no-format",
                fileNames = setOf("nano.gguf", "nano.gguf.json", "neucodec-decoder.onnx", "dave.wav"),
                sideFiles =
                    mapOf(
                        "nano.gguf.json" to
                            """{"sample_rate":24000,"codec_decoder":"neucodec-decoder.onnx",
                            "voices":[{"id":"dave","ref_audio":"dave.wav","ref_text":"hi"}]}""",
                    ),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the codec_decoder file is not present in the bundle`() {
        val bundle =
            ModelBundle(
                id = "missing-decoder",
                fileNames = setOf("nano.gguf", "nano.gguf.json", "dave.wav"),
                sideFiles =
                    mapOf(
                        "nano.gguf.json" to
                            """{"sample_rate":24000,"input_format":"phonemes","codec_decoder":"neucodec-decoder.onnx",
                            "voices":[{"id":"dave","ref_audio":"dave.wav","ref_text":"hi"}]}""",
                    ),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when no voice entry is usable`() {
        val bundle =
            ModelBundle(
                id = "no-usable-voices",
                fileNames = setOf("nano.gguf", "nano.gguf.json", "neucodec-decoder.onnx"),
                sideFiles =
                    mapOf(
                        "nano.gguf.json" to
                            """{"sample_rate":24000,"input_format":"phonemes",
                            "codec_decoder":"neucodec-decoder.onnx","voices":[]}""",
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
    fun `inspect refuses a bundle with two full backbone candidates as ambiguous`() {
        val bundle =
            ModelBundle(
                id = "two-backbones",
                fileNames =
                    setOf(
                        "a.gguf",
                        "a.gguf.json",
                        "b.gguf",
                        "b.gguf.json",
                        "neucodec-decoder.onnx",
                        "dave.wav",
                    ),
                sideFiles =
                    mapOf(
                        "a.gguf.json" to
                            """{"sample_rate":24000,"input_format":"phonemes","codec_decoder":"neucodec-decoder.onnx",
                            "voices":[{"id":"dave","ref_audio":"dave.wav","ref_text":"hi"}]}""",
                        "b.gguf.json" to
                            """{"sample_rate":24000,"input_format":"phonemes","codec_decoder":"neucodec-decoder.onnx",
                            "voices":[{"id":"dave","ref_audio":"dave.wav","ref_text":"hi"}]}""",
                    ),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `forcedMatch accepts a valid single-voice bundle`() {
        val bundle = validBundle(inputFormat = "bpe")

        val match = engine.forcedMatch(bundle)

        assertEquals(NeuTtsEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals("bpe", match.descriptor.assetPaths[NeuTtsEngine.INPUT_FORMAT_ASSET_KEY])
    }

    @Test
    fun `forcedMatch picks the first candidate rather than refuse on ambiguity`() {
        val bundle =
            ModelBundle(
                id = "two-backbones-forced",
                fileNames =
                    setOf(
                        "a.gguf",
                        "a.gguf.json",
                        "b.gguf",
                        "b.gguf.json",
                        "neucodec-decoder.onnx",
                        "dave.wav",
                    ),
                sideFiles =
                    mapOf(
                        "a.gguf.json" to
                            """{"sample_rate":24000,"input_format":"phonemes","codec_decoder":"neucodec-decoder.onnx",
                            "voices":[{"id":"dave","ref_audio":"dave.wav","ref_text":"hi"}]}""",
                        "b.gguf.json" to
                            """{"sample_rate":24000,"input_format":"phonemes","codec_decoder":"neucodec-decoder.onnx",
                            "voices":[{"id":"dave","ref_audio":"dave.wav","ref_text":"hi"}]}""",
                    ),
            )

        val match = engine.forcedMatch(bundle)

        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
    }

    @Test
    fun `forcedMatch throws when the bundle has no usable candidate at all`() {
        val bundle = ModelBundle(id = "unusable", fileNames = setOf("readme.txt"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }

    @Test
    fun `forcedMatch throws for a bare gguf with no manifest (cannot invent a sample rate)`() {
        val bundle = ModelBundle(id = "bare-gguf", fileNames = setOf("nano.gguf"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }
}
