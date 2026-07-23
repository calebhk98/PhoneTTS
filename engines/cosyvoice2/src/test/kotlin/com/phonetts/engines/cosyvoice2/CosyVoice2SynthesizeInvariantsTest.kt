package com.phonetts.engines.cosyvoice2

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CosyVoice3 is autoregressive and non-deterministic (docs/research/model-facts.md), so per
 * CLAUDE.md's TDD guidance we test INVARIANTS, not golden audio hashes: the native pipeline is
 * driven once per sentence, the Flow drains to one chunk per sentence, and every sample is finite
 * and bounded. The fake native session is deterministic ONLY because it is a test double - that is
 * what lets the invariants be asserted, not a claim about the real model.
 */
class CosyVoice2SynthesizeInvariantsTest {
    @Test
    fun `the native pipeline runs once per sentence, one bounded finite chunk each`() =
        runTest {
            val runtime = cosyRuntime(audioFor = { boundedAudio(it.text.length) })
            val engine = CosyVoice2Engine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            val chunks = engine.synthesize("Hello there. This is CosyVoice!", descriptor.defaultVoiceId, 1.0f).toList()

            assertEquals(2, chunks.size, "expected one audio chunk per sentence (Flow must fully drain)")
            val session = runtime.sessions.single()
            assertEquals(2, session.requests.size, "one native synth per sentence")
            val allSamples = chunks.flatMap { it.toList() }
            assertTrue(allSamples.isNotEmpty(), "synthesize produced no samples")
            assertTrue(allSamples.all { it.isFinite() }, "synthesize produced a NaN/Inf sample")
            assertTrue(allSamples.all { abs(it) <= 1.0f }, "synthesize produced an unbounded sample")
        }

    @Test
    fun `each sentence's text reaches the native pipeline as its own request`() =
        runTest {
            val runtime = cosyRuntime()
            val engine = CosyVoice2Engine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("First one. Second one.", descriptor.defaultVoiceId, 1.0f).toList()

            val texts = runtime.sessions.single().requests.map { it.text }
            assertEquals(listOf("First one.", "Second one."), texts)
        }

    @Test
    fun `the selected voice id reaches the native request as its voice name`() =
        runTest {
            val runtime = cosyRuntime(voiceNames = listOf("zero_shot", "fleurs-en"))
            val engine = CosyVoice2Engine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Only one.", "fleurs-en", 1.0f).toList()

            assertEquals("fleurs-en", runtime.sessions.single().requests.single().voiceName)
        }

    @Test
    fun `voices are the SSOT set the native session read from the voices GGUF`() =
        runTest {
            val runtime = cosyRuntime(voiceNames = listOf("zero_shot", "fleurs-en", "fleurs-de"))
            val engine = CosyVoice2Engine(contextWith(runtime))
            engine.load(engine.inspect(validBundle())!!.descriptor)

            assertEquals(listOf("zero_shot", "fleurs-en", "fleurs-de"), engine.voices().map { it.id })
            assertEquals("de", engine.voices().first { it.id == "fleurs-de" }.language)
        }

    @Test
    fun `synthesize throws when the engine has not been loaded`() {
        val engine = CosyVoice2Engine(contextWith(cosyRuntime()))

        assertFailsWith<IllegalStateException> { engine.synthesize("hi", "zero_shot", 1.0f) }
    }

    @Test
    fun `unload closes the native session`() =
        runTest {
            val runtime = cosyRuntime()
            val engine = CosyVoice2Engine(contextWith(runtime))
            engine.load(engine.inspect(validBundle())!!.descriptor)

            engine.unload()

            assertTrue(runtime.sessions.single().closed, "expected the native session to be closed on unload()")
        }

    @Test
    fun `load fails with a clear error when no native runtime is registered`() =
        runTest {
            val engine = CosyVoice2Engine(emptyContext())
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            assertTrue(error.message!!.contains(CosyVoice2Engine.NATIVE_RUNTIME_ID), "error should name the runtime")
        }

    @Test
    fun `load fails clearly when the native ggml backend is unavailable`() =
        runTest {
            val engine = CosyVoice2Engine(contextWith(cosyRuntime(available = false)))
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            assertTrue(error.message!!.contains("withCosyVoice"), "error should point at the native build flag")
        }

    private fun boundedAudio(seed: Int): FloatArray =
        FloatArray(DEFAULT_SAMPLES_PER_SENTENCE) { i -> boundedWave(i + seed) }

    private fun boundedWave(index: Int): Float = (sin(index * 0.1) * 0.5).toFloat()
}
