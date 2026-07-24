package com.phonetts.engines.outetts

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * OuteTTS is an autoregressive LLM-style TTS and is non-deterministic (CLAUDE.md TDD guidance), so
 * this tests INVARIANTS, not golden audio: one native call per sentence, a Flow that fully drains,
 * and every sample finite and bounded. It also proves the discovered decoder type + decoder file
 * name reach [com.phonetts.core.runtime.RuntimeOptions.extras] on every `openTtsSession` call, so a
 * future native OuteTTS bridge can locate and load the right decoder GGUF (WavTokenizer or DAC)
 * without this engine ever branching on which one it is.
 */
class OuteTtsSynthesizeInvariantsTest {
    @Test
    fun `the native pipeline runs once per sentence, one bounded finite chunk each`() =
        runTest {
            val runtime = outeTtsRuntime(audioFor = { boundedAudio(it.text.length) })
            val engine = OuteTtsEngine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            val chunks =
                engine.synthesize("Hello there. This is an OuteTTS voice!", descriptor.defaultVoiceId, 1.0f).toList()

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
            val runtime = outeTtsRuntime()
            val engine = OuteTtsEngine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("First one. Second one.", descriptor.defaultVoiceId, 1.0f).toList()

            val texts = runtime.sessions.single().requests.map { it.text }
            assertEquals(listOf("First one.", "Second one."), texts)
        }

    @Test
    fun `load threads the discovered decoder type and decoder file name through RuntimeOptions_extras`() =
        runTest {
            val runtime = outeTtsRuntime()
            val engine = OuteTtsEngine(contextWith(runtime))
            val descriptor =
                engine.inspect(validBundle(decoder = "dac", decoderFile = "dac-speech-v1.0.gguf"))!!.descriptor

            engine.load(descriptor)

            val options = runtime.optionsSeen.single()
            assertEquals("dac", options.extras[OuteTtsEngine.DECODER_TYPE_OPTION_KEY])
            assertEquals("dac-speech-v1.0.gguf", options.extras[OuteTtsEngine.DECODER_FILE_OPTION_KEY])
        }

    @Test
    fun `a different decoder family on the SAME engine and runtime routes its own id, no code branch`() =
        runTest {
            val runtime = outeTtsRuntime()
            val engine = OuteTtsEngine(contextWith(runtime))
            val descriptor =
                engine
                    .inspect(validBundle(decoder = "wavtokenizer", decoderFile = "wavtokenizer-large-75-f16.gguf"))!!
                    .descriptor

            engine.load(descriptor)

            val options = runtime.optionsSeen.single()
            assertEquals("wavtokenizer", options.extras[OuteTtsEngine.DECODER_TYPE_OPTION_KEY])
            assertEquals("wavtokenizer-large-75-f16.gguf", options.extras[OuteTtsEngine.DECODER_FILE_OPTION_KEY])
        }

    @Test
    fun `voices are the SSOT set the native session reports, mapped back to their language`() =
        runTest {
            val bundle = multiVoiceBundle(voiceIds = listOf("en-female-1-neutral", "en-male-1-neutral"))
            val runtime = outeTtsRuntime(voiceNames = listOf("en-female-1-neutral", "en-male-1-neutral"))
            val engine = OuteTtsEngine(contextWith(runtime))

            engine.load(engine.inspect(bundle)!!.descriptor)

            assertEquals(setOf("en-female-1-neutral", "en-male-1-neutral"), engine.voices().map { it.id }.toSet())
        }

    @Test
    fun `synthesize throws when the engine has not been loaded`() {
        val engine = OuteTtsEngine(contextWith(outeTtsRuntime()))

        assertFailsWith<IllegalStateException> { engine.synthesize("hi", "en-female-1-neutral", 1.0f) }
    }

    @Test
    fun `unload closes the native session`() =
        runTest {
            val runtime = outeTtsRuntime()
            val engine = OuteTtsEngine(contextWith(runtime))
            engine.load(engine.inspect(validBundle())!!.descriptor)

            engine.unload()

            assertTrue(runtime.sessions.single().closed, "expected the native session to be closed on unload()")
        }

    @Test
    fun `load fails with a clear error when no native runtime is registered`() =
        runTest {
            val engine = OuteTtsEngine(emptyContext())
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            assertTrue(error.message!!.contains(OuteTtsEngine.NATIVE_RUNTIME_ID), "error should name the runtime")
        }

    @Test
    fun `load fails clearly when the native OuteTTS backend is unavailable`() =
        runTest {
            val engine = OuteTtsEngine(contextWith(outeTtsRuntime(available = false)))
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            assertTrue(error.message!!.contains("withOuteTts"), "error should point at the native build flag")
        }

    private fun boundedAudio(seed: Int): FloatArray =
        FloatArray(DEFAULT_SAMPLES_PER_SENTENCE) { i -> boundedWave(i + seed) }

    private fun boundedWave(index: Int): Float = (sin(index * 0.1) * 0.5).toFloat()
}
