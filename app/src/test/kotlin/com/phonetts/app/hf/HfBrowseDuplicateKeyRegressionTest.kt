package com.phonetts.app.hf

import com.phonetts.app.testing.FakeHttpClient
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.resolver.InMemoryOverrideStore
import com.phonetts.core.resolver.Resolver
import com.phonetts.core.sideload.DirectoryBundleReader
import com.phonetts.core.sideload.ModelImporter
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

/**
 * Regression test for the bug that actually shipped and crashed the Browse screen: Hugging Face's
 * search endpoint can return the SAME repo id twice in one page (its own ranking re-shuffling
 * mid-request), and [HfBrowseScreen] renders `displayedResults` in a `LazyColumn` keyed by
 * `"hf:${it.id}"` - two rows with the same key throw a duplicate-key `IllegalArgumentException`
 * and crash the whole screen.
 *
 * The fix already lives in [HfBrowseViewModel.search] (`results.distinctBy { it.id }` right where
 * a page is stored into state) - this test feeds a scripted duplicate-id response and asserts the
 * view model's own state never carries the duplicate through, so a future edit that drops that
 * dedup guard fails HERE instead of shipping to a real device again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HfBrowseDuplicateKeyRegressionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        // HfBrowseViewModel.search()/loadDiagnostics() launch on viewModelScope, which needs a
        // Main dispatcher; Unconfined runs the launched coroutine's synchronous portions eagerly on
        // the calling thread so most of the round trip happens before init{} even returns. The
        // withContext(Dispatchers.IO) hop inside search() still crosses onto a real IO thread, so
        // assertions below poll briefly rather than assuming completion is synchronous.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(searchResponseJson: String): HfBrowseViewModel {
        val catalog = ModelCatalog()
        val importer = ModelImporter(
            DirectoryBundleReader(),
            Resolver(engines = emptyList(), overrideStore = InMemoryOverrideStore()) { bundle -> bundle.id },
            catalog,
        )
        val downloader = HfDownloader(tmp.newFolder("models"), importer)
        val hfCatalog = HfCatalog(FakeHttpClient(searchResponseJson))
        return HfBrowseViewModel(
            catalog = hfCatalog,
            downloader = downloader,
            modelCatalog = catalog,
        )
    }

    /** Polls [HfBrowseViewModel.state] until [predicate] holds or [timeoutMs] elapses. */
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
    fun `search results with a duplicated repo id are de-duplicated in state`() {
        val duplicateIdResponse = """
            [
              {"id": "acme/same-repo", "likes": 10, "downloads": 100},
              {"id": "other/unique-repo", "likes": 5, "downloads": 50},
              {"id": "acme/same-repo", "likes": 10, "downloads": 100}
            ]
        """.trimIndent()

        val viewModel = newViewModel(duplicateIdResponse)
        val state = awaitState(viewModel) { !it.loading }

        val ids = state.results.map { it.id }
        assertEquals("raw response had a duplicate id, but state.results must be de-duplicated", 2, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.containsAll(listOf("acme/same-repo", "other/unique-repo")))

        // The exact invariant HfBrowseScreen's LazyColumn depends on: the keys it will render
        // ("hf:${it.id}") must be unique, or Compose throws IllegalArgumentException at render time.
        val renderedKeys = state.results.map { "hf:${it.id}" }
        assertEquals(renderedKeys.size, renderedKeys.toSet().size)
    }

    @Test
    fun `displayedResults for a de-duplicated state stays duplicate-free after sort and filter`() {
        val duplicateIdResponse = """
            [
              {"id": "acme/same-repo", "likes": 1},
              {"id": "acme/same-repo", "likes": 1},
              {"id": "acme/same-repo", "likes": 1}
            ]
        """.trimIndent()

        val viewModel = newViewModel(duplicateIdResponse)
        val state = awaitState(viewModel) { !it.loading }

        val displayed = viewModel.displayedResults(state)
        val renderedKeys = displayed.map { "hf:${it.id}" }
        assertEquals(1, displayed.size)
        assertEquals(renderedKeys.size, renderedKeys.toSet().size)
    }
}
