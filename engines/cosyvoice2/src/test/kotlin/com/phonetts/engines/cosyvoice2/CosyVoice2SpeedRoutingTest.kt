package com.phonetts.engines.cosyvoice2

import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeSession
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the speed value passed to synthesize() reaches the model's NATIVE token-rate parameter —
 * the LLM [com.phonetts.core.runtime.SpeechTokenRequest.speed] (CLAUDE.md rule 2 / spec §9.4) —
 * and is never used to resample audio, which this engine's synthesize() never does (it only ever
 * forwards flow/vocoder output verbatim).
 */
class CosyVoice2SpeedRoutingTest {
    @Test
    fun `synthesize routes the requested speed to the LLM speech-token request`() =
        runTest {
            val runtime = llmRuntime(tokensFor = { longArrayOf(1L) })
            val engine = engineWithBakedVoice(contextWith(runtime, oneFrameFlow(), oneFrameHift()))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("One sentence only.", descriptor.defaultVoiceId, 0.73f).toList()

            val request = runtime.sessions.single().requests.single()
            assertEquals(0.73f, request.speed, "speed must reach the LLM as its native token-rate knob")
        }

    @Test
    fun `each sentence carries the same speed to its own speech-token request`() =
        runTest {
            val runtime = llmRuntime(tokensFor = { longArrayOf(1L) })
            val engine = engineWithBakedVoice(contextWith(runtime, oneFrameFlow(), oneFrameHift()))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("First one. Second one.", descriptor.defaultVoiceId, 1.4f).toList()

            val requests = runtime.sessions.single().requests
            assertEquals(2, requests.size, "one speech-token request per sentence")
            requests.forEach { assertEquals(1.4f, it.speed) }
        }

    private fun oneFrameFlow(): FakeSession =
        FakeSession(outputsFor = { mapOf(CosyVoice2Graphs.FLOW_OUTPUT_MEL to Tensor.floats(floatArrayOf(0.1f))) })

    private fun oneFrameHift(): FakeSession =
        FakeSession(outputsFor = { mapOf(CosyVoice2Graphs.HIFT_OUTPUT_AUDIO to Tensor.floats(floatArrayOf(0.1f))) })
}
