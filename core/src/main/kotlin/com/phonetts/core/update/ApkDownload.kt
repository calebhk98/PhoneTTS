package com.phonetts.core.update

import com.phonetts.core.download.Sha256Verifier

/**
 * The pure half of "download the update inside the app" (the bug this exists for: the app used to
 * hand the release-asset URL to the browser and hope. GitHub answers that URL with a 302 to a
 * **short-lived signed** `release-assets.githubusercontent.com` link, so a browser download that
 * queues or retries past the signature's expiry gets a 403 and sits there forever - "never
 * finishes". Fetching it ourselves, promptly, in one connection, removes that whole class of
 * failure and gives the user visible progress).
 *
 * This file owns the *decisions* - the state a download can be in, whether the bytes that landed
 * are trustworthy - so they are testable on any JVM. The transport (HttpURLConnection), the file,
 * and the package-installer intent live in `:app`.
 */
sealed interface ApkDownloadState {
    /** Nothing in flight; the update banner offers a Download button. */
    data object Idle : ApkDownloadState

    /**
     * Bytes are arriving. [bytesTotal] is null when the release didn't advertise a size - the UI
     * then shows an indeterminate bar rather than a fabricated percentage.
     */
    data class Downloading(val bytesDone: Long, val bytesTotal: Long?) : ApkDownloadState

    /** All bytes in; hashing them before anything is offered for install. */
    data object Verifying : ApkDownloadState

    /**
     * The APK is on disk at [path] and ready to hand to the system installer. [verified] is false
     * when the release advertised no hash to check against - the download still happened, we just
     * can't claim it was verified.
     */
    data class Ready(val path: String, val verified: Boolean) : ApkDownloadState

    /** The download or its verification failed; [reason] is user-facing. */
    data class Failed(val reason: String) : ApkDownloadState
}

/** Percent complete 0..100, or null when the total isn't known (never fabricate one). */
fun ApkDownloadState.Downloading.percentOrNull(): Int? {
    val total = bytesTotal?.takeIf { it > 0 } ?: return null
    return ((bytesDone.toDouble() / total) * PERCENT_SCALE).toInt().coerceIn(0, PERCENT_SCALE)
}

private const val PERCENT_SCALE = 100

/**
 * Fail-closed integrity check for a downloaded APK. A hash we were given and that does NOT match is
 * a hard rejection - never install those bytes. No hash at all is *not* a pass: it yields
 * [Verdict.Unverified], which the UI is expected to say out loud rather than silently claim
 * "verified".
 */
object ApkIntegrity {
    enum class Verdict { VERIFIED, UNVERIFIED, MISMATCH }

    fun check(
        expectedSha256: String?,
        actualSha256: String,
    ): Verdict {
        if (expectedSha256.isNullOrBlank()) return Verdict.UNVERIFIED
        if (Sha256Verifier.matches(actualSha256, expectedSha256)) return Verdict.VERIFIED
        return Verdict.MISMATCH
    }
}
