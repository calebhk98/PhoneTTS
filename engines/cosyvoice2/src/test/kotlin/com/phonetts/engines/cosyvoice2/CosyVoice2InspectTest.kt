package com.phonetts.engines.cosyvoice2

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.model.Origin
import com.phonetts.engines.common.testing.assertInspectRejects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Proves inspect() fails closed (spec §9.1): only a bundle carrying all four GGUF stages of the
 * native CosyVoice3 stack (LLM + flow + HiFT + voices, matched by their `cosyvoice3-<stage>-*.gguf`
 * names) is claimed. That four-file set is the signature; anything less returns null rather than
 * guess. forcedMatch() is the opposite: it never refuses a user's explicit choice, throwing only
 * when the bundle is structurally unusable (no CosyVoice GGUF component at all).
 */
class CosyVoice2InspectTest {
    private val engine = CosyVoice2Engine(emptyContext())

    @Test
    fun `inspect claims a bundle with all four GGUF stages`() {
        val bundle = validBundle()

        val match = assertNotNull(engine.inspect(bundle))

        assertEquals(CosyVoice2Engine.ENGINE_ID, match.engineId)
        assertEquals(Origin.BUILT_IN, match.descriptor.origin)
        assertEquals(24_000, match.descriptor.sampleRate)
        assertEquals(
            "/models/${bundle.id}/cosyvoice3-llm-q4_k.gguf",
            match.descriptor.assetPaths[CosyVoice2Engine.LLM_ASSET],
        )
        assertEquals(
            "/models/${bundle.id}/cosyvoice3-voices.gguf",
            match.descriptor.assetPaths[CosyVoice2Engine.VOICES_ASSET],
        )
    }

    @Test
    fun `inspect fails closed when the LLM gguf is missing`() {
        val bundle =
            ModelBundle(
                id = "incomplete",
                // no cosyvoice3-llm gguf
                fileNames = setOf("cosyvoice3-flow-q8_0.gguf", "cosyvoice3-hift-f16.gguf", "cosyvoice3-voices.gguf"),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect fails closed when the voices gguf is missing`() {
        val bundle =
            ModelBundle(
                id = "incomplete",
                // voices gguf deliberately missing
                fileNames = setOf("cosyvoice3-llm-q4_k.gguf", "cosyvoice3-flow-q8_0.gguf", "cosyvoice3-hift-f16.gguf"),
            )

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect never claims an unrelated bundle`() {
        val bundle = ModelBundle(id = "piper-voice", fileNames = setOf("en_US-voice.onnx", "en_US-voice.onnx.json"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `inspect never claims a bare single gguf`() {
        val bundle = ModelBundle(id = "some-llm", fileNames = setOf("model.gguf"))

        assertInspectRejects(engine, bundle)
    }

    @Test
    fun `forcedMatch never returns null when at least one component is present`() {
        val bundle = ModelBundle(id = "manual", fileNames = setOf("cosyvoice3-llm-q4_k.gguf"))

        val match = engine.forcedMatch(bundle)

        assertEquals(CosyVoice2Engine.ENGINE_ID, match.engineId)
        assertEquals(Origin.SIDELOADED, match.descriptor.origin)
        assertEquals("cosyvoice3-llm-q4_k.gguf", match.descriptor.assetPaths[CosyVoice2Engine.LLM_ASSET])
    }

    @Test
    fun `forcedMatch throws for a bundle with none of CosyVoice's components`() {
        val bundle = ModelBundle(id = "unusable", fileNames = setOf("readme.txt"))

        assertFailsWith<IllegalArgumentException> { engine.forcedMatch(bundle) }
    }
}
