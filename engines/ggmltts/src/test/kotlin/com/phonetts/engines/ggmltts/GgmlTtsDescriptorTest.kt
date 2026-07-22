package com.phonetts.engines.ggmltts

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLAUDE.md rule 2: speed must route to the model's NATIVE parameter and audio must NEVER be
 * resampled to fake it. This shared [com.phonetts.core.runtime.NativeTtsRuntime] seam does not
 * yet carry a per-backend native speed argument (see [GgmlTtsEngine] KDoc "PARAMETERS"), so the
 * only rule-2-compliant behaviour — same as CosyVoice3 — is to advertise a LOCKED speed of 1.0
 * (honest-closed) and never time-scale the native output. This proves both.
 */
class GgmlTtsDescriptorTest {
    @Test
    fun `the descriptor advertises a locked speed of 1_0 (no routed native speed knob yet)`() {
        val descriptor = GgmlTtsEngine(emptyContext()).inspect(validBundle())!!.descriptor

        assertEquals(1.0f, descriptor.speedRange.start)
        assertEquals(1.0f, descriptor.speedRange.endInclusive)
        assertEquals(1.0f, descriptor.defaultSpeed)
        assertTrue(descriptor.parameters.isEmpty(), "expected no fabricated tunable parameters")
    }

    @Test
    fun `synthesize forwards the native audio verbatim and never resamples for speed`() =
        runTest {
            val fixedAudio = FloatArray(DEFAULT_SAMPLES_PER_SENTENCE) { 0.25f }
            val runtime = ggmlRuntime(audioFor = { fixedAudio })
            val engine = GgmlTtsEngine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            // Even at a non-1.0 speed the descriptor's locked range would reject anything else, but
            // 1.0 is the only legal value here — the point is the output length is untouched.
            val chunk = engine.synthesize("One sentence only.", descriptor.defaultVoiceId, 1.0f).toList().single()

            assertEquals(fixedAudio.size, chunk.size, "output length must equal the native audio (no resampling)")
            assertTrue(chunk.all { it == 0.25f }, "samples must be the native output verbatim")
        }
}
