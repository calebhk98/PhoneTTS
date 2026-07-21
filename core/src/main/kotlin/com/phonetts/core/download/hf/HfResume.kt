package com.phonetts.core.download.hf

/**
 * Decides how to fetch a single repo file given what is already on disk, so an interrupted
 * download — a dropped connection, the app being backgrounded and its process reclaimed, a crash
 * mid-fetch — **resumes** on the next attempt instead of starting the whole (often multi-hundred-MB)
 * weight file over. This is the single source of truth for that policy; the `:app` downloader only
 * supplies the on-disk size and an HTTP `Range` request, so the decision stays pure and unit-tested.
 *
 * Fail-safe by construction: it only ever resumes when the partial file is provably a prefix of a
 * known-size target. Anything ambiguous (no advertised size, or a local file larger than advertised)
 * restarts from scratch rather than risk appending to a truncated or wrong file.
 */
enum class ResumeAction { SKIP, RESUME, RESTART }

/** The verdict for one file: what to do, and (for [ResumeAction.RESUME]) the byte offset to start at. */
data class ResumeDecision(
    val action: ResumeAction,
    val offsetBytes: Long,
)

object HfResume {
    /**
     * @param existingBytes bytes already on disk for this file (0 if it isn't there yet).
     * @param expectedBytes the file's advertised size from the repo tree, or `null` if the repo
     *   omitted it (some do) — in which case a partial file can't be trusted and we restart.
     */
    fun decide(
        existingBytes: Long,
        expectedBytes: Long?,
    ): ResumeDecision {
        if (existingBytes <= 0L) return ResumeDecision(ResumeAction.RESTART, 0L)
        // Without an advertised size we can't tell a complete file from a truncated one, so refetch.
        if (expectedBytes == null) return ResumeDecision(ResumeAction.RESTART, 0L)
        if (existingBytes == expectedBytes) return ResumeDecision(ResumeAction.SKIP, existingBytes)
        // Local file bigger than advertised => it's wrong (or expected size stale); start clean.
        if (existingBytes > expectedBytes) return ResumeDecision(ResumeAction.RESTART, 0L)
        return ResumeDecision(ResumeAction.RESUME, existingBytes)
    }
}
