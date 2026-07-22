package com.phonetts.core.registry

import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.prefs.StorageLocationPreference
import com.phonetts.core.resolver.OverrideStore
import com.phonetts.core.resolver.SelectableEngine

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

/**
 * One row's worth of info for a downloaded-but-unidentified bundle (issue #8): its on-disk
 * footprint and why no engine claimed it, so a "manage models" UI can show it honestly ("downloaded,
 * no engine available") instead of it silently vanishing as if it were never downloaded.
 */
data class UnresolvedModelUsage(
    val bundleId: String,
    val sizeBytes: Long,
    val reason: String,
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
 *
 * [storageLocation] (issue #4/#5) is the same plain-`:core` preference the app's storage-location
 * picker writes to; [onStorageLocationChanged] is the app-supplied callback that reacts to a
 * change (migrating already-downloaded models from the OLD base dir to the NEW one, rebuilding
 * anything that captured a fixed base dir, and re-scanning the new location). It receives the
 * previous and new custom-base-path values (both possibly null for "app-private default") so the
 * app layer knows exactly which directory to migrate FROM without re-reading state that
 * [changeStorageLocation] has, by then, already overwritten — and may return a message (e.g. a
 * migration warning) to surface to the user; both are optional so existing callers that don't care
 * about relocatable storage are unaffected.
 *
 * [selectableEnginesProvider] and [assignEngineAction] back the manual "pick an engine" fallback for
 * a bundle [inspect][com.phonetts.core.engine.VoiceEngine.inspect] declined (issue: manual engine
 * pick was described but never actually reachable from the UI). Re-reading a bundle from disk and
 * re-running it through a chosen engine's `forcedMatch` needs a
 * [com.phonetts.core.sideload.BundleReader] and a [com.phonetts.core.resolver.Resolver], neither of
 * which this class otherwise depends on — so, like [dirSizeBytes]/[deleteModelDir], that work is
 * injected rather than pulled in directly. Both default to "not wired": [selectableEnginesProvider]
 * to an empty list, [assignEngineAction] to null (making [assignEngine] throw), so existing callers
 * that don't need manual assignment are unaffected.
 */
class ModelManager(
    private val catalog: ModelCatalog,
    private val dirSizeBytes: (String) -> Long,
    private val deleteModelDir: (String) -> Boolean,
    private val overrideStore: OverrideStore? = null,
    private val engineManager: EngineManager? = null,
    private val storageLocation: StorageLocationPreference? = null,
    private val onStorageLocationChanged: ((previous: String?, next: String?) -> String?)? = null,
    private val selectableEnginesProvider: () -> List<SelectableEngine> = { emptyList() },
    private val assignEngineAction: ((bundleId: String, engineId: String) -> ModelDescriptor)? = null,
) {
    /** Every model in the catalog, paired with its on-disk size. */
    fun usage(): List<ModelUsage> = catalog.list().map { ModelUsage(it, dirSizeBytes(it.modelId)) }

    /**
     * Sum of every model's on-disk size — [usage] (identified models) AND [unresolvedUsage]
     * (downloaded-but-unclaimed bundles, issue #8) — what a "storage used" header reads. A bundle
     * with no engine still occupies real space on the phone; leaving it out of the total made a
     * "0 B used" screen possible even while an unresolved download sat on disk (bug #7/#6:
     * the two are the same root cause — a bundle isn't "not downloaded" just because it isn't
     * identified yet).
     */
    fun totalBytes(): Long = usage().sumOf { it.sizeBytes } + unresolvedUsage().sumOf { it.sizeBytes }

    /**
     * Every bundle on disk no engine could identify (issue #8), paired with its on-disk size —
     * present so a "manage models" UI can list it honestly instead of it looking like nothing was
     * ever downloaded. These are never selectable (rule 4: `inspect()` fails closed, never guessed).
     */
    fun unresolvedUsage(): List<UnresolvedModelUsage> =
        catalog.listUnresolved().map { UnresolvedModelUsage(it.bundleId, dirSizeBytes(it.bundleId), it.reason) }

    /** Delete an unresolved bundle's files and forget it — the user freeing space on a dead download. */
    fun removeUnresolved(bundleId: String): Boolean {
        val filesDeleted = deleteModelDir(bundleId)
        catalog.clearUnresolved(bundleId)
        return filesDeleted
    }

    /** Engines the user can manually assign an unresolved bundle to — see [assignEngine]. */
    fun selectableEngines(): List<SelectableEngine> = selectableEnginesProvider()

    /**
     * Manually assign [bundleId] (a downloaded-but-unidentified bundle, [UnresolvedModelUsage]) to
     * [engineId] — the working end of the "pick an engine" fallback. Re-resolves the bundle through
     * that engine's `forcedMatch` and persists the choice, so on success [bundleId] moves from
     * [unresolvedUsage] to a normal, usable [usage] entry. Throws [IllegalStateException] if no
     * [assignEngineAction] callback was wired at construction, or whatever the injected callback
     * throws (e.g. the chosen engine's `forcedMatch` rejecting the bundle) — a "manage models"
     * caller is expected to catch and surface that message rather than let it crash the app.
     */
    fun assignEngine(
        bundleId: String,
        engineId: String,
    ): ModelDescriptor {
        val assign = assignEngineAction ?: error("manual engine assignment is not wired for this ModelManager")
        return assign(bundleId, engineId)
    }

    /** Where models are currently stored — the app-private default, or a user-picked folder. */
    fun currentStorageDescription(): String =
        storageLocation?.customBasePath() ?: "App-private storage (default — removed on uninstall)"

    /**
     * Switch the models base directory (issue #4/#5). [absolutePath] null reverts to the
     * app-private default. Reads the PREVIOUS path first (rule 4: old vs new must be captured
     * before either is lost), persists the new choice, then runs [onStorageLocationChanged] with
     * both — so the app layer can migrate already-downloaded models from the old base dir to the
     * new one (never silently losing them, the data-loss bug this fixes), rebuild anything holding
     * a fixed reference to the old base dir, and re-scan the new location. Returns whatever message
     * the callback reports (e.g. a migration warning), or null if nothing needs surfacing / no
     * callback is wired.
     */
    fun changeStorageLocation(absolutePath: String?): String? {
        val previous = storageLocation?.customBasePath()
        storageLocation?.setCustomBasePath(absolutePath)
        return onStorageLocationChanged?.invoke(previous, absolutePath)
    }

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
