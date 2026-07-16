package com.phonetts.engines.melotts

import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakePhonemizer
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
 * 9.3/9.4 — synthesize() chunks via TextChunker, runs the frontend then the acoustic session for
 * each chunk, and assembles the REAL 11-input MeloTTS acoustic contract validated in
 * docs/research/onnx-io.md: `x`/`x_lengths`/`sid`/`tone`/`language`/`bert`/`ja_bert`/
 * `noise_scale`/`length_scale`/`noise_scale_w`/`sdp_ratio`. Speed routes INVERSELY into
 * `length_scale` (spec rule #2, never resampling audio), and the acoustic output — auto-numbered
 * by the real export — is read positionally rather than by a hardcoded name.
 */
class MeloEngineSynthesizeTest {
    private val bertPath = "/models/melo-en/bert_model.onnx"
    private val acousticPath = "/models/melo-en/model.onnx"

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
                    MeloEngine.BERT_ASSET to bertPath,
                    MeloEngine.ACOUSTIC_ASSET to acousticPath,
                ),
        )

    /**
     * A BERT session handing back a plausible `[1, L, 768]` hidden-state tensor under an
     * auto-numbered key (like the real `bert_lml_model.onnx` export) — proves MeloFrontend reads
     * it positionally rather than by a fixed name.
     */
    private fun bertSession(): FakeSession =
        FakeSession(
            outputsFor = { inputs ->
                val tokenCount = inputs.getValue("input_ids").asLongs().size
                val hidden = FloatArray(tokenCount * MeloFrontend.JA_BERT_DIM) { 1f }
                mapOf("1467" to Tensor.floats(hidden, intArrayOf(1, tokenCount, MeloFrontend.JA_BERT_DIM)))
            },
        )

    private fun buildEngine(acousticSession: FakeSession): Pair<MeloEngine, FakeRuntime> {
        val runtime = onnxRuntime { path -> if (path == bertPath) bertSession() else acousticSession }
        val engine = MeloEngine(engineContext(runtime, FakePhonemizer()))
        return engine to runtime
    }

    @Test
    fun `load creates a BERT session and an acoustic session from the descriptor asset paths`() =
        runTest {
            val (engine, runtime) = buildEngine(FakeSession())

            engine.load(descriptor())

            assertEquals(listOf(bertPath, acousticPath), runtime.createdPaths)
        }

    @Test
    fun `synthesize emits one chunk per sentence produced by TextChunker`() =
        runTest {
            val acousticSession =
                FakeSession(
                    // Auto-numbered output name, exactly like the real export.
                    outputsFor = { mapOf("14035" to Tensor.floats(floatArrayOf(0.1f, 0.2f))) },
                )
            val (engine, _) = buildEngine(acousticSession)
            engine.load(descriptor())

            val chunks = engine.synthesize("First sentence. Second sentence!", "EN-US", 1.0f).toList()

            assertEquals(2, chunks.size)
            assertEquals(2, acousticSession.runs.size)
            chunks.forEach { assertEquals(listOf(0.1f, 0.2f), it.toList()) }
        }

    @Test
    fun `synthesize routes the requested speed INVERSELY into length_scale`() =
        runTest {
            val acousticSession = FakeSession(outputsFor = { mapOf("out" to Tensor.floats(floatArrayOf(0f))) })
            val (engine, _) = buildEngine(acousticSession)
            engine.load(descriptor())

            engine.synthesize("Hello there.", "EN-US", 1.25f).toList()

            val run = acousticSession.runs.single()
            assertEquals(1f / 1.25f, run.getValue("length_scale").asFloats().single())
        }

    @Test
    fun `synthesize assembles all 11 real acoustic inputs with matching phoneme-length shapes`() =
        runTest {
            val acousticSession = FakeSession(outputsFor = { mapOf("out" to Tensor.floats(floatArrayOf(0f))) })
            val (engine, _) = buildEngine(acousticSession)
            engine.load(descriptor())

            engine.synthesize("Hi.", "EN-BR", 1.0f).toList()

            val run = acousticSession.runs.single()
            val expectedKeys =
                setOf(
                    "x", "x_lengths", "sid", "tone", "language",
                    "bert", "ja_bert", "noise_scale", "length_scale", "noise_scale_w", "sdp_ratio",
                )
            assertEquals(expectedKeys, run.keys)

            val tokenCount = run.getValue("x").asLongs().size
            assertEquals(tokenCount.toLong(), run.getValue("x_lengths").asLongs().single())
            assertEquals(tokenCount, run.getValue("tone").asLongs().size)
            assertEquals(tokenCount, run.getValue("language").asLongs().size)
            // EN-BR is voice index 1 in the descriptor's voice list -> sid.
            assertEquals(1L, run.getValue("sid").asLongs().single())
        }

    @Test
    fun `synthesize zeroes the zh bert input and shapes ja_bert to the phoneme count`() =
        runTest {
            val acousticSession = FakeSession(outputsFor = { mapOf("out" to Tensor.floats(floatArrayOf(0f))) })
            val (engine, _) = buildEngine(acousticSession)
            engine.load(descriptor())

            engine.synthesize("Hi.", "EN-US", 1.0f).toList()

            val run = acousticSession.runs.single()
            val tokenCount = run.getValue("x").asLongs().size
            val bert = run.getValue("bert")
            val jaBert = run.getValue("ja_bert")

            assertEquals(listOf(1, MeloFrontend.BERT_DIM, tokenCount), bert.shape.toList())
            assertTrue(bert.asFloats().all { it == 0f }, "bert (zh, 1024-dim) must stay zeroed for English")
            assertEquals(listOf(1, MeloFrontend.JA_BERT_DIM, tokenCount), jaBert.shape.toList())
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
