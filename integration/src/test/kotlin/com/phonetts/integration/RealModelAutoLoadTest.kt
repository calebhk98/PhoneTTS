package com.phonetts.integration

import com.phonetts.core.engine.EngineContext
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
 * Proves the claim "a user downloads a RANDOM model for one of our engines and it just works — no
 * code changes." This is NOT a re-implementation of the pipeline: it drives the app's OWN code —
 * [DirectoryBundleReader] -> [Resolver] -> each engine's real `inspect()` -> [ModelImporter] ->
 * [EngineManager] -> the engine's real `synthesize()` — over a real, un-curated model, with only
 * the platform backends swapped for their JVM twins ([JvmOnnxRuntime], [EspeakCliPhonemizer]).
 *
 * Network + system `espeak-ng` are required, so it is opt-in: run with `-DrunRealModel=true`.
 * (`gradle :integration:test --tests "*RealModelAutoLoadTest" -DrunRealModel=true`.)
 */
class RealModelAutoLoadTest {
    // A Piper voice that is deliberately NOT in BuiltInCatalog (that ships en_US-lessac-medium) — a
    // stand-in for "some random model the user found and downloaded."
    private val base =
        "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/low/en_US-amy-low.onnx"

    @Test
    fun downloadingARandomPiperVoiceAutoLoadsAndActuallySpeaks() =
        runTest(timeout = 5.minutes) {
            assumeTrue(System.getProperty("runRealModel") == "true", "opt-in: set -DrunRealModel=true")

            // 1. THE DOWNLOAD — fetch the model + its sidecar into a fresh folder, exactly the files
            //    the in-app download/sideload flow would drop on disk.
            val dir = Files.createTempDirectory("random-piper").toFile()
            download(base, File(dir, "en_US-amy-low.onnx"))
            download("$base.json", File(dir, "en_US-amy-low.onnx.json"))

            // 2. THE APP'S REAL WIRING — real engines seeded via ServiceLoader, real resolver/catalog,
            //    real platform backends. The user-pick fallback throws: detection MUST succeed on its own.
            val runtimes = RuntimeRegistry().apply { register(JvmOnnxRuntime()) }
            val context = EngineContext(runtimes, EspeakCliPhonemizer(), dir.absolutePath)
            val registry = EngineRegistry().also { EngineLoader.seed(it, context) }
            val catalog = ModelCatalog()
            val resolver =
                Resolver(registry.list(), InMemoryOverrideStore()) {
                    error("should have auto-detected the Piper voice, not asked the user")
                }
            val importer = ModelImporter(DirectoryBundleReader(), resolver, catalog)

            // 3. THE CLICK — importing the folder is exactly what a download/sideload triggers.
            val descriptor = importer.import(dir.absolutePath)

            assertEquals("piper", descriptor.engineId, "a bare Piper voice must auto-detect as Piper")
            assertTrue(descriptor.voices.isNotEmpty(), "auto-loaded model must be first-class with voices")
            assertEquals(listOf(descriptor.modelId), catalog.list().map { it.modelId }, "it must appear in the catalog")

            // 4. THE USER PLAYS IT — select + synthesize through the engine's real generation path.
            val manager = EngineManager(registry)
            manager.switchTo(descriptor.engineId, descriptor)
            val engine = requireNotNull(manager.currentEngine) { "engine failed to load" }

            val text = "Text to speech turns written words into natural sounding audio."
            val chunks = engine.synthesize(text, descriptor.defaultVoiceId, 1.0f).toList()

            // 5. IT ACTUALLY SPEAKS — real audio out: > 2 seconds, bounded, no NaNs.
            val samples = chunks.sumOf { it.size }
            val durationSeconds = samples.toDouble() / descriptor.sampleRate
            assertTrue(samples > 0, "synthesize produced no audio")
            assertTrue(durationSeconds > 2.0, "expected > 2s of audio, got ${"%.2f".format(durationSeconds)}s")
            assertTrue(
                chunks.all { chunk -> chunk.all { !it.isNaN() && abs(it) <= 1.5f } },
                "audio must be finite and bounded",
            )
            println(
                "REAL AUTO-LOAD: engine=${descriptor.engineId} voice=${descriptor.defaultVoiceId} " +
                    "sr=${descriptor.sampleRate} samples=$samples duration=${"%.2f".format(durationSeconds)}s",
            )
        }

    // A non-Piper engine (StyleTTS2 — completely different architecture: style embeddings + a
    // symbol vocab), and a model NOT in BuiltInCatalog (that ships nano-0.1). Proves the fix that
    // gave KittenFrontend the real StyleTTS2 vocab actually yields audio end-to-end.
    @Test
    fun downloadingARandomKittenModelAutoLoadsAndActuallySpeaks() =
        runTest(timeout = 5.minutes) {
            assumeTrue(System.getProperty("runRealModel") == "true", "opt-in: set -DrunRealModel=true")

            val repo = "https://huggingface.co/KittenML/kitten-tts-nano-0.2/resolve/main"
            val dir = Files.createTempDirectory("random-kitten").toFile()
            download("$repo/kitten_tts_nano_v0_2.onnx", File(dir, "kitten_tts_nano_v0_2.onnx"))
            download("$repo/config.json", File(dir, "config.json"))
            download("$repo/voices.npz", File(dir, "voices.npz"))

            val descriptor = importReal(dir)
            assertEquals("kittentts", descriptor.engineId, "a KittenTTS bundle must auto-detect as KittenTTS")
            assertTrue(descriptor.voices.isNotEmpty())

            val (samples, durationSeconds, finite) = synthesize(descriptor)
            assertTrue(samples > 0, "synthesize produced no audio")
            assertTrue(durationSeconds > 2.0, "expected > 2s, got ${"%.2f".format(durationSeconds)}s")
            assertTrue(finite, "audio must be finite and bounded")
            println(
                "REAL AUTO-LOAD: engine=${descriptor.engineId} voice=${descriptor.defaultVoiceId} " +
                    "sr=${descriptor.sampleRate} samples=$samples duration=${"%.2f".format(durationSeconds)}s",
            )
        }

