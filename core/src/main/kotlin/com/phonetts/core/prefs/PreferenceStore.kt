package com.phonetts.core.prefs

/**
 * Minimal key/value persistence seam, mirroring
 * [com.phonetts.core.resolver.OverrideStore]'s pattern: `:core` defines the interface (plus a
 * plain in-memory double for tests) and `:app` supplies a thin SharedPreferences-backed
 * implementation. Backs [FavoriteVoices] and [DocumentMemory] - neither of those classes touches
 * Android APIs directly, which is what keeps them unit-testable on a plain JVM.
 */
interface PreferenceStore {
    fun getString(key: String): String?

    fun putString(
        key: String,
        value: String,
    )

    fun getStringSet(key: String): Set<String>

    fun putStringSet(
        key: String,
        values: Set<String>,
    )

    fun remove(key: String)
}

/** A non-persistent [PreferenceStore] backed by in-memory maps - the plain-JVM test double. */
class InMemoryPreferenceStore : PreferenceStore {
    private val strings = mutableMapOf<String, String>()
    private val stringSets = mutableMapOf<String, Set<String>>()

    override fun getString(key: String): String? = strings[key]

    override fun putString(
        key: String,
        value: String,
    ) {
        strings[key] = value
    }

    override fun getStringSet(key: String): Set<String> = stringSets[key] ?: emptySet()

    override fun putStringSet(
        key: String,
        values: Set<String>,
    ) {
        stringSets[key] = values
    }

    override fun remove(key: String) {
        strings.remove(key)
        stringSets.remove(key)
    }
}
