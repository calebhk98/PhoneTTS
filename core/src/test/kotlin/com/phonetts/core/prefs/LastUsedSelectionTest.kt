package com.phonetts.core.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LastUsedSelectionTest {
    @Test
    fun `nothing saved yet returns null`() {
        val store = LastUsedSelectionStore(InMemoryPreferenceStore())
        assertNull(store.last())
    }

    @Test
    fun `records and reads back a selection`() {
        val store = LastUsedSelectionStore(InMemoryPreferenceStore())

        store.record(LastUsedSelection("kokoro-en", "voice-a", 1.25f))

        assertEquals(LastUsedSelection("kokoro-en", "voice-a", 1.25f), store.last())
    }

    @Test
    fun `recording again overwrites the previous selection`() {
        val store = LastUsedSelectionStore(InMemoryPreferenceStore())

        store.record(LastUsedSelection("kokoro-en", "voice-a", 1.25f))
        store.record(LastUsedSelection("piper-en", "voice-b", 0.75f))

        assertEquals(LastUsedSelection("piper-en", "voice-b", 0.75f), store.last())
    }

    @Test
    fun `selection persists across separate store instances over the same backing store`() {
        val backing = InMemoryPreferenceStore()
        LastUsedSelectionStore(backing).record(LastUsedSelection("kokoro-en", "voice-a", 1.0f))

        val reloaded = LastUsedSelectionStore(backing)

        assertEquals(LastUsedSelection("kokoro-en", "voice-a", 1.0f), reloaded.last())
    }

    @Test
    fun `clear forgets the saved selection`() {
        val store = LastUsedSelectionStore(InMemoryPreferenceStore())
        store.record(LastUsedSelection("kokoro-en", "voice-a", 1.0f))

        store.clear()

        assertNull(store.last())
    }

    @Test
    fun `a corrupt speed field fails closed to null rather than a partial selection`() {
        val backing = InMemoryPreferenceStore()
        val store = LastUsedSelectionStore(backing)
        store.record(LastUsedSelection("kokoro-en", "voice-a", 1.0f))
        // Simulate a corrupt/garbage value landing in the speed field.
        backing.putString("last_used.speed", "not-a-float")

        assertNull(store.last())
    }
}
