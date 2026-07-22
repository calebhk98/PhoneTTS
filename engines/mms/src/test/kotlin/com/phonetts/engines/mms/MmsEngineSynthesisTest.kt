package com.phonetts.engines.mms

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeSession
import com.phonetts.engines.common.testing.engineContext
import com.phonetts.engines.common.testing.onnxRuntime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §9 coverage for [MmsEngine.synthesize]: the VALIDATED `input_ids`/`attention_mask` tensors it
 * feeds the ONNX session (see [MmsEngine]'s KDoc), the `waveform` output it reads back, sentence
 * chunking (spec rule 8), and the locked speed range (CLAUDE.md rule 2 -- no native speed knob,
 * so any non-1.0 speed must be rejected rather than resampled).
 */
class MmsEngineSynthesisTest {
    private val config =
        """{"_name_or_path": "facebook/mms-tts-eng", "model_type": "vits", "sampling_rate": 16000}"""
    private val vocab = """{"k": 0, " ": 1, "h": 2, "i": 3}"""
    private val tokenizerConfig =
        """{"add_blank": true, "language": "eng", "pad_token": "k", "is_uroman": false, "phonemize": false}"""

    private val bundleSideFiles =
        mapOf("config.json" to config, "vocab.json" to vocab, "tokenizer_config.json" to tokenizerConfig)

    private val bundle =
        ModelBundle(
            id = "mms-tts-eng",
            fileNames = setOf("onnx/model_quantized.onnx", "config.json", "vocab.json", "tokenizer_config.json"),
            sideFiles = bundleSideFiles,
            rootPath = "/models/mms-tts-eng",
        )

    private fun buildLoadedEngine(fakeSession: FakeSession): MmsEngine {
        val fakeRuntime = onnxRuntime(fakeSession)
        // sideFileReader injected so load() doesn't need a real file on disk (spec §9 keeps the
        // seam plain-JVM testable) -- see MmsEngine's KDoc.
        val reader: (String) -> String? = { path ->
            when {
                path.endsWith("vocab.json") -> vocab
                path.endsWith("tokenizer_config.json") -> tokenizerConfig
                else -> null
            }
        }
        return MmsEngine(engineContext(fakeRuntime), sideFileReader = reader)
    }

    @Test
    fun `feeds input_ids and an all-ones attention_mask of the same length and reads waveform back`() =
        runTest {
            val capturedInputs = mutableListOf<Map<String, Tensor>>()
            val fakeSession =
                FakeSession(
                    outputsFor = { inputs ->
                        capturedInputs.add(inputs)
                        mapOf("waveform" to Tensor.floats(floatArrayOf(0.1f, 0.2f, 0.3f)))
                    },
                )
            val engine = buildLoadedEngine(fakeSession)
            val match = assertNotNull(engine.inspect(bundle))
            engine.load(match.descriptor)

            val emitted = engine.synthesize("hi", voiceId = match.descriptor.defaultVoiceId, speed = 1.0f).toList()

            assertEquals(1, emitted.size)
            assertEquals(listOf(0.1f, 0.2f, 0.3f), emitted.single().toList())
            val inputs = capturedInputs.single()
            // add_blank framing: pad(0), h(2), pad(0), i(3), pad(0).
            val ids = inputs.getValue("input_ids").asLongs().toList()
            assertEquals(listOf(0L, 2L, 0L, 3L, 0L), ids)
            val mask = inputs.getValue("attention_mask").asLongs().toList()
            assertEquals(List(ids.size) { 1L }, mask)
        }

    @Test
    fun `a non-1_0 speed is rejected because MMS-VITS exposes no native speed knob`() =
        runTest {
            val fakeSession = FakeSession(outputs = mapOf("waveform" to Tensor.floats(floatArrayOf(0f))))
            val engine = buildLoadedEngine(fakeSession)
            val match = assertNotNull(engine.inspect(bundle))
            engine.load(match.descriptor)

            assertFailsWith<IllegalArgumentException> {
                engine.synthesize("hi", voiceId = match.descriptor.defaultVoiceId, speed = 1.5f).toList()
            }
        }

    @Test
    fun `long text is chunked into sentences and each chunk is run through the session`() =
        runTest {
            val runCount = mutableListOf<Map<String, Tensor>>()
            val fakeSession =
                FakeSession(
                    outputsFor = { inputs ->
                        runCount.add(inputs)
                        mapOf("waveform" to Tensor.floats(floatArrayOf(0.1f)))
                    },
                )
            val engine = buildLoadedEngine(fakeSession)
            val match = assertNotNull(engine.inspect(bundle))
            engine.load(match.descriptor)

            val text = "First sentence. Second sentence!"
            val chunks = engine.synthesize(text, voiceId = match.descriptor.defaultVoiceId, speed = 1.0f).toList()

            assertEquals(2, chunks.size, "expected one audio chunk per sentence (Flow must fully drain)")
            assertEquals(2, runCount.size)
        }

    @Test
    fun `an unknown voice id is rejected`() =
        runTest {
            val fakeSession = FakeSession(outputs = mapOf("waveform" to Tensor.floats(floatArrayOf(0f))))
            val engine = buildLoadedEngine(fakeSession)
            val match = assertNotNull(engine.inspect(bundle))
            engine.load(match.descriptor)

            assertFailsWith<IllegalArgumentException> {
                engine.synthesize("hi", voiceId = "not-a-real-voice", speed = 1.0f).toList()
            }
        }

    @Test
    fun `unload closes the loaded session`() =
        runTest {
            val fakeSession = FakeSession(outputs = mapOf("waveform" to Tensor.floats(floatArrayOf(0f))))
            val engine = buildLoadedEngine(fakeSession)
            val match = assertNotNull(engine.inspect(bundle))
            engine.load(match.descriptor)

            engine.unload()

            assertTrue(fakeSession.closed, "expected the loaded session to be closed on unload()")
            assertEquals(emptyList(), engine.voices())
        }
}
