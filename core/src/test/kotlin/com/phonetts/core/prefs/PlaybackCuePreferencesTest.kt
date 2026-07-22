package com.phonetts.core.prefs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackCuePreferencesTest {
    @Test
    fun `end-of-document cue is off by default`() {
        val prefs = PlaybackCuePreferences(InMemoryPreferenceStore())
        assertFalse(prefs.endOfDocumentCueEnabled())
    }

    @Test
    fun `enabling then reading round-trips true`() {
        val prefs = PlaybackCuePreferences(InMemoryPreferenceStore())
        prefs.setEndOfDocumentCueEnabled(true)
        assertTrue(prefs.endOfDocumentCueEnabled())
    }

    @Test
    fun `disabling after enabling returns to off`() {
        val prefs = PlaybackCuePreferences(InMemoryPreferenceStore())
        prefs.setEndOfDocumentCueEnabled(true)
        prefs.setEndOfDocumentCueEnabled(false)
        assertFalse(prefs.endOfDocumentCueEnabled())
    }
}
