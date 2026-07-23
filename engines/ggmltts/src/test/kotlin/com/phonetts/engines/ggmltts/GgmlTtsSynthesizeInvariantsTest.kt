package com.phonetts.engines.ggmltts

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ggml LLM-style TTS is autoregressive and non-deterministic (CLAUDE.md TDD guidance), so this
 * tests INVARIANTS, not golden audio: one native call per sentence, a Flow that fully drains, and
 * every sample finite and bounded. It also proves the actual generalization this module exists
 * for: the discovered CrispASR backend id reaches [com.phonetts.core.runtime.RuntimeOptions.extras]
 * on every `openTtsSession` call, so a single, backend-parameterized runtime (the app-module
 * `NativeGgmlTtsRuntime` this engine is built for - see `engines/ggmltts/INTEGRATION.md`) can serve
 * any CrispASR backend without this engine, or the runtime, ever branching on which one it is.
 */
class GgmlTtsSynthesizeInvariantsTest {
    @Test
    fun `the native pipeline runs once per sentence, one bounded finite chunk each`() =
        runTest {
            val runtime = ggmlRuntime(audioFor = { boundedAudio(it.text.length) })
            val engine = GgmlTtsEngine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            val chunks =
                engine.synthesize("Hello there. This is a ggml voice!", descriptor.defaultVoiceId, 1.0f).toList()

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
            val runtime = ggmlRuntime()
            val engine = GgmlTtsEngine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("First one. Second one.", descriptor.defaultVoiceId, 1.0f).toList()

            val texts = runtime.sessions.single().requests.map { it.text }
            assertEquals(listOf("First one.", "Second one."), texts)
        }

    @Test
    fun `load threads the discovered backend id through RuntimeOptions_extras`() =
        runTest {
            val runtime = ggmlRuntime()
            val engine = GgmlTtsEngine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle(backend = "piper"))!!.descriptor

            engine.load(descriptor)

            assertEquals("piper", runtime.optionsSeen.single().extras[GgmlTtsEngine.BACKEND_OPTION_KEY])
        }

    @Test
    fun `a different backend on the SAME engine and runtime routes its own id, no code branch`() =
        runTest {
            val runtime = ggmlRuntime()
            val engine = GgmlTtsEngine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle(backend = "kokoro", voiceId = "af_bella"))!!.descriptor

            engine.load(descriptor)

            assertEquals("kokoro", runtime.optionsSeen.single().extras[GgmlTtsEngine.BACKEND_OPTION_KEY])
        }

    @Test
    fun `voices are the SSOT set the native session reports, mapped back to their language`() =
        runTest {
            val bundle = multiVoiceBundle(voiceIds = listOf("en_US-lessac-medium", "en_US-amy-low"))
            val runtime = ggmlRuntime(voiceNames = listOf("en_US-lessac-medium", "en_US-amy-low"))
            val engine = GgmlTtsEngine(contextWith(runtime))

            engine.load(engine.inspect(bundle)!!.descriptor)

            assertEquals(setOf("en_US-lessac-medium", "en_US-amy-low"), engine.voices().map { it.id }.toSet())
        }

    @Test
    fun `synthesize throws when the engine has not been loaded`() {
        val engine = GgmlTtsEngine(contextWith(ggmlRuntime()))

        assertFailsWith<IllegalStateException> { engine.synthesize("hi", "en_US-lessac-medium", 1.0f) }
    }

    @Test
    fun `unload closes the native session`() =
        runTest {
            val runtime = ggmlRuntime()
            val engine = GgmlTtsEngine(contextWith(runtime))
            engine.load(engine.inspect(validBundle())!!.descriptor)

            engine.unload()

            assertTrue(runtime.sessions.single().closed, "expected the native session to be closed on unload()")
        }

    @Test
    fun `load fails with a clear error when no native runtime is registered`() =
        runTest {
            val engine = GgmlTtsEngine(emptyContext())
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            assertTrue(error.message!!.contains(GgmlTtsEngine.NATIVE_RUNTIME_ID), "error should name the runtime")
        }

    @Test
    fun `load fails clearly when the native ggml backend is unavailable`() =
        runTest {
            val engine = GgmlTtsEngine(contextWith(ggmlRuntime(available = false)))
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            assertTrue(error.message!!.contains("withGgmlTts"), "error should point at the native build flag")
        }

    private fun boundedAudio(seed: Int): FloatArray =
        FloatArray(DEFAULT_SAMPLES_PER_SENTENCE) { i -> boundedWave(i + seed) }

    private fun boundedWave(index: Int): Float = (sin(index * 0.1) * 0.5).toFloat()
}
