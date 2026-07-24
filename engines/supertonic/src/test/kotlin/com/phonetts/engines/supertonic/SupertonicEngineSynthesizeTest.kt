package com.phonetts.engines.supertonic

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
 * The four-graph orchestration (see [SupertonicEngine] KDoc): NOT a claim that this matches a real
 * ONNX graph byte-for-byte (nothing here was run against real weights), but a proof of the WIRING
 * this engine is responsible for - the right session gets the right named tensors, speed routes to
 * the duration predictor's output (never a resample of the vocoder's own output), the
 * flow-matching loop runs a fixed number of steps feeding its own output back in, and the vocoder's
 * output gets trimmed to the predicted duration. Every output is read positionally (this engine's
 * own [outputKey] below stands in for whatever real name the graph reports - see the class KDoc's
 * "OUTPUT TENSOR NAMES" note for why that is an explicit, documented assumption).
 */
class SupertonicEngineSynthesizeTest {
    private val outputKey = "output"

    private fun buildLoadedEngine(
        dpSession: FakeSession,
        textEncSession: FakeSession,
        vectorEstSession: FakeSession,
        vocoderSession: FakeSession,
        noiseValue: Float = 0.5f,
    ): SupertonicEngine {
        val runtime =
            onnxRuntime { path ->
                when {
                    path.endsWith(SupertonicEngine.DP_FILE) -> dpSession
                    path.endsWith(SupertonicEngine.TEXT_ENCODER_FILE) -> textEncSession
                    path.endsWith(SupertonicEngine.VECTOR_ESTIMATOR_FILE) -> vectorEstSession
                    path.endsWith(SupertonicEngine.VOCODER_FILE) -> vocoderSession
                    else -> error("unexpected model path: $path")
                }
            }
        return SupertonicEngine(
            engineContext(runtime),
            textReader = { path ->
                when {
                    path.endsWith(SupertonicEngine.INDEXER_FILE) -> asciiIndexerJson()
                    path.contains(SupertonicEngine.VOICE_STYLES_DIR) -> styleJson()
                    else -> error("unexpected text path: $path")
                }
            },
            noiseSource = { size -> FloatArray(size) { noiseValue } },
        )
    }

    private fun dpSessionReturning(durationSeconds: Float) =
        FakeSession(outputsFor = { mapOf(outputKey to Tensor.floats(floatArrayOf(durationSeconds))) })

    private fun textEncSessionReturning() =
        FakeSession(outputsFor = { mapOf(outputKey to Tensor.floats(floatArrayOf(1f, 2f, 3f))) })

    /** Each call adds [STEP_DELTA] to every element of the incoming `noisy_latent`. */
    private fun vectorEstSession(calls: MutableList<Map<String, Tensor>>) =
        FakeSession(
            outputsFor = { inputs ->
                calls.add(inputs)
                val noiseIn = inputs.getValue(SupertonicEngine.NOISY_LATENT_KEY).asFloats()
                mapOf(outputKey to Tensor.floats(FloatArray(noiseIn.size) { i -> noiseIn[i] + STEP_DELTA }))
            },
        )

    private fun vocoderSession(wav: FloatArray) = FakeSession(outputsFor = { mapOf(outputKey to Tensor.floats(wav)) })

    @Test
    fun `the predicted duration trims the vocoder's raw output to the right sample count`() =
        runTest {
            val wav = FloatArray(25_000) { it.toFloat() }
            val engine =
                buildLoadedEngine(
                    dpSessionReturning(0.5f),
                    textEncSessionReturning(),
                    vectorEstSession(mutableListOf()),
                    vocoderSession(wav),
                )
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            val chunks = engine.synthesize("Hi.", "F1", 1.0f).toList()

            val expectedSamples = 22_050 // 0.5s * 44100 Hz
            assertEquals(expectedSamples, chunks.single().size)
            assertEquals(wav.copyOf(expectedSamples).toList(), chunks.single().toList())
        }

    @Test
    fun `speed divides the duration predictor's raw output before it drives generation`() =
        runTest {
            val engine =
                buildLoadedEngine(
                    dpSessionReturning(1.0f),
                    textEncSessionReturning(),
                    vectorEstSession(mutableListOf()),
                    vocoderSession(FloatArray(50_000) { 0f }),
                )
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            // 1.0s raw duration / 2.0 speed = 0.5s -> 22050 samples, exactly like the un-sped-up
            // 0.5s-duration case above - proves speed routes to the native duration knob, never a
            // resample of the vocoder's own output (CLAUDE.md rule 2).
            val chunks = engine.synthesize("Hi.", "F1", 2.0f).toList()

            assertEquals(22_050, chunks.single().size)
        }

