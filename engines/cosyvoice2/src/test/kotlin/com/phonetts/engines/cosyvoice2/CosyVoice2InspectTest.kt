package com.phonetts.engines.cosyvoice2

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Proves inspect() fails closed (spec §9.1): only a bundle with ALL three CosyVoice2 weight
 * components AND a recognized config is claimed. Anything less confident returns null rather
 * than guessing. forcedMatch() is the opposite: it never refuses a user's explicit choice,
 * throwing only when the bundle is structurally unusable (no CosyVoice2 weight file at all).
 */
class CosyVoice2InspectTest {
    private val engine = CosyVoice2Engine(emptyContext())

    @Test
    fun `inspect claims a bundle with all three weight files and a recognized config`() {
        val bundle = validBundle()

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(CosyVoice2Engine.ENGINE_ID, match.engineId)
        assertEquals(Origin.BUILT_IN, match.descriptor.origin)
        assertEquals(24_000, match.descriptor.sampleRate)
        assertEquals("/models/${bundle.id}/llm.onnx", match.descriptor.assetPaths[CosyVoice2Engine.LLM_ASSET_KEY])
        assertEquals("/models/${bundle.id}/flow.onnx", match.descriptor.assetPaths[CosyVoice2Engine.FLOW_ASSET_KEY])
        assertEquals("/models/${bundle.id}/hift.onnx", match.descriptor.assetPaths[CosyVoice2Engine.HIFT_ASSET_KEY])
        assertEquals(
            "/models/${bundle.id}/cosyvoice2.yaml",
            match.descriptor.assetPaths[CosyVoice2Engine.CONFIG_ASSET_KEY],
        )
    }

    @Test
    fun `inspect fails closed when a weight file is missing`() {
        val bundle =
            ModelBundle(
                id = "incomplete",
                // hift.onnx deliberately missing
                fileNames = setOf("llm.onnx", "flow.onnx", "cosyvoice2.yaml"),
                sideFiles = mapOf("cosyvoice2.yaml" to "model_type: cosyvoice2"),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the config carries no CosyVoice2 signature`() {
        val bundle = validBundle(config = "model_type: some-other-model\n")

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the config side file is absent entirely`() {
        val bundle = ModelBundle(id = "no-config", fileNames = setOf("llm.onnx", "flow.onnx", "hift.onnx"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect never claims an unrelated bundle`() {
        val bundle = ModelBundle(id = "piper-voice", fileNames = setOf("en_US-voice.onnx", "en_US-voice.onnx.json"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `forcedMatch never returns null when at least one weight file is present`() {
        val bundle = ModelBundle(id = "manual", fileNames = setOf("llm.onnx"))

        val match = engine.forcedMatch(bundle)

        assertEquals(CosyVoice2Engine.ENGINE_ID, match.engineId)
        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals("llm.onnx", match.descriptor.assetPaths[CosyVoice2Engine.LLM_ASSET_KEY])
    }

    @Test
    fun `forcedMatch throws for a bundle with none of CosyVoice2's weight files`() {
        val bundle = ModelBundle(id = "unusable", fileNames = setOf("readme.txt"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }
}
