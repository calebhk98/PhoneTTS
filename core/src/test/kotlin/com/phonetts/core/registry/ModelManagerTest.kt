package com.phonetts.core.registry

import com.phonetts.core.prefs.InMemoryPreferenceStore
import com.phonetts.core.prefs.StorageLocationPreference
import com.phonetts.core.resolver.OverrideStore
import com.phonetts.core.resolver.SelectableEngine
import com.phonetts.core.testing.FakeEngine
import com.phonetts.core.testing.testDescriptor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [ClearableOverrideStore] double so tests can assert a saved override was actually dropped. */
private class FakeClearableOverrideStore : ClearableOverrideStore {
    private val overrides = mutableMapOf<String, String>()

    override fun get(bundleId: String): String? = overrides[bundleId]

    override fun put(
        bundleId: String,
        engineId: String,
    ) {
        overrides[bundleId] = engineId
    }

    override fun clear(bundleId: String) {
        overrides.remove(bundleId)
    }
}

/** A plain, non-clearable [OverrideStore] double - exercises the "not ClearableOverrideStore" path. */
private class PlainOverrideStore : OverrideStore {
    private val overrides = mutableMapOf<String, String>()

    override fun get(bundleId: String): String? = overrides[bundleId]

    override fun put(
        bundleId: String,
        engineId: String,
    ) {
        overrides[bundleId] = engineId
    }
}

class ModelManagerTest {
    @Test
    fun usageReportsTheInjectedSizeForEveryCatalogedModel() {
        val catalog =
            ModelCatalog().apply {
                add(testDescriptor("m1", "eng"))
                add(testDescriptor("m2", "eng"))
            }
        val sizes = mapOf("m1" to 100L, "m2" to 250L)
        val manager = ModelManager(catalog, dirSizeBytes = { sizes.getValue(it) }, deleteModelDir = { true })

        val usage = manager.usage()

        assertEquals(setOf("m1" to 100L, "m2" to 250L), usage.map { it.descriptor.modelId to it.sizeBytes }.toSet())
        assertEquals(350L, manager.totalBytes())
    }

    // Bug #7: a downloaded-but-unresolved bundle (issue #8) still occupies real disk space, so the
    // "storage used" total must count it too, not just identified models - otherwise a screen full
    // of unresolved downloads (bug #6) reads as "0 B used" even though every byte is really there.
    @Test
    fun totalBytesIncludesUnresolvedBundlesAlongsideIdentifiedModels() {
        val catalog =
            ModelCatalog().apply {
                add(testDescriptor("m1", "eng"))
                markUnresolved("mystery", "no engine claimed it")
            }
        val sizes = mapOf("m1" to 100L, "mystery" to 42L)
        val manager = ModelManager(catalog, dirSizeBytes = { sizes.getValue(it) }, deleteModelDir = { true })

        assertEquals(142L, manager.totalBytes())
    }

    @Test
    fun removeDropsTheModelFromTheCatalogAndDeletesItsFiles() {
        val catalog = ModelCatalog().apply { add(testDescriptor("m1", "eng")) }
        var deleteCalledWith: String? = null
        val manager =
            ModelManager(
                catalog,
                dirSizeBytes = { 0L },
                deleteModelDir = { id ->
                    deleteCalledWith = id
                    true
                },
            )

        val result = manager.remove("m1")

        assertTrue(result.removedFromCatalog)
        assertTrue(result.filesDeleted)
        assertFalse(result.engineUnloaded)
        assertEquals("m1", deleteCalledWith)
        assertNull(catalog.get("m1"))
    }

    @Test
    fun removeReportsWhateverTheInjectedDeleteReturns() {
        val catalog = ModelCatalog().apply { add(testDescriptor("m1", "eng")) }
        val manager = ModelManager(catalog, dirSizeBytes = { 0L }, deleteModelDir = { false })

        val result = manager.remove("m1")

        assertFalse(result.filesDeleted)
        // Catalog + override cleanup still happen even if there was nothing on disk to delete.
        assertNull(catalog.get("m1"))
    }

    @Test
    fun removeOfAnUncatalogedModelIsANoOpThatTouchesNothing() {
        val catalog = ModelCatalog()
        var deleteCalled = false
        val manager =
            ModelManager(
                catalog,
                dirSizeBytes = { 0L },
                deleteModelDir = {
                    deleteCalled = true
                    true
                },
            )

        val result = manager.remove("ghost")

        assertFalse(result.removedFromCatalog)
        assertFalse(result.filesDeleted)
        assertFalse(result.engineUnloaded)
        assertFalse(deleteCalled)
    }

    @Test
    fun removeClearsTheSavedOverrideWhenTheStoreSupportsIt() {
        val catalog = ModelCatalog().apply { add(testDescriptor("m1", "eng")) }
        val overrideStore = FakeClearableOverrideStore().apply { put("m1", "eng") }
        val manager =
            ModelManager(catalog, dirSizeBytes = { 0L }, deleteModelDir = { true }, overrideStore = overrideStore)

        manager.remove("m1")

        assertNull(overrideStore.get("m1"))
    }

