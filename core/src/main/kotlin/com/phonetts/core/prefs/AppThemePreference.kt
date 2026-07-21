package com.phonetts.core.prefs

/**
 * The manual theme choices the user can pick, over and above "follow the system". Kept in `:core`
 * as a plain enum so the *set* of themes has one authority (the picker renders one entry per value,
 * no theme name hardcoded in the UI) and so the persistence logic below is unit-testable on a plain
 * JVM. The concrete Compose `ColorScheme` each value maps to lives in `:app` — this layer only names
 * the options and remembers which one was chosen.
 *
 * [SYSTEM] is the default: follow the OS light/dark setting (and platform dynamic color where
 * available). The rest are fixed schemes tuned for long reading / OLED battery.
 */
enum class AppTheme(val displayName: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
    SEPIA("Sepia (warm, low glare)"),
    TRUE_BLACK("True black (OLED)"),
    HIGH_CONTRAST("High contrast"),
}

/**
 * Persists the user's [AppTheme] choice over an injected [PreferenceStore] (same `:core` logic /
 * `:app` SharedPreferences split as [FavoriteVoices]). Fails safe: an unset or unrecognized stored
 * value resolves to [AppTheme.SYSTEM] rather than throwing, so a renamed/removed theme can never
 * brick the UI.
 */
class AppThemePreference(private val store: PreferenceStore) {
    /** The saved theme, or [AppTheme.SYSTEM] when nothing valid is stored. */
    fun selected(): AppTheme {
        val savedName = store.getString(THEME_KEY) ?: return AppTheme.SYSTEM
        return AppTheme.entries.firstOrNull { it.name == savedName } ?: AppTheme.SYSTEM
    }

    /** Records [theme] as the active choice. */
    fun select(theme: AppTheme) {
        store.putString(THEME_KEY, theme.name)
    }

    companion object {
        private const val THEME_KEY = "app_theme"
    }
}
