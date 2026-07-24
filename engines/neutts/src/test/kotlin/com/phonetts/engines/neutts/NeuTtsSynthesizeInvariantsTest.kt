package com.phonetts.engines.neutts

import com.phonetts.core.testing.FakePhonemizer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * NeuTTS's LM+codec pipeline is autoregressive and non-deterministic (CLAUDE.md TDD guidance), so
 * this tests INVARIANTS, not golden audio: one native call per sentence, a Flow that fully drains,
 * every sample finite and bounded, and - the actual point of this engine's frontend, unlike
 * `GgmlTtsEngine`/CosyVoice3's zero-frontend pipelines - that phonemization via
 * [com.phonetts.core.text.Phonemizer] is invoked exactly when the discovered `input_format` says
 * `"phonemes"`, and never otherwise.
 */
class NeuTtsSynthesizeInvariantsTest {
    @Test
    fun `the native pipeline runs once per sentence, one bounded finite chunk each`() =
        runTest {
            val runtime = neuTtsRuntime(audioFor = { boundedAudio(it.text.length) })
            val engine = NeuTtsEngine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle())!!.descriptor
            engine.load(descriptor)

            val chunks =
                engine.synthesize("Hello there. This is a NeuTTS voice!", descriptor.defaultVoiceId, 1.0f).toList()

            assertEquals(2, chunks.size, "expected one audio chunk per sentence (Flow must fully drain)")
            val session = runtime.sessions.single()
            assertEquals(2, session.requests.size, "one native synth per sentence")
            val allSamples = chunks.flatMap { it.toList() }
            assertTrue(allSamples.isNotEmpty(), "synthesize produced no samples")
            assertTrue(allSamples.all { it.isFinite() }, "synthesize produced a NaN/Inf sample")
            assertTrue(allSamples.all { abs(it) <= 1.0f }, "synthesize produced an unbounded sample")
        }

    @Test
    fun `phoneme-format bundles phonemize each sentence before the native call`() =
        runTest {
            val runtime = neuTtsRuntime()
            val phonemizer = FakePhonemizer(mapping = { "PH($it)" })
            val engine = NeuTtsEngine(contextWith(runtime, phonemizer))
            val descriptor = engine.inspect(validBundle(inputFormat = "phonemes"))!!.descriptor
            engine.load(descriptor)

            engine.synthesize("First one. Second one.", descriptor.defaultVoiceId, 1.0f).toList()

            assertEquals(listOf("First one." to "en", "Second one." to "en"), phonemizer.calls)
            val texts = runtime.sessions.single().requests.map { it.text }
            assertEquals(listOf("PH(First one.)", "PH(Second one.)"), texts)
        }

    @Test
    fun `bpe-format bundles pass sentence text through unphonemized`() =
        runTest {
            val runtime = neuTtsRuntime()
            val phonemizer = FakePhonemizer(mapping = { "PH($it)" })
            val engine = NeuTtsEngine(contextWith(runtime, phonemizer))
            val descriptor = engine.inspect(validBundle(inputFormat = "bpe"))!!.descriptor
            engine.load(descriptor)

            engine.synthesize("First one. Second one.", descriptor.defaultVoiceId, 1.0f).toList()

            assertTrue(phonemizer.calls.isEmpty(), "a non-phoneme input_format must never call the phonemizer")
            val texts = runtime.sessions.single().requests.map { it.text }
            assertEquals(listOf("First one.", "Second one."), texts)
        }

    @Test
    fun `load threads the discovered codec decoder path through RuntimeOptions_extras`() =
        runTest {
            val runtime = neuTtsRuntime()
            val engine = NeuTtsEngine(contextWith(runtime))
            val descriptor = engine.inspect(validBundle(codecDecoderFile = "neucodec-decoder.onnx"))!!.descriptor

            engine.load(descriptor)

            assertEquals(
                "/models/${descriptor.modelId}/neucodec-decoder.onnx",
                runtime.optionsSeen.single().extras[NeuTtsEngine.CODEC_DECODER_OPTION_KEY],
            )
        }

    @Test
    fun `voices are the SSOT set the native session reports`() =
        runTest {
            val bundle =
                multiVoiceBundle(voices = listOf(Triple("dave", "dave.wav", "hi"), Triple("jo", "jo.wav", "hi")))
            val runtime = neuTtsRuntime(voiceNames = listOf("dave", "jo"))
            val engine = NeuTtsEngine(contextWith(runtime))

            engine.load(engine.inspect(bundle)!!.descriptor)

            assertEquals(setOf("dave", "jo"), engine.voices().map { it.id }.toSet())
        }

    @Test
    fun `synthesize throws when the engine has not been loaded`() {
        val engine = NeuTtsEngine(contextWith(neuTtsRuntime()))

        assertFailsWith<IllegalStateException> { engine.synthesize("hi", "dave", 1.0f) }
    }

    @Test
    fun `unload closes the native session`() =
        runTest {
            val runtime = neuTtsRuntime()
            val engine = NeuTtsEngine(contextWith(runtime))
            engine.load(engine.inspect(validBundle())!!.descriptor)

            engine.unload()

            assertTrue(runtime.sessions.single().closed, "expected the native session to be closed on unload()")
        }

    @Test
    fun `load fails with a clear error when no native runtime is registered`() =
        runTest {
            val engine = NeuTtsEngine(emptyContext())
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            assertTrue(error.message!!.contains(NeuTtsEngine.NATIVE_RUNTIME_ID), "error should name the runtime")
        }

    @Test
    fun `load fails clearly when the native NeuTTS backend is unavailable`() =
        runTest {
            val engine = NeuTtsEngine(contextWith(neuTtsRuntime(available = false)))
            val descriptor = engine.inspect(validBundle())!!.descriptor

            val error = assertFailsWith<IllegalStateException> { engine.load(descriptor) }
            assertTrue(error.message!!.contains("withNeuTts"), "error should point at the native build flag")
        }

    private fun boundedAudio(seed: Int): FloatArray =
        FloatArray(DEFAULT_SAMPLES_PER_SENTENCE) { i -> boundedWave(i + seed) }

    private fun boundedWave(index: Int): Float = (sin(index * 0.1) * 0.5).toFloat()
}
