package com.phonetts.app

import android.content.Context
import com.phonetts.core.resolver.OverrideStore

/**
 * SharedPreferences-backed [OverrideStore] — the durable, on-device version of the resolver's
 * bundle→engine decisions the doc comment on [OverrideStore] promised. Survives restarts, so a
 * sideloaded/downloaded model's engine assignment (auto-detected or user-picked) is remembered.
 */
class PrefsOverrideStore(context: Context) : OverrideStore {
    private val prefs = context.getSharedPreferences("model_overrides", Context.MODE_PRIVATE)

    override fun get(bundleId: String): String? = prefs.getString(bundleId, null)

    override fun put(bundleId: String, engineId: String) {
        prefs.edit().putString(bundleId, engineId).apply()
    }
}
