package com.phonetts.core.download.hf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A model returned by the Hugging Face Hub search API (`/api/models`). Only the fields the browse
 * UI needs are declared; the parser ignores everything else, so the shape tolerates the API
 * adding fields. Note `gated` is intentionally omitted — its JSON value is sometimes a boolean and
 * sometimes a string ("auto"/"manual"), and gated repos need token handling that belongs in :app
 * (see docs/research/hf-download.md).
 */
@Serializable
data class HfModelSummary(
    val id: String,
    val likes: Int = 0,
    val downloads: Int = 0,
    @SerialName("pipeline_tag") val pipelineTag: String? = null,
    val tags: List<String> = emptyList(),
)

/**
 * One entry in a repo's file tree (`/api/models/{id}/tree/{rev}?recursive=true`). Directories have
 * no size; files carry [size] in bytes. [oid] is the git blob id (not a stable content hash — HF
 * ETags are unreliable, so the app recomputes SHA-256 after download).
 */
@Serializable
data class HfTreeEntry(
    val type: String,
    val path: String,
    val size: Long? = null,
    val oid: String? = null,
) {
    val isFile: Boolean get() = type == TYPE_FILE

    companion object {
        const val TYPE_FILE = "file"
        const val TYPE_DIRECTORY = "directory"
    }
}
