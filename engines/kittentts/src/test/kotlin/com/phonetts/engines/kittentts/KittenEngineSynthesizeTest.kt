package com.phonetts.engines.kittentts

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeSession
import com.phonetts.engines.common.testing.onnxEngineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers spec §9.4 (speed routing) and the voice-selection requirement from issue #12: the
 * `speed` passed to `synthesize()` and the selected voice's speaker id must both reach the
 * session's inputs unchanged, via the fake inference seam (no real ONNX runtime needed).
 */
class KittenEngineSynthesizeTest {
    private val validConfig = """{"model_type":"kitten_tts","sample_rate":24000}"""
    private val validVoices = """["Bella","Jasper","Luna","Bruno","Rosie","Hugo","Kiki","Leo"]"""

    private val bundle =
        ModelBundle(
            id = "kitten-nano",
            fileNames = setOf("kitten_tts_nano.onnx", KittenEngine.CONFIG_FILE, KittenEngine.VOICES_FILE),
            sideFiles =
                mapOf(
                    KittenEngine.CONFIG_FILE to validConfig,
                    KittenEngine.VOICES_FILE to validVoices,
                ),
            rootPath = "/models/kitten-nano",
        )

    private fun fakeSession() =
        FakeSession(
            outputsFor = { mapOf(KittenEngine.WAVEFORM_KEY to Tensor.floats(floatArrayOf(0.1f, -0.2f))) },
        )

    private fun buildEngine(session: FakeSession): KittenEngine = KittenEngine(onnxEngineContext(session))

    @Test
    fun `speed reaches the session's native speed input`() =
        runTest {
            val session = fakeSession()
            val engine = buildEngine(session)
            val descriptor = engine.inspect(bundle)!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Hello there.", voiceId = descriptor.voices[0].id, speed = 1.35f).toList()

            assertEquals(1, session.runs.size)
            assertEquals(1.35f, session.runs[0].getValue(KittenEngine.SPEED_KEY).asFloats()[0])
        }

    @Test
    fun `selected voice reaches the session's speaker-id input`() =
        runTest {
            val session = fakeSession()
            val engine = buildEngine(session)
            val descriptor = engine.inspect(bundle)!!.descriptor
            val luna = descriptor.voices.first { it.name == "Luna" } // index 2 in validVoices

            engine.load(descriptor)
            engine.synthesize("Hi.", voiceId = luna.id, speed = 1.0f).toList()

            assertEquals(1, session.runs.size)
            assertEquals(2L, session.runs[0].getValue(KittenEngine.SPEAKER_ID_KEY).asLongs()[0])
        }

    @Test
    fun `long text is chunked into sentences and each chunk is run through the session`() =
        runTest {
            val session = fakeSession()
            val engine = buildEngine(session)
            val descriptor = engine.inspect(bundle)!!.descriptor
            engine.load(descriptor)

            val voiceId = descriptor.voices[0].id
            val chunks = engine.synthesize("First sentence. Second sentence!", voiceId, 1.0f).toList()

            assertEquals(2, chunks.size)
            assertEquals(2, session.runs.size)
        }

    @Test
    fun `unload closes the session`() =
        runTest {
            val session = fakeSession()
            val engine = buildEngine(session)
            val descriptor = engine.inspect(bundle)!!.descriptor
            engine.load(descriptor)

            engine.unload()

            assertEquals(true, session.closed)
        }
}
