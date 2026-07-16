package com.phonetts.core.runtime

import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.testing.FakeSpeechTokenRuntime
import com.phonetts.core.testing.FakeSpeechTokenSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Seam test for the second, LLM-style runtime (spec §5.3, the "pluggable second runtime" the
 * design promises). Pure JVM, fakes only: proves a [SpeechTokenRuntime] registers in the same
 * [RuntimeRegistry] as the ONNX backend, is retrievable by id, opens a decoder session that
 * returns speech token ids from a [SpeechTokenRequest], and — crucially — is NOT an ONNX
 * [InferenceSession] (its inherited [Runtime.createSession] fails closed rather than pretend to
 * be one, which is the bug the first CosyVoice2 skeleton had).
 */
class SpeechTokenRuntimeTest {
    @Test
    fun `a speech-token runtime registers and is retrieved from the shared RuntimeRegistry by id`() {
        val runtime = FakeSpeechTokenRuntime(id = "cosyvoice-llm")
        val registry = RuntimeRegistry().apply { register(runtime) }

        val retrieved = registry.get("cosyvoice-llm")

        assertSame(runtime, retrieved, "the LLM runtime must be retrievable exactly like the ONNX one")
        assertTrue(retrieved is SpeechTokenRuntime, "it should surface as the speech-token seam")
    }

    @Test
    fun `openSpeechSession decodes a request into speech token ids`() {
        val expected = longArrayOf(5L, 9L, 2L, 7L)
        val session = FakeSpeechTokenSession(tokensFor = { expected })
        val runtime = FakeSpeechTokenRuntime(sessionFactory = { session })

        val opened = runtime.openSpeechSession("/models/llm.gguf")
        val tokens = opened.generate(SpeechTokenRequest(longArrayOf(1L, 2L), floatArrayOf(0.1f), speed = 1.0f))

        assertContentEquals(expected, tokens)
        assertEquals(listOf("/models/llm.gguf"), runtime.createdPaths)
    }

    @Test
    fun `the request carries the native speed and speaker embedding through to the decoder`() {
        val session = FakeSpeechTokenSession()
        val runtime = FakeSpeechTokenRuntime(sessionFactory = { session })

        val opened = runtime.openSpeechSession("/models/llm.gguf")
        opened.generate(SpeechTokenRequest(longArrayOf(3L), floatArrayOf(0.4f, 0.5f), speed = 0.8f))

        val request = session.requests.single()
        assertEquals(0.8f, request.speed, "speed must reach the decoder as the native token-rate knob")
        assertContentEquals(floatArrayOf(0.4f, 0.5f), request.speakerEmbedding)
    }

    @Test
    fun `close is forwarded to the underlying decoder session`() {
        val session = FakeSpeechTokenSession()
        val runtime = FakeSpeechTokenRuntime(sessionFactory = { session })

        runtime.openSpeechSession("/models/llm.gguf").close()

        assertTrue(session.closed)
    }

    @Test
    fun `createSession fails closed — a speech-token runtime is not an ONNX InferenceSession`() {
        val runtime = FakeSpeechTokenRuntime(id = "cosyvoice-llm")

        val error =
            assertFailsWith<IllegalStateException> { runtime.createSession("/models/llm.gguf", RuntimeOptions()) }

        assertTrue(error.message!!.contains("SpeechTokenRuntime"), "error should explain the seam mismatch")
    }

    @Test
    fun `isAvailable reports the native backend presence`() {
        assertTrue(FakeSpeechTokenRuntime(available = true).isAvailable())
        assertTrue(!FakeSpeechTokenRuntime(available = false).isAvailable())
    }
}