    // Kokoro (also StyleTTS2, and its own repo layout: fp32 onnx/, tokenizer.json vocab, voices/*.bin).
    // Proves the fix that gave KokoroFrontend the real tokenizer.json vocab yields audio end-to-end.
    @Test
    fun downloadingARandomKokoroModelAutoLoadsAndActuallySpeaks() =
        runTest(timeout = 8.minutes) {
            assumeTrue(System.getProperty("runRealModel") == "true", "opt-in: set -DrunRealModel=true")

            val repo = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main"
            val dir = Files.createTempDirectory("random-kokoro").toFile()
            File(dir, "voices").mkdirs()
            download("$repo/onnx/model.onnx", File(dir, "model.onnx")) // fp32 (q8f16 segfaults ORT)
            download("$repo/config.json", File(dir, "config.json"))
            download("$repo/tokenizer.json", File(dir, "tokenizer.json"))
            download("$repo/voices/af_bella.bin", File(dir, "voices/af_bella.bin"))

            val descriptor = importReal(dir)
            assertEquals("kokoro", descriptor.engineId, "a Kokoro bundle must auto-detect as Kokoro")
            assertTrue(descriptor.voices.isNotEmpty())

            val (samples, durationSeconds, finite) = synthesize(descriptor)
            assertTrue(samples > 0, "synthesize produced no audio")
            assertTrue(durationSeconds > 2.0, "expected > 2s, got ${"%.2f".format(durationSeconds)}s")
            assertTrue(finite, "audio must be finite and bounded")
            println(
                "REAL AUTO-LOAD: engine=${descriptor.engineId} voice=${descriptor.defaultVoiceId} " +
                    "sr=${descriptor.sampleRate} samples=$samples duration=${"%.2f".format(durationSeconds)}s",
            )
        }

    // MeloTTS — a different G2P path entirely (bundled lexicon.txt dictionary, no espeak), and a
    // non-curated export (curated ships en_v2; this uses en_newest). Proves the self-describing
    // tokens.txt/lexicon.txt pipeline works end-to-end on a model we never wired.
    @Test
    fun downloadingARandomMeloModelAutoLoadsAndActuallySpeaks() =
        runTest(timeout = 8.minutes) {
            assumeTrue(System.getProperty("runRealModel") == "true", "opt-in: set -DrunRealModel=true")

            val repo = "https://huggingface.co/MiaoMint/MeloTTS-ONNX/resolve/main/onnx_exports/en_newest"
            val dir = Files.createTempDirectory("random-melo").toFile()
            download("$repo/model.onnx", File(dir, "model.onnx"))
            download("$repo/tokens.txt", File(dir, "tokens.txt"))
            download("$repo/lexicon.txt", File(dir, "lexicon.txt"))
            download("$repo/metadata.json", File(dir, "metadata.json"))

            val descriptor = importReal(dir)
            assertEquals("melotts", descriptor.engineId, "a MeloTTS bundle must auto-detect as MeloTTS")
            assertTrue(descriptor.voices.isNotEmpty())

            val (samples, durationSeconds, finite) = synthesize(descriptor)
            assertTrue(samples > 0, "synthesize produced no audio")
            assertTrue(durationSeconds > 2.0, "expected > 2s, got ${"%.2f".format(durationSeconds)}s")
            assertTrue(finite, "audio must be finite and bounded")
            println(
                "REAL AUTO-LOAD: engine=${descriptor.engineId} voice=${descriptor.defaultVoiceId} " +
                    "sr=${descriptor.sampleRate} samples=$samples duration=${"%.2f".format(durationSeconds)}s",
            )
        }

    // The app's real wiring, shared by the per-engine cases above. Returns the auto-resolved descriptor.
    private fun importReal(dir: File): com.phonetts.core.model.ModelDescriptor {
        val runtimes = RuntimeRegistry().apply { register(JvmOnnxRuntime()) }
        val context = EngineContext(runtimes, EspeakCliPhonemizer(), dir.absolutePath)
        val registry = EngineRegistry().also { EngineLoader.seed(it, context) }
        val resolver =
            Resolver(registry.list(), InMemoryOverrideStore()) {
                error("should have auto-detected the model, not asked the user")
            }
        lastRegistry = registry
        return ModelImporter(DirectoryBundleReader(), resolver, ModelCatalog()).import(dir.absolutePath)
    }

    private var lastRegistry: EngineRegistry? = null

    // Runs the engine's real generation path; returns (sampleCount, durationSeconds, allFiniteBounded).
    private suspend fun synthesize(descriptor: com.phonetts.core.model.ModelDescriptor): Triple<Int, Double, Boolean> {
        val manager = EngineManager(requireNotNull(lastRegistry))
        manager.switchTo(descriptor.engineId, descriptor)
        val engine = requireNotNull(manager.currentEngine) { "engine failed to load" }
        val text = "Text to speech turns written words into natural sounding audio."
        val chunks = engine.synthesize(text, descriptor.defaultVoiceId, 1.0f).toList()
        val samples = chunks.sumOf { it.size }
        val finite = chunks.all { chunk -> chunk.all { !it.isNaN() && abs(it) <= 1.5f } }
        return Triple(samples, samples.toDouble() / descriptor.sampleRate, finite)
    }

    private fun download(
        url: String,
        target: File,
    ) {
        URL(url).openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    }
}
