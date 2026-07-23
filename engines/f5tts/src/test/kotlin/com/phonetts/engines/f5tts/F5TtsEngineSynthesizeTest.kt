package com.phonetts.engines.f5tts

import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeSession
import com.phonetts.engines.common.testing.engineContext
import com.phonetts.engines.common.testing.onnxRuntime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The three-graph orchestration (README-io.md): NOT a claim that this matches a real ONNX graph
 * byte-for-byte (nothing here was run against real weights), but a proof of the WIRING this
 * engine is responsible for - the right session gets the right named tensors, the transformer's
 * own `denoised` output is fed back as next iteration's `noise` input, the loop runs a fixed
 * number of times, and the decoder gets the last iteration's state plus `ref_signal_len`
 * forwarded from preprocessing. All per DakeQQ/F5-TTS-ONNX's export contract, cited in
 * `README-io.md`.
 */
class F5TtsEngineSynthesizeTest {
    private val refAudioSamples = FloatArray(F5DurationPlanner.HOP_LENGTH_SAMPLES * 10) { 0.01f }
    private val refText = "reference transcript. "

    // The DakeQQ default (NFE_STEP=32, looped NFE_STEP-1 times) - see README-io.md "NFE step
    // count is also baked in" for why this is an internal orchestration constant, not read from
    // F5TtsEngine (which keeps it private, like every other engine's tensor-shape constants).
    private val expectedTransformerCalls = 31

    private fun buildLoadedEngine(
        preprocessSession: FakeSession,
        transformerSession: FakeSession,
        decodeSession: FakeSession,
    ): F5TtsEngine {
        val runtime =
            onnxRuntime { path ->
                when {
                    path.endsWith(F5TtsEngine.PREPROCESS_FILE) -> preprocessSession
                    path.endsWith(F5TtsEngine.TRANSFORMER_FILE) -> transformerSession
                    path.endsWith(F5TtsEngine.DECODE_FILE) -> decodeSession
                    else -> error("unexpected model path: $path")
                }
            }
        return F5TtsEngine(
            engineContext(runtime),
            textReader = { path ->
                when {
                    path.endsWith(F5TtsEngine.VOCAB_FILE) -> SAMPLE_VOCAB_TEXT
                    path.endsWith(".reference.txt") -> refText
                    else -> error("unexpected text path: $path")
                }
            },
            audioReader = { F5WavDecoder.Decoded(refAudioSamples, 24_000, channels = 1) },
        )
    }

    private fun preprocessOutputs(): Map<String, Tensor> =
        mapOf(
            "noise" to Tensor.floats(floatArrayOf(0.1f)),
            "rope_cos_q" to Tensor.floats(floatArrayOf(1f)),
            "rope_sin_q" to Tensor.floats(floatArrayOf(1f)),
            "rope_cos_k" to Tensor.floats(floatArrayOf(1f)),
            "rope_sin_k" to Tensor.floats(floatArrayOf(1f)),
            "cat_mel_text" to Tensor.floats(floatArrayOf(1f)),
            "cat_mel_text_drop" to Tensor.floats(floatArrayOf(1f)),
            "ref_signal_len" to Tensor.longs(longArrayOf(10L)),
        )

    /** Each call nudges `noise` by a fixed delta and increments `time_step` by 1. */
    private fun transformerStepOutputs(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val noiseIn = inputs.getValue("noise").asFloats()
        val stepIn = inputs.getValue("time_step").asLongs().single()
        return mapOf(
            "denoised" to Tensor.floats(FloatArray(noiseIn.size) { i -> noiseIn[i] + STEP_DELTA }),
            "time_step" to Tensor.longs(longArrayOf(stepIn + 1)),
        )
    }

    private fun bundleWithAliceVoice() = validBundle(extraFiles = setOf("alice.reference.wav", "alice.reference.txt"))

    @Test
    fun `synthesize produces one chunk per sentence and returns the decoder's audio`() =
        runTest {
            val preprocessSession = FakeSession(outputsFor = { preprocessOutputs() })
            val transformerSession = FakeSession(outputsFor = ::transformerStepOutputs)
            val decodeSession =
                FakeSession(outputsFor = { mapOf("output_audio" to Tensor.floats(floatArrayOf(0.25f, -0.25f))) })
            val engine = buildLoadedEngine(preprocessSession, transformerSession, decodeSession)
            val descriptor = engine.inspect(bundleWithAliceVoice())!!.descriptor
            engine.load(descriptor)

            val chunks = engine.synthesize("Hello there.", "alice", 1.0f).toList()

            assertEquals(1, chunks.size, "expected one audio chunk per sentence")
            assertEquals(listOf(0.25f, -0.25f), chunks.single().toList())
        }

    @Test
    fun `the transformer runs exactly the fixed NFE-1 step count once per sentence`() =
        runTest {
            val transformerCalls = mutableListOf<Map<String, Tensor>>()
            val preprocessSession = FakeSession(outputsFor = { preprocessOutputs() })
            val transformerSession =
                FakeSession(
                    outputsFor = { inputs ->
                        transformerCalls.add(inputs)
                        transformerStepOutputs(inputs)
                    },
                )
            val decodeSession = FakeSession(outputsFor = { mapOf("output_audio" to Tensor.floats(floatArrayOf(0f))) })
            val engine = buildLoadedEngine(preprocessSession, transformerSession, decodeSession)
            val descriptor = engine.inspect(bundleWithAliceVoice())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("One sentence. Two sentences.", "alice", 1.0f).toList()

            assertEquals(2 * expectedTransformerCalls, transformerCalls.size)
        }

