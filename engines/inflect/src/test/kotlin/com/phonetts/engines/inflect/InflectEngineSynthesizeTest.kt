package com.phonetts.engines.inflect

import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.model.ModelParameter
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeSession
import com.phonetts.engines.common.testing.engineContext
import com.phonetts.engines.common.testing.onnxRuntime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Spec §9.4 (speed routes to the model's NATIVE parameter, never a resample) plus the rest of the
 * two-graph contract read out of the real ONNX protobufs (see [InflectEngine]'s KDoc): the
 * reciprocal `length_scale`, the rank-0 scalars ONNX Runtime enforces, the noise tensor shaped from
 * `m_p_exp`'s own shape, and the aligned tensors passed through untouched.
 */
class InflectEngineSynthesizeTest {
    private companion object {
        // A small stand-in for the aligned acoustic distribution: 4 "channels" x 3 frames.
        const val CHANNELS = 4
        const val FRAMES = 3
        val ALIGNED_SHAPE = intArrayOf(1, CHANNELS, FRAMES)
        val MASK_SHAPE = intArrayOf(1, 1, FRAMES)
        val MEAN_P = FloatArray(CHANNELS * FRAMES) { it * 0.25f }
        val LOGS_P = FloatArray(CHANNELS * FRAMES) { -it * 0.5f }
        val Y_MASK = FloatArray(FRAMES) { 1f }
        val WAVEFORM = floatArrayOf(0.2f, 0.4f, -0.6f, 0.8f)
        const val NOISE_VALUE = 0.125f
    }

    private lateinit var durationSession: FakeSession
    private lateinit var decodeSession: FakeSession

    private fun loadedEngine(): InflectEngine {
        durationSession =
            FakeSession(
                outputsFor = {
                    mapOf(
                        InflectEngine.M_P_KEY to Tensor.floats(MEAN_P, ALIGNED_SHAPE),
                        InflectEngine.LOGS_P_KEY to Tensor.floats(LOGS_P, ALIGNED_SHAPE),
                        InflectEngine.Y_MASK_KEY to Tensor.floats(Y_MASK, MASK_SHAPE),
                    )
                },
            )
        decodeSession = FakeSession(outputsFor = { mapOf(InflectEngine.WAVEFORM_KEY to Tensor.floats(WAVEFORM)) })

        val runtime =
            onnxRuntime { path ->
                if (path.endsWith(InflectEngine.DURATION_FILE)) durationSession else decodeSession
            }
        // Constant "noise" so the decode inputs are exactly assertable.
        return InflectEngine(engineContext(runtime), noiseSource = { _, size -> FloatArray(size) { NOISE_VALUE } })
    }

    private suspend fun synthesize(
        text: String = "Hi.",
        params: SynthesisParams = SynthesisParams.DEFAULT,
    ): List<FloatArray> {
        val engine = loadedEngine()
        val descriptor = checkNotNull(engine.inspect(InflectFixtures.flatBundle())).descriptor
        engine.load(descriptor)
        return engine.synthesize(text, descriptor.defaultVoiceId, params).toList()
    }

    @Test
    fun `speed routes to the native length_scale as its reciprocal, and nothing else changes`() =
        runTest {
            synthesize(params = SynthesisParams.ofSpeed(1.25f))

            val lengthScale = durationSession.runs.single().getValue(InflectEngine.LENGTH_SCALE_KEY)
            assertEquals(1f / 1.25f, lengthScale.asFloats().single())
        }

    @Test
    fun `a slower speed lengthens rather than resamples - length_scale grows above one`() =
        runTest {
            synthesize(params = SynthesisParams.ofSpeed(0.5f))

            assertEquals(2f, durationSession.runs.single().getValue(InflectEngine.LENGTH_SCALE_KEY).asFloats().single())
        }

    @Test
    fun `length_scale is a rank-0 scalar, as the graph declares - a 1-element vector would fail`() =
        runTest {
            synthesize()

            val lengthScale = durationSession.runs.single().getValue(InflectEngine.LENGTH_SCALE_KEY)
            assertEquals(0, lengthScale.shape.size, "duration.onnx declares length_scale with shape []")
        }

    @Test
    fun `noise_scale is a rank-0 scalar too, and carries the variation value`() =
        runTest {
            val params = SynthesisParams(mapOf(InflectEngine.VARIATION_ID to 0.25f))

            synthesize(params = params)

            val noiseScale = decodeSession.runs.single().getValue(InflectEngine.NOISE_SCALE_KEY)
            assertEquals(0, noiseScale.shape.size, "decode.onnx declares noise_scale with shape []")
            assertEquals(0.25f, noiseScale.asFloats().single())
        }

    @Test
    fun `variation defaults to the model's own published default when the caller sets nothing`() =
        runTest {
            synthesize()

            assertEquals(
                InflectEngine.DEFAULT_VARIATION,
                decodeSession.runs.single().getValue(InflectEngine.NOISE_SCALE_KEY).asFloats().single(),
            )
        }

    @Test
    fun `a variation outside the model's range is refused rather than silently clamped`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                synthesize(params = SynthesisParams(mapOf(InflectEngine.VARIATION_ID to 1.5f)))
            }
        }

    @Test
    fun `a speed outside the model's declared range is refused by the descriptor guard`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                synthesize(params = SynthesisParams.ofSpeed(3f))
            }
        }

    @Test
    fun `tokens and lengths reach the duration graph, blank-interleaved`() =
        runTest {
            synthesize("Hi.")

            val run = durationSession.runs.single()
            val tokens = run.getValue(InflectEngine.TOKENS_KEY).asLongs()
            // "H", "i", "." are all Inflect symbols -> 3 symbols -> 2n+1 = 7 ids.
            assertEquals(7, tokens.size)
            assertContentEquals(intArrayOf(1, 7), run.getValue(InflectEngine.TOKENS_KEY).shape)
            assertEquals(7L, run.getValue(InflectEngine.LENGTHS_KEY).asLongs().single())
        }

    @Test
    fun `the aligned tensors are passed through to decode untouched`() =
        runTest {
            synthesize()

            val decode = decodeSession.runs.single()
            assertContentEquals(MEAN_P, decode.getValue(InflectEngine.M_P_KEY).asFloats())
            assertContentEquals(LOGS_P, decode.getValue(InflectEngine.LOGS_P_KEY).asFloats())
            assertContentEquals(Y_MASK, decode.getValue(InflectEngine.Y_MASK_KEY).asFloats())
        }

    @Test
    fun `zp_noise is shaped from m_p_exp's own runtime shape, not a hardcoded channel count`() =
        runTest {
            synthesize()

            val noise = decodeSession.runs.single().getValue(InflectEngine.ZP_NOISE_KEY)
            assertContentEquals(ALIGNED_SHAPE, noise.shape)
            assertEquals(CHANNELS * FRAMES, noise.asFloats().size)
            assertTrue(noise.asFloats().all { it == NOISE_VALUE })
        }

    @Test
    fun `the waveform is read from the decode graph and shaped with a trailing pause`() =
        runTest {
            val chunks = synthesize("Hi.")

            val audio = chunks.single()
            // A '.' chunk gets upstream's 0.22 s pause at 24 kHz on top of the 4 raw samples.
            val expectedPause = (24_000 * InflectAudioShaping.boundaryPauseSeconds("Hi.")).toInt()
            assertEquals(WAVEFORM.size + expectedPause, audio.size)
            assertTrue(audio.takeLast(expectedPause).all { it == 0f }, "the tail must be silence")
        }

    @Test
    fun `the same sentence produces the same noise twice - generation is reproducible`() =
        runTest {
            // The real Gaussian source (no injected fake) must still be seed-stable per sentence.
            val runtime =
                onnxRuntime { path ->
                    if (path.endsWith(InflectEngine.DURATION_FILE)) {
                        FakeSession(
                            outputsFor = {
                                mapOf(
                                    InflectEngine.M_P_KEY to Tensor.floats(MEAN_P, ALIGNED_SHAPE),
                                    InflectEngine.LOGS_P_KEY to Tensor.floats(LOGS_P, ALIGNED_SHAPE),
                                    InflectEngine.Y_MASK_KEY to Tensor.floats(Y_MASK, MASK_SHAPE),
                                )
                            },
                        )
                    } else {
                        FakeSession(outputsFor = { mapOf(InflectEngine.WAVEFORM_KEY to Tensor.floats(WAVEFORM)) })
                    }
                }
            val engine = InflectEngine(engineContext(runtime))
            val descriptor = checkNotNull(engine.inspect(InflectFixtures.flatBundle())).descriptor
            engine.load(descriptor)

            engine.synthesize("Hello there.", descriptor.defaultVoiceId, 1.0f).toList()
            engine.synthesize("Hello there.", descriptor.defaultVoiceId, 1.0f).toList()

            val decodeRuns = runtime.sessions.flatMap { it.runs }.filter { InflectEngine.ZP_NOISE_KEY in it }
            assertEquals(2, decodeRuns.size)
            assertContentEquals(
                decodeRuns[0].getValue(InflectEngine.ZP_NOISE_KEY).asFloats(),
                decodeRuns[1].getValue(InflectEngine.ZP_NOISE_KEY).asFloats(),
            )
        }

    @Test
    fun `one flow feeds both consumers - each sentence is its own chunk`() =
        runTest {
            val chunks = synthesize("One. Two. Three.")

            assertEquals(3, chunks.size)
            assertEquals(3, durationSession.runs.size)
            assertEquals(3, decodeSession.runs.size)
        }

    @Test
    fun `an unknown voice id fails loudly instead of silently synthesizing the wrong voice`() =
        runTest {
            val engine = loadedEngine()
            val descriptor = checkNotNull(engine.inspect(InflectFixtures.flatBundle())).descriptor
            engine.load(descriptor)

            assertFailsWith<IllegalArgumentException> {
                engine.synthesize("Hi.", "some-other-model-voice", 1.0f).toList()
            }
        }

    @Test
    fun `unload closes both sessions`() =
        runTest {
            val engine = loadedEngine()
            engine.load(checkNotNull(engine.inspect(InflectFixtures.flatBundle())).descriptor)

            engine.unload()

            assertTrue(durationSession.closed, "the duration session must be closed")
            assertTrue(decodeSession.closed, "the decode session must be closed")
            assertTrue(engine.voices().isEmpty(), "an unloaded engine advertises no voices")
        }

    @Test
    fun `the speed parameter the descriptor advertises is the one synthesize actually reads`() =
        runTest {
            val engine = loadedEngine()
            val descriptor = checkNotNull(engine.inspect(InflectFixtures.flatBundle())).descriptor
            engine.load(descriptor)
            val declared = descriptor.parameters.single { it.id == ModelParameter.SPEED_ID }

            engine.synthesize(
                "Hi.",
                descriptor.defaultVoiceId,
                SynthesisParams.ofSpeed(declared.range!!.start),
            ).toList()

            assertEquals(
                1f / declared.range!!.start,
                durationSession.runs.single().getValue(InflectEngine.LENGTH_SCALE_KEY).asFloats().single(),
            )
        }
}
