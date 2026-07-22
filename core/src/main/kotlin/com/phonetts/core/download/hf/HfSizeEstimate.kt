package com.phonetts.core.download.hf

/**
 * The download-size facts derivable purely from a repo's file list (issue #7). Hugging Face only
 * reports per-file byte sizes on the recursive tree endpoint ([HfCatalog.listFiles] /
 * [HfTreeEntry.size]) — the search and model-info endpoints don't carry sizes at all (verified
 * against the live API; see [HfCatalog]'s kdoc for the endpoint split) — so this is the only place
 * a size can honestly come from. [unknownFileCount] > 0 means the tree omitted a size for some
 * file, so [knownBytes] is a lower bound, not the true total; callers must label it as such rather
 * than presenting it as exact (spec: never fabricate a number).
 */
data class HfSizeEstimate(
    val knownBytes: Long,
    val unknownFileCount: Int,
) {
    /** True when every counted file carried a size, so [knownBytes] is the exact total. */
    val isExact: Boolean get() = unknownFileCount == 0
}

/** Computes [HfSizeEstimate] from a repo's file list (or a [QuantizationFilter]-scoped subset). */
object HfSizeEstimator {
    fun estimate(files: List<HfTreeEntry>): HfSizeEstimate {
        val fileEntries = files.filter { it.isFile }
        val known = fileEntries.mapNotNull { it.size }
        return HfSizeEstimate(knownBytes = known.sum(), unknownFileCount = fileEntries.size - known.size)
    }

    /** Same computation over a resolved download plan, so the exact set of files about to be
     * fetched (post quantization-filtering) can report its own size rather than the whole repo's. */
    fun estimateItems(items: List<HfDownloadItem>): HfSizeEstimate {
        val known = items.mapNotNull { it.sizeBytes }
        return HfSizeEstimate(knownBytes = known.sum(), unknownFileCount = items.size - known.size)
    }
}
