package com.phonetts.app.diagnostics

import android.content.Context
import android.os.Process
import com.phonetts.app.AppGraph
import com.phonetts.core.diagnostics.CrashLogFormatter
import kotlin.system.exitProcess

/**
 * Fixes "there is no error log saved, I can't go to the Help page and click copy errors after the app
 * crashes": until this existed, `Thread.setDefaultUncaughtExceptionHandler` was never called anywhere
 * in the app, so a crash recorded nothing durable - only whatever ended up in logcat, which the user
 * cannot read.
 *
 * [install] installs a process-wide [Thread.UncaughtExceptionHandler] that records the crash (via
 * [CrashLogFormatter], `:core`'s pure, deterministic formatting seam) into the SAME durable error log
 * the Help screen already displays ([AppGraph.durableErrorLog] / `HelpScreen`'s `errorLogEntries`),
 * then ALWAYS delegates to whichever handler was previously installed (Android's own, which writes the
 * tombstone/logcat trace and lets the OS crash dialog and any Vitals-style reporting behave normally).
 * The crash is never swallowed and the process is never kept alive past its normal death.
 *
 * Call [install] as the first line of [com.phonetts.app.PhoneTtsApplication.onCreate], before
 * [AppGraph] is constructed - it talks to the durable store directly via [AppGraph.durableErrorLogFor],
 * which needs only a bare [Context], so a crash during [AppGraph]'s own construction is captured too,
 * not just crashes once the app has finished starting.
 */
object CrashReporter {
    // A crash mid-write of the error log itself should not change the OS's own exit code story; this
    // is only used on the (Android-guaranteed-rare) path where no previous handler was installed.
    private const val NO_PREVIOUS_HANDLER_EXIT_CODE = 10

    fun install(context: Context) {
        val errorLog = AppGraph.durableErrorLogFor(context)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Best-effort and synchronous: the process is about to die, so this must be a plain
            // blocking call on the crashing thread - no coroutines, no dispatch elsewhere - and a
            // failure here (e.g. disk full) must never itself throw and mask the real crash.
            runCatching {
                errorLog.record(CrashLogFormatter.format(System.currentTimeMillis(), thread.name, throwable))
            }
            delegateOrTerminate(previousHandler, thread, throwable)
        }
    }

    // Rule: never swallow the crash, never keep a doomed process alive. Android always installs its
    // own default handler before Application.onCreate runs, so [previousHandler] is null only in
    // unusual embeddings (e.g. a JVM unit test); terminate directly rather than let the thread merely
    // return, which would leave the process running in an undefined state.
    private fun delegateOrTerminate(
        previousHandler: Thread.UncaughtExceptionHandler?,
        thread: Thread,
        throwable: Throwable,
    ) {
        if (previousHandler != null) {
            previousHandler.uncaughtException(thread, throwable)
            return
        }
        runCatching { Process.killProcess(Process.myPid()) }
        exitProcess(NO_PREVIOUS_HANDLER_EXIT_CODE)
    }
}
