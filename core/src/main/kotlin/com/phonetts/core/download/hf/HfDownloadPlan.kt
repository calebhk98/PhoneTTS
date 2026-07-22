package com.phonetts.core.download.hf

import com.phonetts.core.download.SafePath

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
    // A DENYLIST, not an allowlist: the user wants every model weight format kept — including
    // PyTorch `.safetensors`/`.bin`/`.pt` — so a future engine can be pointed at an already-
    // downloaded repo. Only pure VCS/repo-metadata that is never part of the model itself is
    // skipped; this is what fixed the sesame/csm-1b failure, whose surfaced error was literally
    // `.../resolve/main/.gitattributes` — a file HF ships in every repo that this app never needed.
    private val VCS_METADATA_EXACT = setOf(".gitattributes", ".gitignore", ".gitmodules", ".gitkeep")
    private const val VCS_METADATA_PREFIX = ".git" // catches the exact names above plus any other .git* file

    fun forFiles(
        modelId: String,
        files: List<HfTreeEntry>,
        revision: String = HfCatalog.DEFAULT_REVISION,
    ): List<HfDownloadItem> =
        files
            .filter { it.isFile && !isVcsMetadata(it.path) }
            .map { entry ->
                // Fail closed: a repo file path that could escape the model folder is rejected.
                SafePath.require(entry.path)
                HfDownloadItem(
                    url = HfEndpoints.resolveUrl(modelId, revision, entry.path),
                    relativePath = entry.path,
                    sizeBytes = entry.size,
                )
            }

    // Matched on the file's own name (not the full path) so `.gitattributes` is skipped whether it
    // sits at repo root or inside a subdirectory.
    private fun isVcsMetadata(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name in VCS_METADATA_EXACT || name.startsWith(VCS_METADATA_PREFIX)
    }
}
