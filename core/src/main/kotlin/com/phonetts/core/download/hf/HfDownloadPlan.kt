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
    // downloaded repo. Only files that are NEVER part of any runnable model payload are skipped:
    // VCS/repo-metadata (this is what fixed the sesame/csm-1b failure, whose surfaced error was
    // literally `.../resolve/main/.gitattributes`), plus docs/source/media/training bookkeeping
    // (this is what fixed the AphaVoice failure, which aborted the whole model on a source file,
    // `matcha/utils/audio.py`, and trims the 45 GB of junk that browsing many repos accumulated).
    private val VCS_METADATA_EXACT = setOf(".gitattributes", ".gitignore", ".gitmodules", ".gitkeep")
    private const val VCS_METADATA_PREFIX = ".git" // catches the exact names above plus any other .git* file

    // Extensions that are provably never a runnable payload: docs, source code, and repo-card media.
    // Matched on the file's own extension, NOT the full path, so `matcha/utils/audio.py` is skipped
    // wherever it sits. Deliberately excludes every extension that carries real payload or config
    // (`.json`/`.txt`/`.bin`/`.pt`/`.pth`/`.ckpt`/`.yaml`/`.yml` and ALL audio: `.wav` is required,
    // F5-TTS ships `<name>.reference.wav` voice-clone pairs), those are dropped only by exact name.
    private val NON_PAYLOAD_EXTENSIONS =
        setOf(
            // docs
            "md", "rst", "ipynb",
            // source code
            "py", "pyc", "pyx", "js", "ts", "jsx", "tsx", "c", "cc", "cpp", "h", "hpp",
            "cu", "m", "mm", "java", "rs", "go",
            // repo-card media
            "png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "ico", "mp4", "mov", "avi", "webm",
        )

    // Exact filenames that are unambiguous training/CI/packaging bookkeeping. Matched by literal
    // name (never by extension, since `.json`/`.txt`/`.bin`/`.pt` are all real payload extensions).
    private val NON_PAYLOAD_EXACT_NAMES =
        setOf(
            "LICENSE", "LICENSE.txt", "LICENSE.md", "NOTICE", "NOTICE.md",
            "CITATION.cff", "CONTRIBUTING.md", "CODE_OF_CONDUCT.md", "SECURITY.md",
            "requirements.txt", "pyproject.toml", "setup.py", "setup.cfg", "Makefile", "Dockerfile", ".dockerignore",
            "optimizer.pt", "optimizer.bin", "scheduler.pt", "training_args.bin", "trainer_state.json",
            "rng_state.pth", "scaler.pt",
        )

    private const val TFEVENTS_PREFIX = "events.out.tfevents." // TensorBoard event logs
    private const val GITHUB_DIR_PREFIX = ".github/" // CI workflow YAML

    fun forFiles(
        modelId: String,
        files: List<HfTreeEntry>,
        revision: String = HfCatalog.DEFAULT_REVISION,
    ): List<HfDownloadItem> =
        files
            .filter { it.isFile && !isVcsMetadata(it.path) && !isNonPayload(it.path) }
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

    // A file that no engine (current or future Kotlin/native — this app never re-executes a repo's
    // own Python/JS) could read as part of running the model. Name/extension based so it matches at
    // any depth in the tree.
    private fun isNonPayload(path: String): Boolean {
        val name = path.substringAfterLast('/')
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in NON_PAYLOAD_EXTENSIONS ||
            name in NON_PAYLOAD_EXACT_NAMES ||
            name.startsWith(TFEVENTS_PREFIX) ||
            path.startsWith(GITHUB_DIR_PREFIX)
    }
}
