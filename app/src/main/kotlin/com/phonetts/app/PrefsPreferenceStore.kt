package com.phonetts.app

import android.content.Context
import com.phonetts.core.prefs.PreferenceStore

/**
 * SharedPreferences-backed [PreferenceStore] — the durable, on-device store behind
 * [com.phonetts.core.prefs.FavoriteVoices] and [com.phonetts.core.prefs.DocumentMemory]. Mirrors
 * [PrefsOverrideStore]'s pattern: a thin adapter with no logic of its own, just field mapping.
 */
class PrefsPreferenceStore(context: Context) : PreferenceStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(
        key: String,
        value: String,
    ) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getStringSet(key: String): Set<String> = prefs.getStringSet(key, emptySet()) ?: emptySet()

    override fun putStringSet(
        key: String,
        values: Set<String>,
    ) {
        prefs.edit().putStringSet(key, values).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        private const val PREFS_NAME = "voice_prefs"
    }
}
