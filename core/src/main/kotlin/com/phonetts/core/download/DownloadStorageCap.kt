package com.phonetts.core.download

/**
 * The per-file download guard. Historically this was a FIXED 2 GB-per-file cap, which wrongly
 * blocked legitimately large models (a >2 GB weights file — e.g. a multi-gigabyte checkpoint — on a
 * phone with plenty of free storage). The real constraint isn't a magic number, it's **free disk
 * space**: allow any file that actually fits, keeping a small margin so a download can't fill the
 * device to 0 bytes (which would wedge the app and the OS). SSOT for that policy lives here so the
 * downloader and its tests agree on one rule.
 */
object DownloadStorageCap {
    /** Storage kept free so a download never fills the device completely (OS + app breathing room). */
    const val DEFAULT_MARGIN_BYTES: Long = 256L * 1024 * 1024

    /**
     * The largest single file allowed given [freeBytes] of currently-free storage, keeping
     * [marginBytes] in reserve. Never negative (a nearly-full device yields 0 = "nothing fits").
     */
    fun capFor(
        freeBytes: Long,
        marginBytes: Long = DEFAULT_MARGIN_BYTES,
    ): Long = (freeBytes - marginBytes).coerceAtLeast(0L)

    /**
     * True when a file of [fileBytes] cannot fit in [freeBytes] of free storage (keeping the
     * margin). An unknown size ([fileBytes] null) returns false — we can't pre-judge it, so the
     * streaming guard ([capFor]) enforces the limit as the bytes actually arrive.
     */
    fun exceedsFreeStorage(
        fileBytes: Long?,
        freeBytes: Long,
        marginBytes: Long = DEFAULT_MARGIN_BYTES,
    ): Boolean = fileBytes != null && fileBytes > capFor(freeBytes, marginBytes)
}
