package com.phonetts.engines.cosyvoice2

import com.phonetts.core.runtime.Tensor
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
 * CLAUDE.md's TDD guidance we test INVARIANTS, not golden audio hashes: tokens flow
 * LLM -> flow -> hift, the Flow drains to one chunk per sentence, the length is in a sane range
 * derived from the fake stages, and every sample is finite and bounded. The fakes are deterministic
 * ONLY because they are test doubles — that is what lets the invariants be asserted, not a claim
 * about the real model.
 */
class CosyVoice2SynthesizeInvariantsTest {
    private val hopLength = 256

    @Test
    fun `tokens flow LLM to flow to hift, one bounded finite chunk per sentence`() =
        runTest {
            val flowSession = FakeSession(outputsFor = ::flowOutputFor)
            val hiftSession = FakeSession(outputsFor = ::vocoderOutputFor)
            val engine = engineWithBakedVoice(contextWith(llmRuntime(), flowSession, hiftSession))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            val chunks =
                engine.synthesize("Hello there. This is CosyVoice2!", descriptor.defaultVoiceId, 1.0f).toList()

            assertEquals(2, chunks.size, "expected one audio chunk per sentence (Flow must fully drain)")
            val allSamples = chunks.flatMap { it.toList() }
            assertTrue(allSamples.isNotEmpty(), "synthesize produced no samples")
            assertTrue(allSamples.all { it.isFinite() }, "synthesize produced a NaN/Inf sample")
            assertTrue(allSamples.all { abs(it) <= 1.0f }, "synthesize produced an unbounded sample")
            // Each sentence: DEFAULT_TOKENS_PER_SENTENCE speech tokens -> mel of equal length -> audio * hop.
            val expectedSamples = chunks.size * DEFAULT_TOKENS_PER_SENTENCE * hopLength
            assertEquals(expectedSamples, allSamples.size, "output length not in the expected sane range")
        }

    @Test
    fun `the LLM speech tokens are exactly what the flow decoder receives`() =
        runTest {
            val flowSession = FakeSession(outputsFor = ::flowOutputFor)
            val hiftSession = FakeSession(outputsFor = ::vocoderOutputFor)
            val runtime = llmRuntime(tokensFor = { longArrayOf(11L, 22L, 33L) })
            val engine = engineWithBakedVoice(contextWith(runtime, flowSession, hiftSession))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("One sentence only.", descriptor.defaultVoiceId, 1.0f).toList()

            val fedTokens = flowSession.runs.single().getValue(CosyVoice2Graphs.FLOW_INPUT_TOKEN).asLongs()
            assertEquals(listOf(11L, 22L, 33L), fedTokens.toList())
        }

    @Test
    fun `synthesize throws when the engine has not been loaded`() {
        val engine = engineWithBakedVoice(contextWith(llmRuntime(), FakeSession(), FakeSession()))

        assertFailsWith<IllegalStateException> { engine.synthesize("hi", "default", 1.0f) }
    }

    @Test
    fun `unload closes the LLM, flow-matching, and vocoder sessions`() =
        runTest {
            val runtime = llmRuntime()
            val flowSession = FakeSession(outputsFor = ::flowOutputFor)
            val hiftSession = FakeSession(outputsFor = ::vocoderOutputFor)
            val engine = engineWithBakedVoice(contextWith(runtime, flowSession, hiftSession))
            engine.load(engine.inspect(validBundle())!!.descriptor)

            engine.unload()

            assertTrue(runtime.sessions.single().closed, "expected the LLM session to be closed on unload()")
            assertTrue(flowSession.closed, "expected the flow-matching session to be closed on unload()")
            assertTrue(hiftSession.closed, "expected the vocoder session to be closed on unload()")
        }

    @Test
    fun `load fails with a clear error when no LLM runtime is registered`() =
        runTest {
            val engine = CosyVoice2Engine(emptyContext())
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            val named = error.message!!.contains(CosyVoice2Engine.LLM_RUNTIME_ID)
            assertTrue(named, "error should name the missing runtime")
        }

    @Test
    fun `load fails clearly when the native LLM backend is unavailable`() =
        runTest {
            val runtime = llmRuntime(available = false)
            val engine = engineWithBakedVoice(contextWith(runtime, FakeSession(), FakeSession()))
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            assertTrue(error.message!!.contains("withCosyVoice"), "error should point at the native build flag")
        }

    private fun flowOutputFor(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val tokenCount = inputs.getValue(CosyVoice2Graphs.FLOW_INPUT_TOKEN).asLongs().size
        return mapOf(CosyVoice2Graphs.FLOW_OUTPUT_MEL to Tensor.floats(FloatArray(tokenCount) { 0.1f }))
    }

    private fun vocoderOutputFor(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val melLength = inputs.getValue(CosyVoice2Graphs.HIFT_INPUT_MEL).asFloats().size
        val audio = FloatArray(melLength * hopLength) { i -> boundedWave(i) }
        return mapOf(CosyVoice2Graphs.HIFT_OUTPUT_AUDIO to Tensor.floats(audio))
    }

    private fun boundedWave(index: Int): Float = (sin(index * 0.1) * 0.5).toFloat()
}
