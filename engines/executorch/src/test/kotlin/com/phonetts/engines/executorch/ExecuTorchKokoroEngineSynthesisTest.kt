package com.phonetts.engines.executorch

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakePhonemizer
import com.phonetts.core.testing.FakeRuntime
import com.phonetts.core.testing.FakeSession
import com.phonetts.engines.common.testing.engineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val CONFIG = """{"modelName": "kokoro", "sample_rate": 24000, "speed_min": 0.5, "speed_max": 2.0}"""

// A tiny stand-in for the engine's own vocab.json: the identity FakePhonemizer returns the input
// text verbatim, so this vocab just needs the letters the test sentences use.
private const val VOCAB = """{"h": 1, "i": 2}"""

private const val DURATION_FILE = "xnnpack/standard/duration_predictor_std.pte"
private const val SYNTH_FILE = "xnnpack/standard/synthesizer_std.pte"

/** Row r's every value is r.toFloat(), so a decoded row is trivially checkable by index. */
private fun tableWithRowMarkers(): FloatArray =
    FloatArray(ExecuTorchKokoroVoiceBinReader.ROWS * ExecuTorchKokoroVoiceBinReader.COLS) { index ->
        (index / ExecuTorchKokoroVoiceBinReader.COLS).toFloat()
    }

/**
 * synthesize()-side plumbing for the two-graph ExecuTorch pipeline: speed routes to the duration
 * predictor's native "speed" parameter (spec §9.4), the active voice's style slice / full row
 * reach the right graphs, the predicted durations expand into the synthesizer's `indices`/`d`
 * inputs (VALIDATED recipe, [DurationExpansion]), and load()/unload() manage BOTH live sessions.
 */
class ExecuTorchKokoroEngineSynthesisTest {
    // A fully in-memory ExecuTorch-Kokoro-shaped bundle: inspect() sees voice ids from file NAMES
    // and reads config/vocab from sideFiles; load() reads each voice's raw bytes through the
    // engine's injected fileReader (Fixture below) -- no temp files needed.
    private fun inMemoryBundle(): ModelBundle =
        ModelBundle(
            id = "et-kokoro-synth-test",
            fileNames =
                setOf(DURATION_FILE, SYNTH_FILE, "config.json", ExecuTorchKokoroEngine.VOCAB_FILE) +
                    setOf("voices/af_heart.bin", "voices/bf_emma.bin"),
            sideFiles = mapOf("config.json" to CONFIG, ExecuTorchKokoroEngine.VOCAB_FILE to VOCAB),
            rootPath = "/virtual/et-kokoro",
        )

    private class Fixture(
        voiceTables: Map<String, FloatArray> =
            mapOf(
                "af_heart" to tableWithRowMarkers(),
                "bf_emma" to ExecuTorchKokoroBinFixtures.uniformTable(0.4f),
            ),
        // pred_dur (one entry per padded token: [pad, h, i, pad]) and d ([1, 6, 2] -- deliberately
        // WIDER than the padded length of 4, so truncation is actually exercised).
        val durationSession: FakeSession =
            FakeSession(
                outputs =
                    mapOf(
                        "output0" to Tensor.longs(longArrayOf(1, 2, 3, 1)),
                        "output1" to
                            Tensor.floats(
                                floatArrayOf(0f, 1f, 10f, 11f, 20f, 21f, 30f, 31f, 40f, 41f, 50f, 51f),
                                intArrayOf(1, 6, 2),
                            ),
                    ),
            ),
        val synthesizerSession: FakeSession =
            FakeSession(outputs = mapOf("output0" to Tensor.floats(floatArrayOf(0.1f, -0.2f)))),
    ) {
        private val voiceBytes = voiceTables.mapValues { (_, table) -> ExecuTorchKokoroBinFixtures.bytesFor(table) }
        private val runtime =
            FakeRuntime(id = "executorch") { path ->
                if (path.contains("duration")) durationSession else synthesizerSession
            }
        val engine =
            ExecuTorchKokoroEngine(
                engineContext(runtime, FakePhonemizer()),
                fileReader = { path -> voiceBytes.getValue(path.substringAfterLast('/').removeSuffix(".bin")) },
                textFileReader = { VOCAB },
            )
    }

    @Test
    fun loadPopulatesVoicesFromTheirBinFiles() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor

            fixture.engine.load(descriptor)