    @Test
    fun `the transformer's denoised output is fed back as the next iteration's noise input`() =
        runTest {
            val transformerCalls = mutableListOf<Map<String, Tensor>>()
            val preprocessSession = FakeSession(outputsFor = { preprocessOutputs() })
            val transformerSession =
                FakeSession(
                    outputsFor = { inputs ->
                        transformerCalls.add(inputs)
                        transformerStepOutputs(inputs)
                    },
                )
            val decodeSession = FakeSession(outputsFor = { mapOf("output_audio" to Tensor.floats(floatArrayOf(0f))) })
            val engine = buildLoadedEngine(preprocessSession, transformerSession, decodeSession)
            val descriptor = engine.inspect(bundleWithAliceVoice())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Hi.", "alice", 1.0f).toList()

            val firstNoise = transformerCalls.first().getValue("noise").asFloats().single()
            val secondNoise = transformerCalls[1].getValue("noise").asFloats().single()
            assertEquals(0.1f, firstNoise, 1e-6f, "first call must feed preprocessing's own noise output")
            assertEquals(
                firstNoise + STEP_DELTA,
                secondNoise,
                1e-6f,
                "second call must feed back the first call's denoised output as noise",
            )
        }

    @Test
    fun `the decoder receives the final denoised state and preprocessing's ref_signal_len`() =
        runTest {
            val decodeCalls = mutableListOf<Map<String, Tensor>>()
            val preprocessSession = FakeSession(outputsFor = { preprocessOutputs() })
            val transformerSession = FakeSession(outputsFor = ::transformerStepOutputs)
            val decodeSession =
                FakeSession(
                    outputsFor = { inputs ->
                        decodeCalls.add(inputs)
                        mapOf("output_audio" to Tensor.floats(floatArrayOf(0f)))
                    },
                )
            val engine = buildLoadedEngine(preprocessSession, transformerSession, decodeSession)
            val descriptor = engine.inspect(bundleWithAliceVoice())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Hi.", "alice", 1.0f).toList()

            val decodeInputs = decodeCalls.single()
            assertEquals(10L, decodeInputs.getValue("ref_signal_len").asLongs().single())
            val expectedFinalNoise = 0.1f + expectedTransformerCalls * STEP_DELTA
            assertEquals(expectedFinalNoise, decodeInputs.getValue("denoised").asFloats().single(), 1e-3f)
        }

    @Test
    fun `preprocessing receives the reference audio, combined text ids, and a positive max_duration`() =
        runTest {
            val preprocessCalls = mutableListOf<Map<String, Tensor>>()
            val preprocessSession =
                FakeSession(
                    outputsFor = { inputs ->
                        preprocessCalls.add(inputs)
                        preprocessOutputs()
                    },
                )
            val transformerSession = FakeSession(outputsFor = ::transformerStepOutputs)
            val decodeSession = FakeSession(outputsFor = { mapOf("output_audio" to Tensor.floats(floatArrayOf(0f))) })
            val engine = buildLoadedEngine(preprocessSession, transformerSession, decodeSession)
            val descriptor = engine.inspect(bundleWithAliceVoice())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("hi", "alice", 1.0f).toList()

            val inputs = preprocessCalls.single()
            assertTrue(inputs.getValue("audio").asFloats().contentEquals(refAudioSamples))
            // "reference transcript. " + "hi", DakeQQ's ref_text + gen_text concatenation, no separator.
            assertEquals((refText + "hi").length, inputs.getValue("text_ids").asLongs().size)
            assertTrue(inputs.getValue("max_duration").asLongs().single() > 0L)
        }

    @Test
    fun `synthesize throws a clear error when the voice has no bundled reference clip`() =
        runTest {
            val preprocessSession = FakeSession(outputsFor = { preprocessOutputs() })
            val transformerSession = FakeSession(outputsFor = ::transformerStepOutputs)
            val decodeSession = FakeSession(outputsFor = { mapOf("output_audio" to Tensor.floats(floatArrayOf(0f))) })
            val engine = buildLoadedEngine(preprocessSession, transformerSession, decodeSession)
            // No .reference.wav/.reference.txt pair -> falls back to DEFAULT_VOICE with no clip.
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            val error =
                assertFailsWith<IllegalStateException> {
                    engine.synthesize("hi", F5TtsEngine.DEFAULT_VOICE.id, 1.0f).toList()
                }
            assertTrue(error.message!!.contains("reference clip"), "error should explain what's missing")
        }

    @Test
    fun `synthesize throws when the engine has not been loaded`() {
        val engine = buildLoadedEngine(FakeSession(), FakeSession(), FakeSession())

        assertFailsWith<IllegalStateException> {
            engine.synthesize("hi", F5TtsEngine.DEFAULT_VOICE.id, 1.0f)
        }
    }

    @Test
    fun `unload closes all three onnx sessions`() =
        runTest {
            val preprocessSession = FakeSession(outputsFor = { preprocessOutputs() })
            val transformerSession = FakeSession(outputsFor = ::transformerStepOutputs)
            val decodeSession = FakeSession(outputsFor = { mapOf("output_audio" to Tensor.floats(floatArrayOf(0f))) })
            val engine = buildLoadedEngine(preprocessSession, transformerSession, decodeSession)
            engine.load(engine.inspect(validBundle())!!.descriptor)

            engine.unload()

            assertTrue(preprocessSession.closed)
            assertTrue(transformerSession.closed)
            assertTrue(decodeSession.closed)
            assertEquals(listOf(F5TtsEngine.DEFAULT_VOICE.id), engine.voices().map { it.id })
        }

    @Test
    fun `load fails with a clear error when no onnx runtime is registered`() =
        runTest {
            val engine = F5TtsEngine(engineContext())
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            assertTrue(error.message!!.contains("onnx"), "error should name the missing runtime")
        }

    private companion object {
        const val STEP_DELTA = 0.001f
    }
}
