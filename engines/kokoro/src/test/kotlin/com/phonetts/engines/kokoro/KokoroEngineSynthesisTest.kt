package com.phonetts.engines.kokoro

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeSession
import com.phonetts.engines.common.testing.onnxEngineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val CONFIG =
    """{"family": "kokoro", "sample_rate": 24000, "speed_min": 0.5, "speed_max": 2.0, """ +
        """"default_voice": "af_heart", "default_speed": 1.0}"""

/**
 * synthesize()-side plumbing against the REAL Kokoro voice format (VALIDATED via
 * `scripts/model-verify/run_kokoro.py`): speed routes to the model's native "speed" parameter
 * (spec §9.4), the STYLE ROW selected by the active voice's `voices/<name>.bin` table -- indexed
 * by that sentence's token count, not a flat per-voice embedding -- reaches the session, and
 * load()/unload() manage the one live session (spec §5.5).
 */
class KokoroEngineSynthesisTest {
    // A fully in-memory Kokoro-shaped bundle: inspect() sees the voice ids from file NAMES
    // (voices/*.bin is binary, never a text side file) and reads the config from sideFiles;
    // load() reads each voice's raw bytes through the engine's injected fileReader (Fixture
    // below) -- no temp files needed. rootPath is a virtual string only used to build asset paths.
    private fun inMemoryBundle(): ModelBundle =
        ModelBundle(
            id = "kokoro-synth-test",
            fileNames = setOf("model.onnx", "config.json", "voices/af_heart.bin", "voices/bf_emma.bin"),
            sideFiles = mapOf("config.json" to CONFIG),
            rootPath = "/virtual/kokoro",
        )

    private class Fixture(
        voiceTables: Map<String, FloatArray> =
            mapOf(
                "af_heart" to KokoroBinFixtures.uniformTable(0.1f),
                "bf_emma" to KokoroBinFixtures.uniformTable(0.4f),
            ),
    ) {
        val session = FakeSession(outputs = mapOf("waveform" to Tensor.floats(floatArrayOf(0.1f, -0.2f))))
        private val voiceBytes = voiceTables.mapValues { (_, table) -> KokoroBinFixtures.bytesFor(table) }
        val engine =
            KokoroEngine(
                onnxEngineContext(session),
                // The injected reader seam: load() gets each voice's raw bytes without touching
                // disk, keyed by the "<dir>/<voiceId>.bin" path the engine itself constructs.
                fileReader = { path -> voiceBytes.getValue(path.substringAfterLast('/').removeSuffix(".bin")) },
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
    fun synthesizeRoutesSpeedToTheNativeSpeedParameter() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.synthesize("Hello world.", "af_heart", 1.7f).toList()

            val run = fixture.session.runs.single()
            assertEquals(1.7f, run.getValue("speed").asFloats()[0])
        }

    @Test
    fun synthesizeFeedsTheSelectedVoicesTableNotAnyOthers() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.synthesize("Hi.", "bf_emma", 1.0f).toList()

            val run = fixture.session.runs.single()
            assertContentEquals(FloatArray(256) { 0.4f }, run.getValue("style").asFloats())
        }

    @Test
    fun synthesizeUsesTheOtherVoicesTableWhenThatOneIsSelectedInstead() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.synthesize("Hi.", "af_heart", 1.0f).toList()

            val run = fixture.session.runs.single()
            assertContentEquals(FloatArray(256) { 0.1f }, run.getValue("style").asFloats())
        }

    @Test
    fun synthesizeSelectsTheStyleRowIndexedByTheSentencesTokenCount() =
        runTest {
            // "hello" is 5 plain-ASCII lowercase letters -> KokoroFrontend's misaki stand-in
            // accepts it whole and emits exactly 5 tokens, so the VALIDATED recipe's
            // `row = min(tokenCount, 509)` (scripts/model-verify/run_kokoro.py line 23) selects
            // row 5 -- distinguishable here because tableWithRowMarkers() makes row r's value r.
            val fixture =
                Fixture(
                    voiceTables =
                        mapOf(
                            "af_heart" to KokoroBinFixtures.tableWithRowMarkers(),
                            "bf_emma" to KokoroBinFixtures.uniformTable(0.4f),
                        ),
                )
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.synthesize("hello", "af_heart", 1.0f).toList()

            val run = fixture.session.runs.single()
            assertContentEquals(FloatArray(256) { 5f }, run.getValue("style").asFloats())
        }

    @Test
    fun synthesizeEmitsOneChunkPerSentence() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            val audio = fixture.engine.synthesize("First sentence. Second sentence!", "af_heart", 1.0f).toList()

            assertEquals(2, audio.size)
            assertEquals(2, fixture.session.runs.size)
        }

    @Test
    fun synthesizeBeforeLoadFailsRatherThanSilentlyProducingNothing() =
        runTest {
            val fixture = Fixture()

            assertFailsWith<IllegalStateException> {
                fixture.engine.synthesize("Hello.", "af_heart", 1.0f).toList()
            }
        }

    @Test
    fun synthesizeWithAnUnknownVoiceIdFails() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            assertFailsWith<IllegalStateException> {
                fixture.engine.synthesize("Hello.", "not-a-real-voice", 1.0f).toList()
            }
        }

    @Test
    fun unloadClosesTheSessionAndDropsVoices() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(inMemoryBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.unload()

            assertTrue(fixture.session.closed)
            assertTrue(fixture.engine.voices().isEmpty())
        }
}
