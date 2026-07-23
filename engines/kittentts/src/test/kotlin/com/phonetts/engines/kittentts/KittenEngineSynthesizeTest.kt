package com.phonetts.engines.kittentts

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeSession
import com.phonetts.engines.common.testing.onnxEngineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// A small fake voices.npz for the seam -- two voices with distinct, easily distinguishable
// 256-dim embeddings so a test can prove the *right* row reaches the session's "style" input.
private val HEART_EMBEDDING = FloatArray(256) { 0.1f }
private val EMMA_EMBEDDING = FloatArray(256) { -0.2f }

// A raw little-endian float32 voices/<name>.bin payload (no numpy header) -- the v0.1 bin layout.
private fun rawFloats(values: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    values.forEach(buffer::putFloat)
    return buffer.array()
}

/**
 * Covers spec §9.4 (speed routing) and the StyleTTS2 voice-selection contract validated in
 * docs/research/onnx-io.md: the selected voice's 256-dim `style` embedding row from `voices.npz`
 * -- not an integer speaker id -- must reach the session, and `speed` must reach the session's
 * native `speed` input unchanged. Mirrors `KokoroEngineSynthesisTest`, since KittenTTS is the
 * same StyleTTS2 shape as Kokoro.
 */
class KittenEngineSynthesizeTest {
    private val validConfig = """{"model_type":"kitten_tts","sample_rate":24000}"""
    private val npzBytes =
        NpzFixtures.npzBytes(mapOf("expr-voice-2-m" to HEART_EMBEDDING, "expr-voice-2-f" to EMMA_EMBEDDING))

    private val bundle =
        ModelBundle(
            id = "kitten-nano",
            fileNames = setOf("kitten_tts_nano.onnx", KittenEngine.CONFIG_FILE, KittenEngine.VOICES_FILE),
            sideFiles = mapOf(KittenEngine.CONFIG_FILE to validConfig),
            rootPath = "/models/kitten-nano",
        )

    private fun fakeSession() =
        FakeSession(
            outputsFor = { mapOf(KittenEngine.WAVEFORM_KEY to Tensor.floats(floatArrayOf(0.1f, -0.2f))) },
        )

    private fun buildEngine(session: FakeSession): KittenEngine =
        // The injected reader seam: load() gets voices.npz's bytes without touching disk.
        KittenEngine(onnxEngineContext(session), fileReader = { npzBytes })

    @Test
    fun `speed reaches the session's native speed input`() =
        runTest {
            val session = fakeSession()
            val engine = buildEngine(session)
            val descriptor = engine.inspect(bundle)!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Hello there.", voiceId = "expr-voice-2-m", speed = 1.35f).toList()

            assertEquals(1, session.runs.size)
            assertEquals(1.35f, session.runs[0].getValue(KittenEngine.SPEED_KEY).asFloats()[0])
        }

    @Test
    fun `selected voice's embedding reaches the session's style input, not another voice's`() =
        runTest {
            val session = fakeSession()
            val engine = buildEngine(session)
            val descriptor = engine.inspect(bundle)!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Hi.", voiceId = "expr-voice-2-f", speed = 1.0f).toList()

            assertEquals(1, session.runs.size)
            assertContentEquals(EMMA_EMBEDDING, session.runs[0].getValue(KittenEngine.STYLE_KEY).asFloats())
        }

    @Test
    fun `the other voice's embedding reaches the style input when that one is selected instead`() =
        runTest {
            val session = fakeSession()
            val engine = buildEngine(session)
            val descriptor = engine.inspect(bundle)!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Hi.", voiceId = "expr-voice-2-m", speed = 1.0f).toList()

            assertContentEquals(HEART_EMBEDDING, session.runs[0].getValue(KittenEngine.STYLE_KEY).asFloats())
        }

