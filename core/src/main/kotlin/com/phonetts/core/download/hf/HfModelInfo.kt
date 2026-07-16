package com.phonetts.core.download.hf

import kotlinx.serialization.Serializable

/**
 * Response body of `GET /api/models/{id}` ([HfEndpoints.modelInfoUrl]) — only the fields the
 * update-check flow needs. [sha] is the commit hash of the repo's current default revision; HF
 * also exposes this as the `X-Repo-Commit` header on a resolve request, but [HttpClient] only
 * surfaces response bodies (spec: keep the transport seam minimal), so the model-info endpoint's
 * body is the testable path here. The parser tolerates the API adding fields, same as
 * [HfModelSummary].
 */
@Serializable
data class HfModelInfo(
    val id: String,
    val sha: String? = null,
)
