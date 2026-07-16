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
 * 9.3/9.4 — synthesize() chunks via TextChunker and, for each chunk, runs the frontend (built
 * from the injected [MeloEngine]'s `tokens.txt`/`lexicon.txt` reader seam) then the PROVEN
 * 7-input acoustic contract (`scripts/model-verify/run_melo2.py`): `x`/`x_lengths`/`tones`/`sid`/
 * `noise_scale`/`length_scale`/`noise_scale_w`. Speed routes INVERSELY into `length_scale` (spec
 * rule 2), and the auto-numbered acoustic output is read positionally.
 */
class MeloEngineSynthesizeTest {
    private val acousticPath = "/models/melo-en/model.onnx"
    private val tokensPath = "/models/melo-en/tokens.txt"
    private val lexiconPath = "/models/melo-en/lexicon.txt"

    private val tokensText =
        """
        _ 0
        p 1
        iy 2
        SP 3
        UNK 4
        , 5
        """.trimIndent()

    private val lexiconText =
        """
        hi p iy 7 8
        """.trimIndent()

    private fun descriptor() =
        ModelDescriptor(
            modelId = "melo-en",
            engineId = MeloEngine.ENGINE_ID,
            displayName = "MeloTTS",
            origin = Origin.BUILT_IN,
            sampleRate = 44_100,
            voices = listOf(Voice("0", "Speaker 1", "en"), Voice("1", "Speaker 2", "en")),
            speedRange = 0.5f..2.0f,
            defaultVoiceId = "0",
            defaultSpeed = 1.0f,
            assetPaths =
                mapOf(
                    MeloEngine.ACOUSTIC_ASSET to acousticPath,
                    MeloEngine.TOKENS_ASSET to tokensPath,
                    MeloEngine.LEXICON_ASSET to lexiconPath,
                ),
        )

    private fun buildEngine(acousticSession: FakeSession): Pair<MeloEngine, FakeRuntime> {
        val runtime = onnxRuntime { acousticSession }
        val reader: (String) -> String = { path ->
            when (path) {
                tokensPath -> tokensText
                lexiconPath -> lexiconText
                else -> error("unexpected read of $path")
            }
        }
        val engine = MeloEngine(engineContext(runtime), reader)
        return engine to runtime
    }

    @Test
    fun `load creates exactly one acoustic session from the descriptor asset path`() =
        runTest {
            val (engine, runtime) = buildEngine(FakeSession())

            engine.load(descriptor())

            assertEquals(listOf(acousticPath), runtime.createdPaths)
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

            val chunks = engine.synthesize("Hi there. Hi again!", "0", 1.0f).toList()

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

            engine.synthesize("Hi.", "0", 1.25f).toList()

            val run = acousticSession.runs.single()
            assertEquals(1f / 1.25f, run.getValue("length_scale").asFloats().single())
        }

    @Test
    fun `synthesize assembles the real 7 acoustic inputs with matching phoneme-length shapes`() =
        runTest {
            val acousticSession = FakeSession(outputsFor = { mapOf("out" to Tensor.floats(floatArrayOf(0f))) })
            val (engine, _) = buildEngine(acousticSession)
            engine.load(descriptor())

            engine.synthesize("Hi.", "1", 1.0f).toList()

            val run = acousticSession.runs.single()
            val expectedKeys =
                setOf("x", "x_lengths", "tones", "sid", "noise_scale", "length_scale", "noise_scale_w")
            assertEquals(expectedKeys, run.keys)

            val tokenCount = run.getValue("x").asLongs().size
            assertEquals(tokenCount.toLong(), run.getValue("x_lengths").asLongs().single())
            assertEquals(tokenCount, run.getValue("tones").asLongs().size)
            // voice id "1" is index 1 in the descriptor's voice list -> sid.
            assertEquals(1L, run.getValue("sid").asLongs().single())
        }

    @Test
    fun `synthesize builds x from the lexicon with add_blank interspersing`() =
        runTest {
            val acousticSession = FakeSession(outputsFor = { mapOf("out" to Tensor.floats(floatArrayOf(0f))) })
            val (engine, _) = buildEngine(acousticSession)
            engine.load(descriptor())

            engine.synthesize("hi", "0", 1.0f).toList()

            val run = acousticSession.runs.single()
            // "hi" -> lexicon phones [p, iy] -> ids [1, 2] -> interspersed [0, 1, 0, 2, 0].
            assertEquals(listOf(0L, 1L, 0L, 2L, 0L), run.getValue("x").asLongs().toList())
            // lexicon tones [7, 8] -> interspersed [0, 7, 0, 8, 0].
            assertEquals(listOf(0L, 7L, 0L, 8L, 0L), run.getValue("tones").asLongs().toList())
        }

    @Test
    fun `synthesize before load fails closed rather than crashing on a null session`() {
        val (engine, _) = buildEngine(FakeSession())

        assertFailsWith<IllegalStateException> {
            engine.synthesize("Hello.", "0", 1.0f)
        }
    }

    @Test
    fun `unload closes the acoustic session and clears the loaded voice list`() =
        runTest {
            val acousticSession = FakeSession()
            val (engine, runtime) = buildEngine(acousticSession)
            engine.load(descriptor())

            engine.unload()

            assertTrue(runtime.sessions.all { it.closed })
            assertEquals(emptyList(), engine.voices())
        }
}
