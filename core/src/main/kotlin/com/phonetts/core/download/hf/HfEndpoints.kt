package com.phonetts.core.download.hf

import java.net.URLEncoder

/**
 * Builds Hugging Face Hub URLs. Kept pure and testable - no HTTP here. The transport lives behind
 * [HttpClient] (implemented in :app).
 */
object HfEndpoints {
    const val API_BASE = "https://huggingface.co/api"
    const val RESOLVE_BASE = "https://huggingface.co"
    const val TTS_PIPELINE_TAG = "text-to-speech"

    /**
     * Search text-to-speech models, most-downloaded first. Blank [query] lists top TTS models.
     * [skip] pages past the first [skip] results of that same ordering - the Hub's `/api/models`
     * list endpoint accepts a plain `skip` offset alongside `limit` (verified against the live API:
     * `limit=N&skip=M` returns exactly the same slice as the tail of a single `limit=N+M` call, for
     * the same query/sort), so "load more" is just a bigger offset, never a second query mechanism.
     * Omitted when 0 so the very first page's URL is unchanged from before pagination existed.
     */
    fun searchModelsUrl(
        query: String,
        limit: Int,
        skip: Int = 0,
    ): String {
        val base = "$API_BASE/models?pipeline_tag=$TTS_PIPELINE_TAG&sort=downloads&direction=-1&limit=$limit"
        val paged = if (skip > 0) "$base&skip=$skip" else base
        if (query.isBlank()) return paged
        return "$paged&search=${encodeQuery(query)}"
    }

    /** The full (recursive) file tree of a repo at a revision. Owner/name keeps its '/', segments encoded. */
    fun treeUrl(
        modelId: String,
        revision: String,
    ): String = "$API_BASE/models/${encodePathSegments(modelId)}/tree/$revision?recursive=true"

    /**
     * A repo's current info, including the commit sha of its default revision (the `sha` field
     * in the body). Used by the update-check flow to detect that an installed model's stored
     * revision is stale against the Hub.
     */
    fun modelInfoUrl(modelId: String): String = "$API_BASE/models/${encodePathSegments(modelId)}"

    /** The model's Hugging Face page (its README/model card + files) - for an "open in browser" link. */
    fun modelPageUrl(modelId: String): String = "$RESOLVE_BASE/${encodePathSegments(modelId)}"

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
