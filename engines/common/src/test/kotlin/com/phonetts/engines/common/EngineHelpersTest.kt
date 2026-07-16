package com.phonetts.engines.common

import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeRuntime
import com.phonetts.core.testing.FakeSession
import com.phonetts.core.testing.testDescriptor
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineHelpersTest {
    @Test
    fun requireRuntimeReturnsRegisteredElseFails() {
        val runtime = FakeRuntime(id = "onnx")
        assertEquals(runtime, requireRuntime(engineContext(runtime), "onnx", "X"))
        assertFailsWith<IllegalStateException> { requireRuntime(engineContext(), "onnx", "X") }
    }

    @Test
    fun requireAssetPathReturnsPresentElseFails() {
        val descriptor = testDescriptor("m", "e").copy(assetPaths = mapOf("weights" to "/p/model.onnx"))
        assertEquals("/p/model.onnx", requireAssetPath(descriptor, "weights", "X"))
        assertFailsWith<IllegalStateException> { requireAssetPath(descriptor, "missing", "X") }
    }

    @Test
    fun openWithRollbackClosesOpenedSessionsWhenLoadingFailsPartway() {
        val first = FakeSession()
        val second = FakeSession()
        assertFailsWith<IllegalStateException> {
            openWithRollback { opened ->
                opened.add(first)
                opened.add(second)
                error("third createSession fails") // simulate a mid-load failure
            }
        }
        assertTrue(first.closed && second.closed, "every already-opened session must be closed on failure")
    }

    @Test
    fun openWithRollbackReturnsTheResultAndKeepsSessionsOpenOnSuccess() {
        val session = FakeSession()
        val result =
            openWithRollback { opened ->
                opened.add(session)
                "loaded"
            }
        assertEquals("loaded", result)
        assertFalse(session.closed, "a successful load must NOT close the sessions it opened")
    }

    @Test
    fun closeAllQuietlyClosesEveryNonNullSession() {
        val a = FakeSession()
        val b = FakeSession()
        closeAllQuietly(a, null, b)
        assertTrue(a.closed && b.closed)
    }

    @Test
    fun joinAssetPathHandlesNullBlankAndTrailingSlash() {
        assertEquals("model.onnx", joinAssetPath(null, "model.onnx"))
        assertEquals("model.onnx", joinAssetPath("", "model.onnx"))
        assertEquals("/root/model.onnx", joinAssetPath("/root", "model.onnx"))
        assertEquals("/root/model.onnx", joinAssetPath("/root/", "model.onnx"))
    }

    @Test
    fun floatsOrErrorPullsOutputElseFails() {
        val outputs = mapOf("audio" to Tensor.floats(floatArrayOf(0.1f, 0.2f)))
        assertEquals(0.2f, outputs.floatsOrError("audio", "X")[1])
        assertFailsWith<IllegalStateException> { outputs.floatsOrError("nope", "X") }
    }

    @Test
    fun singleFloatsOrErrorReadsTheSoleOutputRegardlessOfItsName() {
        // Some ONNX exports auto-number their output (e.g. MeloTTS's acoustic graph), so no fixed
        // key can be hardcoded — the sole entry must still be readable positionally.
        val outputs = mapOf("14035" to Tensor.floats(floatArrayOf(0.1f, 0.2f)))
        assertEquals(listOf(0.1f, 0.2f), outputs.singleFloatsOrError("X").toList())
    }

    @Test
    fun singleFloatsOrErrorFailsClosedWhenThereIsNotExactlyOneOutput() {
        assertFailsWith<IllegalStateException> { emptyMap<String, Tensor>().singleFloatsOrError("X") }
        val twoOutputs =
            mapOf(
                "a" to Tensor.floats(floatArrayOf(0f)),
                "b" to Tensor.floats(floatArrayOf(1f)),
            )
        assertFailsWith<IllegalStateException> { twoOutputs.singleFloatsOrError("X") }
    }
}
