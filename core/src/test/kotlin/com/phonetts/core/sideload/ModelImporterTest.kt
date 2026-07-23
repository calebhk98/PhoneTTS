package com.phonetts.core.sideload

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.resolver.InMemoryOverrideStore
import com.phonetts.core.resolver.Resolver
import com.phonetts.core.testing.FakeEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A [BundleReader] that returns a fixed bundle, so the importer can be tested with no files. */
private class StubReader(private val bundle: ModelBundle) : BundleReader {
    override fun read(location: String): ModelBundle = bundle
}

class ModelImporterTest {
    private val bundle = ModelBundle(id = "dropped", fileNames = setOf("model.onnx"))

    @Test
    fun importedDetectedModelLandsInTheCatalog() {
        val engine = FakeEngine(id = "eng", claims = { it.id == "dropped" })
        val resolver = Resolver(listOf(engine), InMemoryOverrideStore()) { error("should not be asked") }
        val catalog = ModelCatalog()
        val importer = ModelImporter(StubReader(bundle), resolver, catalog)

        val descriptor = importer.import("/anywhere")

        assertEquals("eng", descriptor.engineId)
        assertEquals(listOf(descriptor.modelId), catalog.list().map { it.modelId })
    }

    @Test
    fun importedUnknownModelUsesTheUserPickFallbackThenCatalogs() {
        val rejecting = FakeEngine(id = "eng", claims = { false })
        var asked = false
        val resolver =
            Resolver(listOf(rejecting), InMemoryOverrideStore()) {
                asked = true
                "eng"
            }
        val catalog = ModelCatalog()

        val descriptor = ModelImporter(StubReader(bundle), resolver, catalog).import("/anywhere")

        assertTrue(asked, "the user-pick fallback should run when no engine claims the bundle")
        assertEquals("eng", descriptor.engineId)
        assertEquals(1, catalog.list().size)
    }

    // Issue #8: a bundle no engine claims and the user-pick fallback itself declines (the real
    // shape of "downloaded, no matching engine") must not just vanish - it lands in the catalog's
    // unresolved list so a "manage models" screen can show it honestly instead of pretending
    // nothing was ever downloaded.
    @Test
    fun unidentifiableBundleIsRecordedAsUnresolvedRatherThanSilentlyDropped() {
        val rejecting = FakeEngine(id = "eng", claims = { false })
        val resolver =
            Resolver(listOf(rejecting), InMemoryOverrideStore()) {
                throw IllegalStateException("could not identify model 'dropped'")
            }
        val catalog = ModelCatalog()
        val importer = ModelImporter(StubReader(bundle), resolver, catalog)

        val thrown = kotlin.runCatching { importer.import("/anywhere") }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException, "the failure must still propagate to the caller")
        assertEquals(emptyList(), catalog.list())
        assertEquals(listOf("dropped"), catalog.listUnresolved().map { it.bundleId })
    }

    // Bug #1: the manual "pick an engine" fallback must actually land the bundle in the catalog and
    // clear its unresolved marker - this is the real, end-to-end fix, not just Resolver in isolation.
    @Test
    fun importWithChosenEngineResolvesViaForcedMatchAndClearsTheUnresolvedMarker() {
        val rejecting = FakeEngine(id = "eng-a", claims = { false })
        val chosen = FakeEngine(id = "eng-b", claims = { false })
        val resolver =
            Resolver(listOf(rejecting, chosen), InMemoryOverrideStore()) {
                error("should not consult the auto fallback for a manual pick")
            }
        val catalog = ModelCatalog().apply { markUnresolved("dropped", "no engine recognized it") }
        val importer = ModelImporter(StubReader(bundle), resolver, catalog)

        val descriptor = importer.importWithChosenEngine("/anywhere", "eng-b")

        assertEquals("eng-b", descriptor.engineId)
        assertEquals(listOf(descriptor.modelId), catalog.list().map { it.modelId })
        assertEquals(emptyList(), catalog.listUnresolved())
    }

    @Test
    fun importWithChosenEngineLeavesTheBundleUnresolvedWhenForcedMatchRejectsIt() {
        val rejecting = FakeEngine(id = "eng-a", forcedMatchError = IllegalStateException("no weights file found"))
        val resolver =
            Resolver(listOf(rejecting), InMemoryOverrideStore()) { error("not exercised by this test") }
        val catalog = ModelCatalog().apply { markUnresolved("dropped", "no engine recognized it") }
        val importer = ModelImporter(StubReader(bundle), resolver, catalog)

        val thrown = assertFailsWith<IllegalStateException> { importer.importWithChosenEngine("/anywhere", "eng-a") }

        assertEquals("no weights file found", thrown.message)
        assertEquals(emptyList(), catalog.list())
        assertEquals(listOf("dropped"), catalog.listUnresolved().map { it.bundleId })
    }
}
