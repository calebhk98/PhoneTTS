package com.phonetts.core.registry

import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.resolver.OverrideStore

/**
 * Optional capability an [OverrideStore] can support: dropping a previously saved bundle→engine
 * decision. Deliberately NOT folded into [OverrideStore] itself — that interface already has
 * production implementations ([com.phonetts.core.resolver.InMemoryOverrideStore] and the
 * SharedPreferences-backed one in `:app`), and widening it would ripple into every existing
 * caller for a capability only [ModelManager] needs. [ModelManager] downcasts to this and no-ops
 * for stores that don't support it, so passing a plain [OverrideStore] stays safe.
 */
interface ClearableOverrideStore : OverrideStore {
    /** Forget any saved engine decision for [bundleId]. A no-op if none is recorded. */
    fun clear(bundleId: String)
}

/** One row's worth of info a "manage models" UI needs: the model plus its on-disk footprint. */
data class ModelUsage(
    val descriptor: ModelDescriptor,
    val sizeBytes: Long,
)

/** What happened when [ModelManager.remove] was asked to remove a model. */
data class ModelRemoval(
    val modelId: String,
    /** False if [modelId] was not in the catalog to begin with — nothing else happened. */
    val removedFromCatalog: Boolean,
    /** Whatever the injected `deleteModelDir` reported (e.g. false if there was nothing on disk). */
    val filesDeleted: Boolean,
    /** True if this model's engine was the one currently loaded, and it was unloaded first. */
    val engineUnloaded: Boolean,
)

/**
 * Deletes a model cleanly (spec §1.1.6, "removable models" — here applied to a user deleting a
 * *downloaded* model rather than a whole engine being pulled from the build). Deliberately
 * `:core`-pure: it never touches the filesystem itself. The app supplies [dirSizeBytes] and
 * [deleteModelDir] — real File I/O in `:app`, trivial fakes in tests — so this class is provable
 * on a plain JVM with no Android SDK.
 *
 * A clean removal touches every place a model can be referenced:
 *  - unloads it first if [engineManager] says it is the currently loaded engine (spec rule #6:
 *    one engine loaded at a time, and nothing left holding a reference to torn-down weights),
 *  - deletes its on-disk weights via [deleteModelDir],
 *  - drops it from the [catalog] so the model dropdown stops offering it,
 *  - and clears any saved [OverrideStore] decision for it, so a future re-import of a
 *    same-named bundle re-detects instead of trusting a stale mapping.
 *
 * [overrideStore] and [engineManager] are optional: a caller that doesn't care about persisted
 * overrides or live-unload can omit them and still get catalog + file removal.
 */
class ModelManager(
    private val catalog: ModelCatalog,
    private val dirSizeBytes: (String) -> Long,
    private val deleteModelDir: (String) -> Boolean,
    private val overrideStore: OverrideStore? = null,
    private val engineManager: EngineManager? = null,
) {
    /** Every model in the catalog, paired with its on-disk size. */
    fun usage(): List<ModelUsage> = catalog.list().map { ModelUsage(it, dirSizeBytes(it.modelId)) }

    /** Sum of [usage] sizes — what a "storage used" header reads. */
    fun totalBytes(): Long = usage().sumOf { it.sizeBytes }

    /** Remove [modelId] from the catalog, delete its weights, and clean up all references to it. */
    fun remove(modelId: String): ModelRemoval {
        if (catalog.get(modelId) == null) {
            return ModelRemoval(modelId, removedFromCatalog = false, filesDeleted = false, engineUnloaded = false)
        }

        val engineUnloaded = unloadIfCurrent(modelId)
        val filesDeleted = deleteModelDir(modelId)
        catalog.remove(modelId)
        clearOverride(modelId)

        return ModelRemoval(
            modelId,
            removedFromCatalog = true,
            filesDeleted = filesDeleted,
            engineUnloaded = engineUnloaded,
        )
    }

    private fun unloadIfCurrent(modelId: String): Boolean {
        val manager = engineManager ?: return false
        if (manager.currentDescriptor?.modelId != modelId) return false
        // Use EngineManager's own unload so its currentEngine/currentDescriptor are cleared too —
        // calling currentEngine.unload() directly would leave the manager reporting stale state.
        manager.unloadCurrent()
        return true
    }

    private fun clearOverride(modelId: String) {
        (overrideStore as? ClearableOverrideStore)?.clear(modelId)
    }
}
