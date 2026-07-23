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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Answers "can a user download a RANDOM CosyVoice model from Hugging Face and it just works?" by
 * driving PhoneTTS's OWN code - real [DirectoryBundleReader] -> [Resolver] -> `CosyVoice2Engine`'s
 * real `inspect()` -> [ModelImporter] -> [EngineManager] -> real `synthesize()` - never a
 * re-implementation. The only swap is the platform backend: [JvmNativeCosyVoiceRuntime] binds the
 * SAME `cosyvoice3_tts` C ABI the app's `NativeCosyVoiceRuntime` does, on the desktop JDK.
 *
 * The honest finding this encodes: unlike Piper (any `.onnx`+`.json` voice works), CosyVoice
 * packaging on the Hub is FRAGMENTED and mutually incompatible - the four-file split GGUF
 * (`cstr/cosyvoice3-0.5b-2512-GGUF`: `cosyvoice3-{llm,flow,hift,voices}-*.gguf`) is the one our
 * runtime handles; a single-combined-GGUF repo (e.g. `Lourdle/Fun-CosyVoice3-0.5B-2512-GGUF`:
 * `CosyVoice3-2512_Q4_K_M.gguf` + `frontend-onnx/`) is a DIFFERENT format our `inspect()` correctly
 * refuses (fail-closed, spec rule 4). So "any random cosy model just works" is FALSE, and that
 * refusal is the feature.
 */
class RealCosyVoiceAutoLoadTest {
    /**
     * PURE JVM, no weights/native needed - runs in the normal suite. A "random" CosyVoice model in
     * the WRONG (single-combined-GGUF) packaging must NOT auto-load: the real resolver falls through
     * to the user-pick path rather than guess. This is the fail-closed guarantee, proven with our code.
     */
    @Test
    fun aRandomlyPackagedCosyVoiceModelIsNotSilentlyGuessed() {
        val dir = Files.createTempDirectory("random-combined-cosy").toFile()
        // The Lourdle repo layout: one combined GGUF + an onnx frontend dir - NOT our 4-file split.
        File(dir, "CosyVoice3-2512_Q4_K_M.gguf").writeBytes(byteArrayOf(0))
        File(dir, "frontend-onnx").mkdirs()
        File(dir, "frontend-onnx/speech_tokenizer.onnx").writeBytes(byteArrayOf(0))

        val registry = cosyRegistry(dir)
        val resolver =
            Resolver(registry.list(), InMemoryOverrideStore()) {
                error("USER_PICK_REQUIRED") // fail-closed: no engine confidently claimed this bundle
            }
        val importer = ModelImporter(DirectoryBundleReader(), resolver, ModelCatalog())

        val failure = assertFailsWith<IllegalStateException> { importer.import(dir.absolutePath) }
        assertTrue(
            failure.message!!.contains("USER_PICK_REQUIRED"),
            "a mis-packaged CosyVoice model must fall to the user-pick path, not be silently guessed",
        )
    }

    /**
     * OPT-IN full proof: download the CORRECT (cstr four-file split) GGUF stack and drive it through
     * our real pipeline to real audio. Requires the desktop native lib (`-Dcosyvoice.nativeLib=...`,
     * built by scripts/model-verify/build_jvm_cosyvoice.sh) and `-DrunRealModel=true`; skips cleanly
     * otherwise. A pre-downloaded stack can be supplied via `-Dcosyvoice.modelDir=...` to avoid the
     * 745 MB fetch.
     */
    @Test
    fun downloadingTheCstrCosyStackAutoLoadsAndActuallySpeaks() =
        runTest(timeout = 15.minutes) {
            assumeTrue(System.getProperty("runRealModel") == "true", "opt-in: set -DrunRealModel=true")
            assumeTrue(JvmCosyVoiceNative.isLibraryLoaded, "needs -Dcosyvoice.nativeLib=<libjvmcosyvoice.so>")

            val dir = obtainCstrStack()

            // THE CLICK - import the folder through the app's real resolver/importer chain.
            val registry = cosyRegistry(dir)
            val resolver =
                Resolver(registry.list(), InMemoryOverrideStore()) {
                    error("should have auto-detected the CosyVoice GGUF stack, not asked the user")
                }
            val catalog = ModelCatalog()
            val descriptor = ModelImporter(DirectoryBundleReader(), resolver, catalog).import(dir.absolutePath)

            assertEquals("cosyvoice2", descriptor.engineId, "the 4-file GGUF stack must auto-detect as CosyVoice")
            assertEquals(listOf(descriptor.modelId), catalog.list().map { it.modelId }, "must appear in the catalog")

            // THE USER PLAYS IT - select + synthesize through the engine's real generation path. The
            // engine reads its voice list from the loaded model (SSOT); use whatever it exposes.
            val manager = EngineManager(registry)
            manager.switchTo(descriptor.engineId, descriptor)
            val engine = requireNotNull(manager.currentEngine) { "engine failed to load" }
            val voiceId = engine.voices().firstOrNull { it.id == "fleurs-en" }?.id ?: engine.voices().first().id

            val text = "Text to speech turns written words into natural sounding audio."
            val chunks = engine.synthesize(text, voiceId, 1.0f).toList()

            val samples = chunks.sumOf { it.size }
            val durationSeconds = samples.toDouble() / descriptor.sampleRate
            assertTrue(samples > 0, "synthesize produced no audio")
            assertTrue(durationSeconds > 2.0, "expected > 2s of audio, got ${"%.2f".format(durationSeconds)}s")
            assertTrue(
                chunks.all { chunk -> chunk.all { !it.isNaN() && abs(it) <= 1.5f } },
                "audio must be finite and bounded",
            )
            println(
                "REAL COSY AUTO-LOAD: engine=${descriptor.engineId} voices=${engine.voices().map { it.id }} " +
                    "voice=$voiceId sr=${descriptor.sampleRate} samples=$samples " +
                    "duration=${"%.2f".format(durationSeconds)}s",
            )
        }

    // The app's real engine wiring, with the CosyVoice native runtime's desktop twin registered
    // alongside the ONNX twin (so every engine can still resolve). Directory is the bundle root.
    private fun cosyRegistry(dir: File): EngineRegistry {
        val runtimes =
            RuntimeRegistry().apply {
                register(JvmOnnxRuntime())
                register(JvmNativeCosyVoiceRuntime())
            }
        val context = EngineContext(runtimes, EspeakCliPhonemizer())
        return EngineRegistry().also { EngineLoader.seed(it, context) }
    }

    // Use a pre-downloaded stack if given; else fetch the minimal 745 MB combo from the cstr repo.
    private fun obtainCstrStack(): File {
        System.getProperty("cosyvoice.modelDir")?.let { return File(it) }
        val dir = Files.createTempDirectory("cstr-cosy").toFile()
        val base = "https://huggingface.co/cstr/cosyvoice3-0.5b-2512-GGUF/resolve/main"
        listOf(
            "cosyvoice3-llm-q4_k.gguf",
            "cosyvoice3-flow-q8_0.gguf",
            "cosyvoice3-hift-f16.gguf",
            "cosyvoice3-voices.gguf",
        ).forEach { name -> download("$base/$name", File(dir, name)) }
        return dir
    }

    private fun download(
        url: String,
        target: File,
    ) {
        URL(url).openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    }
}
