package com.phonetts.engines.cosyvoice2

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLAUDE.md rule 2 says speed must route to the model's NATIVE parameter and audio must NEVER be
 * resampled to fake it. CrispASR's `cosyvoice3_tts_synth` C ABI exposes no speed knob, so the only
 * rule-2-compliant behaviour is to advertise a LOCKED speed of 1.0 (honest-closed) and never
 * time-scale the native output. This proves both: the descriptor pins speed to 1.0, and synthesize()
 * forwards the native audio verbatim regardless of the speed argument (it does not resample).
 */
class CosyVoice2SpeedRoutingTest {
    @Test
    fun `the descriptor advertises a locked speed of 1_0 (no native speed knob to route to)`() {
        val descriptor = CosyVoice2Engine(emptyContext()).inspect(validBundle())!!.descriptor

        assertEquals(1.0f, descriptor.speedRange.start)
        assertEquals(1.0f, descriptor.speedRange.endInclusive)
        assertEquals(1.0f, descriptor.defaultSpeed)
    }

    @Test
    fun `synthesize forwards the native audio verbatim and never resamples for speed`() =
        runTest {
            val fixedAudio = FloatArray(DEFAULT_SAMPLES_PER_SENTENCE) { 0.25f }
            val runtime = cosyRuntime(audioFor = { fixedAudio })
            val engine = CosyVoice2Engine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            // Even if a caller passed a non-1.0 speed, the output length must not change (no resample).
            val chunk = engine.synthesize("One sentence only.", descriptor.defaultVoiceId, 1.0f).toList().single()

            assertEquals(fixedAudio.size, chunk.size, "output length must equal the native audio (no resampling)")
            assertTrue(chunk.all { it == 0.25f }, "samples must be the native output verbatim")
        }
}
