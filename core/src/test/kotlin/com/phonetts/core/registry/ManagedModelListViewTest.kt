package com.phonetts.core.registry

import com.phonetts.core.model.ManageModelFacts
import com.phonetts.core.model.Origin
import com.phonetts.core.testing.testDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seam tests for [ManagedModelListView] (issue: downloaded-models sort/filter ignores engine-less
 * models). Before this, [ModelManagementViewModel]'s `visibleUsage` only ever ran over resolved
 * [ModelUsage] rows - an [UnresolvedModelUsage] bundle was appended afterward raw, unsorted, and
 * unfiltered. These tests prove a resolved row and an unresolved bundle now sort/filter together,
 * that the "sorts/filters last" convention holds for a fact only a resolved row has, and that the
 * Origin/Engine/size filters behave the way the Manage screen's UI relies on.
 */
class ManagedModelListViewTest {
    private fun facts(
        peakRamBytes: Long?,
        realtimeMultiple: Double?,
    ): ManageModelFacts =
        ManageModelFacts(
            hfRepoId = null,
            paramCount = null,
            peakRamBytes = peakRamBytes,
            ramIsMeasured = false,
            realtimeMultiple = realtimeMultiple,
            realtimeIsMeasured = false,
        )

    private fun resolved(
        modelId: String,
        engineId: String = "kokoro",
        sizeBytes: Long = 1_000L,
        origin: Origin = Origin.BUILT_IN,
    ) = ManagedModelEntry.Resolved(ModelUsage(testDescriptor(modelId, engineId, origin = origin), sizeBytes))

    private fun unresolved(
        bundleId: String,
        sizeBytes: Long = 1_000L,
    ) = ManagedModelEntry.Unresolved(UnresolvedModelUsage(bundleId, sizeBytes, "unrecognized"))

    @Test
    fun `name query matches an unresolved bundle by its bundle id`() {
        val entries = listOf(resolved("alpha"), unresolved("zzz-mystery-bundle"))
        val visible =
            ManagedModelListView.visible(
                entries,
                emptyMap(),
                ManagedSort.DEFAULT,
                ManagedModelFilter(query = "mystery"),
            )
        assertEquals(listOf("zzz-mystery-bundle"), visible.map { it.modelId })
    }

    @Test
    fun `name sort orders a resolved row and an unresolved bundle in the SAME pass`() {
        // Names picked so alphabetical order interleaves resolved/unresolved rather than grouping
        // them - the exact bug: they used to never mix because unresolved was a separate, unsorted
        // list appended after the sorted resolved one.
        val entries = listOf(resolved("bravo"), unresolved("alpha-bundle"), resolved("charlie"))
        val visible = ManagedModelListView.visible(entries, emptyMap(), ManagedSort.DEFAULT, ManagedModelFilter())
        assertEquals(listOf("alpha-bundle", "bravo", "charlie"), visible.map { it.modelId })
    }

    @Test
    fun `size sort orders both entry kinds together, ascending`() {
        val entries =
            listOf(
                resolved("big", sizeBytes = 300L),
                unresolved("mid-bundle", sizeBytes = 200L),
                resolved("small", sizeBytes = 100L),
            )
        val visible =
            ManagedModelListView.visible(
                entries,
                emptyMap(),
                ManagedSort(ManagedSortKey.SIZE, ascending = true),
                ManagedModelFilter(),
            )
        assertEquals(listOf("small", "mid-bundle", "big"), visible.map { it.modelId })
    }

    @Test
    fun `RAM sort puts an unresolved bundle (no fact) last in ascending order`() {
        val entries = listOf(resolved("has-ram"), unresolved("no-fact-bundle"), resolved("also-has-ram"))
        val facts =
            mapOf(
                "has-ram" to facts(peakRamBytes = 500L, realtimeMultiple = null),
                "also-has-ram" to facts(peakRamBytes = 100L, realtimeMultiple = null),
            )
        val visible =
            ManagedModelListView.visible(
                entries,
                facts,
                ManagedSort(ManagedSortKey.RAM, ascending = true),
                ManagedModelFilter(),
            )
        assertEquals(listOf("also-has-ram", "has-ram", "no-fact-bundle"), visible.map { it.modelId })
    }

    @Test
    fun `an Origin filter excludes an unresolved bundle - it has no known origin`() {
        val entries = listOf(resolved("builtin-model", origin = Origin.BUILT_IN), unresolved("mystery-bundle"))
        val visible =
            ManagedModelListView.visible(
                entries,
                emptyMap(),
                ManagedSort.DEFAULT,
                ManagedModelFilter(origin = Origin.BUILT_IN),
            )
        assertEquals(listOf("builtin-model"), visible.map { it.modelId })
    }

    @Test
    fun `Engine filter Specific matches only that engine's resolved rows`() {
        val entries = listOf(resolved("k1", engineId = "kokoro"), resolved("m1", engineId = "melotts"))
        val visible =
            ManagedModelListView.visible(
                entries,
                emptyMap(),
                ManagedSort.DEFAULT,
                ManagedModelFilter(engine = ManagedEngineFilter.Specific("kokoro")),
            )
        assertEquals(listOf("k1"), visible.map { it.modelId })
    }

    @Test
    fun `Engine filter NoEngine matches only unresolved bundles`() {
        val entries = listOf(resolved("k1"), unresolved("bundle-a"), unresolved("bundle-b"))
        val visible =
            ManagedModelListView.visible(
                entries,
                emptyMap(),
                ManagedSort.DEFAULT,
                ManagedModelFilter(engine = ManagedEngineFilter.NoEngine),
            )
        assertEquals(setOf("bundle-a", "bundle-b"), visible.map { it.modelId }.toSet())
    }

    @Test
    fun `size range filter restricts both entry kinds by their own size`() {
        val entries =
            listOf(
                resolved("too-small", sizeBytes = 10L),
                unresolved("just-right-bundle", sizeBytes = 500L),
                resolved("too-big", sizeBytes = 10_000L),
            )
        val visible =
            ManagedModelListView.visible(
                entries,
                emptyMap(),
                ManagedSort.DEFAULT,
                ManagedModelFilter(minSizeBytes = 100L, maxSizeBytes = 1_000L),
            )
        assertEquals(listOf("just-right-bundle"), visible.map { it.modelId })
    }

    @Test
    fun `descending direction reverses the whole unified list`() {
        // Ascending-by-name (case-insensitive) puts "alpha-bundle" first (its own bundle id is its
        // name), then the two resolved rows by their descriptor display name ("Model a"/"Model c") -
        // reversing that whole order is what this test checks, across BOTH entry kinds at once.
        val entries = listOf(resolved("a"), unresolved("alpha-bundle"), resolved("c"))
        val visible =
            ManagedModelListView.visible(
                entries,
                emptyMap(),
                ManagedSort(ManagedSortKey.NAME, ascending = false),
                ManagedModelFilter(),
            )
        assertEquals(listOf("c", "a", "alpha-bundle"), visible.map { it.modelId })
    }
}
