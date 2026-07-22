package com.phonetts.core.sideload

import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.registry.ModelCatalog
import com.phonetts.core.resolver.Resolver

/**
 * The auto-load entry point (spec §4 Phase 3, §6.2). Reads a user-picked location into a bundle,
 * resolves it to a descriptor (confident auto-detect that fails closed, else the user-pick
 * fallback the [Resolver] drives), and adds it to the [ModelCatalog] so it immediately appears
 * in the model list — no code change for a new model of a known family.
 *
 * This is the SAME pipeline the built-in models use (they are just resolved from a downloaded
 * manifest bundle instead of a sideloaded folder), which is exactly why auto-load is "almost
 * nothing new": inspect → resolve → register, triggered by a file drop.
 *
 * A bundle no engine can identify is a resolve failure, still surfaced to the caller (whoever
 * triggered the import — the launch-time re-scan, a Hugging Face download, a sideload) exactly as
 * before. But it is also recorded in the [catalog] as an [com.phonetts.core.registry.UnresolvedModel]
 * (issue #8) first, so the model isn't just invisible on disk — a "manage models" screen can list it
 * honestly as "downloaded, no engine available" rather than the app acting as if it were never
 * downloaded and misleadingly telling the user to redownload it.
 */
class ModelImporter(
    private val reader: BundleReader,
    private val resolver: Resolver,
    private val catalog: ModelCatalog,
) {
    fun import(location: String): ModelDescriptor {
        val bundle = reader.read(location)
        return runCatching { resolver.resolve(bundle) }
            .onSuccess { catalog.add(it) }
            .onFailure { e -> catalog.markUnresolved(bundle.id, e.message ?: "could not identify this model") }
            .getOrThrow()
    }
}
