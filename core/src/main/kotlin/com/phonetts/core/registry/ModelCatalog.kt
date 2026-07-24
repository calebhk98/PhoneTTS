package com.phonetts.core.registry

import com.phonetts.core.model.ModelDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A downloaded bundle sitting on disk that no registered engine could identify (spec rule 4:
 * `inspect()` fails closed rather than guessing - see [com.phonetts.core.sideload.ModelImporter]).
 * Deliberately NOT a [ModelDescriptor]: nothing here is guessed, only what's externally
 * observable - the bundle's own id (which doubles as its on-disk directory name, spec §7) and why
 * detection declined it. Lets a "manage models" UI list it honestly (issue #8 - "downloaded, no
 * engine available") instead of it silently vanishing as if nothing were ever downloaded.
 */
data class UnresolvedModel(val bundleId: String, val reason: String)

/**
 * The set of models the user can pick - the single source of truth the model dropdown reads
 * (spec §7). Built-in models (downloaded via the manifest) and sideloaded models are added
 * through the SAME path (resolve → add); nothing here distinguishes them beyond
 * [ModelDescriptor.origin], which is for display only. Register a model → it appears; remove
 * it → it vanishes. No UI code is edited either way.
 *
 * Also tracks [UnresolvedModel]s (issue #8) alongside identified ones, so a bundle detection
 * couldn't claim is represented honestly rather than disappearing as if it were never downloaded.
 */
class ModelCatalog {
    private val models = LinkedHashMap<String, ModelDescriptor>()
    private val unresolved = LinkedHashMap<String, UnresolvedModel>()

    private val _revision = MutableStateFlow(0)

    /**
     * A monotonically increasing counter bumped on every mutation of this catalog. A UI can collect
     * it to re-read [list] / [listUnresolved] whenever the set changes, which is what lets model
     * hydration run OFF the main thread at startup (the folder re-import that scales with how many
     * models the user has downloaded) and still have the home screen populate as models land, rather
     * than blocking the first frame on that scan. Conflated (StateFlow), so a burst of adds during
     * hydration coalesces into at most a few refreshes for the collector.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun bumpRevision() {
        _revision.value += 1
    }

    /** Add or replace the descriptor for its [ModelDescriptor.modelId]. */
    @Synchronized
    fun add(descriptor: ModelDescriptor) {
        models[descriptor.modelId] = descriptor
        // A later successful (re-)identification - e.g. an app update registered the right engine -
        // supersedes any earlier "couldn't identify this" marker for the same bundle id.
        unresolved.remove(descriptor.modelId)
        bumpRevision()
    }

    @Synchronized
    fun remove(modelId: String) {
        models.remove(modelId)
        bumpRevision()
    }

    @Synchronized
    fun get(modelId: String): ModelDescriptor? = models[modelId]

    /** Every model currently available, in insertion order. */
    @Synchronized
    fun list(): List<ModelDescriptor> = models.values.toList()

    /** Record [bundleId] as present on disk but unclaimed by any registered engine. */
    @Synchronized
    fun markUnresolved(
        bundleId: String,
        reason: String,
    ) {
        // Already identified (e.g. this call is a stale retry) - an honest identified entry wins.
        if (models.containsKey(bundleId)) return
        unresolved[bundleId] = UnresolvedModel(bundleId, reason)
        bumpRevision()
    }

    /** Drop [bundleId]'s unresolved marker - e.g. its on-disk files were deleted. */
    @Synchronized
    fun clearUnresolved(bundleId: String) {
        unresolved.remove(bundleId)
        bumpRevision()
    }

    /** Every bundle currently on disk that no engine has claimed, in insertion order. */
    @Synchronized
    fun listUnresolved(): List<UnresolvedModel> = unresolved.values.toList()

    /**
     * True if [bundleId] is present on disk in ANY form - identified or not (bug #6). A caller that
     * only wants to know "did the user already fetch this" (e.g. a browse screen deciding whether to
     * offer a Download button) must ask this, not [get]/[list] alone: [list] only covers models a
     * registered engine claimed, so a UI that used it as "is this downloaded" would wrongly invite
     * the user to redownload a bundle that's already sitting on disk, just unresolved. `null` from
     * `inspect()` means "unidentified," not "absent" - a real, surfaced state (spec rule 4), and this
     * is the one place that distinction is collapsed back into a plain yes/no on purpose.
     */
    @Synchronized
    fun isKnown(bundleId: String): Boolean = models.containsKey(bundleId) || unresolved.containsKey(bundleId)

    /** Drop every identified and unresolved entry (issue #4/#5 - switching the storage location). */
    @Synchronized
    fun clear() {
        models.clear()
        unresolved.clear()
        bumpRevision()
    }
}
