package com.phonetts.core.resolver

/**
 * Persists the one durable fact the resolver produces: which engine owns a given bundle
 * (spec §5.6). Keyed by [com.phonetts.core.model.ModelBundle.id]. Once a bundle has a saved
 * engine, the resolver skips re-detection entirely and trusts this store instead (spec §6.2).
 *
 * The Android SharedPreferences-backed implementation lands with `:app`; [InMemoryOverrideStore]
 * is the plain-JVM double used here and in tests.
 */
interface OverrideStore {
    /** The engine id previously chosen for [bundleId], or null if none is recorded yet. */
    fun get(bundleId: String): String?

    /** Record that [bundleId] is owned by [engineId], overwriting any prior decision. */
    fun put(
        bundleId: String,
        engineId: String,
    )
}

/** A non-persistent [OverrideStore] backed by an in-memory map. */
class InMemoryOverrideStore : OverrideStore {
    private val overrides = mutableMapOf<String, String>()

    override fun get(bundleId: String): String? = overrides[bundleId]

    override fun put(
        bundleId: String,
        engineId: String,
    ) {
        overrides[bundleId] = engineId
    }
}
