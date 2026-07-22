package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HfLoadMorePolicyTest {
    @Test
    fun fetchesTheFirstPageEvenWithNothingVisibleYet() {
        assertTrue(
            HfLoadMorePolicy.shouldFetchAnotherPage(
                pagesFetchedSoFar = 0,
                lastPageSize = Int.MAX_VALUE,
                pageSize = 20,
                newlyVisibleCount = 0,
                targetNewVisible = 20,
                maxPages = 5,
            ),
        )
    }

    @Test
    fun stopsOnceTheTargetNewVisibleCountIsReached() {
        assertFalse(
            HfLoadMorePolicy.shouldFetchAnotherPage(
                pagesFetchedSoFar = 1,
                lastPageSize = 20,
                pageSize = 20,
                newlyVisibleCount = 20,
                targetNewVisible = 20,
                maxPages = 5,
            ),
        )
    }

    @Test
    fun keepsGoingWhileBelowTargetAndPagesAreStillFull() {
        assertTrue(
            HfLoadMorePolicy.shouldFetchAnotherPage(
                pagesFetchedSoFar = 2,
                lastPageSize = 20,
                pageSize = 20,
                newlyVisibleCount = 3,
                targetNewVisible = 20,
                maxPages = 5,
            ),
        )
    }

    @Test
    fun stopsWhenTheHubHasNoMoreResults() {
        // A short page (< pageSize) means the Hub ran out, regardless of how few results are visible.
        assertFalse(
            HfLoadMorePolicy.shouldFetchAnotherPage(
                pagesFetchedSoFar = 1,
                lastPageSize = 5,
                pageSize = 20,
                newlyVisibleCount = 0,
                targetNewVisible = 20,
                maxPages = 5,
            ),
        )
    }

    @Test
    fun stopsAtTheMaxPagesCapEvenIfStillBelowTarget() {
        // Guards against spinning forever on a filter that matches almost nothing.
        assertFalse(
            HfLoadMorePolicy.shouldFetchAnotherPage(
                pagesFetchedSoFar = 5,
                lastPageSize = 20,
                pageSize = 20,
                newlyVisibleCount = 0,
                targetNewVisible = 20,
                maxPages = 5,
            ),
        )
    }
}
