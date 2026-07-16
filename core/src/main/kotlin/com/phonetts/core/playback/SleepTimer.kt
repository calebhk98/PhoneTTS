package com.phonetts.core.playback

/**
 * A pure countdown timer for "stop playback after N minutes" (spec-style seam: no Android
 * dependencies, so it is unit-testable on a plain JVM). It has no thread or timer of its own —
 * [start] arms a deadline against the injected [now] clock, [remainingMillis] reports what is
 * left at any instant, and [tick] is the caller's cue to re-check that deadline and fire the
 * armed callback exactly once if it has elapsed. The `:app` foreground service is responsible
 * for calling [tick] on a real schedule (e.g. once a second via a `Handler`/coroutine loop).
 */
class SleepTimer(private val now: () -> Long = { System.currentTimeMillis() }) {
    private var endAtMillis: Long? = null
    private var onExpire: (() -> Unit)? = null
    private var fired = false

    /** True while a countdown is armed and has not yet fired. */
    val isRunning: Boolean
        get() = endAtMillis != null && !fired

    /** Arm a countdown for [durationMillis] from now, replacing any countdown already running. */
    fun start(
        durationMillis: Long,
        onExpire: () -> Unit,
    ) {
        require(durationMillis > 0) { "durationMillis must be positive" }
        endAtMillis = now() + durationMillis
        this.onExpire = onExpire
        fired = false
    }

    /** Disarm the countdown. Does nothing if it already fired or was never started. */
    fun cancel() {
        endAtMillis = null
        onExpire = null
        fired = false
    }

    /**
     * Milliseconds left in the current countdown, clamped to zero. `0` when nothing is armed or
     * the deadline has already passed.
     */
    fun remainingMillis(): Long {
        val end = endAtMillis ?: return NO_TIME_REMAINING
        return (end - now()).coerceAtLeast(NO_TIME_REMAINING)
    }

    /**
     * Re-check the armed deadline against [now]. If it has elapsed and has not already fired,
     * invoke the [start]-supplied callback exactly once. A no-op if nothing is armed, or the
     * deadline has not yet been reached.
     */
    fun tick() {
        val end = endAtMillis ?: return
        if (fired || now() < end) return
        fired = true
        onExpire?.invoke()
    }

    private companion object {
        const val NO_TIME_REMAINING = 0L
    }
}
