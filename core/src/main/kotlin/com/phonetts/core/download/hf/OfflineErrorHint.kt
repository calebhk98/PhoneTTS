package com.phonetts.core.download.hf

/**
 * Turns a raw transport error into a friendly, actionable line when it's really just "no internet"
 * (issue: a 5-minute network drop surfaced `Unable to resolve host "huggingface.co": No address
 * associated with hostname` — accurate, but not what a user should have to read). Pattern-based
 * rather than exception-type based so it works no matter where the error was already stringified —
 * the download path ([HfDownloader]) and the search path both funnel their messages through here.
 *
 * Fail-safe: if the message doesn't clearly indicate a connectivity problem, this returns `null` and
 * the caller keeps the original text — a genuine, specific error is never masked behind a generic
 * "check your connection".
 */
object OfflineErrorHint {
    private const val FRIENDLY = "No internet connection — check your network and try again."

    // Substrings that only appear when the device couldn't reach the network at all (DNS failure,
    // no route, connection refused/timed out at the socket level) — not a server-side or file-level
    // error, which the user can't fix by reconnecting.
    private val OFFLINE_MARKERS =
        listOf(
            "unable to resolve host",
            "no address associated with hostname",
            "failed to connect",
            "network is unreachable",
            "unable to resolve",
            "connection refused",
        )

    /** A friendly connectivity message if [raw] looks like an offline failure, else `null`. */
    fun messageFor(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val lower = raw.lowercase()
        if (OFFLINE_MARKERS.any { it in lower }) return FRIENDLY
        return null
    }

    /** [raw] replaced with the friendly connectivity message when it looks like an offline failure,
     * otherwise [raw] untouched. Convenience for call sites that always want a displayable string. */
    fun humanize(raw: String): String = messageFor(raw) ?: raw
}
