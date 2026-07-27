package com.phonetts.integration

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.registry.EngineLoader
import com.phonetts.core.registry.EngineManager
import com.phonetts.core.registry.EngineRegistry
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.resolver.InMemoryOverrideStore
import com.phonetts.core.resolver.Resolver
import com.phonetts.core.sideload.DirectoryBundleReader
import com.phonetts.core.sideload.ModelImporter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.URL
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The real Inflect v2 graphs, driven through the app's OWN pipeline (`DirectoryBundleReader` ->
 * `Resolver` -> `InflectEngine.inspect()` -> `EngineManager` -> the engine's real `synthesize()`)
 * with only the platform backends swapped for their JVM twins ([JvmOnnxRuntime],
 * [EspeakCliPhonemizer]).
 *
 * This exists because Inflect's contract has TWO things a fake session cannot prove:
 *
 *  1. **Rank-0 scalars.** `duration.onnx` declares `length_scale` (and `decode.onnx`
 *     `noise_scale`) with `shape=[]`, not `[1]`. ONNX Runtime enforces the declared rank, so only
 *     a real session can show that `Tensor.rank0Float` is accepted where `Tensor.scalarFloat`
 *     would be rejected.
 *  2. **Two graphs handing tensors to each other.** `decode.onnx` consumes `duration.onnx`'s three
 *     outputs plus a host-sampled noise tensor shaped from `m_p_exp`; a shape or name mistake
 *     surfaces only when real ORT runs it.
 *
 * Network + a system `espeak-ng` are required, so it is opt-in like its sibling
 * [RealModelAutoLoadTest]: run with `-DrunRealModel=true`, optionally pointing
 * `-Dinflect.modelDir=<dir>` at an already-downloaded copy to skip the fetch.
 * (`gradle :integration:test --tests "*RealInflectAutoLoadTest" -DrunRealModel=true`.)
 */
class RealInflectAutoLoadTest {
    private companion object {
        const val REPO = "https://huggingface.co/owensong/Inflect-Nano-v2-ONNX/resolve/main"
        const val TEXT = "Text to speech turns written words into natural sounding audio."

        // Inflect's own published sample rate; asserted rather than assumed so a descriptor that
        // stopped reading config.json would fail here instead of quietly retuning playback.
        const val EXPECTED_SAMPLE_RATE = 24_000
    }

    private var registry: EngineRegistry? = null

    @Test
    fun theRealInflectGraphsAutoLoadAndActuallySpeak() =
        runTest(timeout = 10.minutes) {
            assumeTrue(System.getProperty("runRealModel") == "true", "opt-in: set -DrunRealModel=true")

            val descriptor = importReal(modelDir())

            assertEquals("inflect", descriptor.engineId, "an Inflect bundle must auto-detect as Inflect")
            assertEquals(EXPECTED_SAMPLE_RATE, descriptor.sampleRate, "sample rate must come from config.json")
            assertEquals(1, descriptor.voices.size, "the release ships exactly one fixed voice")
            assertEquals(
                listOf("speed", "variation"),
                descriptor.parameters.map { it.id },
                "both native knobs must be advertised",
            )

            val audio = synthesize(descriptor, SynthesisParams.DEFAULT)

            val seconds = audio.size.toDouble() / descriptor.sampleRate
            assertTrue(audio.isNotEmpty(), "synthesize produced no audio")
            assertTrue(seconds > 2.0, "expected > 2s of audio, got ${"%.2f".format(seconds)}s")
            assertTrue(audio.all { !it.isNaN() && abs(it) <= 1.0f }, "audio must be finite and within [-1, 1]")
            // Real speech, not a silent buffer of the right length.
            assertTrue(audio.any { abs(it) > 0.05f }, "audio is silent - the graphs ran but produced nothing audible")
            println(
                "REAL INFLECT: sr=${descriptor.sampleRate} samples=${audio.size} " +
                    "duration=${"%.2f".format(seconds)}s peak=${"%.3f".format(audio.maxOf { abs(it) })}",
            )
        }