    @Test
    fun removeToleratesAnOverrideStoreThatCannotBeCleared() {
        val catalog = ModelCatalog().apply { add(testDescriptor("m1", "eng")) }
        val overrideStore = PlainOverrideStore().apply { put("m1", "eng") }
        val manager =
            ModelManager(catalog, dirSizeBytes = { 0L }, deleteModelDir = { true }, overrideStore = overrideStore)

        // No exception, and - since this store can't drop entries - the stale mapping stays;
        // that's the documented, safe degradation for a non-Clearable OverrideStore.
        val result = manager.remove("m1")

        assertTrue(result.removedFromCatalog)
        assertEquals("eng", overrideStore.get("m1"))
    }

    @Test
    fun removeUnloadsTheEngineWhenTheModelIsCurrentlyLoaded() =
        runTest {
            val catalog = ModelCatalog().apply { add(testDescriptor("m1", "a")) }
            val engineA = FakeEngine(id = "a")
            val registry =
                EngineRegistry().apply {
                    register(engineA)
                }
            val engineManager = EngineManager(registry)
            engineManager.switchTo("a", testDescriptor("m1", "a"))

            val manager =
                ModelManager(catalog, dirSizeBytes = { 0L }, deleteModelDir = { true }, engineManager = engineManager)

            val result = manager.remove("m1")

            assertTrue(result.engineUnloaded)
            assertEquals(1, engineA.unloadCount)
        }

    @Test
    fun removeLeavesADifferentlyLoadedEngineAlone() =
        runTest {
            val catalog =
                ModelCatalog().apply {
                    add(testDescriptor("m1", "a"))
                    add(testDescriptor("m2", "b"))
                }
            val engineA = FakeEngine(id = "a")
            val engineB = FakeEngine(id = "b")
            val registry =
                EngineRegistry().apply {
                    register(engineA)
                    register(engineB)
                }
            val engineManager = EngineManager(registry)
            engineManager.switchTo("b", testDescriptor("m2", "b"))

            val manager =
                ModelManager(catalog, dirSizeBytes = { 0L }, deleteModelDir = { true }, engineManager = engineManager)

            val result = manager.remove("m1")

            assertFalse(result.engineUnloaded)
            assertEquals(0, engineB.unloadCount)
        }

    // Issue #8: an unidentified bundle must show up somewhere honest, not just disappear.
    @Test
    fun unresolvedUsageReportsUnclaimedBundlesWithTheirSize() {
        val catalog = ModelCatalog().apply { markUnresolved("mystery", "no engine claimed it") }
        val manager = ModelManager(catalog, dirSizeBytes = { 42L }, deleteModelDir = { true })

        val usage = manager.unresolvedUsage()

        assertEquals(listOf(UnresolvedModelUsage("mystery", 42L, "no engine claimed it")), usage)
    }

    @Test
    fun removeUnresolvedDeletesFilesAndForgetsTheMarker() {
        val catalog = ModelCatalog().apply { markUnresolved("mystery", "no engine claimed it") }
        var deleteCalledWith: String? = null
        val manager =
            ModelManager(
                catalog,
                dirSizeBytes = { 0L },
                deleteModelDir = { id ->
                    deleteCalledWith = id
                    true
                },
            )

        val filesDeleted = manager.removeUnresolved("mystery")

        assertTrue(filesDeleted)
        assertEquals("mystery", deleteCalledWith)
        assertEquals(emptyList(), manager.unresolvedUsage())
    }

    // Bug #1: the manual "pick an engine" fallback must be reachable through ModelManager, the class
    // the Manage screen's ViewModel actually talks to - not just live in Resolver in isolation.
    @Test
    fun selectableEnginesReturnsWhateverTheInjectedProviderReports() {
        val engines = listOf(SelectableEngine("eng-a", "Engine A"), SelectableEngine("eng-b", "Engine B"))
        val manager =
            ModelManager(
                ModelCatalog(),
                dirSizeBytes = { 0L },
                deleteModelDir = { true },
                selectableEnginesProvider = { engines },
            )

        assertEquals(engines, manager.selectableEngines())
    }

    @Test
    fun selectableEnginesDefaultsToEmptyWhenNoProviderIsWired() {
        val manager = ModelManager(ModelCatalog(), dirSizeBytes = { 0L }, deleteModelDir = { true })

        assertEquals(emptyList(), manager.selectableEngines())
    }

