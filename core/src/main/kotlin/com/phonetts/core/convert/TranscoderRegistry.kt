package com.phonetts.core.convert

import com.phonetts.core.model.ModelBundle

/**
 * Holds the registered [BundleTranscoder]s and, given a bundle, returns the first that claims it -
 * fail-closed, exactly the way [com.phonetts.core.resolver.Resolver] tries engines via `inspect()`
 * and picks the first confident match. Returns null when no transcoder recognizes the bundle,
 * which is the normal case: the import pipeline then proceeds with the bundle unchanged.
 *
 * There are no per-architecture recipes yet (issue #120 build order step 1 is this reusable
 * infrastructure only), so the default registry is empty and [transcoderFor] returns null for
 * every bundle. A recipe is added by including it in [transcoders]; nothing else changes.
 */
class TranscoderRegistry(
    private val transcoders: List<BundleTranscoder> = emptyList(),
) {
    /** The first transcoder confident it recognizes [bundle], or null if none claim it. */
    fun transcoderFor(bundle: ModelBundle): BundleTranscoder? = transcoders.firstOrNull { it.canTranscode(bundle) }

    /** Every registered transcoder, in registration order (for display/diagnostics). */
    fun all(): List<BundleTranscoder> = transcoders
}
