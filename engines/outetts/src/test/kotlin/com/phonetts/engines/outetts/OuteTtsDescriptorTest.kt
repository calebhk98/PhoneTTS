package com.phonetts.engines.outetts

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLAUDE.md rule 2: speed must route to the model's NATIVE parameter and audio must NEVER be
 * resampled to fake it. OuteTTS's autoregressive generation has no native speed/duration knob (no
 * `NativeTtsRequest` field for it either, matching CosyVoice3/GgmlTtsEngine), so the only rule-2-
 * compliant behaviour is to advertise a LOCKED speed of 1.0 (honest-closed) and never time-scale
 * the native output. This also proves the license is surfaced honestly (CLAUDE.md rule 1: a
 * discovered fact, never a literal) via the descriptor's own fields, with no `:core` change.
 */
class OuteTtsDescriptorTest {
    @Test
    fun `the descriptor advertises a locked speed of 1_0 (no native speed knob exists)`() {
        val descriptor = OuteTtsEngine(emptyContext()).inspect(validBundle())!!.descriptor

        assertEquals(1.0f, descriptor.speedRange.start)
        assertEquals(1.0f, descriptor.speedRange.endInclusive)
        assertEquals(1.0f, descriptor.defaultSpeed)
        assertTrue(descriptor.parameters.isEmpty(), "expected no fabricated tunable parameters")
    }

    @Test
    fun `the descriptor surfaces the discovered per-checkpoint license, never a hardcoded one`() {
        val apacheBundle = validBundle(license = "Apache-2.0")
        val ncBundle = validBundle(id = "nc-checkpoint", license = "CC-BY-NC-4.0")
        val engine = OuteTtsEngine(emptyContext())

        val apacheDescriptor = engine.inspect(apacheBundle)!!.descriptor
        val ncDescriptor = engine.inspect(ncBundle)!!.descriptor

        assertTrue(apacheDescriptor.displayName.contains("Apache-2.0"))
        assertTrue(ncDescriptor.displayName.contains("CC-BY-NC-4.0"))
        assertEquals("Apache-2.0", apacheDescriptor.assetPaths[OuteTtsEngine.LICENSE_ASSET_KEY])
        assertEquals("CC-BY-NC-4.0", ncDescriptor.assetPaths[OuteTtsEngine.LICENSE_ASSET_KEY])
    }

    @Test
    fun `synthesize forwards the native audio verbatim and never resamples for speed`() =
        runTest {
            val fixedAudio = FloatArray(DEFAULT_SAMPLES_PER_SENTENCE) { 0.25f }
            val runtime = outeTtsRuntime(audioFor = { fixedAudio })
            val engine = OuteTtsEngine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            // Even at a non-1.0 speed the descriptor's locked range would reject anything else, but
            // 1.0 is the only legal value here - the point is the output length is untouched.
            val chunk = engine.synthesize("One sentence only.", descriptor.defaultVoiceId, 1.0f).toList().single()

            assertEquals(fixedAudio.size, chunk.size, "output length must equal the native audio (no resampling)")
            assertTrue(chunk.all { it == 0.25f }, "samples must be the native output verbatim")
        }
}
