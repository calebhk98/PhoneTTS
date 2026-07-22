package com.phonetts.app.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug

/**
 * Device facts the resource-cost UI needs (issue #38/#39): how much RAM is free right now, and a
 * stable name for this phone so benchmark history is compared like-for-like. These are DEVICE facts,
 * not model facts, so they legitimately live in `:app` (the SSOT rule governs model constants).
 */
object DeviceInfo {
    /** Free RAM available to apps right now, in bytes; 0 if it can't be read. */
    fun availableRamBytes(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem
    }

    /**
     * This device's TOTAL RAM in bytes (`ActivityManager.MemoryInfo.totalMem`), not what's free
     * right now; 0 if it can't be read. This is the figure that decides whether a model can
     * physically fit at all ([com.phonetts.core.model.DeviceRamFit]) — free RAM churns with
     * whatever else happens to be running and is the wrong number to warn against.
     */
    fun totalRamBytes(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem
    }

    /** A human-readable model name for this device (e.g. "SM-A165F"), used to key benchmark history. */
    val name: String = Build.MODEL ?: Build.DEVICE ?: "unknown-device"

    /**
     * Total PSS of this process right now, in bytes — an observed footprint we record after a model
     * has loaded + generated so [com.phonetts.core.prefs.ResourceUsageStore] can refine the a-priori
     * estimate from real previous loads (issue #38). One engine loads at a time, so process PSS while
     * a model is loaded is a fair "RAM used by this model" reading; 0 if it can't be sampled.
     */
    fun processMemoryBytes(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong() * BYTES_PER_KILOBYTE
    }

    private const val BYTES_PER_KILOBYTE = 1024L
}
