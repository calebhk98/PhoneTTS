package com.phonetts.core.download

import kotlinx.serialization.Serializable

/**
 * The manifest describing models available to download (spec §8, §11.6). Weights are NEVER
 * bundled in the APK: the app ships this manifest, downloads the listed files into app-private
 * storage, verifies each against its [ManifestFile.sha256], and only then loads them.
 *
 * A manifest entry SEEDS the resolver - it never bypasses it. [ManifestModel.engineId] is a
 * hint; if absent, the resolver auto-detects via `inspect()` or falls back to the user pick.
 */
@Serializable
data class ModelManifest(
    val models: List<ManifestModel> = emptyList(),
)

@Serializable
data class ManifestModel(
    val modelId: String,
    val displayName: String,
    /** Optional engine hint. Null means "let the resolver detect or ask." */
    val engineId: String? = null,
    val files: List<ManifestFile> = emptyList(),
)

@Serializable
data class ManifestFile(
    /** Relative path the file is stored under inside the model bundle. */
    val name: String,
    val url: String,
    /** Lower- or upper-case hex SHA-256; verified before the file is trusted. */
    val sha256: String,
    val sizeBytes: Long? = null,
)
