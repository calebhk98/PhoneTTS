package com.phonetts.engines.common

import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeRuntime
import com.phonetts.core.testing.FakeSession
import com.phonetts.core.testing.testDescriptor
import com.phonetts.engines.common.testing.engineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