            assertEquals(setOf("af_heart", "bf_emma"), fixture.engine.voices().map { it.id }.toSet())
        }

    @Test
    fun synthesizeRoutesSpeedToTheDurationPredictorsNativeSpeedParameter() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.synthesize("hi", "af_heart", 1.7f).toList()

            val run = fixture.durationSession.runs.single()
            assertEquals(1.7f, run.getValue("speed").asFloats()[0])
        }

    @Test
    fun synthesizeFeedsTheSelectedVoicesStyleSliceAndFullRowToTheRightGraphs() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            // "hi" -> inner tokens [h, i] -> padded [0, h, i, 0] -> innerTokenCount = 2 -> row index 1.
            fixture.engine.synthesize("hi", "af_heart", 1.0f).toList()

            val durationRun = fixture.durationSession.runs.single()
            val synthRun = fixture.synthesizerSession.runs.single()
            val expectedStyle = FloatArray(ExecuTorchKokoroVoiceBinReader.STYLE_COLS) { 1f }
            val expectedVoiceVec = FloatArray(ExecuTorchKokoroVoiceBinReader.COLS) { 1f }
            assertContentEquals(expectedStyle, durationRun.getValue("v_style").asFloats())
            assertContentEquals(expectedVoiceVec, synthRun.getValue("voice_vec").asFloats())
        }

    @Test
    fun synthesizeBuildsIndicesFromThePredictedDurationsViaRepeatInterleave() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.synthesize("hi", "af_heart", 1.0f).toList()

            // pred_dur = [1, 2, 3, 1] -> repeat_interleave([0,1,2,3], pred_dur) = [0,1,1,2,2,2,3].
            val synthRun = fixture.synthesizerSession.runs.single()
            assertContentEquals(longArrayOf(0, 1, 1, 2, 2, 2, 3), synthRun.getValue("indices").asLongs())
        }

    @Test
    fun synthesizeTruncatesDToThePaddedTokenLengthBeforeFeedingTheSynthesizer() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.synthesize("hi", "af_heart", 1.0f).toList()

            // d came back as [1, 6, 2]; padded length is 4 ("hi" -> [pad, h, i, pad]), so only the
            // first 4 rows should reach the synthesizer.
            val synthRun = fixture.synthesizerSession.runs.single()
            val d = synthRun.getValue("d")
            assertContentEquals(intArrayOf(1, 4, 2), d.shape)
            assertContentEquals(floatArrayOf(0f, 1f, 10f, 11f, 20f, 21f, 30f, 31f), d.asFloats())
        }

    @Test
    fun synthesizeUsesTheOtherVoicesRowWhenThatOneIsSelectedInstead() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.synthesize("hi", "bf_emma", 1.0f).toList()

            val synthRun = fixture.synthesizerSession.runs.single()
            val expectedVoiceVec = FloatArray(ExecuTorchKokoroVoiceBinReader.COLS) { 0.4f }
            assertContentEquals(expectedVoiceVec, synthRun.getValue("voice_vec").asFloats())
        }

    @Test
    fun synthesizeEmitsOneChunkPerSentence() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            val audio = fixture.engine.synthesize("hi. hi.", "af_heart", 1.0f).toList()

            assertEquals(2, audio.size)
            assertEquals(2, fixture.durationSession.runs.size)
            assertEquals(2, fixture.synthesizerSession.runs.size)
        }

    @Test
    fun synthesizeReturnsTheSynthesizersAudioOutput() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            val audio = fixture.engine.synthesize("hi", "af_heart", 1.0f).toList()

            assertContentEquals(floatArrayOf(0.1f, -0.2f), audio.single())
        }

    @Test
    fun synthesizeBeforeLoadFailsRatherThanSilentlyProducingNothing() =
        runTest {
            val fixture = Fixture()

            assertFailsWith<IllegalStateException> {
                fixture.engine.synthesize("hi", "af_heart", 1.0f).toList()
            }
        }

    @Test
    fun synthesizeWithAnUnknownVoiceIdFailsThroughTheSharedVoiceLookupHelper() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            val error =
                assertFailsWith<IllegalArgumentException> {
                    fixture.engine.synthesize("hi", "not-a-real-voice", 1.0f).toList()
                }
            assertTrue(error.message!!.contains("not-a-real-voice"))
            assertTrue(error.message!!.contains("not among its known voices"))
        }

    @Test
    fun unloadClosesBothSessionsAndDropsVoices() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.unload()

            assertTrue(fixture.durationSession.closed)
            assertTrue(fixture.synthesizerSession.closed)
            assertTrue(fixture.engine.voices().isEmpty())
        }
}
