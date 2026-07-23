package com.phonetts.core.compare

import kotlin.test.Test
import kotlin.test.assertEquals

class TournamentSchedulingTest {
    // ---- prefetchAhead (issue #112) -------------------------------------------------------------

    @Test
    fun fetchesAtLeastMinAheadEvenWithAnEmptyBank() {
        // Nothing banked yet, but the immediate next entry must still be scheduled.
        val ahead = prefetchAhead(0.0, upcomingGenerationSeconds = listOf(2.0, 2.0, 2.0), minAhead = 1, maxAhead = 8)

        assertEquals(1, ahead)
    }

    @Test
    fun runsSeveralAheadWhenTheBankCoversTheirCumulativeCost() {
        // 5s banked, each upcoming entry costs 2s -> the first two fit (4s), the third would be 6s.
        val ahead = prefetchAhead(5.0, listOf(2.0, 2.0, 2.0, 2.0), minAhead = 1, maxAhead = 8)

        assertEquals(2, ahead)
    }

    @Test
    fun aSlowFirstEntryDrainsTheBankAndCollapsesLookAheadToMin() {
        // A slower-than-real-time model (10s) exceeds the 4s bank, so only the min-ahead one is taken.
        val ahead = prefetchAhead(4.0, upcomingGenerationSeconds = listOf(10.0, 1.0, 1.0), minAhead = 1, maxAhead = 8)

        assertEquals(1, ahead)
    }

    @Test
    fun neverExceedsMaxAheadEvenWithAHugeBank() {
        val ahead = prefetchAhead(1_000.0, listOf(1.0, 1.0, 1.0, 1.0, 1.0), minAhead = 1, maxAhead = 3)

        assertEquals(3, ahead)
    }

    @Test
    fun neverExceedsTheNumberOfUpcomingEntries() {
        val ahead = prefetchAhead(1_000.0, upcomingGenerationSeconds = listOf(1.0, 1.0), minAhead = 5, maxAhead = 8)

        assertEquals(2, ahead)
    }

    @Test
    fun emptyUpcomingListSchedulesNothing() {
        assertEquals(0, prefetchAhead(100.0, upcomingGenerationSeconds = emptyList(), minAhead = 2, maxAhead = 8))
    }

    @Test
    fun negativeCostsAreTreatedAsFreeAndDoNotDrainTheBank() {
        // A defensive input (a bogus non-positive estimate) must not spuriously stop the loop.
        val ahead = prefetchAhead(0.0, upcomingGenerationSeconds = listOf(-1.0, -1.0, 5.0), minAhead = 0, maxAhead = 8)

        assertEquals(2, ahead)
    }

    // ---- bracketRoundSizes (issue #113) ---------------------------------------------------------

    @Test
    fun roundSizesForAPowerOfTwoField() {
        assertEquals(listOf(4, 2, 1), bracketRoundSizes(8))
    }

    @Test
    fun roundSizesForAnOddFieldAccountForByes() {
        // 5 entries: round 1 pairs two of them (one bye) -> 2 comparisons, then 1, then the final.
        assertEquals(listOf(2, 1, 1), bracketRoundSizes(5))
    }

    @Test
    fun roundSizesForThreeEntries() {
        assertEquals(listOf(1, 1), bracketRoundSizes(3))
    }

    @Test
    fun twoEntriesIsASingleComparison() {
        assertEquals(listOf(1), bracketRoundSizes(2))
    }

    @Test
    fun aFieldTooSmallToCompareHasNoRounds() {
        assertEquals(emptyList(), bracketRoundSizes(1))
        assertEquals(emptyList(), bracketRoundSizes(0))
    }
}
