package com.phonetts.engines.pockettts

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Proves inspect() fails closed (spec §9.1): only the exact community-export bundle layout
 * (docs/research/pockettts-facts.md) is claimed, and every way that layout can be
 * incomplete/ambiguous is refused rather than guessed at. forcedMatch() never refuses a bundle
 * that has the real files, even with no known voice names, per its own more-permissive contract.
 */
class PocketTtsInspectTest {
    private val engine = PocketTtsEngine(emptyContext())

    @Test
    fun `inspect claims a real bundle and reads its discovered facts`() {
        val bundle =
            pocketBundle(bundleName = "english_2026-04", sampleRate = 24_000, voiceIds = listOf("alba", "jean"))

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(PocketTtsEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.BUILT_IN, match.descriptor.origin)
        assertEquals(24_000, match.descriptor.sampleRate)
        assertEquals(setOf("alba", "jean"), match.descriptor.voices.map { it.id }.toSet())
        assertEquals("alba", match.descriptor.defaultVoiceId)
    }

    @Test
    fun `inspect accepts a bundle whose quantizable graphs ship only as _int8 onnx`() {
        val bundle =
            pocketBundle(
                quantizedInt8Only = setOf("flow_lm_main", "flow_lm_flow", "mimi_decoder"),
            )

        assertNotNull(engine.inspect(bundle))
    }

    @Test
    fun `inspect fails closed when text_conditioner exists only as int8 (never quantized upstream)`() {
        val bundle = pocketBundle(quantizedInt8Only = setOf("text_conditioner"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when bundle json is missing`() {
        assertInspectRejects(engine, pocketBundleMissingFile(PocketTtsEngine.BUNDLE_METADATA_FILE))
    }

    @Test
    fun `inspect fails closed when tokenizer model is missing`() {
        assertInspectRejects(engine, pocketBundleMissingFile(PocketTtsEngine.TOKENIZER_FILE))
    }

    @Test
    fun `inspect fails closed when any one of the 5 onnx graphs is entirely missing`() {
        PocketTtsEngine.ALL_GRAPH_STEMS.forEach { stem ->
            val bundle = pocketBundleMissingFile("$stem.onnx")
            assertInspectRejects(engine, bundle)
        }
    }

    @Test
    fun `inspect fails closed when bundle json is malformed`() {
        val bundle = pocketBundle()
        val broken = bundle.copy(sideFiles = mapOf(PocketTtsEngine.BUNDLE_METADATA_FILE to "{not valid json"))

        assertInspectRejects(engine, broken)
    }

    @Test
    fun `inspect fails closed when the flow_lm_state_manifest field is absent`() {
        assertInspectRejects(engine, pocketBundle(omitManifests = setOf("flow")))
    }

    @Test
    fun `inspect fails closed when the mimi_state_manifest field is absent`() {
        assertInspectRejects(engine, pocketBundle(omitManifests = setOf("mimi")))
    }

    @Test
    fun `inspect fails closed when sample rate is missing or non-positive`() {
        assertInspectRejects(engine, pocketBundle(sampleRate = 0))
    }

    @Test
    fun `inspect fails closed when there are no predefined voices to default to`() {
        assertInspectRejects(engine, pocketBundle(voiceIds = emptyList()))
    }

    @Test
    fun `inspect never claims an unrelated bundle`() {
        val bundle = ModelBundle(id = "some-onnx-model", fileNames = setOf("model.onnx", "config.json"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `forcedMatch accepts a valid bundle`() {
        val bundle = pocketBundle(bundleName = "german")

        val match = engine.forcedMatch(bundle)

        assertEquals(PocketTtsEngine.ENGINE_ID, match.engineId)
        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
    }

    @Test
    fun `forcedMatch falls back to a single default voice when none are known`() {
        val bundle = pocketBundle(voiceIds = emptyList())

        val match = engine.forcedMatch(bundle)

        assertEquals(1, match.descriptor.voices.size)
    }

    @Test
    fun `forcedMatch throws when the bundle is not a Pocket TTS ONNX bundle at all`() {
        val bundle = ModelBundle(id = "unusable", fileNames = setOf("readme.txt"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }
}
