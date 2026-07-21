package com.phonetts.core.prefs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LongDocumentPreferencesTest {
    @Test
    fun `long-document mode is off by default`() {
        val prefs = LongDocumentPreferences(InMemoryPreferenceStore())
        assertFalse(prefs.enabled())
    }

    @Test
    fun `enabling then reading round-trips true`() {
        val prefs = LongDocumentPreferences(InMemoryPreferenceStore())
        prefs.setEnabled(true)
        assertTrue(prefs.enabled())
    }

    @Test
    fun `disabling after enabling returns to off`() {
        val prefs = LongDocumentPreferences(InMemoryPreferenceStore())
        prefs.setEnabled(true)
        prefs.setEnabled(false)
        assertFalse(prefs.enabled())
    }
}
