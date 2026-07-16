package com.phonetts.integration

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.model.Origin
import com.phonetts.core.registry.EngineLoader
import com.phonetts.core.registry.EngineRegistry
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.resolver.InMemoryOverrideStore
import com.phonetts.core.resolver.Resolver
import com.phonetts.core.sideload.DirectoryBundleReader
import com.phonetts.core.sideload.ModelImporter
import com.phonetts.core.testing.FakePhonemizer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3 proven end-to-end against the REAL five engines: a user drops a downloaded model
 * folder in, and it becomes a usable, selectable model with no code changes.
 *
 *  - A folder shaped like a known family is auto-detected by the owning engine and catalogued.
 *  - A folder nothing recognizes falls through to the user-pick fallback and is still catalogued
 *    (first-class), the choice being what completes the descriptor (spec §6.2).
 *
 * The whole app-side wiring is just: DirectoryBundleReader → Resolver(registry) → ModelCatalog.
 */
class AutoLoadIntegrationTest {
    private fun engineContext() = EngineContext(runtimes = RuntimeRegistry(), phonemizer = FakePhonemizer())

    private fun seededRegistry(): EngineRegistry {
        val registry = EngineRegistry()
        EngineLoader.seed(registry, engineContext())
        return registry
    }

    private fun writeFolder(
        name: String,
        files: Map<String, String>,
    ): String {
        val dir = Files.createTempDirectory(name).toFile()
        files.forEach { (fileName, contents) -> File(dir, fileName).writeText(contents) }
        return dir.absolutePath
    }

    @Test
    fun autoDetectsAKnownFamilyFolderAndCatalogsIt() {
        val registry = seededRegistry()
        val catalog = ModelCatalog()
        val resolver = Resolver(registry.list(), InMemoryOverrideStore()) { error("should auto-detect, not ask") }
        val importer = ModelImporter(DirectoryBundleReader(), resolver, catalog)

        // A KittenTTS-shaped bundle: an .onnx plus its config marker and its voices.npz style-
        // embedding table (present by name — inspect() fingerprints it; the rows are decoded at
        // load()). Contents are irrelevant to detection, so an empty placeholder file suffices.
        val folder =
            writeFolder(
                "kitten-download",
                mapOf(
                    "model.onnx" to "",
                    "config.json" to """{"model_type":"kitten_tts"}""",
                    "voices.npz" to "",
                ),
            )

        val descriptor = importer.import(folder)

        assertEquals("kittentts", descriptor.engineId)
        assertTrue(descriptor.voices.isNotEmpty(), "a sideloaded model must be first-class with voices")
        assertEquals(listOf(descriptor.modelId), catalog.list().map { it.modelId })
    }

    @Test
    fun unrecognizedFolderFallsBackToTheUserPickAndIsStillCatalogued() {
        val registry = seededRegistry()
        val catalog = ModelCatalog()
        var asked = false
        val resolver =
            Resolver(registry.list(), InMemoryOverrideStore()) {
                asked = true
                "piper" // the user assigns an engine; its family defaults complete the descriptor
            }
        val importer = ModelImporter(DirectoryBundleReader(), resolver, catalog)

        // A bare .onnx with no companion files — not self-describing, so every inspect() refuses it.
        val folder = writeFolder("mystery-download", mapOf("mystery.onnx" to ""))

        val descriptor = importer.import(folder)

        assertTrue(asked, "no engine should confidently claim a bare .onnx — fail closed to the user pick")
        assertEquals("piper", descriptor.engineId)
        assertEquals(Origin.SIDELOADED, descriptor.origin)
        assertEquals(1, catalog.list().size)
    }
}
