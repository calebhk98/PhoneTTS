package com.phonetts.engines.ggmltts

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Proves inspect() fails closed (spec §9.1): only a `<name>.gguf` + `<name>.gguf.json` manifest
 * pair naming a non-blank backend and a positive sample rate is claimed, and a bundle whose
 * multiple voice manifests disagree is refused rather than guessed at. forcedMatch() never
 * refuses a user's explicit choice except when the bundle has no usable weights+manifest pair at
 * all — but per its own contract it is free to be more permissive about a backend/sample-rate
 * mismatch than inspect()'s auto-detect is.
 */
class GgmlTtsInspectTest {
    private val engine = GgmlTtsEngine(emptyContext())

    @Test
    fun `inspect claims a gguf plus manifest pair and reads its discovered facts`() {
        val bundle = validBundle(backend = "piper", sampleRate = 22_050, voiceId = "en_US-lessac-medium")

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(GgmlTtsEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.BUILT_IN, match.descriptor.origin)
        assertEquals(22_050, match.descriptor.sampleRate)
        assertEquals("piper", match.descriptor.assetPaths[GgmlTtsEngine.BACKEND_ASSET_KEY])
        assertEquals(
            "/models/${bundle.id}/en_US-lessac-medium.gguf",
            match.descriptor.assetPaths["en_US-lessac-medium"],
        )
        assertEquals(listOf("en_US-lessac-medium"), match.descriptor.voices.map { it.id })
    }

    @Test
    fun `inspect works identically for a different CrispASR backend (no per-backend code)`() {
        val bundle = validBundle(backend = "kokoro", sampleRate = 24_000, voiceId = "af_bella")

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(24_000, match.descriptor.sampleRate)
        assertEquals("kokoro", match.descriptor.assetPaths[GgmlTtsEngine.BACKEND_ASSET_KEY])
    }

    @Test
    fun `inspect claims a multi-voice bundle whose manifests agree on backend and sample rate`() {
        val bundle = multiVoiceBundle(voiceIds = listOf("en_US-lessac-medium", "en_US-amy-low"))

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(setOf("en_US-lessac-medium", "en_US-amy-low"), match.descriptor.voices.map { it.id }.toSet())
    }

    @Test
    fun `inspect fails closed when the gguf has no manifest sidecar`() {
        val bundle = ModelBundle(id = "bare", fileNames = setOf("some-voice.gguf"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the manifest is malformed JSON`() {
        val bundle =
            ModelBundle(
                id = "broken-manifest",
                fileNames = setOf("voice.gguf", "voice.gguf.json"),
                sideFiles = mapOf("voice.gguf.json" to "{not valid json"),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the manifest is missing a backend id`() {
        val bundle =
            ModelBundle(
                id = "no-backend",
                fileNames = setOf("voice.gguf", "voice.gguf.json"),
                sideFiles = mapOf("voice.gguf.json" to """{"sample_rate":22050}"""),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the manifest sample rate is missing or non-positive`() {
        val bundle =
            ModelBundle(
                id = "bad-rate",
                fileNames = setOf("voice.gguf", "voice.gguf.json"),
                sideFiles = mapOf("voice.gguf.json" to """{"backend":"piper","sample_rate":0}"""),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect refuses a bundle whose voice manifests disagree on backend`() {
        val bundle =
            ModelBundle(
                id = "mixed-backends",
                fileNames = setOf("a.gguf", "a.gguf.json", "b.gguf", "b.gguf.json"),
                sideFiles =
                    mapOf(
                        "a.gguf.json" to """{"backend":"piper","sample_rate":22050}""",
                        "b.gguf.json" to """{"backend":"kokoro","sample_rate":22050}""",
                    ),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect refuses a bundle whose voice manifests disagree on sample rate`() {
        val bundle =
            ModelBundle(
                id = "mixed-rates",
                fileNames = setOf("a.gguf", "a.gguf.json", "b.gguf", "b.gguf.json"),
                sideFiles =
                    mapOf(
                        "a.gguf.json" to """{"backend":"piper","sample_rate":22050}""",
                        "b.gguf.json" to """{"backend":"piper","sample_rate":16000}""",
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
        val bundle = validBundle(backend = "melotts", sampleRate = 44_100, voiceId = "melo-en")

        val match = engine.forcedMatch(bundle)

        assertEquals(GgmlTtsEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals("melotts", match.descriptor.assetPaths[GgmlTtsEngine.BACKEND_ASSET_KEY])
    }

    @Test
    fun `forcedMatch picks the first entry rather than refuse on a backend mismatch`() {
        val bundle =
            ModelBundle(
                id = "mixed-backends-forced",
                fileNames = setOf("a.gguf", "a.gguf.json", "b.gguf", "b.gguf.json"),
                sideFiles =
                    mapOf(
                        "a.gguf.json" to """{"backend":"piper","sample_rate":22050}""",
                        "b.gguf.json" to """{"backend":"kokoro","sample_rate":22050}""",
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
        val bundle = ModelBundle(id = "bare-gguf", fileNames = setOf("some-voice.gguf"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }
}
