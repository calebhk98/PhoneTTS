package com.phonetts.app.playback

import android.os.Handler
import android.os.Looper
import com.phonetts.core.playback.SleepTimer

private const val TICK_INTERVAL_MILLIS = 1_000L

/**
 * The `:app` wiring for [SleepTimer]: the core class is deliberately timer-free (see its kdoc)
 * so it stays plain-JVM testable, which means something on the Android side has to actually call
 * [SleepTimer.tick] on a schedule. This drives that tick once a second on the main [Handler] for
 * as long as a countdown is armed, and stops rescheduling itself the moment it fires.
 */
class SleepTimerRunner(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val timer: SleepTimer = SleepTimer(),
) {
    private val tickRunnable =
        object : Runnable {
            override fun run() {
                timer.tick()
                if (timer.isRunning) handler.postDelayed(this, TICK_INTERVAL_MILLIS)
            }
        }

    /** True while a countdown is armed and has not yet fired. */
    val isRunning: Boolean get() = timer.isRunning

    /** Milliseconds left in the current countdown; `0` if none is running. */
    fun remainingMillis(): Long = timer.remainingMillis()

    /** Arm a countdown for [durationMillis], replacing any countdown already running. */
    fun start(
        durationMillis: Long,
        onExpire: () -> Unit,
    ) {
        handler.removeCallbacks(tickRunnable)
        timer.start(durationMillis, onExpire)
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MILLIS)
    }

    /** Disarm the countdown without firing [onExpire], and stop the tick loop. */
    fun cancel() {
        handler.removeCallbacks(tickRunnable)
        timer.cancel()
    }
}