    /**
     * Speed reaches the model's NATIVE duration knob (CLAUDE.md rule 2): half speed must produce
     * substantially MORE samples, which a resample-after-the-fact could never do (it would return
     * the same number of samples at a shifted pitch). This is also the only place the rank-0
     * `length_scale` tensor is proven acceptable to real ONNX Runtime.
     */
    @Test
    fun speedRoutesToTheNativeDurationKnobOnTheRealGraph() =
        runTest(timeout = 10.minutes) {
            assumeTrue(System.getProperty("runRealModel") == "true", "opt-in: set -DrunRealModel=true")

            val descriptor = importReal(modelDir())
            val atNormalSpeed = synthesize(descriptor, SynthesisParams.ofSpeed(1.0f)).size
            val atHalfSpeed = synthesize(descriptor, SynthesisParams.ofSpeed(0.5f)).size

            assertTrue(
                atHalfSpeed > atNormalSpeed * 1.5,
                "half speed must lengthen the audio natively: got $atHalfSpeed vs $atNormalSpeed samples",
            )
        }

    /** The variation knob reaches `noise_scale`: a steadier setting changes the sampled waveform. */
    @Test
    fun theVariationKnobChangesTheRealOutput() =
        runTest(timeout = 10.minutes) {
            assumeTrue(System.getProperty("runRealModel") == "true", "opt-in: set -DrunRealModel=true")

            val descriptor = importReal(modelDir())
            val varied = synthesize(descriptor, SynthesisParams(mapOf("variation" to 0.667f)))
            val steady = synthesize(descriptor, SynthesisParams(mapOf("variation" to 0.0f)))

            assertTrue(steady.isNotEmpty() && varied.isNotEmpty(), "both settings must produce audio")
            val overlap = minOf(steady.size, varied.size)
            assertTrue(
                (0 until overlap).any { abs(steady[it] - varied[it]) > 1e-4f },
                "noise_scale reached the graph but changed nothing - it is probably not wired",
            )
        }

    /** An already-downloaded copy if one was pointed at, otherwise a fresh temp download. */
    private fun modelDir(): File {
        System.getProperty("inflect.modelDir")?.let { return File(it) }
        val dir = Files.createTempDirectory("real-inflect").toFile()
        download("$REPO/onnx/duration.onnx", File(dir, "duration.onnx"))
        download("$REPO/onnx/decode.onnx", File(dir, "decode.onnx"))
        download("$REPO/config.json", File(dir, "config.json"))
        return dir
    }

    // The app's real wiring. The user-pick fallback throws: detection MUST succeed on its own.
    private fun importReal(dir: File): ModelDescriptor {
        val runtimes = RuntimeRegistry().apply { register(JvmOnnxRuntime()) }
        val context = EngineContext(runtimes, EspeakCliPhonemizer())
        val engines = EngineRegistry().also { EngineLoader.seed(it, context) }
        registry = engines
        val resolver =
            Resolver(engines.list(), InMemoryOverrideStore()) {
                error("should have auto-detected the Inflect bundle, not asked the user")
            }
        return ModelImporter(DirectoryBundleReader(), resolver, ModelCatalog()).import(dir.absolutePath)
    }

    private suspend fun synthesize(
        descriptor: ModelDescriptor,
        params: SynthesisParams,
    ): FloatArray {
        val manager = EngineManager(requireNotNull(registry))
        manager.switchTo(descriptor.engineId, descriptor)
        val engine = requireNotNull(manager.currentEngine) { "engine failed to load" }
        val chunks = engine.synthesize(TEXT, descriptor.defaultVoiceId, params).toList()
        return chunks.reduceOrNull { a, b -> a + b } ?: FloatArray(0)
    }

    private fun download(
        url: String,
        target: File,
    ) {
        URL(url).openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    }
}
