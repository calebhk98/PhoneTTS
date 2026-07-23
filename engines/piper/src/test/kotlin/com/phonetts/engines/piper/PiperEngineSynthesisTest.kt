package com.phonetts.engines.piper

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeRuntime
import com.phonetts.core.testing.FakeSession
import com.phonetts.engines.common.testing.engineContext
import com.phonetts.engines.common.testing.onnxRuntime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §9.4 speed-routing coverage plus phoneme->id mapping through the full [PiperEngine.synthesize]
 * path. The engine must feed the session a `length_scale` that is the INVERSE of the requested
 * speed (larger length_scale = slower audio) and must never resample output to change speed.
 */
class PiperEngineSynthesisTest {
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
            id = "voice-pack",
            fileNames = setOf("voice.onnx", "voice.onnx.json"),
            sideFiles = mapOf("voice.onnx.json" to sidecarJson),
            rootPath = "/models/voice-pack",
        )

    private fun buildLoadedEngine(fakeSession: FakeSession): Pair<PiperEngine, FakeRuntime> {
        val fakeRuntime = onnxRuntime(fakeSession)
        // sidecarReader injected so load() doesn't need a real file on disk (spec §9 keeps the
        // seam plain-JVM testable) - see PiperEngine's KDoc.
        val engine = PiperEngine(engineContext(fakeRuntime), sidecarReader = { sidecarJson })
        return engine to fakeRuntime
    }

    @Test
    fun `feeds the requested speed as the inverse of the config default length_scale`() =
        runTest {
            val capturedInputs = mutableListOf<Map<String, Tensor>>()
            val fakeSession =
                FakeSession(
                    outputsFor = { inputs ->
                        capturedInputs.add(inputs)
                        mapOf("output" to Tensor.floats(floatArrayOf(0.1f, 0.2f, 0.3f)))
                    },
                )
            val (engine, _) = buildLoadedEngine(fakeSession)
            val match = assertNotNull(engine.inspect(bundle))
            engine.load(match.descriptor)

            val speed = 2.0f
            val emitted = engine.synthesize("hi", voiceId = "voice", speed = speed).toList()

            assertEquals(1, emitted.size)
            assertEquals(1, capturedInputs.size)
            val scales = capturedInputs.single().getValue("scales").asFloats()
            // config default length_scale (1.0) / speed(2.0) => 0.5: half the length_scale, so
            // faster speech, with NO resampling of the emitted audio (rule 2).
            val expectedLengthScale = 1.0f / speed
            assertEquals(expectedLengthScale, scales[1], 1e-6f)
        }

    @Test
    fun `a slower speed produces a larger length_scale than a faster one`() =
        runTest {
            val slowRuns = mutableListOf<Map<String, Tensor>>()
            val fastRuns = mutableListOf<Map<String, Tensor>>()
            val slowSession =
                FakeSession(
                    outputsFor = { inputs ->
                        slowRuns.add(inputs)
                        mapOf("output" to Tensor.floats(floatArrayOf(0f)))
                    },
                )
            val fastSession =
                FakeSession(
                    outputsFor = { inputs ->
                        fastRuns.add(inputs)
                        mapOf("output" to Tensor.floats(floatArrayOf(0f)))
                    },
                )
            val slowRuntime = onnxRuntime(slowSession)
            val fastRuntime = onnxRuntime(fastSession)

            val slowEngine = PiperEngine(engineContext(slowRuntime), sidecarReader = { sidecarJson })
            val fastEngine = PiperEngine(engineContext(fastRuntime), sidecarReader = { sidecarJson })
            val slowMatch = assertNotNull(slowEngine.inspect(bundle))
            val fastMatch = assertNotNull(fastEngine.inspect(bundle))
            slowEngine.load(slowMatch.descriptor)
            fastEngine.load(fastMatch.descriptor)

            slowEngine.synthesize("hi", "voice", speed = 0.5f).toList()
            fastEngine.synthesize("hi", "voice", speed = 1.5f).toList()

            val slowLengthScale = slowRuns.single().getValue("scales").asFloats()[1]
            val fastLengthScale = fastRuns.single().getValue("scales").asFloats()[1]
            assertTrue(
                slowLengthScale > fastLengthScale,
                "expected slower speed to yield a larger length_scale: slow=$slowLengthScale fast=$fastLengthScale",
            )
        }

    @Test
    fun `maps phonemes to ids via the voice's phoneme_id_map with BOS-PAD-EOS framing`() =
        runTest {
            val capturedInputs = mutableListOf<Map<String, Tensor>>()
            val fakeSession =
                FakeSession(
                    outputsFor = { inputs ->
                        capturedInputs.add(inputs)
                        mapOf("output" to Tensor.floats(floatArrayOf(0f)))
                    },
                )
            val (engine, _) = buildLoadedEngine(fakeSession)
            val match = assertNotNull(engine.inspect(bundle))
            engine.load(match.descriptor)

            // engineContext()'s default phonemizer is identity, so phonemizing "hi" yields "hi".
            engine.synthesize("hi", voiceId = "voice", speed = 1.0f).toList()

            val ids = capturedInputs.single().getValue("input").asLongs().toList()
            // BOS(1), h(10), pad(0), i(11), pad(0), EOS(2) per phoneme_id_map above.
            assertEquals(listOf(1L, 10L, 0L, 11L, 0L, 2L), ids)
        }

    @Test
    fun `long text is chunked into sentences and each chunk is run through the session`() =
        runTest {
            val runCount = mutableListOf<Map<String, Tensor>>()
            val fakeSession =
                FakeSession(
                    outputsFor = { inputs ->
                        runCount.add(inputs)
                        mapOf("output" to Tensor.floats(floatArrayOf(0.1f)))
                    },
                )
            val (engine, _) = buildLoadedEngine(fakeSession)
            val match = assertNotNull(engine.inspect(bundle))
            engine.load(match.descriptor)

            val chunks =
                engine.synthesize("First sentence. Second sentence!", voiceId = "voice", speed = 1.0f).toList()

            assertEquals(2, chunks.size, "expected one audio chunk per sentence (Flow must fully drain)")
            assertEquals(2, runCount.size)
        }

    @Test
    fun `voice selection reaches the right loaded voice's own session`() =
        runTest {
            val alphaSession =
                FakeSession(outputsFor = { mapOf("output" to Tensor.floats(floatArrayOf(0.1f))) })
            val betaSession =
                FakeSession(outputsFor = { mapOf("output" to Tensor.floats(floatArrayOf(0.2f))) })
            val multiVoiceBundle =
                ModelBundle(
                    id = "voice-pack-2",
                    fileNames = setOf("alpha.onnx", "alpha.onnx.json", "beta.onnx", "beta.onnx.json"),
                    sideFiles = mapOf("alpha.onnx.json" to sidecarJson, "beta.onnx.json" to sidecarJson),
                    rootPath = "/models/voice-pack-2",
                )
            val runtime = onnxRuntime { path -> if (path.endsWith("alpha.onnx")) alphaSession else betaSession }
            val engine = PiperEngine(engineContext(runtime), sidecarReader = { sidecarJson })
            val match = assertNotNull(engine.inspect(multiVoiceBundle))
            engine.load(match.descriptor)

            engine.synthesize("hi", voiceId = "alpha", speed = 1.0f).toList()

            assertEquals(1, alphaSession.runs.size)
            assertEquals(0, betaSession.runs.size)
        }

    @Test
    fun `unload closes every loaded voice session`() =
        runTest {
            val fakeSession = FakeSession(outputs = mapOf("output" to Tensor.floats(floatArrayOf(0f))))
            val (engine, _) = buildLoadedEngine(fakeSession)
            val match = assertNotNull(engine.inspect(bundle))
            engine.load(match.descriptor)

            engine.unload()

            assertTrue(fakeSession.closed, "expected the loaded voice's session to be closed on unload()")
            assertEquals(emptyList(), engine.voices())
        }
}
