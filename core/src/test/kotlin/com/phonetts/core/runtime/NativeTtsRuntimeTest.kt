package com.phonetts.core.runtime

import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.testing.FakeNativeTtsRuntime
import com.phonetts.core.testing.FakeNativeTtsSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Seam test for the second, non-ONNX runtime (spec §5.3, the "pluggable second runtime" the design
 * promises). Pure JVM, fakes only: proves a [NativeTtsRuntime] registers in the same
 * [RuntimeRegistry] as the ONNX backend, is retrievable by id, opens a full TTS session that
 * synthesizes audio from a [NativeTtsRequest], exposes the model's baked voice names, and -
 * crucially - is NOT an ONNX [InferenceSession] (its inherited [Runtime.createSession] fails closed
 * rather than pretend to be one).
 */
class NativeTtsRuntimeTest {
    @Test
    fun `a native TTS runtime registers and is retrieved from the shared RuntimeRegistry by id`() {
        val runtime = FakeNativeTtsRuntime(id = "cosyvoice")
        val registry = RuntimeRegistry().apply { register(runtime) }

        val retrieved = registry.get("cosyvoice")

        assertSame(runtime, retrieved, "the native runtime must be retrievable exactly like the ONNX one")
        assertTrue(retrieved is NativeTtsRuntime, "it should surface as the native-TTS seam")
    }

    @Test
    fun `openTtsSession synthesizes a request into audio`() {
        val expected = floatArrayOf(0.2f, -0.3f, 0.4f)
        val session = FakeNativeTtsSession(audioFor = { expected })
        val runtime = FakeNativeTtsRuntime(sessionFactory = { session })

        val opened = runtime.openTtsSession("/models/cosyvoice3")
        val audio = opened.synthesize(NativeTtsRequest(text = "hello", voiceName = "fleurs-en"))

        assertContentEquals(expected, audio)
        assertEquals(listOf("/models/cosyvoice3"), runtime.createdDirs)
    }

    @Test
    fun `the request carries the text and voice name through to the pipeline`() {
        val session = FakeNativeTtsSession()
        val runtime = FakeNativeTtsRuntime(sessionFactory = { session })

        val opened = runtime.openTtsSession("/models/cosyvoice3")
        opened.synthesize(NativeTtsRequest(text = "the quick brown fox", voiceName = "zero_shot"))

        val request = session.requests.single()
        assertEquals("the quick brown fox", request.text)
        assertEquals("zero_shot", request.voiceName)
    }

    @Test
    fun `the session exposes the model's baked voice names and sample rate as the SSOT`() {
        val session = FakeNativeTtsSession(sampleRate = 24_000, voiceNames = listOf("zero_shot", "fleurs-en"))
        val runtime = FakeNativeTtsRuntime(sessionFactory = { session })

        val opened = runtime.openTtsSession("/models/cosyvoice3")

        assertEquals(24_000, opened.sampleRate)
        assertContentEquals(listOf("zero_shot", "fleurs-en"), opened.voiceNames)
    }

    @Test
    fun `close is forwarded to the underlying pipeline session`() {
        val session = FakeNativeTtsSession()
        val runtime = FakeNativeTtsRuntime(sessionFactory = { session })

        runtime.openTtsSession("/models/cosyvoice3").close()

        assertTrue(session.closed)
    }

    @Test
    fun `createSession fails closed - a native TTS runtime is not an ONNX InferenceSession`() {
        val runtime = FakeNativeTtsRuntime(id = "cosyvoice")

        val error =
            assertFailsWith<IllegalStateException> { runtime.createSession("/models/cosyvoice3", RuntimeOptions()) }

        assertTrue(error.message!!.contains("NativeTtsRuntime"), "error should explain the seam mismatch")
    }

    @Test
    fun `isAvailable reports the native backend presence`() {
        assertTrue(FakeNativeTtsRuntime(available = true).isAvailable())
        assertTrue(!FakeNativeTtsRuntime(available = false).isAvailable())
    }
}
