package com.phonetts.engines.pockettts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the SSOT facts this engine's descriptor carries are all discovered from the bundle, not
 * hardcoded (CLAUDE.md rule 1): sample rate and voices come from `bundle.json`, there is no
 * fabricated speed knob (CLAUDE.md rule 2 - no native speed control has been identified for this
 * model), and every real asset path - including which precision variant was actually found - is
 * recorded for whoever finishes real inference later (see class KDoc "STATUS").
 */
class PocketTtsDescriptorTest {
    private val engine = PocketTtsEngine(emptyContext())

    @Test
    fun `descriptor sample rate and voices come from the bundle, not a literal`() {
        val bundle = pocketBundle(sampleRate = 24_000, voiceIds = listOf("alba", "azelma", "cosette"))

        val descriptor = engine.inspect(bundle)!!.descriptor

        assertEquals(24_000, descriptor.sampleRate)
        assertEquals(setOf("alba", "azelma", "cosette"), descriptor.voices.map { it.id }.toSet())
    }

    @Test
    fun `display name includes the bundle's own language variant name`() {
        val descriptor = engine.inspect(pocketBundle(bundleName = "italian_24l")).let { it!!.descriptor }

        val message = "expected the bundle name in '${descriptor.displayName}'"
        assertTrue(descriptor.displayName.contains("italian_24l"), message)
    }

    @Test
    fun `descriptor advertises no speed knob (honest-closed, CLAUDE_md rule 2)`() {
        val descriptor = engine.inspect(pocketBundle())!!.descriptor

        assertTrue(descriptor.parameters.isEmpty(), "expected no fabricated tunable parameters")
        assertEquals(1.0f, descriptor.speedRange.start)
        assertEquals(1.0f, descriptor.speedRange.endInclusive)
    }

    @Test
    fun `descriptor reports an unknown resource cost rather than a guess`() {
        val descriptor = engine.inspect(pocketBundle())!!.descriptor

        assertEquals(null, descriptor.resourceCost.approxPeakRamBytes)
    }

    @Test
    fun `descriptor does not claim voice blending`() {
        val descriptor = engine.inspect(pocketBundle())!!.descriptor

        assertFalse(descriptor.supportsVoiceBlend)
    }

    @Test
    fun `asset paths record all 5 graphs plus config and tokenizer`() {
        val descriptor = engine.inspect(pocketBundle(id = "pt-en")).let { it!!.descriptor }

        val paths = descriptor.assetPaths
        assertEquals("/models/pt-en/text_conditioner.onnx", paths[PocketTtsEngine.TEXT_CONDITIONER_STEM])
        assertEquals("/models/pt-en/mimi_encoder.onnx", paths[PocketTtsEngine.MIMI_ENCODER_STEM])
        assertEquals("/models/pt-en/flow_lm_main.onnx", paths[PocketTtsEngine.FLOW_LM_MAIN_STEM])
        assertEquals("/models/pt-en/flow_lm_flow.onnx", paths[PocketTtsEngine.FLOW_LM_FLOW_STEM])
        assertEquals("/models/pt-en/mimi_decoder.onnx", paths[PocketTtsEngine.MIMI_DECODER_STEM])
        assertEquals("/models/pt-en/bundle.json", paths[PocketTtsEngine.CONFIG_ASSET_KEY])
        assertEquals("/models/pt-en/tokenizer.model", paths[PocketTtsEngine.TOKENIZER_ASSET_KEY])
    }

    @Test
    fun `asset paths point at the int8 file when that is the only variant present`() {
        val bundle = pocketBundle(id = "pt-int8", quantizedInt8Only = setOf("flow_lm_main"))

        val descriptor = engine.inspect(bundle)!!.descriptor

        assertEquals(
            "/models/pt-int8/flow_lm_main_int8.onnx",
            descriptor.assetPaths[PocketTtsEngine.FLOW_LM_MAIN_STEM],
        )
    }
}
