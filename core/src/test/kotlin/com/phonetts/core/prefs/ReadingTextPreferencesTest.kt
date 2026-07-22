package com.phonetts.core.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingTextPreferencesTest {
    @Test
    fun `default scale is returned when nothing is stored`() {
        val prefs = ReadingTextPreferences(InMemoryPreferenceStore())
        assertEquals(ReadingTextPreferences.DEFAULT_SCALE, prefs.scale())
    }

    @Test
    fun `set then read round-trips the scale`() {
        val prefs = ReadingTextPreferences(InMemoryPreferenceStore())
        prefs.setScale(1.4f)
        assertEquals(1.4f, prefs.scale(), 0.0001f)
    }

    @Test
    fun `scale is persisted across separate instances over the same store`() {
        val store = InMemoryPreferenceStore()
        ReadingTextPreferences(store).setScale(1.3f)
        assertEquals(1.3f, ReadingTextPreferences(store).scale(), 0.0001f)
    }

    @Test
    fun `set clamps above the maximum`() {
        val prefs = ReadingTextPreferences(InMemoryPreferenceStore())
        val stored = prefs.setScale(99f)
        assertEquals(ReadingTextPreferences.MAX_SCALE, stored)
        assertEquals(ReadingTextPreferences.MAX_SCALE, prefs.scale())
    }

    @Test
    fun `set clamps below the minimum`() {
        val prefs = ReadingTextPreferences(InMemoryPreferenceStore())
        val stored = prefs.setScale(0.1f)
        assertEquals(ReadingTextPreferences.MIN_SCALE, stored)
    }

    @Test
    fun `increased and decreased step by one step and stay in range`() {
        val prefs = ReadingTextPreferences(InMemoryPreferenceStore())
        prefs.setScale(1.0f)
        assertEquals(1.0f + ReadingTextPreferences.STEP, prefs.increased(), 0.0001f)

        prefs.setScale(ReadingTextPreferences.MAX_SCALE)
        assertEquals(ReadingTextPreferences.MAX_SCALE, prefs.increased())

        prefs.setScale(ReadingTextPreferences.MIN_SCALE)
        assertTrue(prefs.decreased() >= ReadingTextPreferences.MIN_SCALE)
    }
}
