package com.phonetts.app.session

import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.prefs.InMemoryPreferenceStore
import com.phonetts.core.prefs.ResourceUsageStore
import com.phonetts.core.registry.ManagedEngineFilter
import com.phonetts.core.registry.ManagedSortKey
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.registry.ModelManager
import com.phonetts.core.session.SessionStateStore
import com.phonetts.core.store.InMemoryDurableStore
import com.phonetts.app.manage.ModelManagementViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #131: [SessionStateStore.manage]/`.updateManage` existed and were fully unit-tested at the
 * `:core` layer ([com.phonetts.core.session.SessionStateStoreTest]), but nothing in `:app` ever
 * called them - the Downloaded-models screen's sort/filter reset on every relaunch exactly as the
 * device report described ("Set a sort on Downloaded models, leave, come back - FAILS"). These
 * tests exercise the fix through [ModelManagementViewModel] itself, not the store directly, so a
 * regression in the WIRING (not just the store) would be caught here.
 */
class ModelManagementSessionWiringTest {
    private fun catalogWithOneModel(engineId: String = "kokoro"): ModelCatalog {
        val catalog = ModelCatalog()
        catalog.add(
            ModelDescriptor(
                modelId = "test-model",
                engineId = engineId,
                displayName = "Test Model",
                origin = Origin.BUILT_IN,
                sampleRate = 22_050,
                voices = listOf(Voice("v0", "Voice 0", "en")),
                defaultVoiceId = "v0",
            ),
        )
        return catalog
    }

    private fun newViewModel(
        sessionState: SessionStateStore?,
        catalog: ModelCatalog = catalogWithOneModel(),
    ): ModelManagementViewModel =
        ModelManagementViewModel(
            modelManager = ModelManager(catalog = catalog, dirSizeBytes = { 0L }, deleteModelDir = { true }),
            resourceUsage = ResourceUsageStore(InMemoryPreferenceStore()),
            availableRamBytes = { 0L },
            sessionState = sessionState,
        )

    @Test
    fun `sort, query, and origin-engine-size filters survive a relaunch`() {
        val backing = InMemoryDurableStore()
        val catalog = catalogWithOneModel(engineId = "kokoro")

        val firstLaunch = newViewModel(SessionStateStore(backing), catalog)
        firstLaunch.setSortKey(ManagedSortKey.SIZE)
        firstLaunch.toggleSortDirection() // NAME/true default -> SIZE/false
        firstLaunch.setQuery("kokoro")
        firstLaunch.setOriginFilter(Origin.BUILT_IN)
        firstLaunch.setEngineFilter(ManagedEngineFilter.Specific("kokoro"))
        firstLaunch.setSizeFilter(minSizeBytes = 10L, maxSizeBytes = 1_000L)

        // A brand-new instance over the SAME backing document (not the same object) - modelling a
        // real process death exactly like SessionStateStoreTest's relaunch() does.
        val relaunched = newViewModel(SessionStateStore(backing), catalog)
        val state = relaunched.state.value

        assertEquals(ManagedSortKey.SIZE, state.sort.key)
        assertFalse(state.sort.ascending)
        assertEquals("kokoro", state.query)
        assertEquals(Origin.BUILT_IN, state.originFilter)
        assertEquals(ManagedEngineFilter.Specific("kokoro"), state.engineFilter)
        assertEquals(10L, state.minSizeBytes)
        assertEquals(1_000L, state.maxSizeBytes)
    }

    @Test
    fun `a null sessionState (no call site wired yet) behaves exactly like before this feature`() {
        val viewModel = newViewModel(sessionState = null)
        val state = viewModel.state.value

        assertEquals(ManagedSortKey.NAME, state.sort.key)
        assertTrue(state.sort.ascending)
        assertEquals("", state.query)
        assertNull(state.originFilter)
        assertEquals(ManagedEngineFilter.All, state.engineFilter)

        // Setting a filter must not crash just because there is nowhere to persist it to.
        viewModel.setQuery("anything")
        assertEquals("anything", viewModel.state.value.query)
    }

