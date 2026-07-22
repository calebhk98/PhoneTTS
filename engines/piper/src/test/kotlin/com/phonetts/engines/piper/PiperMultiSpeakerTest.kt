package com.phonetts.engines.piper

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeSession
import com.phonetts.engines.common.testing.engineContext
import com.phonetts.engines.common.testing.onnxRuntime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Multi-speaker Piper coverage. A VITS graph with `num_speakers > 1` (VCTK, LibriTTS, L2Arctic, …)
 * has an extra REQUIRED `sid` input; the engine previously fed only the three single-speaker inputs,
 * so the ONNX session rejected the run and those voices showed up as "failed" in the benchmark. This
 * proves the fix: each speaker becomes its own [com.phonetts.core.engine.Voice], synthesis feeds the
 * right `sid`, single-speaker voices still feed NO `sid`, and the shared graph is loaded exactly once.
 */
class PiperMultiSpeakerTest {
    private val multiSpeakerSidecar =
        """
        {
          "audio": {"sample_rate": 22050},
          "espeak": {"voice": "en-us"},
          "phoneme_id_map": {"^": [1], "$": [2], "_": [0], "h": [10], "i": [11]},
          "inference": {"noise_scale": 0.667, "length_scale": 1.0, "noise_w": 0.8},
          "num_speakers": 3,
          "speaker_id_map": {"alice": 0, "bob": 1, "carol": 2}
        }
        """.trimIndent()

    private val singleSpeakerSidecar =
        """
        {
          "audio": {"sample_rate": 22050},
          "espeak": {"voice": "en-us"},
          "phoneme_id_map": {"^": [1], "$": [2], "_": [0], "h": [10], "i": [11]}
        }
        """.trimIndent()

    private fun bundle(sidecar: String) =
        ModelBundle(
            id = "en_US-libritts_r-medium",
            fileNames = setOf("voice.onnx", "voice.onnx.json"),
            sideFiles = mapOf("voice.onnx.json" to sidecar),
            rootPath = "/models/en_US-libritts_r-medium",
        )

    @Test
    fun `a multi-speaker graph fans out into one voice per speaker, ordered by sid`() {
        val engine = PiperEngine(engineContext(), sidecarReader = { multiSpeakerSidecar })

        val match = assertNotNull(engine.inspect(bundle(multiSpeakerSidecar)))

        val voices = match.descriptor.voices
        assertEquals(listOf("voice#alice", "voice#bob", "voice#carol"), voices.map { it.id })
        assertEquals("voice#alice", match.descriptor.defaultVoiceId)
        assertTrue(voices.first().name.contains("alice"), "speaker name should surface in the display name")
    }

    @Test
    fun `synthesis feeds the selected speaker's sid, and the graph is loaded only once`() =
        runTest {
            val captured = mutableListOf<Map<String, Tensor>>()
            val session =
                FakeSession(
                    outputsFor = { inputs ->
                        captured.add(inputs)
                        mapOf("output" to Tensor.floats(floatArrayOf(0f)))
                    },
                )
            val runtime = onnxRuntime(session)
            val engine = PiperEngine(engineContext(runtime), sidecarReader = { multiSpeakerSidecar })
            val match = assertNotNull(engine.inspect(bundle(multiSpeakerSidecar)))
            engine.load(match.descriptor)

            engine.synthesize("hi", voiceId = "voice#bob", speed = 1.0f).toList()

            val sid = captured.single().getValue("sid").asLongs()
            assertEquals(listOf(1L), sid.toList(), "bob's sid (1) must be fed to the session")
            // Three speaker-voices, but the shared VITS graph must be created ONCE (spec rule 6).
            assertEquals(1, runtime.createdPaths.size, "the multi-speaker graph must load exactly once")
        }

    @Test
    fun `a single-speaker graph still feeds no sid input`() =
        runTest {
            val captured = mutableListOf<Map<String, Tensor>>()
            val session =
                FakeSession(
                    outputsFor = { inputs ->
                        captured.add(inputs)
                        mapOf("output" to Tensor.floats(floatArrayOf(0f)))
                    },
                )
            val engine = PiperEngine(engineContext(onnxRuntime(session)), sidecarReader = { singleSpeakerSidecar })
            val match = assertNotNull(engine.inspect(bundle(singleSpeakerSidecar)))
            engine.load(match.descriptor)

            engine.synthesize("hi", voiceId = "voice", speed = 1.0f).toList()

            assertFalse(captured.single().containsKey("sid"), "single-speaker graphs have no sid input")
        }

    @Test
    fun `unload closes the shared multi-speaker session and clears the voices`() =
        runTest {
            val session = FakeSession(outputs = mapOf("output" to Tensor.floats(floatArrayOf(0f))))
            val engine = PiperEngine(engineContext(onnxRuntime(session)), sidecarReader = { multiSpeakerSidecar })
            val match = assertNotNull(engine.inspect(bundle(multiSpeakerSidecar)))
            engine.load(match.descriptor)

            engine.unload()

            assertTrue(session.closed, "the shared graph's session must be closed on unload()")
            assertEquals(emptyList(), engine.voices())
        }

    @Test
    fun `num_speakers with no speaker_id_map exposes bare index speakers`() {
        val sidecar =
            """
            {
              "audio": {"sample_rate": 22050},
              "phoneme_id_map": {"^": [1], "$": [2], "_": [0]},
              "num_speakers": 2
            }
            """.trimIndent()

        val config = assertNotNull(PiperVoiceConfig.parse(sidecar))

        assertTrue(config.isMultiSpeaker)
        assertEquals(listOf(PiperSpeaker("0", 0), PiperSpeaker("1", 1)), config.speakers())
    }
}
