package com.phonetts.engines.cosyvoice2

import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeRuntime
import com.phonetts.core.testing.FakeSession
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CosyVoice2 is autoregressive and non-deterministic (docs/research/model-facts.md), so per
 * CLAUDE.md's TDD guidance we test INVARIANTS, not golden audio hashes: the Flow drains, its
 * length falls in a sane range derived from the (fake) generation loop, and every sample is
 * finite and bounded. The fakes below are deterministic ONLY because they are test doubles —
 * that's what lets the invariants be asserted reliably, not a claim about the real model.
 */
class CosyVoice2SynthesizeInvariantsTest {
    private val hopLength = 256

    @Test
    fun `synthesize output is finite, bounded, and its length is in a sane range`() =
        runTest {
            var llmCalls = 0
            val llmSession =
                FakeSession(
                    outputsFor = { _ ->
                        llmCalls++
                        // Global call counter, not per-sentence: stopping on a multiple of
                        // STEPS_PER_SENTENCE means every sentence generates exactly that many
                        // tokens, since the previous sentence always finishes on a multiple too.
                        val stop = if (llmCalls % STEPS_PER_SENTENCE == 0) 1f else 0f
                        mapOf(
                            CosyVoice2Engine.LLM_OUTPUT_NEXT_TOKEN to Tensor.longs(longArrayOf(llmCalls.toLong())),
                            CosyVoice2Engine.LLM_OUTPUT_STOP to Tensor.floats(floatArrayOf(stop)),
                        )
                    },
                )
            val flowSession = FakeSession(outputsFor = { inputs -> flowOutputFor(inputs) })
            val hiftSession = FakeSession(outputsFor = { inputs -> vocoderOutputFor(inputs) })
            val engine = CosyVoice2Engine(contextWithRuntime(fakeRuntimeFor(llmSession, flowSession, hiftSession)))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            val chunks =
                engine.synthesize("Hello there. This is CosyVoice2!", descriptor.defaultVoiceId, 1.0f).toList()

            assertEquals(2, chunks.size, "expected one audio chunk per sentence (Flow must fully drain)")
            val allSamples = chunks.flatMap { it.toList() }
            assertTrue(allSamples.isNotEmpty(), "synthesize produced no samples")
            assertTrue(allSamples.all { it.isFinite() }, "synthesize produced a NaN/Inf sample")
            assertTrue(allSamples.all { abs(it) <= 1.0f }, "synthesize produced an unbounded sample")
            val expectedSamples = chunks.size * STEPS_PER_SENTENCE * hopLength
            assertEquals(expectedSamples, allSamples.size, "output length not in the expected sane range")
        }

    @Test
    fun `synthesize throws when the engine has not been loaded`() {
        val engine = CosyVoice2Engine(contextWithRuntime(FakeRuntime(id = CosyVoice2Engine.RUNTIME_ID)))

        assertFailsWith<IllegalStateException> { engine.synthesize("hi", "default", 1.0f) }
    }

    @Test
    fun `load fails with a clear error when no runtime is registered under the expected id`() =
        runTest {
            val engine = CosyVoice2Engine(emptyContext())
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            assertTrue(
                error.message!!.contains(CosyVoice2Engine.RUNTIME_ID),
                "error should name the missing runtime id",
            )
        }

    private fun flowOutputFor(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val tokenCount = inputs.getValue(CosyVoice2Engine.FLOW_INPUT_TOKENS).asLongs().size
        return mapOf(CosyVoice2Engine.FLOW_OUTPUT_MEL to Tensor.floats(FloatArray(tokenCount) { 0.1f }))
    }

    private fun vocoderOutputFor(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val melLength = inputs.getValue(CosyVoice2Engine.VOCODER_INPUT_MEL).asFloats().size
        val audio = FloatArray(melLength * hopLength) { i -> boundedWave(i) }
        return mapOf(CosyVoice2Engine.VOCODER_OUTPUT_AUDIO to Tensor.floats(audio))
    }

    private fun boundedWave(index: Int): Float = (sin(index * 0.1) * 0.5).toFloat()

    companion object {
        private const val STEPS_PER_SENTENCE = 3
    }
}
