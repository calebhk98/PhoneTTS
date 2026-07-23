package com.phonetts.core.model

/**
 * The single, honest answer to "will this model fit on this device?" (issue #38 follow-up). The
 * only thing that decides whether a model physically CAN'T fit is the device's TOTAL RAM
 * ([totalRamBytes], `ActivityManager.MemoryInfo.totalMem`) - never how much happens to be free
 * right now ([totalRamBytes] is stable per-device; free RAM churns with whatever else is running
 * and is usually well under total even on a phone that could easily run the model once other apps
 * get evicted). A merely-tight fit (e.g. a 3.5 GB model on a 4 GB phone) is NOT a "won't fit" case
 * - per the maintainer, that should never warn. There is deliberately no "tight" tier here: a
 * model either can't physically fit, or it's left alone.
 */
object DeviceRamFit {
    /**
     * True only when [peakRamBytes] is known AND exceeds [totalRamBytes] (minus a small, fixed
     * [reserveBytes] the OS/other processes always need) - i.e. the model genuinely cannot fit on
     * this device no matter what else is running. An unknown peak ([peakRamBytes] null) or an
     * unreadable total ([totalRamBytes] <= 0, e.g. `ActivityManager` unavailable) is "we don't
     * know", never treated as a failure to fit (CLAUDE.md rule 4's fail-closed spirit: no
     * confident-looking warning from an absent number).
     */
    fun modelExceedsDeviceRam(
        peakRamBytes: Long?,
        totalRamBytes: Long,
        reserveBytes: Long = DEFAULT_OS_RESERVE_BYTES,
    ): Boolean {
        if (peakRamBytes == null || totalRamBytes <= 0L) return false
        val usableRamBytes = (totalRamBytes - reserveBytes).coerceAtLeast(0L)
        return peakRamBytes > usableRamBytes
    }

    /**
     * No reserve by default - the maintainer's own example (3.5 GB peak on a 4 GB phone) draws the
     * line at total RAM itself with nothing subtracted, and any reserve only pushes borderline
     * cases toward warning, the opposite of what was asked for. Callers that want a small, fixed
     * OS allowance can still pass one explicitly; this is not a "tight" band, just an optional knob.
     */
    const val DEFAULT_OS_RESERVE_BYTES: Long = 0L
}