    @Test
    fun assignEngineDelegatesToTheInjectedActionWithBothIds() {
        var seenBundleId: String? = null
        var seenEngineId: String? = null
        val descriptor = testDescriptor("mystery", "eng-b")
        val manager =
            ModelManager(
                ModelCatalog(),
                dirSizeBytes = { 0L },
                deleteModelDir = { true },
                assignEngineAction = { bundleId, engineId ->
                    seenBundleId = bundleId
                    seenEngineId = engineId
                    descriptor
                },
            )

        val result = manager.assignEngine("mystery", "eng-b")

        assertEquals("mystery", seenBundleId)
        assertEquals("eng-b", seenEngineId)
        assertEquals(descriptor, result)
    }

    @Test
    fun assignEngineFailsClosedWhenNoActionIsWired() {
        val manager = ModelManager(ModelCatalog(), dirSizeBytes = { 0L }, deleteModelDir = { true })

        assertFailsWith<IllegalStateException> { manager.assignEngine("mystery", "eng-b") }
    }

    @Test
    fun assignEngineLetsTheInjectedActionsFailurePropagate() {
        val manager =
            ModelManager(
                ModelCatalog(),
                dirSizeBytes = { 0L },
                deleteModelDir = { true },
                assignEngineAction = { _, _ -> error("engine rejected this bundle: missing config.json") },
            )

        val thrown = assertFailsWith<IllegalStateException> { manager.assignEngine("mystery", "eng-b") }
        assertEquals("engine rejected this bundle: missing config.json", thrown.message)
    }

    // Issue #4/#5: switching storage location persists the choice and runs the app's rebuild hook.
    @Test
    fun currentStorageDescriptionReportsTheAppPrivateDefaultWhenNoneIsSet() {
        val manager = ModelManager(ModelCatalog(), dirSizeBytes = { 0L }, deleteModelDir = { true })
        assertTrue(manager.currentStorageDescription().contains("default"))
    }

    @Test
    fun changeStorageLocationPersistsThePathAndInvokesTheCallback() {
        val storageLocation = StorageLocationPreference(InMemoryPreferenceStore())
        var callbackRuns = 0
        val manager =
            ModelManager(
                ModelCatalog(),
                dirSizeBytes = { 0L },
                deleteModelDir = { true },
                storageLocation = storageLocation,
                onStorageLocationChanged = { _, _ ->
                    callbackRuns++
                    null
                },
            )

        manager.changeStorageLocation("/storage/1234-5678/PhoneTTS/models")

        assertEquals("/storage/1234-5678/PhoneTTS/models", storageLocation.customBasePath())
        assertEquals("/storage/1234-5678/PhoneTTS/models", manager.currentStorageDescription())
        assertEquals(1, callbackRuns)
    }

    @Test
    fun changeStorageLocationToNullRevertsToTheDefault() {
        val storageLocation = StorageLocationPreference(InMemoryPreferenceStore()).apply { setCustomBasePath("/x") }
        val manager =
            ModelManager(
                ModelCatalog(),
                dirSizeBytes = { 0L },
                deleteModelDir = { true },
                storageLocation = storageLocation,
            )

        manager.changeStorageLocation(null)

        assertNull(storageLocation.customBasePath())
    }

    // Rule 4: the callback must see the OLD path exactly as it was before this call overwrote it,
    // paired with the new one - the app layer needs both to know what to migrate from/to.
    @Test
    fun changeStorageLocationPassesThePreviousAndNextPathToTheCallback() {
        val storageLocation =
            StorageLocationPreference(InMemoryPreferenceStore()).apply { setCustomBasePath("/old/path") }
        var seenPrevious: String? = "not called"
        var seenNext: String? = "not called"
        val manager =
            ModelManager(
                ModelCatalog(),
                dirSizeBytes = { 0L },
                deleteModelDir = { true },
                storageLocation = storageLocation,
                onStorageLocationChanged = { previous, next ->
                    seenPrevious = previous
                    seenNext = next
                    null
                },
            )

        manager.changeStorageLocation("/new/path")

        assertEquals("/old/path", seenPrevious)
        assertEquals("/new/path", seenNext)
    }

    // The callback's return value (e.g. a migration warning) must reach the caller so the UI can
    // show it, instead of only ever seeing the generic "folder picked" message.
    @Test
    fun changeStorageLocationReturnsWhateverMessageTheCallbackReports() {
        val storageLocation = StorageLocationPreference(InMemoryPreferenceStore())
        val manager =
            ModelManager(
                ModelCatalog(),
                dirSizeBytes = { 0L },
                deleteModelDir = { true },
                storageLocation = storageLocation,
                onStorageLocationChanged = { _, _ -> "some models could not be moved" },
            )

        val message = manager.changeStorageLocation("/new/path")

        assertEquals("some models could not be moved", message)
    }

    @Test
    fun changeStorageLocationReturnsNullWhenNoCallbackIsWired() {
        val manager = ModelManager(ModelCatalog(), dirSizeBytes = { 0L }, deleteModelDir = { true })

        assertNull(manager.changeStorageLocation("/new/path"))
    }
}