    @Test
    fun `the vector estimator runs a fixed number of denoising steps with an incrementing current_step`() =
        runTest {
            val vectorCalls = mutableListOf<Map<String, Tensor>>()
            val engine =
                buildLoadedEngine(
                    dpSessionReturning(0.5f),
                    textEncSessionReturning(),
                    vectorEstSession(vectorCalls),
                    vocoderSession(FloatArray(30_000) { 0f }),
                )
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Hi.", "F1", 1.0f).toList()

            assertEquals(8, vectorCalls.size)
            for ((step, call) in vectorCalls.withIndex()) {
                assertEquals(step.toFloat(), call.getValue(SupertonicEngine.CURRENT_STEP_KEY).asFloats().single())
                assertEquals(8f, call.getValue(SupertonicEngine.TOTAL_STEP_KEY).asFloats().single())
            }
        }

    @Test
    fun `each denoising step feeds the previous step's output back in as noisy_latent`() =
        runTest {
            val vectorCalls = mutableListOf<Map<String, Tensor>>()
            val engine =
                buildLoadedEngine(
                    dpSessionReturning(0.5f),
                    textEncSessionReturning(),
                    vectorEstSession(vectorCalls),
                    vocoderSession(FloatArray(30_000) { 0f }),
                    noiseValue = 0.1f,
                )
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Hi.", "F1", 1.0f).toList()

            val firstIn = vectorCalls.first().getValue(SupertonicEngine.NOISY_LATENT_KEY).asFloats().first()
            val secondIn = vectorCalls[1].getValue(SupertonicEngine.NOISY_LATENT_KEY).asFloats().first()
            assertEquals(0.1f, firstIn, 1e-6f)
            assertEquals(0.1f + STEP_DELTA, secondIn, 1e-6f)
        }

    @Test
    fun `the selected voice's style vectors reach the duration predictor and text encoder`() =
        runTest {
            val dpCalls = mutableListOf<Map<String, Tensor>>()
            val textEncCalls = mutableListOf<Map<String, Tensor>>()
            val dpSession =
                FakeSession(
                    outputsFor = { inputs ->
                        dpCalls.add(inputs)
                        mapOf(outputKey to Tensor.floats(floatArrayOf(0.5f)))
                    },
                )
            val textEncSession =
                FakeSession(
                    outputsFor = { inputs ->
                        textEncCalls.add(inputs)
                        mapOf(outputKey to Tensor.floats(floatArrayOf(1f)))
                    },
                )
            val engine =
                buildLoadedEngine(
                    dpSession,
                    textEncSession,
                    vectorEstSession(mutableListOf()),
                    vocoderSession(FloatArray(30_000) { 0f }),
                )
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.synthesize("Hi.", "F1", 1.0f).toList()

            assertEquals(DP_SIZE, dpCalls.single().getValue(SupertonicEngine.STYLE_DP_KEY).asFloats().size)
            assertEquals(TTL_SIZE, textEncCalls.single().getValue(SupertonicEngine.STYLE_TTL_KEY).asFloats().size)
        }

    @Test
    fun `synthesize with an unknown voice id fails`() =
        runTest {
            val engine =
                buildLoadedEngine(
                    dpSessionReturning(0.5f),
                    textEncSessionReturning(),
                    vectorEstSession(mutableListOf()),
                    vocoderSession(FloatArray(30_000) { 0f }),
                )
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            assertFailsWith<IllegalStateException> {
                engine.synthesize("Hi.", "not-a-real-voice", 1.0f).toList()
            }
        }

    @Test
    fun `synthesize before load fails rather than silently producing nothing`() {
        val engine =
            buildLoadedEngine(
                dpSessionReturning(0.5f),
                textEncSessionReturning(),
                vectorEstSession(mutableListOf()),
                vocoderSession(FloatArray(30_000) { 0f }),
            )

        assertFailsWith<IllegalStateException> {
            engine.synthesize("Hi.", "F1", 1.0f)
        }
    }

    @Test
    fun `unload closes all four onnx sessions and clears voices`() =
        runTest {
            val dpSession = dpSessionReturning(0.5f)
            val textEncSession = textEncSessionReturning()
            val vectorEstSessionInstance = vectorEstSession(mutableListOf())
            val vocoderSessionInstance = vocoderSession(FloatArray(30_000) { 0f })
            val engine = buildLoadedEngine(dpSession, textEncSession, vectorEstSessionInstance, vocoderSessionInstance)
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            engine.unload()

            assertTrue(dpSession.closed)
            assertTrue(textEncSession.closed)
            assertTrue(vectorEstSessionInstance.closed)
            assertTrue(vocoderSessionInstance.closed)
            assertEquals(emptyList(), engine.voices())
        }

    @Test
    fun `load fails with a clear error when no onnx runtime is registered`() =
        runTest {
            val engine = SupertonicEngine(engineContext())
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            assertTrue(error.message!!.contains("onnx"), "error should name the missing runtime")
        }

    private companion object {
        const val STEP_DELTA = 0.001f
    }
}