    @Test
    fun `a renamed-away sort key falls back to the default rather than crashing`() {
        val backing = InMemoryDurableStore()
        SessionStateStore(backing).updateManage { it.copy(sortKey = "SOME_SORT_KEY_THAT_NO_LONGER_EXISTS") }

        val restored = newViewModel(SessionStateStore(backing))

        assertEquals(
            "an unrecognised persisted sort key must fall back to the default, never crash construction",
            ManagedSortKey.NAME,
            restored.state.value.sort.key,
        )
    }

    @Test
    fun `an origin filter this build no longer recognises is dropped, not crashed on`() {
        val backing = InMemoryDurableStore()
        SessionStateStore(backing).updateManage { it.copy(filters = mapOf("origin" to "GARBAGE_ORIGIN")) }

        val restored = newViewModel(SessionStateStore(backing))

        assertNull(restored.state.value.originFilter)
    }

    /**
     * The scenario CLAUDE.md's own review flagged as the dangerous one: a persisted engine filter
     * naming an engine that has since been uninstalled must not leave the Downloaded-models screen
     * silently filtered down to zero rows with no visible way out. It must fall back to "All".
     */
    @Test
    fun `an engine filter naming an uninstalled engine falls back to All instead of filtering to nothing`() {
        val backing = InMemoryDurableStore()
        SessionStateStore(backing).updateManage {
            it.copy(filters = mapOf("engine" to "specific:some-engine-that-was-uninstalled"))
        }
        // The catalog on THIS relaunch only has "kokoro" - the persisted engine id is gone.
        val catalog = catalogWithOneModel(engineId = "kokoro")

        val restored = newViewModel(SessionStateStore(backing), catalog)

        assertEquals(ManagedEngineFilter.All, restored.state.value.engineFilter)
        // And the model list is NOT stuck empty - the whole point of failing closed here.
        val visible = ModelManagementViewModel.visibleEntries(restored.state.value)
        assertTrue("the catalog's one model must still be visible, not hidden by a stale filter", visible.isNotEmpty())
    }

    @Test
    fun `a genuinely still-installed engine filter DOES survive a relaunch`() {
        val backing = InMemoryDurableStore()
        val catalog = catalogWithOneModel(engineId = "kokoro")
        newViewModel(SessionStateStore(backing), catalog).setEngineFilter(ManagedEngineFilter.Specific("kokoro"))

        val restored = newViewModel(SessionStateStore(backing), catalog)

        assertEquals(ManagedEngineFilter.Specific("kokoro"), restored.state.value.engineFilter)
    }

    @Test
    fun `an unparseable size bound is dropped rather than crashing`() {
        val backing = InMemoryDurableStore()
        SessionStateStore(backing).updateManage {
            it.copy(filters = mapOf("minSize" to "not-a-number", "maxSize" to "also-not-a-number"))
        }

        val restored = newViewModel(SessionStateStore(backing))

        assertNull(restored.state.value.minSizeBytes)
        assertNull(restored.state.value.maxSizeBytes)
    }

    @Test
    fun `clearAdvancedFilters persists the clear, not just local state`() {
        val backing = InMemoryDurableStore()
        val catalog = catalogWithOneModel(engineId = "kokoro")
        val viewModel = newViewModel(SessionStateStore(backing), catalog)
        viewModel.setOriginFilter(Origin.BUILT_IN)
        viewModel.setEngineFilter(ManagedEngineFilter.Specific("kokoro"))
        viewModel.setSizeFilter(1L, 2L)

        viewModel.clearAdvancedFilters()

        val restored = newViewModel(SessionStateStore(backing), catalog)
        assertNull(restored.state.value.originFilter)
        assertEquals(ManagedEngineFilter.All, restored.state.value.engineFilter)
        assertNull(restored.state.value.minSizeBytes)
        assertNull(restored.state.value.maxSizeBytes)
    }
}
