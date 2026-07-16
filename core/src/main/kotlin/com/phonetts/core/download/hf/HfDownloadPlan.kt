package com.phonetts.core.download.hf

/** One file to fetch: where from ([url]) and where it goes inside the model folder ([relativePath]). */
data class HfDownloadItem(
    val url: String,
    val relativePath: String,
    val sizeBytes: Long?,
)

/**
 * Turns a repo's file tree into the concrete list of downloads that reconstruct the model folder on
 * device. The downloaded folder is then handed to the existing auto-load path
 * (`DirectoryBundleReader → resolve → inspect()`), so no engine-specific knowledge is needed here.
 */
object HfDownloadPlan {
    fun forFiles(
        modelId: String,
        files: List<HfTreeEntry>,
        revision: String = HfCatalog.DEFAULT_REVISION,
    ): List<HfDownloadItem> =
        files
            .filter { it.isFile }
            .map { entry ->
                HfDownloadItem(
                    url = HfEndpoints.resolveUrl(modelId, revision, entry.path),
                    relativePath = entry.path,
                    sizeBytes = entry.size,
                )
            }
}
