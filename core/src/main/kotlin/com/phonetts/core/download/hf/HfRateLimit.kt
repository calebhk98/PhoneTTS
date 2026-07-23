package com.phonetts.core.download.hf

import java.io.IOException

/**
 * A Hugging Face `/api/models` request that was rejected with HTTP 429 (rate limited). Distinct from
 * a generic transport [IOException] so the browse layer can treat it as an EXPECTED, self-resolving
 * cooldown (show a countdown, auto-retry once it lifts) rather than a real, user-actionable error
 * that belongs in the copyable error log.
 *
 * [retryAfterMs] is the server's own hint for how long to wait, when it supplied one (see
 * [HfRateLimitHeader]); `null` when the response carried no usable hint and the caller must fall
 * back to [HfRateLimitBackoff].
 */
class HfRateLimitedException(
    val retryAfterMs: Long?,
    url: String,
) : IOException("HTTP 429 for $url")

/**
 * Parses Hugging Face's IETF ratelimit headers. HF does NOT send a classic `Retry-After`; instead
 * every response carries `RateLimit: "api";r=<remaining>;t=<seconds-until-reset>` (and a
 * `RateLimit-Policy`). On a 429 the `t=` value is the authoritative wait. Pure and side-effect free
 * so it is unit-tested without any HTTP client.
 */
object HfRateLimitHeader {
    /** The `t=<seconds>` reset hint from a `RateLimit` header value, or `null` if absent/malformed. */
    fun parseResetSeconds(rateLimitHeader: String?): Long? {
        if (rateLimitHeader.isNullOrBlank()) return null
        return rateLimitHeader
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("t=") }
            ?.substringAfter('=')
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
    }

    /** A plain `Retry-After: <seconds>` header (defensive: a future CDN edge might add one), or `null`. */
    fun parseRetryAfterSeconds(retryAfterHeader: String?): Long? =
        retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
}

/**
 * How long to wait before retrying after a 429 on the browse/list path. Prefers the server's own
 * reset hint when present (authoritative), otherwise an exponential backoff with jitter, capped so a
 * cooldown never becomes a multi-minute silent sleep. Pure: [random] is injectable so the jitter is
 * deterministic in tests, mirroring the seam style of [HfLoadMorePolicy].
 */
object HfRateLimitBackoff {
    const val BASE_BACKOFF_MS = 2_000L
    const val MAX_BACKOFF_MS = 60_000L
    const val JITTER_RATIO = 0.2
    const val MAX_AUTO_RETRIES = 3

    // Maps a [0,1) sample to the [-1,1) centre of the jitter band.
    private const val JITTER_MIDPOINT = 0.5

    /**
     * @param consecutiveCount how many consecutive 429s have occurred (1 on the first).
     * @param serverHintMs the server's reset hint in ms, or `null` to use exponential backoff.
     * @param random a [0,1) source; defaults to [Math.random].
     */
    fun nextDelayMs(
        consecutiveCount: Int,
        serverHintMs: Long?,
        random: () -> Double = { Math.random() },
    ): Long {
        val exponential = BASE_BACKOFF_MS.toDouble() * pow2(consecutiveCount - 1)
        val base = (serverHintMs?.toDouble() ?: exponential).coerceAtMost(MAX_BACKOFF_MS.toDouble())
        val jitter = 1.0 + (random() - JITTER_MIDPOINT) * 2.0 * JITTER_RATIO
        // Never retry before the server's stated reset (bounded by the cap), so honoring the hint
        // can't be undercut by downward jitter and trigger an immediate second 429.
        val floor = serverHintMs?.coerceAtMost(MAX_BACKOFF_MS) ?: 0L
        return (base * jitter).toLong().coerceIn(floor, MAX_BACKOFF_MS)
    }

    private fun pow2(exponent: Int): Double = Math.pow(2.0, exponent.coerceAtLeast(0).toDouble())
}
