package com.phonetts.engines.piper

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeSession
import com.phonetts.engines.common.testing.engineContext
import com.phonetts.engines.common.testing.onnxRuntime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * issue #110 load/synthesize-time verification. Some incompatible Piper variants carry NO signal in
 * the sidecar or file name that inspect() could fail closed on — most notably tiny (~1-1.7 MB)
 * non-standard "medium" exports whose graph does not match the assumed VITS input/dtype contract.
 * The [com.phonetts.core.runtime.InferenceSession] seam cannot introspect a graph's inputs/dtypes,
 * so those can only be caught by actually loading and running the graph. When they fail, the engine
 * must surface a clear, named [PiperUnsupportedVariantException] instead of an uncaught crash.
 */
class PiperUnsupportedVariantTest {
    private val sidecarJson =
        """
        {
          "audio": {"sample_rate": 22050},
          "espeak": {"voice": "en-us"},
          "phoneme_id_map": {"^": [1], "$": [2], "_": [0], "h": [10], "i": [11]},
          "inference": {"noise_scale": 0.667, "length_scale": 1.0, "noise_w": 0.8}
        }
        """.trimIndent()

    private val bundle =
        ModelBundle(
            id = "tiny-incompatible-medium",
            fileNames = setOf("voice.onnx", "voice.onnx.json"),
            sideFiles = mapOf("voice.onnx.json" to sidecarJson),
            rootPath = "/models/tiny-incompatible-medium",
        )

    @Test
    fun `load reports a clear unsupported-variant error when the graph fails to open`() =
        runTest {
            // A tiny/incompatible export that ORT refuses to load surfaces as a create failure.
            val runtime = onnxRuntime { error("corrupt or incompatible ONNX graph") }
            val engine = PiperEngine(engineContext(runtime), sidecarReader = { sidecarJson })
            val match = assertNotNull(engine.inspect(bundle))

            val failure = assertFailsWith<PiperUnsupportedVariantException> { engine.load(match.descriptor) }

            assertTrue(
                failure.message.orEmpty().contains("not supported by this app"),
                "expected a clear unsupported-variant message, got: ${failure.message}",
            )
        }

    @Test
    fun `synthesize reports a clear unsupported-variant error when the graph rejects the input contract`() =
        runTest {
            // The graph opened, but rejects our fixed input/input_lengths/scales contract at run()
            // (e.g. an fp16 graph refusing float32 scales, or a non-standard export with other inputs).
            val session = FakeSession(outputsFor = { error("dtype/name mismatch on input 'scales'") })
            val engine = PiperEngine(engineContext(onnxRuntime(session)), sidecarReader = { sidecarJson })
            val match = assertNotNull(engine.inspect(bundle))
            engine.load(match.descriptor)

            val failure =
                assertFailsWith<PiperUnsupportedVariantException> {
                    engine.synthesize("hi", voiceId = "voice", speed = 1.0f).toList()
                }

            assertTrue(
                failure.message.orEmpty().contains("not supported by this app"),
                "expected a clear unsupported-variant message, got: ${failure.message}",
            )
        }

    @Test
    fun `a well-behaved graph still synthesizes normally`() =
        runTest {
            val session = FakeSession(outputsFor = { mapOf("output" to Tensor.floats(floatArrayOf(0.1f, 0.2f))) })
            val engine = PiperEngine(engineContext(onnxRuntime(session)), sidecarReader = { sidecarJson })
            val match = assertNotNull(engine.inspect(bundle))
            engine.load(match.descriptor)

            val chunks = engine.synthesize("hi", voiceId = "voice", speed = 1.0f).toList()

            assertTrue(chunks.isNotEmpty(), "a compatible Piper graph must still produce audio")
        }
}
