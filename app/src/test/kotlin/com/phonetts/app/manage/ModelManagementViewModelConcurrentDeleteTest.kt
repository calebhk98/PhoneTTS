package com.phonetts.app.manage

import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.prefs.InMemoryPreferenceStore
import com.phonetts.core.prefs.ResourceUsageStore
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.registry.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression test for the single-slot progress-state bug: [ModelManagementViewModel] used to track
 * an in-flight delete as one nullable `deletingId`/`deletingUnresolvedId`/`assigningUnresolvedId`
 * slot, so deleting two models in quick succession meant the second delete overwrote the first's id,
 * and whichever finished first cleared the spinner for BOTH rows - including the one still mid-delete.
 * [UiState.deletingIds] is now a `Set<String>` (mirroring
 * [com.phonetts.app.hf.HfBrowseViewModel]'s `queuedDownloadIds`), so this test starts two deletes,
 * lets the FASTER one finish first, and asserts the SLOWER one's row still shows progress
 * independently the whole time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagementViewModelConcurrentDeleteTest {
    @Before
    fun setUp() {
        // viewModelScope.launch needs a Main dispatcher; Unconfined runs each launch's synchronous
        // portion eagerly, matching the pattern the existing HfBrowseViewModel regression tests use.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun descriptor(modelId: String): ModelDescriptor =
        ModelDescriptor(
            modelId = modelId,
            engineId = "fake-engine",
            displayName = "Model $modelId",
            origin = Origin.BUILT_IN,
            sampleRate = 22_050,
            voices = listOf(Voice("v0", "Voice 0", "en")),
            defaultVoiceId = "v0",
        )

    @Test
    fun `deleting two models in quick succession shows progress independently for both rows`() {
        val slowModelId = "slow-model"
        val fastModelId = "fast-model"

        val catalog = ModelCatalog()
        catalog.add(descriptor(slowModelId))
        catalog.add(descriptor(fastModelId))

        // deleteModelDir blocks for the SLOW model until the test releases it; the fast one returns
        // immediately. Real blocking (not virtual time) because delete() hops onto Dispatchers.IO -
        // a real thread pool - to run this off Main, exactly like the code under test.
        val releaseSlowDelete = CountDownLatch(1)
        val started = ConcurrentHashMap<String, Boolean>()
        val modelManager =
            ModelManager(
                catalog = catalog,
                dirSizeBytes = { 0L },
                deleteModelDir = { modelId ->
                    started[modelId] = true
                    if (modelId == slowModelId) releaseSlowDelete.await(5, TimeUnit.SECONDS)
                    true
                },
            )

        val viewModel =
            ModelManagementViewModel(
                modelManager = modelManager,
                resourceUsage = ResourceUsageStore(InMemoryPreferenceStore()),
                availableRamBytes = { 0L },
            )

        viewModel.delete(slowModelId)
        viewModel.delete(fastModelId)

        // Both deletes are in flight: the slow one is parked inside deleteModelDir, the fast one has
        // already completed (Unconfined + a same-thread blocking call would deadlock if fast() ran on
        // the same dispatcher; it runs on the real Dispatchers.IO instead, so give it a moment).
        awaitUntil(5_000) { started.containsKey(slowModelId) }
        awaitUntil(5_000) { fastModelId !in viewModel.state.value.deletingIds }

        // The bug this guards against: the fast delete finishing must NOT clear the slow row's
        // spinner too - a single nullable slot would have wiped both.
        assertTrue(
            "slow model's row must still show progress while its delete is still running",
            slowModelId in viewModel.state.value.deletingIds,
        )
        assertTrue(fastModelId !in viewModel.state.value.deletingIds)

        releaseSlowDelete.countDown()
        awaitUntil(5_000) { slowModelId !in viewModel.state.value.deletingIds }
        assertEquals(emptySet<String>(), viewModel.state.value.deletingIds)
    }

    private fun awaitUntil(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for condition")
    }
}
