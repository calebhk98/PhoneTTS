package com.phonetts.core.download.builtin

import com.phonetts.core.download.hf.HfCatalog
import com.phonetts.core.download.hf.HfDownloadItem
import com.phonetts.core.download.hf.HfEndpoints

// A curated, one-tap-downloadable model: a specific Hugging Face repo + the exact files that
// reconstruct a working model folder on device. Unlike a browsed repo, a built-in model names its
// files explicitly (and where they should land locally), so the user gets a known-good model in a
// single tap without searching. The downloaded folder still goes through the normal auto-load
// pipeline (DirectoryBundleReader -> resolve -> inspect()), so NO engine is named here - detection
// stays the single source of truth. These are the models proven to produce valid audio in
// docs/MODEL-VERIFICATION.md.

/** One file of a built-in model: where it lives in the repo, and its name inside the model folder. */
data class BuiltInFile(
    val repoPath: String,
    val localName: String,
)

data class BuiltInModel(
    val id: String,
    val displayName: String,
    val repoId: String,
    val approxSizeMb: Int,
    val files: List<BuiltInFile>,
    val revision: String = HfCatalog.DEFAULT_REVISION,
    val note: String? = null,
    // The Runtime this model needs to actually run (e.g. CosyVoice3's native ggml backend), or null
    // when any build can run it (the ONNX models). The recommended list hides a model whose required
    // runtime isn't available on this build, so a one-tap download never lands a model that can't
    // load - the browse layer filters on this; core states the requirement, it doesn't resolve it.
    val requiresRuntimeId: String? = null,
) {
    /**
     * The concrete downloads: each file fetched from its repo path but written under its local
     * name, so a nested repo layout (e.g. Piper's voices tree) lands flat in the model folder the
     * engines expect.
     */
    fun downloadItems(): List<HfDownloadItem> =
        files.map { file ->
            HfDownloadItem(
                url = HfEndpoints.resolveUrl(repoId, revision, file.repoPath),
                relativePath = file.localName,
                sizeBytes = null,
            )
        }
}
