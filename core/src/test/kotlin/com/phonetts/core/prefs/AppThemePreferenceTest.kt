package com.phonetts.core.prefs

import kotlin.test.Test
import kotlin.test.assertEquals

class AppThemePreferenceTest {
    @Test
    fun `defaults to SYSTEM when nothing has been chosen`() {
        val pref = AppThemePreference(InMemoryPreferenceStore())
        assertEquals(AppTheme.SYSTEM, pref.selected())
    }

    @Test
    fun `select then selected round-trips every theme option`() {
        val pref = AppThemePreference(InMemoryPreferenceStore())
        AppTheme.entries.forEach { theme ->
            pref.select(theme)
            assertEquals(theme, pref.selected())
        }
    }

    @Test
    fun `selecting a new theme overwrites the previous choice`() {
        val pref = AppThemePreference(InMemoryPreferenceStore())
        pref.select(AppTheme.SEPIA)
        pref.select(AppTheme.TRUE_BLACK)
        assertEquals(AppTheme.TRUE_BLACK, pref.selected())
    }

    @Test
    fun `an unrecognized stored value fails safe to SYSTEM`() {
        val store = InMemoryPreferenceStore()
        store.putString("app_theme", "MIDNIGHT_NEON") // a theme that no longer exists
        assertEquals(AppTheme.SYSTEM, AppThemePreference(store).selected())
    }
}
