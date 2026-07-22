package com.phonetts.core.compare

import kotlin.test.Test
import kotlin.test.assertEquals

class RosterDedupTest {
    private data class Pick(val modelId: String, val voiceId: String)

    private val key: (Pick) -> Pair<String, String> = { it.modelId to it.voiceId }

    @Test
    fun appendsGenuinelyNewAdditionsOntoAnEmptyRoster() {
        val result = mergeUnique(emptyList(), listOf(Pick("a", "v1"), Pick("b", "v1")), key)

        assertEquals(listOf(Pick("a", "v1"), Pick("b", "v1")), result)
    }

    @Test
    fun skipsAnAdditionAlreadyPresentInExisting() {
        val existing = listOf(Pick("a", "v1"))

        val result = mergeUnique(existing, listOf(Pick("a", "v1"), Pick("b", "v1")), key)

        assertEquals(listOf(Pick("a", "v1"), Pick("b", "v1")), result)
    }

    @Test
    fun skipsDuplicatesWithinTheAdditionsBatchItself() {
        // e.g. "Add all models" handed a list that (for whatever reason) names the same model+voice twice.
        val result = mergeUnique(emptyList(), listOf(Pick("a", "v1"), Pick("a", "v1"), Pick("b", "v1")), key)

        assertEquals(listOf(Pick("a", "v1"), Pick("b", "v1")), result)
    }

    @Test
    fun keepsTheFirstOccurrencesPayloadWhenIdsDiffButKeysCollide() {
        // Two distinct objects that key-collide (e.g. two roster entries with different generated
        // ids but the same model+voice) — the earlier one wins, later ones are dropped.
        data class Entry(val id: String, val modelId: String, val voiceId: String)
        val entryKey: (Entry) -> Pair<String, String> = { it.modelId to it.voiceId }
        val existing = listOf(Entry("entry-0", "a", "v1"))

        val result = mergeUnique(existing, listOf(Entry("entry-1", "a", "v1"), Entry("entry-2", "b", "v1")), entryKey)

        assertEquals(listOf(Entry("entry-0", "a", "v1"), Entry("entry-2", "b", "v1")), result)
    }

    @Test
    fun dedupesAnAlreadyDuplicatedRosterWhenPassedAsAdditionsAgainstAnEmptyExisting() {
        // Mirrors how TournamentController cleans up a roster that accumulated duplicates before
        // this fix existed: pass it as `additions` against an empty `existing`.
        val dirtyRoster = listOf(Pick("a", "v1"), Pick("a", "v1"), Pick("b", "v1"), Pick("a", "v1"))

        val result = mergeUnique(emptyList(), dirtyRoster, key)

        assertEquals(listOf(Pick("a", "v1"), Pick("b", "v1")), result)
    }

    @Test
    fun emptyAdditionsIsANoOp() {
        val existing = listOf(Pick("a", "v1"))

        assertEquals(existing, mergeUnique(existing, emptyList(), key))
    }
}
