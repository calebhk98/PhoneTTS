package com.phonetts.app.session

import androidx.test.core.app.ApplicationProvider
import com.phonetts.app.PhoneTtsApplication
import com.phonetts.app.benchmark.BenchmarkViewModel
import com.phonetts.app.testing.ShadowEnvironmentAllFilesAccess
import com.phonetts.core.session.SessionStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #131: [SessionStateStore.benchmark]/`.updateBenchmark` existed and were fully unit-tested
 * at the `:core` layer, but nothing in `:app` ever called them, so the Benchmarks screen's sort/
 * query/"show history" toggle reset on every relaunch. Unlike [ModelManagementViewModel] (which
 * takes plain, easily-faked constructor params), [BenchmarkViewModel] takes the whole [AppGraph] -
 * so this needs a real graph, built the same way [com.phonetts.app.ScreenSmokeTest] already does
 * (via [PhoneTtsApplication] under Robolectric) rather than a hand-rolled fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowEnvironmentAllFilesAccess::class])
class BenchmarkSessionWiringTest {
    private fun graph() = ApplicationProvider.getApplicationContext<PhoneTtsApplication>().graph

    @Test
    fun `sort, query, and show-history survive constructing a fresh view model over the same graph`() {
        val graph = graph()
        val first = BenchmarkViewModel(graph)
        first.setSortKey(BenchmarkViewModel.SortKey.RTF)
        first.toggleSortDirection() // NAME/true default -> RTF/false
        first.setQuery("kokoro")
        first.toggleHistory() // off -> on

        // A second view model built over the SAME AppGraph (and therefore the same underlying
        // SessionStateStore instance/persisted document) - proving construction actually SEEDS from
        // whatever is currently in the session store, which is exactly what was missing before this
        // fix: previously nothing ever called updateBenchmark, so this second instance would always
        // have shown the bare defaults no matter what the first one did.
        val second = BenchmarkViewModel(graph)
        val state = second.state.value

        assertEquals(BenchmarkViewModel.SortKey.RTF, state.sort.key)
        assertFalse(state.sort.ascending)
        assertEquals("kokoro", state.query)
        assertTrue(state.showHistory)
    }

    @Test
    fun `a renamed-away sort key falls back to the default rather than crashing`() {
        val graph = graph()
        graph.sessionState.updateBenchmark { it.copy(sortKey = "SOME_SORT_KEY_THAT_NO_LONGER_EXISTS") }

        val viewModel = BenchmarkViewModel(graph)

        assertEquals(
            "an unrecognised persisted sort key must fall back to the default, never crash construction",
            BenchmarkViewModel.SortKey.NAME,
            viewModel.state.value.sort.key,
        )
    }

    @Test
    fun `a garbage show-history value is read as false rather than crashing`() {
        val graph = graph()
        graph.sessionState.updateBenchmark { it.copy(filters = mapOf("showHistory" to "not-a-boolean-at-all")) }

        val viewModel = BenchmarkViewModel(graph)

        assertFalse(viewModel.state.value.showHistory)
    }
}
