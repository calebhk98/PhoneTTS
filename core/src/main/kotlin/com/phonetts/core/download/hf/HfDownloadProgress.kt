package com.phonetts.core.download.hf

/**
 * A live download's cumulative progress, used to derive an "estimated time remaining" from a
 * *measured* running throughput (bytes transferred since [startedAtMs]) — never a fabricated or
 * assumed transfer rate (issue #7: only show what's actually knowable). This is pure arithmetic
 * over numbers the caller already has; `:app` only supplies the wall-clock (`nowMs`) and updates
 * [bytesDone] as bytes actually land on disk.
 */
data class HfDownloadProgress(
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    // Null when some file in the plan has no advertised size (see HfSizeEstimate) — an ETA needs a
    // real total, not a guessed one.
    val bytesTotal: Long? = null,
    val startedAtMs: Long = 0,
) {
    /**
     * Average bytes/sec since [startedAtMs], or null before enough time or bytes have passed to
     * trust the reading (a same-millisecond sample would otherwise look like an absurd instant rate).
     */
    fun bytesPerSecond(nowMs: Long): Double? {
        val elapsedSeconds = (nowMs - startedAtMs) / MILLIS_PER_SECOND
        if (elapsedSeconds < MIN_ELAPSED_SECONDS || bytesDone <= 0L) return null
        return bytesDone / elapsedSeconds
    }

    /** Seconds remaining, or null when the total size isn't known yet or there's no throughput reading. */
    fun etaSeconds(nowMs: Long): Double? {
        val total = bytesTotal ?: return null
        val rate = bytesPerSecond(nowMs) ?: return null
        if (rate <= 0.0) return null
        return (total - bytesDone).coerceAtLeast(0L) / rate
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000.0

        // Below this, a throughput reading is noise (e.g. the very first progress callback) rather
        // than a measurement worth trusting for an ETA.
        private const val MIN_ELAPSED_SECONDS = 1.0
    }
}
