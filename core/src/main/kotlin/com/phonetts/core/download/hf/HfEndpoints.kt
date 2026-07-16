package com.phonetts.core.download.hf

import java.net.URLEncoder

/**
 * Builds Hugging Face Hub URLs. Kept pure and testable — no HTTP here. The transport lives behind
 * [HttpClient] (implemented in :app).
 */
object HfEndpoints {
    const val API_BASE = "https://huggingface.co/api"
    const val RESOLVE_BASE = "https://huggingface.co"
    const val TTS_PIPELINE_TAG = "text-to-speech"

    /** Search text-to-speech models, most-downloaded first. Blank [query] lists top TTS models. */
    fun searchModelsUrl(
        query: String,
        limit: Int,
    ): String {
        val base = "$API_BASE/models?pipeline_tag=$TTS_PIPELINE_TAG&sort=downloads&direction=-1&limit=$limit"
        if (query.isBlank()) return base
        return "$base&search=${encodeQuery(query)}"
    }

    /** The full (recursive) file tree of a repo at a revision. Owner/name keeps its '/', segments encoded. */
    fun treeUrl(
        modelId: String,
        revision: String,
    ): String = "$API_BASE/models/${encodePathSegments(modelId)}/tree/$revision?recursive=true"

    /** The download URL for one file. Owner/name and the file path keep their '/'; each segment is encoded. */
    fun resolveUrl(
        modelId: String,
        revision: String,
        path: String,
    ): String = "$RESOLVE_BASE/${encodePathSegments(modelId)}/resolve/$revision/${encodePathSegments(path)}"

    private fun encodeQuery(value: String): String = encodeUriComponent(value)

    private fun encodePathSegments(path: String): String = path.split('/').joinToString("/") { encodeUriComponent(it) }

    // URLEncoder is form/query encoding (space -> "+"); URIs want "%20" instead, for a query value
    // or a path segment alike.
    private fun encodeUriComponent(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
