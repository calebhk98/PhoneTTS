package com.phonetts.engines.cosyvoice2

import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeSession
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the speed value passed to synthesize() reaches the model's native speed input
 * (CLAUDE.md rule 2 / spec §9.4) — never used to resample audio, which this engine's
 * synthesize() never does (it only ever forwards decoder/vocoder output verbatim).
 */
class CosyVoice2SpeedRoutingTest {
    @Test
    fun `synthesize routes the requested speed to the LLM backbone's native speed input`() =
        runTest {
            val llmSession = stoppingImmediatelyLlmSession()
            val engine =
                CosyVoice2Engine(
                    contextWithRuntime(fakeRuntimeFor(llmSession, oneFrameFlowSession(), oneFrameHiftSession())),
                )
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("One sentence only.", descriptor.defaultVoiceId, 0.73f).toList()

            assertEquals(1, llmSession.runs.size)
            val speedTensor = llmSession.runs.first().getValue(CosyVoice2Engine.LLM_INPUT_SPEED)
            assertEquals(0.73f, speedTensor.asFloats().first())
        }

    @Test
    fun `the same speed value is routed on every autoregressive step of a multi-step generation`() =
        runTest {
            var step = 0
            val llmSession =
                FakeSession(
                    outputsFor = { _ ->
                        step++
                        mapOf(
                            CosyVoice2Engine.LLM_OUTPUT_NEXT_TOKEN to Tensor.longs(longArrayOf(step.toLong())),
                            CosyVoice2Engine.LLM_OUTPUT_STOP to Tensor.floats(floatArrayOf(if (step >= 2) 1f else 0f)),
                        )
                    },
                )
            val engine =
                CosyVoice2Engine(
                    contextWithRuntime(fakeRuntimeFor(llmSession, oneFrameFlowSession(), oneFrameHiftSession())),
                )
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Only one.", descriptor.defaultVoiceId, 1.4f).toList()

            assertEquals(2, llmSession.runs.size)
            llmSession.runs.forEach { inputs ->
                assertEquals(1.4f, inputs.getValue(CosyVoice2Engine.LLM_INPUT_SPEED).asFloats().first())
            }
        }

    private fun stoppingImmediatelyLlmSession(): FakeSession =
        FakeSession(
            outputsFor = {
                mapOf(
                    CosyVoice2Engine.LLM_OUTPUT_NEXT_TOKEN to Tensor.longs(longArrayOf(1)),
                    CosyVoice2Engine.LLM_OUTPUT_STOP to Tensor.floats(floatArrayOf(1f)),
                )
            },
        )

    private fun oneFrameFlowSession(): FakeSession =
        FakeSession(outputsFor = { mapOf(CosyVoice2Engine.FLOW_OUTPUT_MEL to Tensor.floats(floatArrayOf(0.1f))) })

    private fun oneFrameHiftSession(): FakeSession =
        FakeSession(outputsFor = { mapOf(CosyVoice2Engine.VOCODER_OUTPUT_AUDIO to Tensor.floats(floatArrayOf(0.1f))) })
}
