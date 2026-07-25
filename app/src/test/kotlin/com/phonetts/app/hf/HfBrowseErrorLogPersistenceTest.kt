package com.phonetts.app.hf

import com.phonetts.app.testing.FakeHttpClient
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.resolver.InMemoryOverrideStore
import com.phonetts.core.resolver.Resolver
import com.phonetts.core.sideload.DirectoryBundleReader
import com.phonetts.core.sideload.ModelImporter
import com.phonetts.core.store.DurableErrorLog
import com.phonetts.core.store.InMemoryDurableStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Issue #88: a search/size/download error must survive a restart, not just live in the in-memory
 * `HfBrowseUiState.errorLog` (issue #3's session log, which dies with the process). [HfBrowseViewModel.logError]
 * is the one funnel every one of those failures goes through (see its kdoc), so this exercises it via
 * a failing [search] and checks the durable mirror - reusing the SAME [DurableErrorLog] `:core` seam
 * [com.phonetts.app.AppGraph.durableErrorLog] already persists crash rows through, rather than a new
 * storage mechanism.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HfBrowseErrorLogPersistenceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(durableErrorLog: DurableErrorLog? = null): HfBrowseViewModel {
        val modelCatalog = ModelCatalog()
        val importer =
            ModelImporter(
                DirectoryBundleReader(),
                Resolver(engines = emptyList(), overrideStore = InMemoryOverrideStore()) { bundle -> bundle.id },
                modelCatalog,
            )
        val downloader = HfDownloader(tmp.newFolder("models-${System.nanoTime()}"), importer)
        val failingHttp = FakeHttpClient { throw IOException("simulated offline search failure") }
        val hfCatalog = HfCatalog(failingHttp)
        return HfBrowseViewModel(
            catalog = hfCatalog,
            downloader = downloader,
            modelCatalog = modelCatalog,
            durableErrorLog = durableErrorLog,
        )
    }

    private fun awaitState(
        viewModel: HfBrowseViewModel,
        timeoutMs: Long = 5_000,
        predicate: (HfBrowseUiState) -> Boolean,
    ): HfBrowseUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = viewModel.state.value
        while (System.currentTimeMillis() < deadline) {
            last = viewModel.state.value
            if (predicate(last)) return last
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for HfBrowseViewModel state; last state: $last")
    }

    @Test
    fun `a search failure is mirrored into the durable error log when one is wired`() {
        val durableErrorLog = DurableErrorLog(InMemoryDurableStore())
        // HfBrowseViewModel's own init block already runs one search() as soon as it's constructed
        // (see its `init { runCatching { search() } ... }`) - the failing HTTP client means THAT
        // first attempt is the one that fails, so this test doesn't call search() again itself
        // (which would double the count: two failures logged where the assertion below expects one).
        val viewModel = newViewModel(durableErrorLog)

        val state = awaitState(viewModel) { !it.loading }

        assertTrue("the session error banner must still show (issue #3, unchanged)", state.error != null)
        val entries = durableErrorLog.entries()
        assertEquals("the failure must reach the durable log exactly once", 1, entries.size)
        assertEquals("browse", entries.single().source)
        assertTrue(
            "the durable entry must carry a real, human-readable message, not blank",
            entries.single().message.isNotBlank(),
        )
    }

    @Test
    fun `a search failure with no durable error log wired behaves exactly as before - no crash, session log still works`() {
        // No durableErrorLog passed - defaults to null, same fail-closed shape as [notifier] elsewhere
        // in this class (a test/build that wires nothing up must not need real Android storage).
        // Same reasoning as above: the constructor's own init-triggered search() is what fails here.
        val viewModel = newViewModel(durableErrorLog = null)

        val state = awaitState(viewModel) { !it.loading }

        assertTrue("the session error banner must still show even with no durable sink wired", state.error != null)
    }
}
