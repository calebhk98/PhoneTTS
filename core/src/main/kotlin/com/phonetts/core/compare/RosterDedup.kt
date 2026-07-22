package com.phonetts.core.compare

/**
 * Merge [additions] onto [existing], skipping any element whose [key] already appears — either
 * already in [existing], or earlier within [additions] itself — keeping the first occurrence seen
 * in each. This is the shared de-dup rule behind Compare's tournament roster builder (issue #92:
 * "the tournament can currently have duplicates"): adding the same model+voice pick twice, whether
 * one at a time ("Add to bracket") or in bulk ("Add all models"), must be a no-op rather than a
 * second roster entry that pads the bracket with a mirror match.
 *
 * Passing the CURRENT roster back in as [additions] against an empty [existing] (see call sites in
 * `TournamentController`) also collapses any duplicates a roster already accumulated before this
 * rule existed — "de-dup existing rosters too".
 *
 * Generic and pure so it needs nothing from `ModelDescriptor`/the app-layer roster-entry type it is
 * used for — just a caller-supplied [key] extractor — which keeps it exhaustively testable here in
 * `:core` (spec: seams tested at the deterministic layer, not the ViewModel).
 */
fun <T, K> mergeUnique(
    existing: List<T>,
    additions: List<T>,
    key: (T) -> K,
): List<T> {
    val seen = existing.mapTo(mutableSetOf(), key)
    val result = ArrayList<T>(existing.size + additions.size)
    result.addAll(existing)
    for (item in additions) {
        if (seen.add(key(item))) result.add(item)
    }
    return result
}