    @Test
    fun `tokens reach the session's input_ids input`() =
        runTest {
            val session = fakeSession()
            val engine = buildEngine(session)
            val descriptor = engine.inspect(bundle)!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Hi.", voiceId = "expr-voice-2-m", speed = 1.0f).toList()

            val tokens = session.runs[0].getValue(KittenEngine.INPUT_IDS_KEY).asLongs()
            // "H", "i", "." are all StyleTTS2 symbols; the frontend wraps them with a pad (0) each end.
            assertEquals("Hi.".length + 2, tokens.size)
            assertEquals(0L, tokens.first())
            assertEquals(0L, tokens.last())
        }

    @Test
    fun `output is read from the waveform tensor`() =
        runTest {
            val session = fakeSession()
            val engine = buildEngine(session)
            val descriptor = engine.inspect(bundle)!!.descriptor
            engine.load(descriptor)

            val chunks = engine.synthesize("Hi.", voiceId = "expr-voice-2-m", speed = 1.0f).toList()

            assertContentEquals(floatArrayOf(0.1f, -0.2f), chunks.single())
        }

    @Test
    fun `long text is chunked into sentences and each chunk is run through the session`() =
        runTest {
            val session = fakeSession()
            val engine = buildEngine(session)
            val descriptor = engine.inspect(bundle)!!.descriptor
            engine.load(descriptor)

            val chunks = engine.synthesize("First sentence. Second sentence!", "expr-voice-2-m", 1.0f).toList()

            assertEquals(2, chunks.size)
            assertEquals(2, session.runs.size)
        }

    @Test
    fun `synthesize with an unknown voice id fails`() =
        runTest {
            val session = fakeSession()
            val engine = buildEngine(session)
            val descriptor = engine.inspect(bundle)!!.descriptor
            engine.load(descriptor)

            assertFailsWith<IllegalStateException> {
                engine.synthesize("Hi.", "not-a-real-voice", 1.0f).toList()
            }
        }

    @Test
    fun `synthesize before load fails rather than silently producing nothing`() =
        runTest {
            val engine = buildEngine(fakeSession())

            assertFailsWith<IllegalStateException> {
                engine.synthesize("Hi.", "expr-voice-2-m", 1.0f).toList()
            }
        }

    @Test
    fun `onnx-community v0-1 bin voices load and the selected 256-dim row reaches the style input`() =
        runTest {
            // The voices/<name>.bin layout (onnx-community/kitten-tts-nano-0.1-ONNX): each voice is a
            // raw little-endian float32 [256] file. Prove the RIGHT voice's row reaches the session.
            val session = fakeSession()
            val engine =
                KittenEngine(
                    onnxEngineContext(session),
                    fileReader = { path ->
                        when {
                            path.endsWith("expr-voice-2-m.bin") -> rawFloats(HEART_EMBEDDING)
                            path.endsWith("expr-voice-2-f.bin") -> rawFloats(EMMA_EMBEDDING)
                            else -> ByteArray(0)
                        }
                    },
                )
            val bundle =
                ModelBundle(
                    id = "kitten-v0.1",
                    fileNames =
                        setOf(
                            "model.onnx",
                            KittenEngine.CONFIG_FILE,
                            "voices/expr-voice-2-m.bin",
                            "voices/expr-voice-2-f.bin",
                        ),
                    sideFiles = mapOf(KittenEngine.CONFIG_FILE to """{"model_type":"style_text_to_speech_2"}"""),
                    rootPath = "/models/kitten-v0.1",
                )
            val descriptor = engine.inspect(bundle)!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Hi.", voiceId = "expr-voice-2-f", speed = 1.0f).toList()

            assertContentEquals(EMMA_EMBEDDING, session.runs[0].getValue(KittenEngine.STYLE_KEY).asFloats())
        }

    @Test
    fun `unload closes the session and drops voices`() =
        runTest {
            val session = fakeSession()
            val engine = buildEngine(session)
            val descriptor = engine.inspect(bundle)!!.descriptor
            engine.load(descriptor)

            engine.unload()

            assertEquals(true, session.closed)
            assertEquals(emptyList(), engine.voices())
        }
}
