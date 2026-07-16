package com.phonetts.engines.kokoro

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.ModelBundle
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakePhonemizer
import com.phonetts.core.testing.FakeRuntime
import com.phonetts.core.testing.FakeSession
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val CONFIG =
    """{"family": "kokoro", "sample_rate": 24000, "speed_min": 0.5, "speed_max": 2.0, """ +
        """"default_voice": "af_heart", "default_speed": 1.0}"""

// A small fake voices/embeddings table for the seam - two voices with distinct, easily
// distinguishable embeddings so a test can prove the *right* row reaches the session.
private const val VOICES =
    """[
        {"id": "af_heart", "name": "Heart", "language": "en-us", "embedding": [0.1, 0.2, 0.3]},
        {"id": "bf_emma", "name": "Emma", "language": "en-gb", "embedding": [0.4, -0.1, 0.2]}
    ]"""

/**
 * synthesize()-side plumbing: speed routes to the model's native "speed" parameter (spec §9.4),
 * the selected voice's row from the embeddings table (not a per-file weight) reaches the
 * session, and load()/unload() manage the one live session (spec §5.5).
 */
class KokoroEngineSynthesisTest {
    /** Writes a real Kokoro-shaped bundle to a temp dir so load() has real files to read from disk. */
    private fun realBundle(): ModelBundle {
        val dir = Files.createTempDirectory("kokoro-engine-test").toFile()
        File(dir, "model.onnx").writeText("stub-weights")
        File(dir, "config.json").writeText(CONFIG)
        File(dir, "voices.json").writeText(VOICES)
        return ModelBundle(
            id = "kokoro-synth-test",
            fileNames = setOf("model.onnx", "config.json", "voices.json"),
            sideFiles = mapOf("config.json" to CONFIG, "voices.json" to VOICES),
            rootPath = dir.absolutePath,
        )
    }

    private class Fixture {
        val session = FakeSession(outputs = mapOf("audio" to Tensor.floats(floatArrayOf(0.1f, -0.2f))))
        val runtime = FakeRuntime(id = "onnx", sessionFactory = { session })
        val engine =
            KokoroEngine(
                EngineContext(
                    runtimes = RuntimeRegistry().apply { register(runtime) },
                    phonemizer = FakePhonemizer(),
                ),
            )
    }

    @Test
    fun loadPopulatesVoicesFromTheEmbeddingsTable() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(realBundle())).descriptor

            fixture.engine.load(descriptor)

            assertEquals(setOf("af_heart", "bf_emma"), fixture.engine.voices().map { it.id }.toSet())
        }

    @Test
    fun synthesizeRoutesSpeedToTheNativeSpeedParameter() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(realBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.synthesize("Hello world.", "af_heart", 1.7f).toList()

            val run = fixture.session.runs.single()
            assertEquals(1.7f, run.getValue("speed").asFloats()[0])
        }

    @Test
    fun synthesizeFeedsTheSelectedVoicesEmbeddingNotAnyOthers() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(realBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.synthesize("Hi.", "bf_emma", 1.0f).toList()

            val run = fixture.session.runs.single()
            assertContentEquals(floatArrayOf(0.4f, -0.1f, 0.2f), run.getValue("style").asFloats())
        }

    @Test
    fun synthesizeUsesTheOtherVoicesEmbeddingWhenThatOneIsSelectedInstead() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(realBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.synthesize("Hi.", "af_heart", 1.0f).toList()

            val run = fixture.session.runs.single()
            assertContentEquals(floatArrayOf(0.1f, 0.2f, 0.3f), run.getValue("style").asFloats())
        }

    @Test
    fun synthesizeEmitsOneChunkPerSentence() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(realBundle())).descriptor
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
            val descriptor = requireNotNull(fixture.engine.inspect(realBundle())).descriptor
            fixture.engine.load(descriptor)

            assertFailsWith<IllegalStateException> {
                fixture.engine.synthesize("Hello.", "not-a-real-voice", 1.0f).toList()
            }
        }

    @Test
    fun unloadClosesTheSessionAndDropsVoices() =
        runTest {
            val fixture = Fixture()
            val descriptor = requireNotNull(fixture.engine.inspect(realBundle())).descriptor
            fixture.engine.load(descriptor)

            fixture.engine.unload()

            assertTrue(fixture.session.closed)
            assertTrue(fixture.engine.voices().isEmpty())
        }
}
