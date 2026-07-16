package com.phonetts.engines.melotts

import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeRuntime
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
 * 9.3/9.4 — synthesize() chunks via TextChunker, runs the frontend then the acoustic session
 * for each chunk, and routes [speed] straight into the acoustic session's native `speed` input
 * (never resampling the emitted audio, spec rule #2).
 */
class MeloEngineSynthesizeTest {
    private val bertOutputPath = "/models/melo-en/bert_model.onnx"
    private val acousticOutputPath = "/models/melo-en/model.onnx"

    private fun descriptor() =
        ModelDescriptor(
            modelId = "melo-en",
            engineId = MeloEngine.ENGINE_ID,
            displayName = "MeloTTS",
            origin = Origin.BUILT_IN,
            sampleRate = 44_100,
            voices = listOf(Voice("EN-US", "EN-US", "EN"), Voice("EN-BR", "EN-BR", "EN")),
            speedRange = 0.5f..2.0f,
            defaultVoiceId = "EN-US",
            defaultSpeed = 1.0f,
            assetPaths =
                mapOf(
                    MeloEngine.BERT_ASSET to bertOutputPath,
                    MeloEngine.ACOUSTIC_ASSET to acousticOutputPath,
                ),
        )

    private fun buildEngine(acousticSession: FakeSession): Pair<MeloEngine, FakeRuntime> {
        val bertSession = FakeSession(outputs = mapOf("bert_features" to Tensor.floats(FloatArray(0))))
        val runtime = onnxRuntime { path -> if (path == bertOutputPath) bertSession else acousticSession }
        val engine = MeloEngine(engineContext(runtime))
        return engine to runtime
    }

    @Test
    fun `load creates a BERT session and an acoustic session from the descriptor asset paths`() =
        runTest {
            val (engine, runtime) = buildEngine(FakeSession())

            engine.load(descriptor())

            assertEquals(listOf(bertOutputPath, acousticOutputPath), runtime.createdPaths)
        }

    @Test
    fun `synthesize emits one chunk per sentence produced by TextChunker`() =
        runTest {
            val acousticSession =
                FakeSession(
                    outputsFor = { mapOf("audio" to Tensor.floats(floatArrayOf(0.1f, 0.2f))) },
                )
            val (engine, _) = buildEngine(acousticSession)
            engine.load(descriptor())

            val chunks = engine.synthesize("First sentence. Second sentence!", "EN-US", 1.0f).toList()

            assertEquals(2, chunks.size)
            assertEquals(2, acousticSession.runs.size)
            chunks.forEach { assertEquals(listOf(0.1f, 0.2f), it.toList()) }
        }

    @Test
    fun `synthesize routes the requested speed straight into the acoustic session's speed input`() =
        runTest {
            val acousticSession = FakeSession(outputsFor = { mapOf("audio" to Tensor.floats(floatArrayOf(0f))) })
            val (engine, _) = buildEngine(acousticSession)
            engine.load(descriptor())

            engine.synthesize("Hello there.", "EN-US", 1.75f).toList()

            val run = acousticSession.runs.single()
            assertEquals(1.75f, run.getValue("speed").asFloats().single())
        }

    @Test
    fun `synthesize feeds the frontend's token ids and BERT features into the acoustic session`() =
        runTest {
            val acousticSession = FakeSession(outputsFor = { mapOf("audio" to Tensor.floats(floatArrayOf(0f))) })
            val (engine, _) = buildEngine(acousticSession)
            engine.load(descriptor())

            engine.synthesize("Hi.", "EN-BR", 1.0f).toList()

            val run = acousticSession.runs.single()
            assertTrue(run.containsKey("token_ids"))
            assertTrue(run.containsKey("bert_features"))
            // EN-BR is voice index 1 in the descriptor's voice list.
            assertEquals(1L, run.getValue("speaker_id").asLongs().single())
        }

    @Test
    fun `synthesize before load fails closed rather than crashing on a null session`() {
        val (engine, _) = buildEngine(FakeSession())

        assertFailsWith<IllegalStateException> {
            engine.synthesize("Hello.", "EN-US", 1.0f)
        }
    }

    @Test
    fun `unload closes both sessions and clears the loaded voice list`() =
        runTest {
            val acousticSession = FakeSession()
            val (engine, runtime) = buildEngine(acousticSession)
            engine.load(descriptor())

            engine.unload()

            assertTrue(runtime.sessions.all { it.closed })
            assertEquals(emptyList(), engine.voices())
        }
}
