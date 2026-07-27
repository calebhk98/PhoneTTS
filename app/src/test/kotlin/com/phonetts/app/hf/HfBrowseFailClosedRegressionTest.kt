package com.phonetts.app.hf

import com.phonetts.app.ModelStorage
import com.phonetts.app.testing.FakeHttpClient
import com.phonetts.core.download.builtin.BuiltInCatalog
import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.engine.Voice
import com.phonetts.core.metrics.BenchmarkHistory
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.model.Origin
import com.phonetts.core.prefs.PreferenceStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for a SECOND Browse-crashes-on-open bug class, distinct from the duplicate-key
 * crash [HfBrowseDuplicateKeyRegressionTest] already covers (that one shipped a fix in v0.1.12 that
 * did NOT resolve the report - the device tester still could not open Browse).
 *
 * Auditing every LazyColumn key confirmed the duplicate-key fix is sufficient (recommended/piper/hf
 * sections use distinct "rec:"/"piper:"/"hf:" key prefixes, so even an id that coincidentally
 * collides across sections - e.g. rhasspy/piper-voices ships `en_US-lessac-medium`, the exact same
 * voice [BuiltInCatalog.PIPER_LESSAC] curates - can never produce a duplicate LazyColumn key). The
 * structural gap found instead: several code paths that run AUTOMATICALLY the instant Browse has
 * anything real to show - [HfBrowseViewModel.displayedResults] (called from a Compose `remember`
 * block on every recomposition after results load) and [HfBrowseViewModel.recommended] (evaluated
 * synchronously while the class is CONSTRUCTED, i.e. before the screen renders anything at all) -
 * had no protection against an exception from data/state this class does not fully control the
 * shape of: a real device's persisted [BenchmarkHistory] (SharedPreferences-backed; a real device
 * that's been upgraded across many versions is exactly where a stored value's type could mismatch
 * what a later read expects), or a runtime-availability probe supplied by the app graph. Unlike
 * [HfResultsView.apply]'s sort/filter math (reviewed and found to only use `sortedBy`/
 * `sortedByDescending` over Comparable primitives - no custom Comparator, so the classic TimSort
 * "violates its general contract" crash cannot occur there), THESE call sites had zero error
 * handling at all - a single throw anywhere in them took the whole screen (and app) down.
 *
 * These tests exercise the fail-closed fixes added to close that gap. They do not prove a
 * `PreferenceStore`/`isRuntimeAvailable` throwing is THE exact crash the tester hit (no crash log
 * exists from v0.1.12 to confirm it), but they prove the class of failure - "opening Browse touches
 * on-device state outside its control, and any hiccup there used to be fatal" - can no longer bring
 * the screen down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HfBrowseFailClosedRegressionTest {

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

    /** A [PreferenceStore] that throws on every read - simulating the real Android footgun where
     * `SharedPreferences.getString` throws `ClassCastException` if the key was ever written with a
     * different value type (a plausible outcome on a real device carried across many app versions,
     * since every preference in this app shares one SharedPreferences file). */
    private class ThrowingPreferenceStore : PreferenceStore {
        override fun getString(key: String): String? = error("simulated on-device preference read failure")

        override fun putString(
            key: String,
            value: String,
        ) = error("simulated on-device preference write failure")

        override fun getStringSet(key: String): Set<String> = error("simulated on-device preference read failure")

        override fun putStringSet(
            key: String,
            values: Set<String>,
        ) = error("simulated on-device preference write failure")

        override fun remove(key: String) = error("simulated on-device preference write failure")
    }

    private fun newViewModel(
        searchResponseJson: String,
        isRuntimeAvailable: (String) -> Boolean = { false },
        benchmarkHistory: BenchmarkHistory? = null,
        modelCatalog: ModelCatalog = ModelCatalog(),
        totalRamBytes: () -> Long = { 0L },
    ): HfBrowseViewModel {
        val importer =
            ModelImporter(
                DirectoryBundleReader(),
                Resolver(engines = emptyList(), overrideStore = InMemoryOverrideStore()) { bundle -> bundle.id },
                modelCatalog,
            )
        val downloader = HfDownloader(tmp.newFolder("models-${System.nanoTime()}"), importer)
        val hfCatalog = HfCatalog(FakeHttpClient(searchResponseJson))
        return HfBrowseViewModel(
            catalog = hfCatalog,
            downloader = downloader,
            modelCatalog = modelCatalog,
            isRuntimeAvailable = isRuntimeAvailable,
            benchmarkHistory = benchmarkHistory,
            totalRamBytes = totalRamBytes,
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
    fun `constructing the view model does not throw when isRuntimeAvailable throws, and hides that model`() {
        // Every recommended model whose id doesn't require a runtime must still show; only the one
        // whose probe throws (CosyVoice3, the only entry with requiresRuntimeId set) is hidden -
        // fail-closed hides just the model in question, it does not blank the whole list.
        val viewModel =
            newViewModel(
                searchResponseJson = "[]",
                isRuntimeAvailable = { throw IllegalStateException("simulated native-library probe crash") },
            )

        val recommendedIds = viewModel.recommended.map { it.id }
        assertFalse(
            "a throwing runtime probe must hide the model it was asked about, not appear as available",
            BuiltInCatalog.COSYVOICE3_05B.id in recommendedIds,
        )
        // Derived from the catalog itself, not a hand-written list of four ids (CLAUDE.md: derive
        // anything that must track reality). The hand-written version silently stopped covering
        // any model added to BuiltInCatalog afterwards - which is exactly the case this asserts.
        val ungatedIds = BuiltInCatalog.ALL.filter { it.requiresRuntimeId == null }.map { it.id }
        assertTrue(
            "every OTHER recommended model (no runtime requirement) must still show",
            recommendedIds.containsAll(ungatedIds),
        )
    }

    @Test
    fun `constructing the view model does not throw when totalRamBytes throws, and reads back as unknown`() {
        // deviceTotalRamBytes is a property initializer - it runs DURING construction, before the
        // screen renders anything, exactly like the isRuntimeAvailable probe above. In production
        // this is wired to DeviceInfo.totalRamBytes(context), i.e. a real ActivityManager Binder
        // call (`getMemoryInfo`) that can throw on a misbehaving OEM framework or dead system-server
        // binder - a real, if rare, on-device failure mode this test simulates directly rather than
        // trying to reproduce the underlying Binder condition.
        val viewModel =
            newViewModel(
                searchResponseJson = "[]",
                totalRamBytes = { throw IllegalStateException("simulated ActivityManager binder failure") },
            )

        // Fail-closed to the same "unknown" value totalRamBytes' own kdoc already promises for an
        // ordinary failed reading (0L - DeviceRamFit's own "can't say, never warn" sentinel) - a
        // thrown exception must degrade the same way a merely-wrong reading already does, not crash
        // construction outright.
        assertEquals(0L, viewModel.deviceTotalRamBytes)
    }

    @Test
    fun `displayedResults does not crash when the on-device benchmark history throws`() {
        val modelId = "acme/some-repo"
        val catalog = ModelCatalog()
        // rtfEstimates() only calls into BenchmarkHistory for a result that is BOTH installed
        // (present in the catalog) AND matched by bundle id - so the descriptor must exist first.
        catalog.add(
            ModelDescriptor(
                modelId = ModelStorage.sanitize(modelId),
                engineId = "fake-engine",
                displayName = "Test model",
                origin = Origin.BUILT_IN,
                sampleRate = 22_050,
                voices = listOf(Voice("v0", "Voice 0", "en")),
                defaultVoiceId = "v0",
            ),
        )
        val throwingHistory = BenchmarkHistory(ThrowingPreferenceStore())

        val viewModel =
            newViewModel(
                searchResponseJson = """[{"id": "$modelId", "likes": 1, "downloads": 5}]""",
                benchmarkHistory = throwingHistory,
                modelCatalog = catalog,
            )
        val state = awaitState(viewModel) { !it.loading }
        assertEquals(1, state.results.size)

        // Must not throw - this is exactly what Compose calls, unprotected, on every recomposition
        // after real results land (i.e. the instant Browse has anything to show).
        val displayed = viewModel.displayedResults(state)
        assertEquals("a throwing history must fail closed to the raw fetched results, not crash", 1, displayed.size)

        // The other Compose-facing menu derivations must be equally unbreakable.
        viewModel.availableTags(state)
        viewModel.availableLanguages(state)
        viewModel.availableFormats(state)
        viewModel.availableEngines(state)
    }

    @Test
    fun `loadMore does not crash when the on-device benchmark history throws mid-page`() {
        // Must match one of fullPage's ids below - rtfEstimates() only calls into BenchmarkHistory
        // for a result BOTH installed (present in the catalog) AND matched by bundle id.
        val modelId = "acme/repo-1"
        val catalog = ModelCatalog()
        catalog.add(
            ModelDescriptor(
                modelId = ModelStorage.sanitize(modelId),
                engineId = "fake-engine",
                displayName = "Test model",
                origin = Origin.BUILT_IN,
                sampleRate = 22_050,
                voices = listOf(Voice("v0", "Voice 0", "en")),
                defaultVoiceId = "v0",
            ),
        )
        val throwingHistory = BenchmarkHistory(ThrowingPreferenceStore())

        // A full first page (PAGE_SIZE = HfCatalog.DEFAULT_LIMIT) so canLoadMore is true and
        // loadMore() actually runs its fetch-and-merge loop (which calls the same sort/filter
        // pipeline, and therefore the same throwing history, as displayedResults()).
        val fullPage =
            (1..HfCatalog.DEFAULT_LIMIT).joinToString(",", prefix = "[", postfix = "]") { i ->
                """{"id": "acme/repo-$i", "likes": 1, "downloads": $i}"""
            }
        val viewModel =
            newViewModel(
                searchResponseJson = fullPage,
                benchmarkHistory = throwingHistory,
                modelCatalog = catalog,
            )
        val afterSearch = awaitState(viewModel) { !it.loading }
        assertTrue(afterSearch.canLoadMore)

        // Must not crash the coroutine/app - loadMore()'s body previously had NO error handling at
        // all (unlike every sibling network method in this class), so any exception surfaced here
        // used to propagate uncaught.
        viewModel.loadMore()
        val afterLoadMore = awaitState(viewModel) { !it.loadingMore }
        // Whatever the outcome (results merged, or an error logged), the view model must still be
        // alive and responsive - reading state again must not throw either.
        assertTrue(afterLoadMore.results.isNotEmpty())
    }
}
